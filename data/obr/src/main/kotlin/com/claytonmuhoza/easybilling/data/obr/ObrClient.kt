package com.claytonmuhoza.easybilling.data.obr

import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.transmission.ExchangeRecorder
import com.claytonmuhoza.easybilling.domain.transmission.LoginOutcome
import com.claytonmuhoza.easybilling.domain.transmission.LoginTarget
import com.claytonmuhoza.easybilling.domain.transmission.ObrAuthentication
import com.claytonmuhoza.easybilling.domain.transmission.ObrExchange
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.cert.CertificateException
import java.time.Duration
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.resume

/** Builds the HTTP client of the EBMS. */
object ObrHttp {
    /** Longest time a call may take, request and response included (`docs/architecture.md` § 7.6). */
    val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(15)

    /**
     * The client never retries by itself: a request that may have reached the OBR is only sent
     * again by the send queue, which knows whether that is safe. The certificate of the server is
     * checked by the system, without pinning.
     *
     * @param timeout longest time a call may take.
     * @return the HTTP client.
     */
    fun client(timeout: Duration = DEFAULT_TIMEOUT): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(timeout)
        .connectTimeout(timeout)
        .readTimeout(timeout)
        .writeTimeout(timeout)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .build()
}

/**
 * Client of the EBMS API: every call is a JSON `POST`, and every exchange is recorded with its
 * secrets masked.
 *
 * @param http the HTTP client, from [ObrHttp.client].
 * @param recorder receives every exchange.
 * @param time source of the time of each exchange.
 */
class ObrClient(
    private val http: OkHttpClient,
    private val recorder: ExchangeRecorder,
    private val time: TimeProvider,
) : ObrAuthentication {

    override suspend fun login(target: LoginTarget): LoginOutcome {
        val body = buildJsonObject {
            put("username", target.credentials.username)
            put("password", target.credentials.password)
        }
        return when (val reply = post(target, LOGIN, body.toString())) {
            is Reply.Failed -> if (reply.certificate) LoginOutcome.CertificateRejected else LoginOutcome.Unreachable
            is Reply.Answered -> loginOutcome(reply)
        }
    }

    private fun loginOutcome(reply: Reply.Answered): LoginOutcome {
        val json = parse(reply.body)
        return when (reply.code) {
            HTTP_OK -> json?.token()?.let { LoginOutcome.Success(it) } ?: LoginOutcome.InvalidResponse
            HTTP_UNAUTHORIZED -> LoginOutcome.WrongCredentials
            HTTP_BAD_REQUEST, HTTP_FORBIDDEN -> LoginOutcome.Refused(json?.text("msg").orEmpty())
            in HTTP_SERVER_ERRORS -> LoginOutcome.ServerError
            else -> LoginOutcome.InvalidResponse
        }
    }

    /** What came back from a call. */
    private sealed interface Reply {
        /** The server answered with [code] and [body]. */
        data class Answered(val code: Int, val body: String) : Reply

        /** No answer; [certificate] tells whether the certificate of the server was refused. */
        data class Failed(val certificate: Boolean, val cause: String) : Reply
    }

    private suspend fun post(target: LoginTarget, service: String, body: String): Reply {
        val request = Request.Builder()
            .url(target.serverUrl + service)
            .post(body.toRequestBody(JSON))
            .build()
        val startedAt = time.now()
        val start = System.nanoTime()
        val reply = execute(http.newCall(request))
        recorder.record(
            ObrExchange(
                startedAt = startedAt,
                environment = target.environment,
                service = service,
                requestBody = SecretMasking.mask(body),
                statusCode = (reply as? Reply.Answered)?.code,
                responseBody = (reply as? Reply.Answered)?.body?.let(SecretMasking::mask).orEmpty(),
                durationMillis = Duration.ofNanos(System.nanoTime() - start).toMillis(),
                failure = (reply as? Reply.Failed)?.cause.orEmpty(),
            ),
        )
        return reply
    }

    private suspend fun execute(call: Call): Reply = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    val reply = try {
                        response.use { Reply.Answered(it.code, it.body.string()) }
                    } catch (failure: IOException) {
                        failed(failure)
                    }
                    continuation.resume(reply)
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(failed(e))
                }
            },
        )
    }

    private fun failed(failure: IOException): Reply.Failed {
        // When several addresses are tried (IPv6 and IPv4), the failure of one address is thrown
        // and the others are attached as suppressed: a refused certificate may be among them.
        val related = failure.related()
        val certificate = related.any { it is SSLPeerUnverifiedException } ||
            related.any { it is SSLHandshakeException } && related.any { it is CertificateException }
        return Reply.Failed(certificate, "${failure::class.java.simpleName}: ${failure.message.orEmpty()}")
    }

    /** @return this failure, its causes and the failures suppressed by any of them. */
    private fun Throwable.related(): List<Throwable> = generateSequence(this) { it.cause }
        .flatMap { sequenceOf(it) + it.suppressed.flatMap { suppressed -> suppressed.related() } }
        .toList()

    private fun parse(body: String): JsonObject? =
        try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: SerializationException) {
            null
        }

    private fun JsonObject.token(): String? {
        val success = (this["success"] as? JsonPrimitive)?.booleanOrNull
        val token = (this["result"] as? JsonObject)?.text("token")
        return token?.takeIf { success == true && it.isNotEmpty() }
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.content?.trim()

    private companion object {
        const val LOGIN = "login/"
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val HTTP_OK = 200
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        val HTTP_SERVER_ERRORS = 500..599
    }
}

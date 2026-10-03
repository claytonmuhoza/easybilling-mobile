package com.claytonmuhoza.easybilling.data.obr

import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.transmission.ExchangeRecorder
import com.claytonmuhoza.easybilling.domain.transmission.LoginTarget
import com.claytonmuhoza.easybilling.domain.transmission.ObrExchange
import kotlinx.coroutines.suspendCancellableCoroutine
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

/** What came back from a call. */
internal sealed interface ObrReply {
    /** The server answered with [code] and [body]. */
    data class Answered(val code: Int, val body: String) : ObrReply

    /** No answer; [certificate] tells whether the certificate of the server was refused. */
    data class Failed(val certificate: Boolean, val cause: String) : ObrReply
}

/**
 * Sends the calls to the EBMS: every call is a JSON `POST`, and every exchange is recorded with
 * its secrets masked. The token travels in a header, which is never recorded.
 *
 * @param http the HTTP client, from [ObrHttp.client].
 * @param recorder receives every exchange.
 * @param time source of the time of each exchange.
 */
internal class ObrTransport(
    private val http: OkHttpClient,
    private val recorder: ExchangeRecorder,
    private val time: TimeProvider,
) {
    /**
     * Sends one JSON `POST` and records the exchange.
     *
     * @param target the server to call.
     * @param service the service, as named by the specification (`login/`).
     * @param body the JSON body.
     * @param token the token sent as `Authorization: Bearer`, or null for `login/`.
     * @return what came back.
     */
    suspend fun post(target: LoginTarget, service: String, body: String, token: String? = null): ObrReply {
        val request = Request.Builder()
            .url(target.serverUrl + service)
            .post(body.toRequestBody(JSON))
            .apply { token?.let { header("Authorization", "Bearer $it") } }
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
                statusCode = (reply as? ObrReply.Answered)?.code,
                responseBody = (reply as? ObrReply.Answered)?.body?.let(SecretMasking::mask).orEmpty(),
                durationMillis = Duration.ofNanos(System.nanoTime() - start).toMillis(),
                failure = (reply as? ObrReply.Failed)?.cause.orEmpty(),
            ),
        )
        return reply
    }

    private suspend fun execute(call: Call): ObrReply = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    val reply = try {
                        response.use { ObrReply.Answered(it.code, it.body.string()) }
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

    private fun failed(failure: IOException): ObrReply.Failed {
        // When several addresses are tried (IPv6 and IPv4), the failure of one address is thrown
        // and the others are attached as suppressed: a refused certificate may be among them.
        val related = failure.related()
        val certificate = related.any { it is SSLPeerUnverifiedException } ||
            related.any { it is SSLHandshakeException } && related.any { it is CertificateException }
        return ObrReply.Failed(certificate, "${failure::class.java.simpleName}: ${failure.message.orEmpty()}")
    }

    /** @return this failure, its causes and the failures suppressed by any of them. */
    private fun Throwable.related(): List<Throwable> = generateSequence(this) { it.cause }
        .flatMap { sequenceOf(it) + it.suppressed.flatMap { suppressed -> suppressed.related() } }
        .toList()

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

package com.claytonmuhoza.easybilling.data.obr

import com.claytonmuhoza.easybilling.domain.customer.TinLookup
import com.claytonmuhoza.easybilling.domain.customer.TinReply
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.transmission.ExchangeRecorder
import com.claytonmuhoza.easybilling.domain.transmission.LoginOutcome
import com.claytonmuhoza.easybilling.domain.transmission.LoginTarget
import com.claytonmuhoza.easybilling.domain.transmission.ObrAuthentication
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.time.Duration

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
 * Client of the EBMS API: builds the requests of the specification and reads its answers.
 *
 * @param http the HTTP client, from [ObrHttp.client].
 * @param recorder receives every exchange.
 * @param time source of the time of each exchange.
 */
class ObrClient(http: OkHttpClient, recorder: ExchangeRecorder, time: TimeProvider) :
    ObrAuthentication,
    TinLookup {

    private val transport = ObrTransport(http, recorder, time)

    override suspend fun login(target: LoginTarget): LoginOutcome {
        val body = buildJsonObject {
            put("username", target.credentials.username)
            put("password", target.credentials.password)
        }
        return when (val reply = transport.post(target, LOGIN, body.toString())) {
            is ObrReply.Failed -> if (reply.certificate) LoginOutcome.CertificateRejected else LoginOutcome.Unreachable
            is ObrReply.Answered -> loginOutcome(reply)
        }
    }

    private fun loginOutcome(reply: ObrReply.Answered): LoginOutcome {
        val json = parse(reply.body)
        return when (reply.code) {
            HTTP_OK -> json?.token()?.let { LoginOutcome.Success(it) } ?: LoginOutcome.InvalidResponse
            HTTP_UNAUTHORIZED -> LoginOutcome.WrongCredentials
            HTTP_BAD_REQUEST, HTTP_FORBIDDEN -> LoginOutcome.Refused(json?.text("msg").orEmpty())
            in HTTP_SERVER_ERRORS -> LoginOutcome.ServerError
            else -> LoginOutcome.InvalidResponse
        }
    }

    override suspend fun check(target: LoginTarget, token: String, tin: String): TinReply {
        val body = buildJsonObject { put("tp_TIN", tin) }
        return when (val reply = transport.post(target, CHECK_TIN, body.toString(), token)) {
            is ObrReply.Failed -> if (reply.certificate) TinReply.CertificateRejected else TinReply.Unreachable
            is ObrReply.Answered -> tinReply(reply)
        }
    }

    private fun tinReply(reply: ObrReply.Answered): TinReply {
        val json = parse(reply.body)
        val message = json?.text("msg").orEmpty()
        return when (reply.code) {
            HTTP_OK -> json?.taxpayerName()?.let { TinReply.Known(it) } ?: TinReply.InvalidResponse
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> TinReply.TokenRefused
            HTTP_BAD_REQUEST -> if (message == UNKNOWN_TIN) TinReply.Unknown else TinReply.Refused(message)
            in HTTP_SERVER_ERRORS -> TinReply.ServerError
            else -> TinReply.InvalidResponse
        }
    }

    private companion object {
        const val LOGIN = "login/"
        const val CHECK_TIN = "checkTIN/"

        /** The only refusal of `checkTIN/` that means the TIN does not exist (page 28). */
        const val UNKNOWN_TIN = "NIF du contribuable inconnu."
        const val HTTP_OK = 200
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        val HTTP_SERVER_ERRORS = 500..599
    }
}

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

private fun JsonObject.taxpayerName(): String? {
    val success = (this["success"] as? JsonPrimitive)?.booleanOrNull
    val taxpayers = (this["result"] as? JsonObject)?.get("taxpayer") as? JsonArray
    val name = (taxpayers?.firstOrNull() as? JsonObject)?.text("tp_name")
    return name?.takeIf { success == true && it.isNotEmpty() }
}

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.content?.trim()

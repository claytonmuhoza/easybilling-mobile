package com.claytonmuhoza.easybilling.testing.obr

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fake EBMS server for the tests. Its answers follow the OBR specification V0.7: same paths, same
 * HTTP codes, same messages. Tests can force the next answers to reproduce failures.
 *
 * Only `login/` is served for now; the other services arrive with the steps that use them.
 *
 * @param username the user name the server accepts.
 * @param password the password the server accepts.
 */
class ObrSimulator(
    var username: String = DEFAULT_USERNAME,
    var password: String = DEFAULT_PASSWORD,
) : Closeable {

    /** The underlying server, for settings a test needs (HTTPS, for example) before [start]. */
    val server = MockWebServer()

    /** Every request received, oldest first. */
    val requests: MutableList<RecordedRequest> = CopyOnWriteArrayList()

    private val forced = ConcurrentLinkedQueue<MockResponse>()
    private val issuedTokens = AtomicInteger()

    /** Number of tokens given so far. */
    val loginCount: Int get() = issuedTokens.get()

    /** Address of the API, ending with `/`, as configured in the application. */
    val baseUrl: String get() = server.url(API_PATH).toString()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return forced.poll() ?: answer(request)
            }
        }
    }

    /** Starts the server on a free local port. */
    fun start() = server.start()

    override fun close() = server.close()

    /** The next request gets [response] instead of the normal answer. */
    fun forceNext(response: MockResponse) {
        forced += response
    }

    /** The next request gets an HTTP [code] with the OBR body `{"success":false,"msg":...}`. */
    fun failNext(code: Int, message: String) = forceNext(json(code, failure(message)))

    /** The next request waits [seconds] before any answer. */
    fun delayNext(seconds: Long) =
        forceNext(json(OK, failure("")).newBuilder().headersDelay(seconds, TimeUnit.SECONDS).build())

    /** The next request reaches the server, then the connection is cut: the answer is lost. */
    fun dropNext() = forceNext(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())

    private fun answer(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
        "${API_PATH}login/" -> login(request)
        else -> MockResponse.Builder().code(NOT_FOUND).build()
    }

    private fun login(request: RecordedRequest): MockResponse {
        val body = if (request.method == "POST") parse(request.body?.utf8().orEmpty()) else null
        val user = (body?.get("username") as? JsonPrimitive)?.content.orEmpty()
        val secret = (body?.get("password") as? JsonPrimitive)?.content.orEmpty()
        return when {
            request.method != "POST" -> json(BAD_REQUEST, failure(MESSAGE_NOT_POST))
            body == null -> json(BAD_REQUEST, failure(MESSAGE_INVALID_JSON))
            user.isEmpty() -> json(BAD_REQUEST, failure(MESSAGE_NO_USERNAME))
            secret.isEmpty() -> json(BAD_REQUEST, failure(MESSAGE_NO_PASSWORD))
            user != username || secret != password -> json(UNAUTHORIZED, failure(MESSAGE_WRONG_CREDENTIALS))
            else -> json(
                OK,
                buildJsonObject {
                    put("success", true)
                    put("msg", "Opération réussie")
                    putJsonObject("result") { put("token", "simulated-token-${issuedTokens.incrementAndGet()}") }
                },
            )
        }
    }

    private fun parse(body: String): JsonObject? =
        try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: SerializationException) {
            null
        }

    private fun failure(message: String) = buildJsonObject {
        put("success", false)
        put("msg", message)
    }

    private fun json(code: Int, body: JsonObject) = MockResponse.Builder()
        .code(code)
        .setHeader("Content-Type", "application/json")
        .body(body.toString())
        .build()

    /** Values of the specification and of the simulator. */
    companion object {
        /** Path of the API on the server. */
        const val API_PATH = "/ebms_api/"

        /** User name accepted unless a test sets another one. */
        const val DEFAULT_USERNAME = "ws400000000100001"

        /** Password accepted unless a test sets another one. */
        const val DEFAULT_PASSWORD = "simulated-password"

        /** Message of a wrong login (401), page 16 of the specification. */
        const val MESSAGE_WRONG_CREDENTIALS = "Nom d'utilisateur ou mot de passe incorrect."

        /** Message of a server error (500), page 16 of the specification. */
        const val MESSAGE_SERVER_ERROR = "Quelque chose a mal tourné. Veuillez réessayer plus tard."

        /** Message of a login without user name (400), page 16 of the specification. */
        const val MESSAGE_NO_USERNAME = "Veuillez fournir un nom d'utilisateur."

        /** Message of a login without password (400), page 16 of the specification. */
        const val MESSAGE_NO_PASSWORD = "Veuillez fournir un mot de passe."

        /** Message of a request that is not a POST (400), page 16 of the specification. */
        const val MESSAGE_NOT_POST = "Veuillez envoyer les données en utilisant la méthode POST."

        /** Message of a body that is not JSON (400), page 16 of the specification. */
        const val MESSAGE_INVALID_JSON = "Le format de la chaine de caractère JSON est invalide."

        private const val OK = 200
        private const val BAD_REQUEST = 400
        private const val UNAUTHORIZED = 401
        private const val NOT_FOUND = 404
    }
}

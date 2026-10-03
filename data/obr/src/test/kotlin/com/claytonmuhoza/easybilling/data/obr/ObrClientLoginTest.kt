package com.claytonmuhoza.easybilling.data.obr

import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentials
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.transmission.ExchangeRecorder
import com.claytonmuhoza.easybilling.domain.transmission.LoginOutcome
import com.claytonmuhoza.easybilling.domain.transmission.LoginTarget
import com.claytonmuhoza.easybilling.domain.transmission.ObrExchange
import com.claytonmuhoza.easybilling.testing.obr.ObrSimulator
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import okhttp3.tls.HeldCertificate
import okhttp3.tls.HandshakeCertificates
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `login/` against the simulated OBR server (UC-A05, UC-D08). */
class ObrClientLoginTest {

    private val simulator = ObrSimulator()
    private val exchanges = mutableListOf<ObrExchange>()
    private val now = Instant.parse("2026-10-03T08:00:00Z")
    private val http: OkHttpClient = ObrHttp.client(timeout = Duration.ofSeconds(1))
    private val client: ObrClient = ObrClient(http, ExchangeRecorder { exchanges.add(it) }, TimeProvider { now })

    private val credentials = EbmsCredentials(ObrSimulator.DEFAULT_USERNAME, ObrSimulator.DEFAULT_PASSWORD)
    private lateinit var target: LoginTarget

    @BeforeTest
    fun start() {
        simulator.start()
        target = LoginTarget(EbmsEnvironment.TEST, simulator.baseUrl, credentials)
    }

    @AfterTest
    fun stop() = simulator.close()

    @Test
    fun `the right credentials give the token`() = runTest {
        assertEquals(LoginOutcome.Success("simulated-token-1"), client.login(target))
    }

    @Test
    fun `the request is a JSON POST to login with only the user name and the password`() = runTest {
        client.login(target)

        val request = simulator.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/ebms_api/login/", request.url.encodedPath)
        assertTrue(request.headers["Content-Type"].orEmpty().startsWith("application/json"))
        assertNull(request.headers["Authorization"])
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(setOf("username", "password"), body.keys)
        assertTrue(body.values.all { it is JsonPrimitive && it.isString }, "Every value is a non-null string: $body")
    }

    @Test
    fun `a password with spaces and quotes is sent as typed`() = runTest {
        simulator.password = """ pa"ss word """

        val typed = target.copy(credentials = credentials.copy(password = """ pa"ss word """))

        assertIs<LoginOutcome.Success>(client.login(typed))
    }

    @Test
    fun `wrong credentials give a distinct outcome`() = runTest {
        simulator.password = "another"

        assertEquals(LoginOutcome.WrongCredentials, client.login(target))
    }

    @Test
    fun `a refusal keeps the message of the OBR`() = runTest {
        simulator.failNext(400, ObrSimulator.MESSAGE_NO_PASSWORD)

        assertEquals(LoginOutcome.Refused(ObrSimulator.MESSAGE_NO_PASSWORD), client.login(target))
    }

    @Test
    fun `a server error gives a distinct outcome`() = runTest {
        simulator.failNext(500, ObrSimulator.MESSAGE_SERVER_ERROR)
        assertEquals(LoginOutcome.ServerError, client.login(target))

        simulator.forceNext(MockResponse.Builder().code(503).body("Service Unavailable").build())
        assertEquals(LoginOutcome.ServerError, client.login(target))
    }

    @Test
    fun `a success without token or a body that is not JSON is not a response of the specification`() = runTest {
        simulator.forceNext(MockResponse.Builder().code(200).body("""{"success":true,"msg":"ok"}""").build())
        assertEquals(LoginOutcome.InvalidResponse, client.login(target))

        simulator.forceNext(MockResponse.Builder().code(200).body("<html>Maintenance</html>").build())
        assertEquals(LoginOutcome.InvalidResponse, client.login(target))
    }

    @Test
    fun `a server that does not answer in time is unreachable`() = runTest {
        simulator.delayNext(seconds = 3)

        assertEquals(LoginOutcome.Unreachable, client.login(target))
    }

    @Test
    fun `a connection cut before the answer is unreachable`() = runTest {
        simulator.dropNext()

        assertEquals(LoginOutcome.Unreachable, client.login(target))
    }

    @Test
    fun `a server that is not running is unreachable`() = runTest {
        val freePort = ServerSocket(0).use { it.localPort }

        val nobody = target.copy(serverUrl = "http://127.0.0.1:$freePort/ebms_api/")

        assertEquals(LoginOutcome.Unreachable, client.login(nobody))
    }

    @Test
    fun `an untrusted certificate is refused before anything is sent`() = runTest {
        simulator.close()
        val secure = ObrSimulator()
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        secure.server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory())
        secure.start()
        try {
            val url = secure.baseUrl

            // A TLS handshake can take more than the one second of the other tests on a busy machine.
            val patientHttp = ObrHttp.client(Duration.ofSeconds(10))
            val patient = ObrClient(patientHttp, ExchangeRecorder { exchanges.add(it) }, TimeProvider { now })

            val outcome = patient.login(target.copy(serverUrl = url))

            assertEquals(LoginOutcome.CertificateRejected, outcome, "$exchanges")
            assertEquals(emptyList(), secure.requests)
        } finally {
            secure.close()
        }
    }

    @Test
    fun `each exchange is recorded with its code and duration, and without any secret`() = runTest {
        client.login(target)

        val exchange = exchanges.single()
        assertEquals(now, exchange.startedAt)
        assertEquals(EbmsEnvironment.TEST, exchange.environment)
        assertEquals("login/", exchange.service)
        assertEquals(200, exchange.statusCode)
        assertTrue(exchange.durationMillis >= 0)
        assertFalse(ObrSimulator.DEFAULT_PASSWORD in exchange.requestBody, exchange.requestBody)
        assertFalse("simulated-token-1" in exchange.responseBody, exchange.responseBody)
        assertEquals(ObrSimulator.DEFAULT_USERNAME, field(exchange.requestBody, "username"))
        assertEquals(SecretMasking.MASK, field(exchange.requestBody, "password"))
    }

    @Test
    fun `a failed exchange is recorded with its cause`() = runTest {
        simulator.dropNext()

        client.login(target)

        val exchange = exchanges.single()
        assertNull(exchange.statusCode)
        assertEquals("", exchange.responseBody)
        assertTrue(exchange.failure.isNotEmpty())
    }

    @Test
    fun `the client never retries a request by itself`() {
        assertEquals(false, http.retryOnConnectionFailure)
        assertEquals(1_000, http.callTimeoutMillis)
    }

    private fun field(body: String, name: String): String =
        ((Json.parseToJsonElement(body) as JsonObject)[name] as JsonPrimitive).content
}

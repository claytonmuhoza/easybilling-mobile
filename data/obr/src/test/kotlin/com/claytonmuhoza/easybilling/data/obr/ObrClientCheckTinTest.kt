package com.claytonmuhoza.easybilling.data.obr

import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentials
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.customer.TinCheck
import com.claytonmuhoza.easybilling.domain.customer.TinCheckFailure
import com.claytonmuhoza.easybilling.domain.customer.TinReply
import com.claytonmuhoza.easybilling.domain.customer.VerifyTin
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.transmission.CachingTokenProvider
import com.claytonmuhoza.easybilling.domain.transmission.ExchangeRecorder
import com.claytonmuhoza.easybilling.domain.transmission.LoginOutcome
import com.claytonmuhoza.easybilling.domain.transmission.LoginTarget
import com.claytonmuhoza.easybilling.domain.transmission.ObrExchange
import com.claytonmuhoza.easybilling.testing.obr.ObrSimulator
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `checkTIN/` against the simulated OBR server (UC-B06). */
class ObrClientCheckTinTest {

    private val simulator = ObrSimulator()
    private val exchanges = mutableListOf<ObrExchange>()
    private val now = Instant.parse("2026-10-03T08:00:00Z")
    private val client = ObrClient(
        ObrHttp.client(timeout = Duration.ofSeconds(1)),
        ExchangeRecorder { exchanges.add(it) },
        TimeProvider { now },
    )
    private lateinit var target: LoginTarget

    @BeforeTest
    fun start() {
        simulator.start()
        val credentials = EbmsCredentials(ObrSimulator.DEFAULT_USERNAME, ObrSimulator.DEFAULT_PASSWORD)
        target = LoginTarget(EbmsEnvironment.TEST, simulator.baseUrl, credentials)
    }

    @AfterTest
    fun stop() = simulator.close()

    private suspend fun token(): String = assertIs<LoginOutcome.Success>(client.login(target)).token

    @Test
    fun `a TIN the OBR knows gives the name of its owner`() = runTest {
        assertEquals(TinReply.Known(ObrSimulator.KNOWN_TAXPAYER), client.check(target, token(), ObrSimulator.KNOWN_TIN))
    }

    @Test
    fun `the request is a JSON POST to checkTIN with the token and only the TIN`() = runTest {
        val token = token()

        client.check(target, token, ObrSimulator.KNOWN_TIN)

        val request = simulator.requests.last()
        assertEquals("POST", request.method)
        assertEquals("/ebms_api/checkTIN/", request.url.encodedPath)
        assertEquals("Bearer $token", request.headers["Authorization"])
        assertTrue(request.headers["Content-Type"].orEmpty().startsWith("application/json"))
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(mapOf("tp_TIN" to JsonPrimitive(ObrSimulator.KNOWN_TIN)), body.toMap())
    }

    @Test
    fun `a TIN the OBR does not know is unknown`() = runTest {
        assertEquals(TinReply.Unknown, client.check(target, token(), "999"))
    }

    @Test
    fun `another refusal keeps the message of the OBR and does not mark the TIN unknown`() = runTest {
        assertEquals(TinReply.Refused(ObrSimulator.MESSAGE_NO_TIN), client.check(target, token(), ""))
    }

    @Test
    fun `the name is read even when the OBR pads it with spaces`() = runTest {
        simulator.taxpayers["4000303030"] = "  BRASSERIE DU LAC "

        assertEquals(TinReply.Known("BRASSERIE DU LAC"), client.check(target, token(), "4000303030"))
    }

    @Test
    fun `a refused token asks for a new one`() = runTest {
        val token = token()
        simulator.expireTokens()

        assertEquals(TinReply.TokenRefused, client.check(target, token, ObrSimulator.KNOWN_TIN))
    }

    @Test
    fun `a server error gives a distinct reply`() = runTest {
        val token = token()
        simulator.failNext(500, ObrSimulator.MESSAGE_SERVER_ERROR)

        assertEquals(TinReply.ServerError, client.check(target, token, ObrSimulator.KNOWN_TIN))
    }

    @Test
    fun `a success without a name or a body that is not JSON is not a response of the specification`() = runTest {
        val token = token()
        val broken = listOf(
            """{"success":true,"msg":"ok"}""",
            """{"success":true,"msg":"ok","result":{"taxpayer":[]}}""",
            """{"success":true,"msg":"ok","result":{"taxpayer":[{"tp_name":""}]}}""",
            "<html>Maintenance</html>",
        )
        for (body in broken) {
            simulator.forceNext(MockResponse.Builder().code(200).body(body).build())
            assertEquals(TinReply.InvalidResponse, client.check(target, token, ObrSimulator.KNOWN_TIN), body)
        }
    }

    @Test
    fun `without network the OBR is unreachable`() = runTest {
        val token = token()

        simulator.dropNext()
        assertEquals(TinReply.Unreachable, client.check(target, token, ObrSimulator.KNOWN_TIN))

        simulator.delayNext(seconds = 3)
        assertEquals(TinReply.Unreachable, client.check(target, token, ObrSimulator.KNOWN_TIN))

        val freePort = ServerSocket(0).use { it.localPort }
        val nobody = target.copy(serverUrl = "http://127.0.0.1:$freePort/ebms_api/")
        assertEquals(TinReply.Unreachable, client.check(nobody, token, ObrSimulator.KNOWN_TIN))
    }

    @Test
    fun `the exchange is recorded without the token`() = runTest {
        val token = token()

        client.check(target, token, ObrSimulator.KNOWN_TIN)

        val exchange = exchanges.last()
        assertEquals("checkTIN/", exchange.service)
        assertEquals(200, exchange.statusCode)
        assertEquals("""{"tp_TIN":"${ObrSimulator.KNOWN_TIN}"}""", exchange.requestBody)
        assertTrue(ObrSimulator.KNOWN_TAXPAYER in exchange.responseBody)
        assertFalse(exchanges.any { token in it.requestBody || token in it.responseBody })
    }

    @Test
    fun `with the token provider, an expired token is renewed and the check sent again once`() = runTest {
        val tokens = CachingTokenProvider({ target }, client, TimeProvider { now })
        val verify = VerifyTin({ target }, tokens, client)
        assertEquals(TinCheck.Known(ObrSimulator.KNOWN_TAXPAYER), verify(ObrSimulator.KNOWN_TIN))

        simulator.expireTokens()

        assertEquals(TinCheck.Known(ObrSimulator.KNOWN_TAXPAYER), verify(ObrSimulator.KNOWN_TIN))
        assertEquals(2, simulator.loginCount)
        assertEquals(
            listOf("login/", "checkTIN/", "checkTIN/", "login/", "checkTIN/"),
            simulator.requests.map { it.url.encodedPath.removePrefix(ObrSimulator.API_PATH) },
        )
    }

    @Test
    fun `with the token provider, a server that is down says the OBR could not be reached`() = runTest {
        simulator.close()
        val tokens = CachingTokenProvider({ target }, client, TimeProvider { now })

        val check = VerifyTin({ target }, tokens, client)(ObrSimulator.KNOWN_TIN)

        assertEquals(TinCheck.Failed(TinCheckFailure.UNREACHABLE), check)
    }
}

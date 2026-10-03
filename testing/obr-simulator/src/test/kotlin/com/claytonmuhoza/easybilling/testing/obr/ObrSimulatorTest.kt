package com.claytonmuhoza.easybilling.testing.obr

import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The simulator answers `login/` and `checkTIN/` as the specification describes (pages 16 and 28). */
class ObrSimulatorTest {

    private val simulator = ObrSimulator()

    @BeforeTest
    fun start() = simulator.start()

    @AfterTest
    fun stop() = simulator.close()

    private fun post(path: String, body: String, method: String = "POST", token: String? = null): Pair<Int, String> {
        val connection = URI(simulator.baseUrl + path).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        if (method == "POST") {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = connection.responseCode
        val stream = if (code < 400) connection.inputStream else connection.errorStream
        return code to stream.bufferedReader().readText()
    }

    @Test
    fun `the right credentials give a token`() {
        val (code, body) = post("login/", """{"username":"ws400000000100001","password":"simulated-password"}""")

        assertEquals(200, code)
        assertTrue(""""token":"simulated-token-1"""" in body, body)
        assertEquals(1, simulator.loginCount)
    }

    @Test
    fun `wrong credentials are refused with 401`() {
        val (code, body) = post("login/", """{"username":"ws400000000100001","password":"wrong"}""")

        assertEquals(401, code)
        assertTrue(ObrSimulator.MESSAGE_WRONG_CREDENTIALS in body, body)
    }

    @Test
    fun `a missing field or a broken body is refused with 400`() {
        assertEquals(400, post("login/", """{"username":"ws400000000100001"}""").first)
        assertEquals(400, post("login/", """{"password":"simulated-password"}""").first)
        assertEquals(400, post("login/", """not json""").first)
        assertEquals(400, post("login/", "", method = "GET").first)
    }

    private fun token(): String {
        post("login/", """{"username":"ws400000000100001","password":"simulated-password"}""")
        return "simulated-token-${simulator.loginCount}"
    }

    @Test
    fun `a known TIN gives the name of its owner`() {
        val (code, body) = post("checkTIN/", """{"tp_TIN":"4000202020"}""", token = token())

        assertEquals(200, code)
        assertEquals(
            """{"success":true,"msg":"Opération réussie","result":{"taxpayer":[{"tp_name":"NDIKUMANA Jean Marie"}]}}""",
            body,
        )
    }

    @Test
    fun `an unknown TIN, a missing TIN or a broken body is refused with 400`() {
        val token = token()
        simulator.taxpayers["4000303030"] = "BRASSERIE DU LAC"

        assertEquals(200, post("checkTIN/", """{"tp_TIN":"4000303030"}""", token = token).first)
        val (code, body) = post("checkTIN/", """{"tp_TIN":"999"}""", token = token)
        assertEquals(400, code)
        assertTrue(ObrSimulator.MESSAGE_UNKNOWN_TIN in body, body)
        assertTrue(ObrSimulator.MESSAGE_NO_TIN in post("checkTIN/", """{}""", token = token).second)
        assertTrue(ObrSimulator.MESSAGE_INVALID_JSON in post("checkTIN/", """not json""", token = token).second)
        assertTrue(ObrSimulator.MESSAGE_USE_POST in post("checkTIN/", "", method = "GET", token = token).second)
    }

    @Test
    fun `a call without an accepted token is refused with 403`() {
        val (code, body) = post("checkTIN/", """{"tp_TIN":"4000202020"}""")
        assertEquals(403, code)
        assertTrue(ObrSimulator.MESSAGE_NO_API_KEY in body, body)
        assertEquals(403, post("checkTIN/", """{"tp_TIN":"4000202020"}""", token = "made-up").first)

        val token = token()
        simulator.expireTokens()
        assertEquals(403, post("checkTIN/", """{"tp_TIN":"4000202020"}""", token = token).first)
        assertEquals(200, post("checkTIN/", """{"tp_TIN":"4000202020"}""", token = token()).first)
    }

    @Test
    fun `a forced failure is served once`() {
        simulator.failNext(500, ObrSimulator.MESSAGE_SERVER_ERROR)

        assertEquals(500, post("login/", """{"username":"ws400000000100001","password":"simulated-password"}""").first)
        assertEquals(200, post("login/", """{"username":"ws400000000100001","password":"simulated-password"}""").first)
    }
}

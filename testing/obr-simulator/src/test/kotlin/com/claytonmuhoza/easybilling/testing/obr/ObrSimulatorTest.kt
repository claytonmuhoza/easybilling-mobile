package com.claytonmuhoza.easybilling.testing.obr

import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The simulator answers `login/` as the specification describes (page 16). */
class ObrSimulatorTest {

    private val simulator = ObrSimulator()

    @BeforeTest
    fun start() = simulator.start()

    @AfterTest
    fun stop() = simulator.close()

    private fun post(path: String, body: String, method: String = "POST"): Pair<Int, String> {
        val connection = URI(simulator.baseUrl + path).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
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

    @Test
    fun `a forced failure is served once`() {
        simulator.failNext(500, ObrSimulator.MESSAGE_SERVER_ERROR)

        assertEquals(500, post("login/", """{"username":"ws400000000100001","password":"simulated-password"}""").first)
        assertEquals(200, post("login/", """{"username":"ws400000000100001","password":"simulated-password"}""").first)
    }
}

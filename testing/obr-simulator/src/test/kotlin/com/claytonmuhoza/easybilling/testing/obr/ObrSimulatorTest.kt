package com.claytonmuhoza.easybilling.testing.obr

import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The simulator answers `login/`, `checkTIN/`, `addInvoice_confirm/` and `getInvoice/` as the
 * specification describes (pages 16 to 28).
 */
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

    /** A complete invoice, as short as the mandatory fields allow. */
    private fun invoice(number: String, customerTin: String = "") = """
        {"invoice_number":"$number","invoice_date":"2026-10-03 10:15:42","tp_type":"2","tp_name":"Boutique",
        "tp_TIN":"4000000001","tp_trade_number":"RC 1","tp_phone_number":"22","tp_address_commune":"Mukaza",
        "tp_address_quartier":"Rohero","vat_taxpayer":"1","ct_taxpayer":"0","tl_taxpayer":"0",
        "tp_fiscal_center":"DMC","tp_activity_sector":"Commerce","tp_legal_form":"SPRL","payment_type":"1",
        "customer_name":"Client comptoir","customer_TIN":"$customerTin","invoice_identifier":"id/$number",
        "invoice_items":[{"item_designation":"Savon","item_quantity":"1","item_price":"1000"}]}
    """.trimIndent()

    @Test
    fun `an invoice is acknowledged with a registered number and a signature, then found by its identifier`() {
        val token = token()

        val (code, body) = post("addInvoice_confirm/", invoice("FN-1"), token = token)

        assertEquals(200, code)
        assertTrue(ObrSimulator.MESSAGE_INVOICE_ADDED in body, body)
        assertTrue(""""invoice_registered_number":"4530253"""" in body, body)
        assertTrue(""""electronic_signature":"signature-1"""" in body, body)
        assertEquals(setOf("id/FN-1"), simulator.invoices.keys)

        val (found, invoices) = post("getInvoice/", """{"invoice_identifier":"id/FN-1"}""", token = token)
        assertEquals(200, found)
        assertTrue(""""invoices":[{"invoice_number":"FN-1"""" in invoices, invoices)
        val unknown = post("getInvoice/", """{"invoice_identifier":"x"}""", token = token)
        assertTrue(ObrSimulator.MESSAGE_UNKNOWN_IDENTIFIER in unknown.second, unknown.second)
        assertTrue(ObrSimulator.MESSAGE_NO_IDENTIFIER in post("getInvoice/", """{}""", token = token).second)
    }

    @Test
    fun `an invoice that is incomplete, known already or for an unknown customer TIN is refused with 400`() {
        val token = token()
        post("addInvoice_confirm/", invoice("FN-1"), token = token)

        val duplicate = post("addInvoice_confirm/", invoice("FN-1"), token = token)
        assertEquals(400, duplicate.first)
        assertTrue(ObrSimulator.MESSAGE_DUPLICATE_NUMBER in duplicate.second, duplicate.second)
        val incomplete = post("addInvoice_confirm/", invoice("FN-2").replace("\"RC 1\"", "\"\""), token = token)
        assertTrue(ObrSimulator.MESSAGE_MISSING_FIELDS in incomplete.second, incomplete.second)
        val unknown = post("addInvoice_confirm/", invoice("FN-2", customerTin = "999"), token = token)
        assertTrue(ObrSimulator.MESSAGE_UNKNOWN_CUSTOMER_TIN in unknown.second, unknown.second)
        assertEquals(200, post("addInvoice_confirm/", invoice("FN-2", customerTin = "4000202020"), token = token).first)
        assertEquals(403, post("addInvoice_confirm/", invoice("FN-3")).first)
        assertEquals(setOf("id/FN-1", "id/FN-2"), simulator.invoices.keys)
    }

    @Test
    fun `a lost acknowledgement leaves the invoice on the server`() {
        val token = token()
        simulator.loseNextAcknowledgement()

        // The sender gets no answer; the Java client used here may even send the request again.
        runCatching { post("addInvoice_confirm/", invoice("FN-1"), token = token) }

        assertEquals(setOf("id/FN-1"), simulator.invoices.keys)
        assertEquals(200, post("getInvoice/", """{"invoice_identifier":"id/FN-1"}""", token = token).first)
    }

    @Test
    fun `a forced failure is served once`() {
        simulator.failNext(500, ObrSimulator.MESSAGE_SERVER_ERROR)

        assertEquals(500, post("login/", """{"username":"ws400000000100001","password":"simulated-password"}""").first)
        assertEquals(200, post("login/", """{"username":"ws400000000100001","password":"simulated-password"}""").first)
    }
}

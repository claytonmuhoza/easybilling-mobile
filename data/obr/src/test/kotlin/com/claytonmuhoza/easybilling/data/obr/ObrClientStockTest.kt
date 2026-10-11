package com.claytonmuhoza.easybilling.data.obr

import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentials
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.stock.MovementId
import com.claytonmuhoza.easybilling.domain.stock.MovementType
import com.claytonmuhoza.easybilling.domain.stock.StockMovement
import com.claytonmuhoza.easybilling.domain.stock.StockMovementContent
import com.claytonmuhoza.easybilling.domain.transmission.ExchangeRecorder
import com.claytonmuhoza.easybilling.domain.transmission.LoginOutcome
import com.claytonmuhoza.easybilling.domain.transmission.LoginTarget
import com.claytonmuhoza.easybilling.domain.transmission.MovementOutcome
import com.claytonmuhoza.easybilling.domain.transmission.ObrExchange
import com.claytonmuhoza.easybilling.testing.obr.ObrSimulator
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `AddStockMovement/` against the simulated OBR server (UC-F01 to F07, pages 30 to 32). */
class ObrClientStockTest {

    private val simulator = ObrSimulator()
    private val exchanges = mutableListOf<ObrExchange>()
    private val now = Instant.parse("2026-10-03T08:15:42Z")
    private val client = ObrClient(
        ObrHttp.client(timeout = Duration.ofSeconds(1)),
        ExchangeRecorder { exchanges.add(it) },
        TimeProvider { now },
    )
    private lateinit var target: LoginTarget

    private fun movement(type: MovementType = MovementType.EN, invoiceRef: String? = null) = StockMovement(
        id = MovementId(1),
        content = StockMovementContent(
            reference = "A1-000007",
            itemId = ItemId(1),
            itemCode = "ART-0001",
            designation = "Savon de Marseille",
            unit = "pièce",
            type = type,
            quantity = Quantity.of("12.5"),
            unitCost = Money.of("812.5", Currency.BIF),
            description = "Facture fournisseur F-88",
            invoiceRef = invoiceRef,
            occurredAt = now,
            environment = EbmsEnvironment.TEST,
            recordedBy = UserId(1),
        ),
    )

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
    fun `a movement is sent with every field of the specification, as texts`() = runTest {
        val outcome = client.addStockMovement(target, token(), movement())

        assertEquals(MovementOutcome.Accepted, outcome)
        assertEquals("/ebms_api/AddStockMovement/", simulator.requests.last().url.encodedPath)
        assertEquals(
            mapOf(
                "system_or_device_id" to ObrSimulator.DEFAULT_USERNAME,
                "item_code" to "ART-0001",
                "item_designation" to "Savon de Marseille",
                "item_quantity" to "12.5",
                "item_measurement_unit" to "pièce",
                "item_cost_price" to "812.5",
                "item_cost_price_currency" to "BIF",
                "item_movement_type" to "EN",
                "item_movement_invoice_ref" to "",
                "item_movement_description" to "A1-000007 Facture fournisseur F-88",
                // 08:15:42 UTC is 10:15:42 in Bujumbura.
                "item_movement_date" to "2026-10-03 10:15:42",
            ),
            simulator.stockMovements.single().mapValues { (it.value as JsonPrimitive).content },
        )
        assertTrue(simulator.stockMovements.single().values.all { (it as JsonPrimitive).isString })
        assertEquals(200, exchanges.last().statusCode)
    }

    @Test
    fun `goods brought back name their invoice`() = runTest {
        client.addStockMovement(target, token(), movement(MovementType.ER, invoiceRef = "FN-00001/2026"))

        val sent = simulator.stockMovements.single()
        assertEquals("ER", (sent["item_movement_type"] as JsonPrimitive).content)
        assertEquals("FN-00001/2026", (sent["item_movement_invoice_ref"] as JsonPrimitive).content)
    }

    @Test
    fun `another system identifier is a refusal with the message of the OBR`() = runTest {
        val token = token()
        val other = target.copy(credentials = EbmsCredentials("ws999999999900001", ObrSimulator.DEFAULT_PASSWORD))

        val outcome = client.addStockMovement(other, token, movement())

        assertEquals(MovementOutcome.Rejected(ObrSimulator.MESSAGE_WRONG_SYSTEM_ID), outcome)
        assertEquals(emptyList(), simulator.stockMovements)
    }

    @Test
    fun `a refused token, a server error and a lost answer are told apart`() = runTest {
        val token = token()
        simulator.expireTokens()
        assertEquals(MovementOutcome.TokenRefused, client.addStockMovement(target, token, movement()))

        val fresh = token()
        simulator.failNext(500, ObrSimulator.MESSAGE_SERVER_ERROR)
        assertEquals(MovementOutcome.NotDelivered("HTTP 500"), client.addStockMovement(target, fresh, movement()))

        // The request left and no answer came: the OBR may have the movement.
        simulator.dropNext()
        assertIs<MovementOutcome.Uncertain>(client.addStockMovement(target, fresh, movement()))
    }

    @Test
    fun `a movement the server kept without answering is uncertain`() = runTest {
        val token = token()
        simulator.loseNextMovementAnswer()

        assertIs<MovementOutcome.Uncertain>(client.addStockMovement(target, token, movement()))
        assertEquals(1, simulator.stockMovements.size)
    }

    @Test
    fun `a success that cannot be read is uncertain, not accepted`() = runTest {
        val token = token()
        for (body in listOf("", "<html>Maintenance</html>", """{"success":false,"msg":"?"}""")) {
            simulator.forceNext(MockResponse.Builder().code(200).body(body).build())
            assertIs<MovementOutcome.Uncertain>(client.addStockMovement(target, token, movement()), body)
        }
    }

    @Test
    fun `a server nobody answers at was certainly not reached`() = runTest {
        val token = token()
        val freePort = ServerSocket(0).use { it.localPort }
        val nobody = target.copy(serverUrl = "http://127.0.0.1:$freePort/ebms_api/")

        assertIs<MovementOutcome.NotDelivered>(client.addStockMovement(nobody, token, movement()))
        assertEquals(emptyList(), simulator.stockMovements)
    }

    @Test
    fun `the server keeps the same movement twice, since it cannot tell them apart`() = runTest {
        val token = token()

        client.addStockMovement(target, token, movement())
        client.addStockMovement(target, token, movement())

        assertEquals(2, simulator.stockMovements.size)
    }
}

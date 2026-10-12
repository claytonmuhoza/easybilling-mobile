package com.claytonmuhoza.easybilling.data.obr

import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentials
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.stock.DmcLine
import com.claytonmuhoza.easybilling.domain.stock.ImportedGoods
import com.claytonmuhoza.easybilling.domain.stock.MovementId
import com.claytonmuhoza.easybilling.domain.stock.MovementType
import com.claytonmuhoza.easybilling.domain.stock.StockMovement
import com.claytonmuhoza.easybilling.domain.stock.StockMovementContent
import com.claytonmuhoza.easybilling.domain.transmission.DmcOutcome
import com.claytonmuhoza.easybilling.domain.transmission.ExchangeRecorder
import com.claytonmuhoza.easybilling.domain.transmission.LoginOutcome
import com.claytonmuhoza.easybilling.domain.transmission.LoginTarget
import com.claytonmuhoza.easybilling.domain.transmission.MovementOutcome
import com.claytonmuhoza.easybilling.testing.obr.ObrSimulator
import com.claytonmuhoza.easybilling.testing.obr.SimulatedDmc
import com.claytonmuhoza.easybilling.testing.obr.SimulatedDmcItem
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `getDmcItems` and `AddStockMovementImporters/` against the simulated OBR server (UC-F03, F04,
 * pages 32 to 36 of the specification).
 */
class ObrClientImportTest {

    private val simulator = ObrSimulator()
    private val now = Instant.parse("2026-10-03T08:15:42Z")
    private val client = ObrClient(
        ObrHttp.client(timeout = Duration.ofSeconds(1)),
        ExchangeRecorder { },
        TimeProvider { now },
    )
    private lateinit var target: LoginTarget

    /** The declaration of the example of page 33. */
    private val declared = SimulatedDmc(
        TIN,
        listOf(
            SimulatedDmcItem("84412000000", "Machines pour la fabrication de sacs", "10", "Sacs"),
            SimulatedDmcItem("84141000000", "Pompes à vide", "5", "Sacs"),
        ),
    )

    private fun movement(type: MovementType = MovementType.EN) = StockMovement(
        id = MovementId(1),
        content = StockMovementContent(
            reference = "A1-000007",
            itemId = ItemId(1),
            itemCode = "100",
            designation = "Amstel 65cl",
            unit = "bouteille",
            type = type,
            quantity = Quantity.of("5"),
            unitCost = Money.of("2000", Currency.BIF),
            description = "",
            occurredAt = now,
            environment = EbmsEnvironment.TEST,
            recordedBy = UserId(1),
            imported = ImportedGoods(REFERENCE, 1, "15119090000", BigDecimal("300"), "Jerrycans"),
        ),
    )

    @BeforeTest
    fun start() {
        simulator.start()
        simulator.dmcs[REFERENCE] = declared
        val credentials = EbmsCredentials(ObrSimulator.DEFAULT_USERNAME, ObrSimulator.DEFAULT_PASSWORD)
        target = LoginTarget(EbmsEnvironment.TEST, simulator.baseUrl, credentials)
    }

    @AfterTest
    fun stop() = simulator.close()

    private suspend fun token(): String = assertIs<LoginOutcome.Success>(client.login(target)).token

    @Test
    fun `a declaration is asked with the NIF and its reference, and read line by line`() = runTest {
        val outcome = client.getDmcItems(target, token(), TIN, REFERENCE)

        val request = simulator.requests.last()
        assertEquals("/ebms_api/getDmcItems", request.url.encodedPath)
        val sent = Json.parseToJsonElement(request.body!!.utf8()) as JsonObject
        assertEquals(
            mapOf("nif" to TIN, "reference_dmc" to REFERENCE),
            sent.mapValues { (it.value as JsonPrimitive).content },
        )
        assertEquals(
            DmcOutcome.Found(
                listOf(
                    DmcLine(1, "84412000000", "Machines pour la fabrication de sacs", BigDecimal("10"), "Sacs"),
                    DmcLine(2, "84141000000", "Pompes à vide", BigDecimal("5"), "Sacs"),
                ),
            ),
            outcome,
        )
    }

    @Test
    fun `a quantity that is not a number is read as unknown`() = runTest {
        simulator.dmcs[REFERENCE] = SimulatedDmc(TIN, listOf(SimulatedDmcItem("84412000000", "Machines", "", "Sacs")))

        val lines = assertIs<DmcOutcome.Found>(client.getDmcItems(target, token(), TIN, REFERENCE)).lines

        assertEquals(null, lines.single().quantity)
    }

    @Test
    fun `the declaration of another NIF is refused with the message of the OBR`() = runTest {
        val outcome = client.getDmcItems(target, token(), "4000000028", REFERENCE)

        assertEquals(DmcOutcome.Refused(ObrSimulator.MESSAGE_DMC_OF_ANOTHER_TIN), outcome)
        assertEquals(
            DmcOutcome.Refused(ObrSimulator.MESSAGE_DMC_NO_TIN),
            client.getDmcItems(target, token(), "", REFERENCE),
        )
    }

    @Test
    fun `a refused token, a server error, a lost answer and an unreadable success are told apart`() = runTest {
        val token = token()
        simulator.expireTokens()
        assertEquals(DmcOutcome.TokenRefused, client.getDmcItems(target, token, TIN, REFERENCE))

        val fresh = token()
        simulator.failNext(500, ObrSimulator.MESSAGE_SERVER_ERROR)
        assertEquals(DmcOutcome.Unavailable("HTTP 500"), client.getDmcItems(target, fresh, TIN, REFERENCE))
        simulator.dropNext()
        assertIs<DmcOutcome.Unavailable>(client.getDmcItems(target, fresh, TIN, REFERENCE))
        for (body in listOf("<html>Maintenance</html>", """{"success":true,"msg":"?"}""")) {
            simulator.forceNext(MockResponse.Builder().code(200).body(body).build())
            assertIs<DmcOutcome.Unavailable>(client.getDmcItems(target, fresh, TIN, REFERENCE), body)
        }
    }

    @Test
    fun `an entry of imported goods goes to its own service with the fields of the declaration`() = runTest {
        val outcome = client.addStockMovement(target, token(), movement())

        assertEquals(MovementOutcome.Accepted, outcome)
        assertEquals("/ebms_api/AddStockMovementImporters/", simulator.requests.last().url.encodedPath)
        assertEquals(emptyList(), simulator.stockMovements)
        assertEquals(
            mapOf(
                "system_or_device_id" to ObrSimulator.DEFAULT_USERNAME,
                "item_code" to "100",
                "item_designation" to "Amstel 65cl",
                "item_quantity" to "5",
                "item_measurement_unit" to "bouteille",
                "item_cost_price" to "2000",
                "item_cost_price_currency" to "BIF",
                "item_movement_type" to "EN",
                "item_movement_invoice_ref" to "",
                "item_movement_description" to "A1-000007",
                "item_movement_date" to "2026-10-03 10:15:42",
                "reference_dmc" to REFERENCE,
                "rubrique_tarifaire" to "15119090000",
                "nombre_par_paquet" to "300",
                "description_paquet" to "Jerrycans",
            ),
            simulator.importedMovements.single().mapValues { (it.value as JsonPrimitive).content },
        )
        assertTrue(simulator.importedMovements.single().values.all { (it as JsonPrimitive).isString })
    }

    @Test
    fun `the service of the imported goods only takes the type EN`() = runTest {
        val outcome = client.addStockMovement(target, token(), movement(MovementType.EI))

        assertEquals(MovementOutcome.Rejected(ObrSimulator.MESSAGE_INVALID_IMPORT_TYPE), outcome)
        assertEquals(emptyList(), simulator.importedMovements)
    }

    @Test
    fun `an entry the server kept without answering is uncertain`() = runTest {
        val token = token()
        simulator.loseNextMovementAnswer()

        assertIs<MovementOutcome.Uncertain>(client.addStockMovement(target, token, movement()))
        assertEquals(1, simulator.importedMovements.size)
    }

    private companion object {
        /** The NIF and the reference of the example of page 33. */
        const val TIN = "4000007155"
        const val REFERENCE = "2025BIPORC7090"
    }
}

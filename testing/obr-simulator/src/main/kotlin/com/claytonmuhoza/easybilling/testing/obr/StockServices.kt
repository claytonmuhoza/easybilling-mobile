package com.claytonmuhoza.easybilling.testing.obr

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect

private const val BAD_REQUEST = 400
private const val NOT_FOUND = 404

/**
 * The fields `AddStockMovement/` refuses to do without (page 31 of the specification), under the
 * names of its example. The currency is left out: page 14 says it is BIF when it is not sent.
 */
private val MANDATORY_MOVEMENT_FIELDS = listOf(
    "system_or_device_id", "item_code", "item_designation", "item_quantity", "item_measurement_unit",
    "item_cost_price", "item_movement_type", "item_movement_date",
)

/**
 * The fields `AddStockMovementImporters/` refuses to do without (page 35 of the specification),
 * under the names of its example.
 */
private val MANDATORY_IMPORT_FIELDS = listOf(
    "system_or_device_id", "item_code", "item_designation", "item_quantity", "item_measurement_unit",
    "item_cost_price", "item_cost_price_currency", "item_movement_type", "item_movement_date",
    "reference_dmc", "rubrique_tarifaire", "nombre_par_paquet", "description_paquet",
)

/** The values of `item_movement_type` (page 14 of the specification). */
private val MOVEMENT_TYPES =
    setOf("EN", "ER", "EI", "EAJ", "ET", "EAU", "SN", "SP", "SV", "SD", "SC", "SAJ", "ST", "SAU")

/**
 * Adds a stock movement (pages 30 to 32 of the specification). The server gives no identifier to
 * a movement and does not compare it with the ones it has: the same movement sent twice is kept
 * twice.
 */
internal fun ObrServices.addStockMovement(body: JsonObject): MockResponse = movement(
    body,
    MANDATORY_MOVEMENT_FIELDS,
    MOVEMENT_TYPES,
    ObrSimulator.MESSAGE_INVALID_MOVEMENT_TYPE,
    stockMovements,
)

/**
 * Adds an entry of imported goods (pages 34 to 36 of the specification): the checks of
 * `AddStockMovement/`, with more mandatory fields and the type EN only. The specification does not
 * say that the declaration is compared with the ones of the taxpayer: the simulator does not.
 */
internal fun ObrServices.addImportedMovement(body: JsonObject): MockResponse = movement(
    body,
    MANDATORY_IMPORT_FIELDS,
    setOf("EN"),
    ObrSimulator.MESSAGE_INVALID_IMPORT_TYPE,
    importedMovements,
)

private fun ObrServices.movement(
    body: JsonObject,
    mandatory: List<String>,
    types: Set<String>,
    wrongType: String,
    kept: MutableList<JsonObject>,
): MockResponse {
    fun text(name: String) = (body[name] as? JsonPrimitive)?.content.orEmpty()
    val missing = mandatory.filter { text(it).isEmpty() }
    return when {
        missing.isNotEmpty() -> movementRefusal(ObrSimulator.MESSAGE_MOVEMENT_MISSING_FIELDS + missing.joinToString())
        text("system_or_device_id") != username -> movementRefusal(ObrSimulator.MESSAGE_WRONG_SYSTEM_ID)
        text("item_movement_type") !in types -> movementRefusal(wrongType)
        else -> {
            kept += body
            if (loseNextMovementAnswer) {
                loseNextMovementAnswer = false
                MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
            } else {
                jsonResponse(
                    HTTP_OK,
                    buildJsonObject {
                        put("success", true)
                        put("msg", ObrSimulator.MESSAGE_MOVEMENT_ADDED)
                    },
                )
            }
        }
    }
}

private fun movementRefusal(message: String) = jsonResponse(BAD_REQUEST, failure(message))

/**
 * A line of a customs declaration the server knows (page 33 of the specification).
 *
 * @property tariffHeading `rubrique_tarifaire`.
 * @property description `description_article`.
 * @property quantity `quantite`, as the server writes it.
 * @property packaging `description_packet`.
 */
data class SimulatedDmcItem(
    val tariffHeading: String,
    val description: String,
    val quantity: String,
    val packaging: String,
)

/**
 * A customs declaration the server knows.
 *
 * @property tin the NIF it belongs to.
 * @property items its lines.
 */
data class SimulatedDmc(val tin: String, val items: List<SimulatedDmcItem>)

/**
 * Gives the lines of a customs declaration (pages 32 to 34 of the specification). The
 * specification gives the 404 of a declaration of another NIF and says nothing of a reference
 * nobody declared: the simulator answers the same 404 for both.
 */
internal fun ObrServices.getDmcItems(body: JsonObject): MockResponse {
    fun text(name: String) = (body[name] as? JsonPrimitive)?.content.orEmpty()
    val tin = text("nif")
    val reference = text("reference_dmc")
    val dmc = dmcs[reference]?.takeIf { it.tin == tin }
    return when {
        tin.isEmpty() -> movementRefusal(ObrSimulator.MESSAGE_DMC_NO_TIN)
        dmc == null -> jsonResponse(NOT_FOUND, failure(ObrSimulator.MESSAGE_DMC_OF_ANOTHER_TIN))
        else -> jsonResponse(
            HTTP_OK,
            buildJsonObject {
                put("success", true)
                put("msg", ObrSimulator.MESSAGE_SUCCESS)
                putJsonObject("result") {
                    putJsonArray("items") {
                        dmc.items.forEach { item ->
                            add(
                                buildJsonObject {
                                    put("rubrique_tarifaire", item.tariffHeading)
                                    put("description_article", item.description)
                                    put("quantite", item.quantity)
                                    put("description_packet", item.packaging)
                                },
                            )
                        }
                    }
                    put("reference_dmc", reference)
                    put("nif", tin)
                }
            },
        )
    }
}

package com.claytonmuhoza.easybilling.testing.obr

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect

private const val BAD_REQUEST = 400

/**
 * The fields `AddStockMovement/` refuses to do without (page 31 of the specification), under the
 * names of its example. The currency is left out: page 14 says it is BIF when it is not sent.
 */
private val MANDATORY_MOVEMENT_FIELDS = listOf(
    "system_or_device_id", "item_code", "item_designation", "item_quantity", "item_measurement_unit",
    "item_cost_price", "item_movement_type", "item_movement_date",
)

/** The values of `item_movement_type` (page 14 of the specification). */
private val MOVEMENT_TYPES =
    setOf("EN", "ER", "EI", "EAJ", "ET", "EAU", "SN", "SP", "SV", "SD", "SC", "SAJ", "ST", "SAU")

/**
 * Adds a stock movement (pages 30 to 32 of the specification). The server gives no identifier to
 * a movement and does not compare it with the ones it has: the same movement sent twice is kept
 * twice.
 */
internal fun ObrServices.addStockMovement(body: JsonObject): MockResponse {
    fun text(name: String) = (body[name] as? JsonPrimitive)?.content.orEmpty()
    val missing = MANDATORY_MOVEMENT_FIELDS.filter { text(it).isEmpty() }
    return when {
        missing.isNotEmpty() -> movementRefusal(ObrSimulator.MESSAGE_MOVEMENT_MISSING_FIELDS + missing.joinToString())
        text("system_or_device_id") != username -> movementRefusal(ObrSimulator.MESSAGE_WRONG_SYSTEM_ID)
        text("item_movement_type") !in MOVEMENT_TYPES -> movementRefusal(ObrSimulator.MESSAGE_INVALID_MOVEMENT_TYPE)
        else -> {
            stockMovements += body
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

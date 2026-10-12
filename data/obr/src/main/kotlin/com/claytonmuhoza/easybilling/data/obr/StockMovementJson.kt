package com.claytonmuhoza.easybilling.data.obr

import com.claytonmuhoza.easybilling.domain.shared.BusinessCalendar
import com.claytonmuhoza.easybilling.domain.stock.StockMovement
import com.claytonmuhoza.easybilling.domain.transmission.MovementOutcome
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.format.DateTimeFormatter

/**
 * Writes a stock movement as the body of `AddStockMovement/` (pages 30 and 31 of the
 * specification) or of `AddStockMovementImporters/` (page 35) and reads its answer (pages 31, 32,
 * 35 and 36). Every value is a text, numbers included;
 * a value that does not exist is an empty text, never `null`; the date is written in the time of
 * Bujumbura.
 */
internal object StockMovementJson {
    private val DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(BusinessCalendar.ZONE)

    private const val HTTP_OK = 200
    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_FORBIDDEN = 403

    /**
     * @param movement the movement.
     * @param systemId the system identifier of the taxpayer. The specification names the field
     * `system_id` in its table, `device_id` in its list of mandatory fields and
     * `system_or_device_id` in its example: the name of the example is sent.
     * @return the body to send.
     */
    fun of(movement: StockMovement, systemId: String): JsonObject = buildJsonObject {
        val content = movement.content
        put("system_or_device_id", systemId)
        put("item_code", content.itemCode)
        put("item_designation", content.designation)
        put("item_quantity", content.quantity.toPlainString())
        put("item_measurement_unit", content.unit)
        put("item_cost_price", content.unitCost.toPlainString())
        put("item_cost_price_currency", content.unitCost.currency.name)
        put("item_movement_type", content.type.code)
        put("item_movement_invoice_ref", content.invoiceRef.orEmpty())
        put("item_movement_description", content.obrDescription)
        put("item_movement_date", DATE.format(content.occurredAt))
        // The table of page 14 names the third `nombre_piece_par_paquet`; the example and the list of
        // mandatory fields of page 35 say `nombre_par_paquet`, which is sent.
        content.imported?.let { goods ->
            put("reference_dmc", goods.dmcReference)
            put("rubrique_tarifaire", goods.tariffHeading)
            put("nombre_par_paquet", goods.piecesPerPacket.toPlainString())
            put("description_paquet", goods.packaging)
        }
    }

    /**
     * @param reply what the server answered, or why it did not.
     * @param read reads the body of an answer as JSON, or gives null when it is not.
     * @return what the answer means for the movement. A request that left without an answer, or a
     * success that cannot be read, is uncertain: the OBR may have the movement, and cannot be asked.
     */
    fun outcome(reply: ObrReply, read: (String) -> JsonObject?): MovementOutcome = when (reply) {
        is ObrReply.Failed ->
            if (reply.requestSent) MovementOutcome.Uncertain(reply.cause) else MovementOutcome.NotDelivered(reply.cause)
        is ObrReply.Answered -> answered(reply.code, read(reply.body))
    }

    private fun answered(code: Int, json: JsonObject?): MovementOutcome = when (code) {
        // A "yes" without the `success` of page 31: a page of the network, or an answer cut short.
        HTTP_OK ->
            if ((json?.get("success") as? JsonPrimitive)?.booleanOrNull == true) {
                MovementOutcome.Accepted
            } else {
                MovementOutcome.Uncertain("HTTP $HTTP_OK without a confirmation")
            }
        HTTP_FORBIDDEN -> MovementOutcome.TokenRefused
        HTTP_BAD_REQUEST -> MovementOutcome.Rejected((json?.get("msg") as? JsonPrimitive)?.content?.trim().orEmpty())
        // An error of the server, or a code the specification does not give: to send again.
        else -> MovementOutcome.NotDelivered("HTTP $code")
    }
}

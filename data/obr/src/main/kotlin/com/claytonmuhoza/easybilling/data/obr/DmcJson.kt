package com.claytonmuhoza.easybilling.data.obr

import com.claytonmuhoza.easybilling.domain.stock.DmcLine
import com.claytonmuhoza.easybilling.domain.transmission.DmcOutcome
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Writes the body of `getDmcItems` and reads its answer (pages 32 to 34 of the specification).
 */
internal object DmcJson {
    private const val HTTP_OK = 200
    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404

    /**
     * @param tin the NIF of the business.
     * @param reference the reference of the declaration.
     * @return the body to send.
     */
    fun of(tin: String, reference: String): JsonObject = buildJsonObject {
        put("nif", tin)
        put("reference_dmc", reference)
    }

    /**
     * @param reply what the server answered, or why it did not.
     * @param read reads the body of an answer as JSON, or gives null when it is not.
     * @return what the answer means. The 404 of a declaration of another NIF and every 400 are
     * refusals told with the message of the OBR.
     */
    fun outcome(reply: ObrReply, read: (String) -> JsonObject?): DmcOutcome = when (reply) {
        is ObrReply.Failed -> DmcOutcome.Unavailable(reply.cause)
        is ObrReply.Answered -> answered(reply.code, read(reply.body))
    }

    private fun answered(code: Int, json: JsonObject?): DmcOutcome = when (code) {
        HTTP_OK -> json?.lines()?.let { DmcOutcome.Found(it) }
            ?: DmcOutcome.Unavailable("HTTP $HTTP_OK without the items")
        HTTP_FORBIDDEN -> DmcOutcome.TokenRefused
        HTTP_BAD_REQUEST, HTTP_NOT_FOUND -> DmcOutcome.Refused(json?.text("msg").orEmpty())
        else -> DmcOutcome.Unavailable("HTTP $code")
    }

    /**
     * @return the lines of the declaration in this answer (page 33), ranked from one in the order
     * of the OBR, or null if the answer is not a success with its `items`.
     */
    private fun JsonObject.lines(): List<DmcLine>? {
        val success = (this["success"] as? JsonPrimitive)?.booleanOrNull
        val items = (this["result"] as? JsonObject)?.get("items") as? JsonArray
        return items?.takeIf { success == true }?.filterIsInstance<JsonObject>()?.mapIndexed { index, item ->
            DmcLine(
                rank = index + 1,
                tariffHeading = item.text("rubrique_tarifaire").orEmpty(),
                description = item.text("description_article").orEmpty(),
                quantity = item.text("quantite")?.toBigDecimalOrNull(),
                packaging = item.text("description_packet").orEmpty(),
            )
        }
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.content?.trim()
}

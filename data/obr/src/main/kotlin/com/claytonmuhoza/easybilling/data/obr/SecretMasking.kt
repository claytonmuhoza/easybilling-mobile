package com.claytonmuhoza.easybilling.data.obr

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Hides the secrets of a body before it is written to the log of the exchanges. */
object SecretMasking {
    /** What replaces a secret. */
    const val MASK = "••••"

    /** Names of the JSON fields that hold a secret in the OBR specification. */
    private val secretFields = setOf("password", "token")

    /** Same fields in a body that is not valid JSON; an escaped quote does not end the value. */
    private val secretPattern = Regex("\"(password|token)\"\\s*:\\s*\"(?:\\\\.|[^\"\\\\])*(\"?)")

    /**
     * @param body a request or response body.
     * @return the body with the value of every secret field replaced by [MASK].
     */
    fun mask(body: String): String =
        try {
            mask(Json.parseToJsonElement(body)).toString()
        } catch (_: SerializationException) {
            secretPattern.replace(body) { "\"${it.groupValues[1]}\":\"$MASK${it.groupValues[2]}" }
        }

    private fun mask(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapValues { (name, value) -> if (name in secretFields) JsonPrimitive(MASK) else mask(value) },
        )
        is JsonArray -> JsonArray(element.map(::mask))
        else -> element
    }
}

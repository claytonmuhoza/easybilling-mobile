package com.claytonmuhoza.easybilling.testing.obr

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal const val HTTP_OK = 200
private const val BAD_REQUEST = 400
private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val NOT_FOUND = 404
private const val BEARER = "Bearer "

/** The fields `addInvoice_confirm/` refuses to do without (page 21 of the specification). */
private val MANDATORY_INVOICE_FIELDS = listOf(
    "invoice_number", "invoice_date", "tp_type", "tp_name", "tp_TIN", "tp_trade_number", "tp_phone_number",
    "tp_address_commune", "tp_address_quartier", "vat_taxpayer", "ct_taxpayer", "tl_taxpayer",
    "tp_fiscal_center", "tp_activity_sector", "tp_legal_form", "payment_type", "customer_name",
    "invoice_identifier",
)

/**
 * What the simulated EBMS answers to each service, and what it remembers: the tokens it gave, the
 * taxpayers it knows and the invoices it accepted.
 *
 * @param username the user name the server accepts.
 * @param password the password the server accepts.
 */
internal class ObrServices(var username: String, var password: String) {

    val taxpayers: MutableMap<String, String> =
        ConcurrentHashMap(mapOf(ObrSimulator.KNOWN_TIN to ObrSimulator.KNOWN_TAXPAYER))
    val invoices: MutableMap<String, JsonObject> = ConcurrentHashMap()
    val cancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    var alternateSignatureName = false

    @Volatile
    var loseNextAcknowledgement = false

    private val issuedTokens = AtomicInteger()
    private val registered = AtomicInteger()
    private val acceptedTokens: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val loginCount: Int get() = issuedTokens.get()

    fun expireTokens() = acceptedTokens.clear()

    fun answer(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
        "${ObrSimulator.API_PATH}login/" -> login(request)
        "${ObrSimulator.API_PATH}checkTIN/" -> authenticated(request, ::checkTin)
        "${ObrSimulator.API_PATH}addInvoice_confirm/" -> authenticated(request, ::addInvoice)
        "${ObrSimulator.API_PATH}getInvoice/" -> authenticated(request, ::getInvoice)
        else -> MockResponse.Builder().code(NOT_FOUND).build()
    }

    private fun login(request: RecordedRequest): MockResponse {
        val body = if (request.method == "POST") parse(request.body?.utf8().orEmpty()) else null
        val user = body.text("username")
        val secret = body.text("password")
        return when {
            request.method != "POST" -> refusal(ObrSimulator.MESSAGE_NOT_POST)
            body == null -> refusal(ObrSimulator.MESSAGE_INVALID_JSON)
            user.isEmpty() -> refusal(ObrSimulator.MESSAGE_NO_USERNAME)
            secret.isEmpty() -> refusal(ObrSimulator.MESSAGE_NO_PASSWORD)
            user != username || secret != password ->
                jsonResponse(UNAUTHORIZED, failure(ObrSimulator.MESSAGE_WRONG_CREDENTIALS))
            else -> jsonResponse(
                HTTP_OK,
                buildJsonObject {
                    put("success", true)
                    put("msg", ObrSimulator.MESSAGE_SUCCESS)
                    putJsonObject("result") {
                        put("token", "simulated-token-${issuedTokens.incrementAndGet()}".also { acceptedTokens += it })
                    }
                },
            )
        }
    }

    /** The checks every service makes before reading its body: the method, the token, the JSON. */
    private fun authenticated(request: RecordedRequest, service: (JsonObject) -> MockResponse): MockResponse {
        val body = if (request.method == "POST") parse(request.body?.utf8().orEmpty()) else null
        val token = request.headers["Authorization"].orEmpty().removePrefix(BEARER)
        return when {
            request.method != "POST" -> refusal(ObrSimulator.MESSAGE_USE_POST)
            token !in acceptedTokens -> jsonResponse(FORBIDDEN, failure(ObrSimulator.MESSAGE_NO_API_KEY))
            body == null -> refusal(ObrSimulator.MESSAGE_INVALID_JSON)
            else -> service(body)
        }
    }

    private fun checkTin(body: JsonObject): MockResponse {
        val tin = body.text("tp_TIN")
        val owner = taxpayers[tin]
        return when {
            tin.isEmpty() -> refusal(ObrSimulator.MESSAGE_NO_TIN)
            owner == null -> refusal(ObrSimulator.MESSAGE_UNKNOWN_TIN)
            else -> jsonResponse(
                HTTP_OK,
                buildJsonObject {
                    put("success", true)
                    put("msg", ObrSimulator.MESSAGE_SUCCESS)
                    putJsonObject("result") {
                        putJsonArray("taxpayer") { add(buildJsonObject { put("tp_name", owner) }) }
                    }
                },
            )
        }
    }

    private fun addInvoice(body: JsonObject): MockResponse {
        val number = body.text("invoice_number")
        val customerTin = body.text("customer_TIN")
        val complete = MANDATORY_INVOICE_FIELDS.all { body.text(it).isNotEmpty() } &&
            (body["invoice_items"] as? JsonArray).orEmpty().isNotEmpty()
        return when {
            !complete -> refusal(ObrSimulator.MESSAGE_MISSING_FIELDS)
            invoices.values.any { it.text("invoice_number") == number } ->
                refusal(ObrSimulator.MESSAGE_DUPLICATE_NUMBER)
            customerTin.isNotEmpty() && customerTin !in taxpayers ->
                refusal(ObrSimulator.MESSAGE_UNKNOWN_CUSTOMER_TIN)
            else -> {
                invoices[body.text("invoice_identifier")] = body
                if (loseNextAcknowledgement) lost() else acknowledgement(number)
            }
        }
    }

    private fun lost(): MockResponse {
        loseNextAcknowledgement = false
        return MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
    }

    private fun acknowledgement(number: String): MockResponse {
        val rank = registered.incrementAndGet()
        return jsonResponse(
            HTTP_OK,
            buildJsonObject {
                put("success", true)
                put("msg", ObrSimulator.MESSAGE_INVOICE_ADDED)
                putJsonObject("result") {
                    put("invoice_number", number)
                    put("invoice_registered_number", "${REGISTERED_BASE + rank}")
                    put("invoice_registered_date", "2026-10-03 10:04:%02d".format(rank % SECONDS))
                }
                put(if (alternateSignatureName) "electronique_signature" else "electronic_signature", "signature-$rank")
            },
        )
    }

    private fun getInvoice(body: JsonObject): MockResponse {
        val identifier = body.text("invoice_identifier")
        val invoice = invoices[identifier]
        return when {
            identifier.isEmpty() -> refusal(ObrSimulator.MESSAGE_NO_IDENTIFIER)
            invoice == null -> refusal(ObrSimulator.MESSAGE_UNKNOWN_IDENTIFIER)
            else -> jsonResponse(
                HTTP_OK,
                buildJsonObject {
                    put("success", true)
                    put("msg", ObrSimulator.MESSAGE_SUCCESS)
                    putJsonObject("result") { putJsonArray("invoices") { add(invoice.read(identifier)) } }
                },
            )
        }
    }

    /** @return this invoice as it is read: as it was sent, with `cancelled_invoice` (`N` or `Y`). */
    private fun JsonObject.read(identifier: String) = buildJsonObject {
        forEach { (name, value) -> put(name, value) }
        put("cancelled_invoice", if (identifier in cancelled) "Y" else "N")
    }

    private fun refusal(message: String) = jsonResponse(BAD_REQUEST, failure(message))

    private companion object {
        /** The registered numbers follow each other from this one. */
        const val REGISTERED_BASE = 4_530_252
        const val SECONDS = 60
    }
}

private fun JsonObject?.text(name: String): String = (this?.get(name) as? JsonPrimitive)?.content.orEmpty()

private fun parse(body: String): JsonObject? =
    try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

internal fun failure(message: String) = buildJsonObject {
    put("success", false)
    put("msg", message)
}

internal fun jsonResponse(code: Int, body: JsonObject) = MockResponse.Builder()
    .code(code)
    .setHeader("Content-Type", "application/json")
    .body(body.toString())
    .build()

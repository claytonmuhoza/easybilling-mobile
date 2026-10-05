package com.claytonmuhoza.easybilling.data.obr

import com.claytonmuhoza.easybilling.domain.configuration.TaxProfile
import com.claytonmuhoza.easybilling.domain.customer.TinLookup
import com.claytonmuhoza.easybilling.domain.customer.TinReply
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.transmission.ExchangeRecorder
import com.claytonmuhoza.easybilling.domain.transmission.LoginOutcome
import com.claytonmuhoza.easybilling.domain.transmission.LoginTarget
import com.claytonmuhoza.easybilling.domain.transmission.LookupOutcome
import com.claytonmuhoza.easybilling.domain.transmission.ObrAuthentication
import com.claytonmuhoza.easybilling.domain.transmission.ObrGateway
import com.claytonmuhoza.easybilling.domain.transmission.ObrInvoiceCopy
import com.claytonmuhoza.easybilling.domain.transmission.ObrReceipt
import com.claytonmuhoza.easybilling.domain.transmission.SendOutcome
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.math.BigDecimal
import java.time.Duration

/** Builds the HTTP client of the EBMS. */
object ObrHttp {
    /** Longest time a call may take, request and response included (`docs/architecture.md` § 7.6). */
    val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(15)

    /**
     * The client never retries by itself: a request that may have reached the OBR is only sent
     * again by the send queue, which knows whether that is safe. The certificate of the server is
     * checked by the system, without pinning.
     *
     * @param timeout longest time a call may take.
     * @return the HTTP client.
     */
    fun client(timeout: Duration = DEFAULT_TIMEOUT): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(timeout)
        .connectTimeout(timeout)
        .readTimeout(timeout)
        .writeTimeout(timeout)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .build()
}

/**
 * Client of the EBMS API: builds the requests of the specification and reads its answers.
 *
 * @param http the HTTP client, from [ObrHttp.client].
 * @param recorder receives every exchange.
 * @param time source of the time of each exchange.
 */
class ObrClient(http: OkHttpClient, recorder: ExchangeRecorder, time: TimeProvider) :
    ObrAuthentication,
    TinLookup,
    ObrGateway {

    private val transport = ObrTransport(http, recorder, time)

    override suspend fun login(target: LoginTarget): LoginOutcome {
        val body = buildJsonObject {
            put("username", target.credentials.username)
            put("password", target.credentials.password)
        }
        return when (val reply = transport.post(target, LOGIN, body.toString())) {
            is ObrReply.Failed -> if (reply.certificate) LoginOutcome.CertificateRejected else LoginOutcome.Unreachable
            is ObrReply.Answered -> loginOutcome(reply)
        }
    }

    private fun loginOutcome(reply: ObrReply.Answered): LoginOutcome {
        val json = parse(reply.body)
        return when (reply.code) {
            HTTP_OK -> json?.token()?.let { LoginOutcome.Success(it) } ?: LoginOutcome.InvalidResponse
            HTTP_UNAUTHORIZED -> LoginOutcome.WrongCredentials
            HTTP_BAD_REQUEST, HTTP_FORBIDDEN -> LoginOutcome.Refused(json?.text("msg").orEmpty())
            in HTTP_SERVER_ERRORS -> LoginOutcome.ServerError
            else -> LoginOutcome.InvalidResponse
        }
    }

    override suspend fun check(target: LoginTarget, token: String, tin: String): TinReply {
        val body = buildJsonObject { put("tp_TIN", tin) }
        return when (val reply = transport.post(target, CHECK_TIN, body.toString(), token)) {
            is ObrReply.Failed -> if (reply.certificate) TinReply.CertificateRejected else TinReply.Unreachable
            is ObrReply.Answered -> tinReply(reply)
        }
    }

    private fun tinReply(reply: ObrReply.Answered): TinReply {
        val json = parse(reply.body)
        val message = json?.text("msg").orEmpty()
        return when (reply.code) {
            HTTP_OK -> json?.taxpayerName()?.let { TinReply.Known(it) } ?: TinReply.InvalidResponse
            HTTP_FORBIDDEN -> TinReply.TokenRefused
            // The server answers 401 for an unknown TIN, and adds words to the message.
            HTTP_BAD_REQUEST, HTTP_UNAUTHORIZED ->
                if (message.startsWith(UNKNOWN_TIN)) TinReply.Unknown else TinReply.Refused(message)
            in HTTP_SERVER_ERRORS -> TinReply.ServerError
            else -> TinReply.InvalidResponse
        }
    }

    override suspend fun addInvoice(
        target: LoginTarget,
        token: String,
        invoice: Invoice,
        seller: TaxProfile,
    ): SendOutcome {
        val body = InvoiceJson.of(invoice, seller).toString()
        return when (val reply = transport.post(target, ADD_INVOICE, body, token)) {
            // A request that never left can be sent again; one that left may have been received.
            is ObrReply.Failed ->
                if (reply.requestSent) SendOutcome.Uncertain(reply.cause) else SendOutcome.NotDelivered(reply.cause)
            is ObrReply.Answered -> sendOutcome(reply)
        }
    }

    private fun sendOutcome(reply: ObrReply.Answered): SendOutcome {
        val json = parse(reply.body)
        return when (reply.code) {
            // The OBR answered "yes" in a way that cannot be read: it has the invoice, or may have it.
            HTTP_OK -> json?.receipt(reply.body)?.let { SendOutcome.Accepted(it) }
                ?: SendOutcome.Uncertain("HTTP $HTTP_OK without an acknowledgement")
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> SendOutcome.TokenRefused
            // The server answers 409 for a number it already has.
            HTTP_BAD_REQUEST, HTTP_CONFLICT -> SendOutcome.Rejected(json?.text("msg").orEmpty())
            else -> SendOutcome.NotDelivered("HTTP ${reply.code}")
        }
    }

    override suspend fun getInvoice(target: LoginTarget, token: String, identifier: String): LookupOutcome {
        val body = buildJsonObject { put("invoice_identifier", identifier) }
        return when (val reply = transport.post(target, GET_INVOICE, body.toString(), token)) {
            is ObrReply.Failed -> LookupOutcome.Unavailable(reply.cause)
            is ObrReply.Answered -> lookupOutcome(reply, identifier)
        }
    }

    private fun lookupOutcome(reply: ObrReply.Answered, identifier: String): LookupOutcome {
        val json = parse(reply.body)
        val copy = if (reply.code == HTTP_OK) json?.invoiceCopy(identifier) else null
        return when {
            copy != null -> LookupOutcome.Found(copy)
            reply.code == HTTP_FORBIDDEN -> LookupOutcome.TokenRefused
            // The server answers 401 for an unknown identifier; the specification says 400.
            reply.code in UNKNOWN_CODES && json?.text("msg") == UNKNOWN_IDENTIFIER -> LookupOutcome.NotFound
            else -> LookupOutcome.Unavailable("HTTP ${reply.code}")
        }
    }

    private companion object {
        const val LOGIN = "login/"
        const val CHECK_TIN = "checkTIN/"
        const val ADD_INVOICE = "addInvoice_confirm/"
        const val GET_INVOICE = "getInvoice/"

        /** The only refusal of `getInvoice/` that means the OBR does not have the invoice (page 19). */
        const val UNKNOWN_IDENTIFIER = "Identifiant de la facture inconnu."

        /**
         * The start of the only refusal of `checkTIN/` that means the TIN does not exist (page 28);
         * the server was seen to add words after it.
         */
        const val UNKNOWN_TIN = "NIF du contribuable inconnu."
        const val HTTP_OK = 200
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_CONFLICT = 409

        /**
         * The codes "unknown" comes with: 400 in the specification, 401 on the server. Only 403 is
         * a refused token, on every service but `login/`.
         */
        val UNKNOWN_CODES = setOf(HTTP_BAD_REQUEST, HTTP_UNAUTHORIZED)
        val HTTP_SERVER_ERRORS = 500..599
    }
}

/**
 * @return the JSON object of [body], or null if there is none. The server sometimes writes a
 * warning in HTML before the object: what precedes the first brace is skipped.
 */
private fun parse(body: String): JsonObject? =
    try {
        Json.parseToJsonElement(body.substring(body.indexOf('{').coerceAtLeast(0))) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

private fun JsonObject.token(): String? {
    val success = (this["success"] as? JsonPrimitive)?.booleanOrNull
    val token = (this["result"] as? JsonObject)?.text("token")
    return token?.takeIf { success == true && it.isNotEmpty() }
}

private fun JsonObject.taxpayerName(): String? {
    val success = (this["success"] as? JsonPrimitive)?.booleanOrNull
    val taxpayers = (this["result"] as? JsonObject)?.get("taxpayer") as? JsonArray
    val name = (taxpayers?.firstOrNull() as? JsonObject)?.text("tp_name")
    return name?.takeIf { success == true && it.isNotEmpty() }
}

/**
 * @param raw the body the acknowledgement was read from, kept as the proof.
 * @return the acknowledgement of an invoice, or null if the body is not one. Both spellings of
 * the signature found in the specification are read.
 */
private fun JsonObject.receipt(raw: String): ObrReceipt? {
    val success = (this["success"] as? JsonPrimitive)?.booleanOrNull
    val result = this["result"] as? JsonObject
    val number = result?.text("invoice_registered_number")
    val signature = text("electronic_signature") ?: text("electronique_signature")
    return if (success == true && !number.isNullOrEmpty()) {
        ObrReceipt(number, result.text("invoice_registered_date").orEmpty(), signature.orEmpty(), raw)
    } else {
        null
    }
}

/**
 * @return the invoice with [identifier] in this answer of `getInvoice/` (pages 17 and 18), or null
 * if the answer does not hold it. The total is the sum of the `item_total_amount`: the answer has
 * no total of its own.
 */
private fun JsonObject.invoiceCopy(identifier: String): ObrInvoiceCopy? {
    val success = (this["success"] as? JsonPrimitive)?.booleanOrNull
    val invoices = (this["result"] as? JsonObject)?.get("invoices") as? JsonArray
    val invoice = invoices.orEmpty().filterIsInstance<JsonObject>()
        .firstOrNull { it.text("invoice_identifier") == identifier }
        ?.takeIf { success == true }
        ?: return null
    val items = (invoice["invoice_items"] as? JsonArray).orEmpty()
    val totals = items.map { (it as? JsonObject)?.text("item_total_amount")?.toBigDecimalOrNull() }
    return ObrInvoiceCopy(
        number = invoice.text("invoice_number").orEmpty(),
        date = invoice.text("invoice_date").orEmpty(),
        customerName = invoice.text("customer_name").orEmpty(),
        itemCount = items.size,
        total = totals.filterNotNull().takeIf { it.size == totals.size && it.isNotEmpty() }
            ?.fold(BigDecimal.ZERO, BigDecimal::add),
        cancelled = when (invoice.text("cancelled_invoice")) {
            CANCELLED_YES -> true
            CANCELLED_NO -> false
            else -> null
        },
    )
}

/** The values of `cancelled_invoice`. */
private const val CANCELLED_YES = "Y"
private const val CANCELLED_NO = "N"

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.content?.trim()

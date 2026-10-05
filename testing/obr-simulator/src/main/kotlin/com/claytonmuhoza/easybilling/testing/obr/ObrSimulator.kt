package com.claytonmuhoza.easybilling.testing.obr

import kotlinx.serialization.json.JsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Fake EBMS server for the tests. Its answers follow the OBR specification V0.7: same paths, same
 * HTTP codes, same messages. Tests can force the next answers to reproduce failures.
 *
 * `login/`, `checkTIN/`, `addInvoice_confirm/` and `getInvoice/` are served for now; the other
 * services arrive with the steps that use them. The specification does not say how long a token
 * lives on the server side nor what an expired token gets: here a token is accepted until
 * [expireTokens], and any call without an accepted token gets the 403 of a missing token.
 *
 * @param username the user name the server accepts.
 * @param password the password the server accepts.
 */
class ObrSimulator(
    username: String = DEFAULT_USERNAME,
    password: String = DEFAULT_PASSWORD,
) : Closeable {

    private val services = ObrServices(username, password)

    /** The user name the server accepts. */
    var username: String by services::username

    /** The password the server accepts. */
    var password: String by services::password

    /** The underlying server, for settings a test needs (HTTPS, for example) before [start]. */
    val server = MockWebServer()

    /** Every request received, oldest first. */
    val requests: MutableList<RecordedRequest> = CopyOnWriteArrayList()

    /** The TINs the server knows, with the name of their owner (`tp_name`). */
    val taxpayers: MutableMap<String, String> get() = services.taxpayers

    /** The invoices the server accepted, by `invoice_identifier`, as they were sent. */
    val invoices: Map<String, JsonObject> get() = services.invoices

    /**
     * Whether the unknown and the duplicate are answered as the specification says (400, its
     * messages) instead of as the server of the OBR really does (see [ObrServices.asSpecified]).
     */
    var asSpecified: Boolean by services::asSpecified

    /** Whether the acknowledgements spell the signature `electronique_signature`, as some examples do. */
    var alternateSignatureName: Boolean by services::alternateSignatureName

    private val forced = ConcurrentLinkedQueue<MockResponse>()

    /** Number of tokens given so far. */
    val loginCount: Int get() = services.loginCount

    /** Address of the API, ending with `/`, as configured in the application. */
    val baseUrl: String get() = server.url(API_PATH).toString()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return forced.poll() ?: services.answer(request)
            }
        }
    }

    /** Starts the server on a free local port. */
    fun start() = server.start()

    override fun close() = server.close()

    /** The next request gets [response] instead of the normal answer. */
    fun forceNext(response: MockResponse) {
        forced += response
    }

    /** The next request gets an HTTP [code] with the OBR body `{"success":false,"msg":...}`. */
    fun failNext(code: Int, message: String) = forceNext(jsonResponse(code, failure(message)))

    /** The next request waits [seconds] before any answer. */
    fun delayNext(seconds: Long) =
        forceNext(jsonResponse(HTTP_OK, failure("")).newBuilder().headersDelay(seconds, TimeUnit.SECONDS).build())

    /** The next request reaches the server, then the connection is cut: the answer is lost. */
    fun dropNext() = forceNext(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())

    /**
     * The next invoice is accepted and kept by the server, but the connection is cut before the
     * acknowledgement leaves: the sender cannot know whether the invoice was received.
     */
    fun loseNextAcknowledgement() {
        services.loseNextAcknowledgement = true
    }

    /**
     * The invoice is cancelled on the server: `getInvoice/` now reads it with `cancelled_invoice` `Y`.
     *
     * @param identifier the `invoice_identifier` of an accepted invoice.
     */
    fun markCancelled(identifier: String) {
        services.cancelled += identifier
    }

    /** Every token given so far is now refused: the next authenticated call needs a new login. */
    fun expireTokens() = services.expireTokens()

    /** Values of the specification and of the simulator. */
    companion object {
        /** Path of the API on the server. */
        const val API_PATH = "/ebms_api/"

        /** User name accepted unless a test sets another one. */
        const val DEFAULT_USERNAME = "ws400000000100001"

        /** Password accepted unless a test sets another one. */
        const val DEFAULT_PASSWORD = "simulated-password"

        /** Message of a wrong login (401), page 16 of the specification. */
        const val MESSAGE_WRONG_CREDENTIALS = "Nom d'utilisateur ou mot de passe incorrect."

        /** Message of a server error (500), page 16 of the specification. */
        const val MESSAGE_SERVER_ERROR = "Quelque chose a mal tourné. Veuillez réessayer plus tard."

        /** Message of a login without user name (400), page 16 of the specification. */
        const val MESSAGE_NO_USERNAME = "Veuillez fournir un nom d'utilisateur."

        /** Message of a login without password (400), page 16 of the specification. */
        const val MESSAGE_NO_PASSWORD = "Veuillez fournir un mot de passe."

        /** Message of a request that is not a POST (400), page 16 of the specification. */
        const val MESSAGE_NOT_POST = "Veuillez envoyer les données en utilisant la méthode POST."

        /** Message of a body that is not JSON (400), page 16 of the specification. */
        const val MESSAGE_INVALID_JSON = "Le format de la chaine de caractère JSON est invalide."

        /** A TIN the server knows unless a test changes [taxpayers]. */
        const val KNOWN_TIN = "4000202020"

        /** The owner of [KNOWN_TIN], as in the example of page 28 of the specification. */
        const val KNOWN_TAXPAYER = "NDIKUMANA Jean Marie"

        /** Message of a success (200), pages 16 and 28 of the specification. */
        const val MESSAGE_SUCCESS = "Opération réussie"

        /** Message of a call without a token (403), page 28 of the specification. */
        const val MESSAGE_NO_API_KEY = "La clé API est manquante."

        /** Message of `checkTIN/` without a TIN (400), page 28 of the specification. */
        const val MESSAGE_NO_TIN = "Veuillez fournir le NIF du contribuable."

        /** Message of `checkTIN/` for a TIN the OBR does not know (400), page 28 of the specification. */
        const val MESSAGE_UNKNOWN_TIN = "NIF du contribuable inconnu."

        /** Message the server really gives for a TIN it does not know, with 401 (observed on 5 October 2026). */
        const val MESSAGE_UNKNOWN_TIN_OBSERVED = "NIF du contribuable inconnu. empty"

        /** Message of a request that is not a POST (400), pages 19 and 28 of the specification. */
        const val MESSAGE_USE_POST = "Veuillez utiliser la méthode POST pour envoyer les données."

        /** Message of an accepted invoice (200), page 21 of the specification. */
        const val MESSAGE_INVOICE_ADDED = "La facture a été ajoutée avec succès!"

        /** Message of an invoice without one of its mandatory fields (400), page 22 of the specification. */
        const val MESSAGE_MISSING_FIELDS = "Veuillez fournir tous les champs obligatoires."

        /** Message of an invoice whose number the OBR already has (400), page 26 of the specification. */
        const val MESSAGE_DUPLICATE_NUMBER = "Une facture avec le même numéro de facture existe déjà."

        /** Message the server really gives for a number it already has, with 409 (observed on 5 October 2026). */
        const val MESSAGE_DUPLICATE_NUMBER_OBSERVED = "Une facture avec le même numéro existe déjà."

        /** Message of an invoice dated after the clock of the OBR (400), page 22 of the specification. */
        const val MESSAGE_DATE_IN_FUTURE = "La date de facturation fournie est supérieur à la date actuelle."

        /** Message of an invoice whose customer TIN the OBR does not know (400), page 25 of the specification. */
        const val MESSAGE_UNKNOWN_CUSTOMER_TIN =
            "Le NIF du client n'est pas connu par l'OBR. Veuillez fournir un NIF valide"

        /** Message of `getInvoice/` without an identifier (400), page 19 of the specification. */
        const val MESSAGE_NO_IDENTIFIER = "Veuillez fournir un identifiant de la facture."

        /** Message of `getInvoice/` for an identifier the OBR does not know (400), page 19 of the specification. */
        const val MESSAGE_UNKNOWN_IDENTIFIER = "Identifiant de la facture inconnu."
    }
}

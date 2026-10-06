package com.claytonmuhoza.easybilling.domain.document

import com.claytonmuhoza.easybilling.domain.configuration.TaxProfile
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceLine
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceStatus
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceTotals
import com.claytonmuhoza.easybilling.domain.invoicing.PaymentType
import com.claytonmuhoza.easybilling.domain.shared.BusinessCalendar
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.transmission.ObrReceipt
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Something the document says: a name and its value.
 *
 * @property obrName the field of `addInvoice` it shows, or null for what is not sent to the OBR.
 * @property label its name on the document.
 * @property value its value, written for a reader.
 */
data class DocumentField(val obrName: String?, val label: String, val value: String)

/**
 * A line of the document.
 *
 * @property cells its values, in the order of [InvoiceDocument.columns].
 */
data class DocumentLine(val cells: List<DocumentField>)

/**
 * What a document says of an invoice the OBR has not registered, or no longer holds as valid.
 *
 * @property text the words written across the document.
 */
enum class DocumentNotice(val text: String) {
    /** Not acknowledged by the OBR yet (UC-C01, 6a). */
    PENDING("En cours de transmission à l'OBR"),

    /** Refused by the OBR: the document is not a valid invoice. */
    REJECTED("REJETÉE PAR L'OBR – sans valeur fiscale"),

    /** Cancelled at the OBR. */
    CANCELLED("ANNULÉE"),
}

/**
 * An invoice as it leaves the application, as a PDF or a ticket (UC-G01, UC-G02): what to write,
 * in French whatever the language of the application, and nothing of how to lay it out.
 *
 * @property title the kind of document.
 * @property number `invoice_number`.
 * @property date `invoice_date`, in the time of Bujumbura.
 * @property identifier `invoice_identifier`, written in clear on every invoice.
 * @property notice what to say of an invoice that is not registered, or null.
 * @property seller the seller: the tax profile the invoice is sent with.
 * @property customer the customer.
 * @property payment how the invoice is paid, in which currency, and the cashier who issued it.
 * @property columns the names of the columns of [lines].
 * @property lines the lines.
 * @property totals the totals, the amount to pay last.
 * @property obr what the OBR acknowledged; empty while it has not.
 * @property footer the footer chosen in the settings; may be empty.
 * @property logo the logo chosen in the settings, or null.
 * @property qrContent what the QR code carries, the identifier, or null when it is not shown.
 */
data class InvoiceDocument(
    val title: String,
    val number: DocumentField,
    val date: DocumentField,
    val identifier: DocumentField,
    val notice: DocumentNotice?,
    val seller: List<DocumentField>,
    val customer: List<DocumentField>,
    val payment: List<DocumentField>,
    val columns: List<String>,
    val lines: List<DocumentLine>,
    val totals: List<DocumentField>,
    val obr: List<DocumentField>,
    val footer: String,
    val logo: DocumentLogo?,
    val qrContent: String?,
) {
    /** @return everything the document says outside its lines. */
    fun fields(): List<DocumentField> = listOf(number, date, identifier) + seller + customer + payment + totals + obr

    /** The mentions no document may leave out, and how a document is built. */
    companion object {
        /**
         * The mandatory fields of `addInvoice` (page 21 of the specification), lines apart:
         * every one is on the document.
         */
        val MANDATORY_FIELDS = setOf(
            "invoice_number", "invoice_date", "tp_type", "tp_name", "tp_TIN", "tp_trade_number", "tp_phone_number",
            "tp_address_commune", "tp_address_quartier", "vat_taxpayer", "ct_taxpayer", "tl_taxpayer",
            "tp_fiscal_center", "tp_activity_sector", "tp_legal_form", "payment_type", "customer_name",
            "invoice_identifier",
        )

        /** The mandatory fields of each line of `addInvoice`. */
        val MANDATORY_ITEM_FIELDS = setOf(
            "item_designation", "item_quantity", "item_price", "item_ct", "item_tl", "item_price_nvat", "vat",
            "item_price_wvat", "item_total_amount",
        )

        /**
         * @param invoice the invoice.
         * @param seller the tax profile the invoice is sent with.
         * @param status where the invoice stands with the OBR.
         * @param receipt the acknowledgement of the OBR, or null.
         * @param settings the footer, the logo and whether the QR code is shown.
         * @param cashier the name of the user who issued the invoice, or null if it cannot be read.
         * @return the document of the invoice.
         */
        fun of(
            invoice: Invoice,
            seller: TaxProfile,
            status: InvoiceStatus,
            receipt: ObrReceipt?,
            settings: DocumentSettings,
            cashier: String?,
        ): InvoiceDocument {
            val content = invoice.content
            return InvoiceDocument(
                title = TITLES.getValue(content.type),
                number = DocumentField("invoice_number", "Facture n°", content.number),
                date = DocumentField("invoice_date", "Date", DocumentText.DATE.format(content.issuedAt)),
                identifier = DocumentField("invoice_identifier", "Identifiant", content.identifier),
                notice = when (status) {
                    InvoiceStatus.Pending -> DocumentNotice.PENDING
                    is InvoiceStatus.Rejected -> DocumentNotice.REJECTED
                    InvoiceStatus.Cancelled -> DocumentNotice.CANCELLED
                    InvoiceStatus.Registered -> null
                },
                seller = sellerFields(seller),
                customer = with(content.customer) {
                    listOfNotNull(
                        DocumentField("customer_name", "Client", name),
                        tin?.let { DocumentField("customer_TIN", "NIF", it) },
                        address.takeIf { it.isNotEmpty() }?.let { DocumentField("customer_address", "Adresse", it) },
                        tin?.let {
                            DocumentField("vat_customer_payer", "Assujetti à la TVA", DocumentText.yesNo(vatPayer))
                        },
                    )
                },
                payment = listOfNotNull(
                    DocumentField("payment_type", "Paiement", PAYMENTS.getValue(content.paymentType)),
                    DocumentField("invoice_currency", "Devise", content.currency.name),
                    cashier?.let { DocumentField(null, "Caissier", it) },
                ),
                columns = COLUMNS.map { it.second },
                lines = content.lines.map { line(it) },
                totals = totals(content.totals),
                obr = receipt?.let {
                    listOf(
                        DocumentField(null, "N° d'enregistrement OBR", it.registeredNumber),
                        DocumentField(null, "Enregistrée le", it.registeredDate),
                    )
                }.orEmpty(),
                footer = settings.footer,
                logo = settings.logo,
                qrContent = content.identifier.takeIf { settings.showQrCode },
            )
        }
    }
}

private val TITLES = mapOf(
    DocumentType.FN to "FACTURE",
    DocumentType.FA to "FACTURE D'AVOIR",
    DocumentType.RC to "REMBOURSEMENT DE CAUTION",
    DocumentType.RHF to "RÉDUCTION HORS FACTURE",
)

private val PAYMENTS = mapOf(
    PaymentType.CASH to "Espèces",
    PaymentType.BANK to "Banque",
    PaymentType.CREDIT to "Crédit",
    PaymentType.OTHER to "Autres",
)

/** The columns of the lines: the field of `addInvoice`, its name, and how to read it from a line. */
private val COLUMNS: List<Triple<String, String, (InvoiceLine) -> String>> = listOf(
    Triple("item_designation", "Désignation") { it.designation },
    Triple("item_quantity", "Qté") { DocumentText.number(it.quantity.value) },
    Triple("item_price", "P.U. HT") { DocumentText.number(it.amounts.unitPrice.amount) },
    Triple("item_ct", "TC") { DocumentText.number(it.amounts.consumptionTax.amount) },
    Triple("item_tl", "PFL") { DocumentText.number(it.amounts.flatRateWithholding.amount) },
    Triple("item_price_nvat", "HTVA") { DocumentText.number(it.amounts.priceWithoutVat.amount) },
    Triple("vat", "TVA") { DocumentText.number(it.amounts.vat.amount) },
    Triple("item_price_wvat", "TVAC") { DocumentText.number(it.amounts.priceWithVat.amount) },
    Triple("item_total_amount", "Total") { DocumentText.number(it.amounts.total.amount) },
)

private fun line(line: InvoiceLine) =
    DocumentLine(COLUMNS.map { (obrName, label, value) -> DocumentField(obrName, label, value(line)) })

/** The seller: every mandatory field, and the optional ones that are filled. */
private fun sellerFields(seller: TaxProfile): List<DocumentField> {
    fun optional(obrName: String, label: String, value: String) =
        value.takeIf { it.isNotEmpty() }?.let { DocumentField(obrName, label, it) }
    val type = if (seller.taxpayerType == TaxpayerType.NATURAL_PERSON) "Personne physique" else "Personne morale"
    return listOfNotNull(
        DocumentField("tp_name", "Vendeur", seller.name),
        DocumentField("tp_TIN", "NIF", seller.tin),
        DocumentField("tp_trade_number", "Registre de commerce", seller.tradeNumber),
        DocumentField("tp_type", "Type de contribuable", type),
        DocumentField("tp_legal_form", "Forme juridique", seller.legalForm),
        DocumentField("tp_activity_sector", "Secteur d'activité", seller.activitySector),
        DocumentField("tp_fiscal_center", "Centre fiscal", seller.fiscalCenter.code),
        DocumentField("tp_phone_number", "Téléphone", seller.phoneNumber),
        optional("tp_postal_number", "Boîte postale", seller.postalNumber),
        optional("tp_address_province", "Province", seller.address.province),
        DocumentField("tp_address_commune", "Commune", seller.address.commune),
        DocumentField("tp_address_quartier", "Quartier", seller.address.quartier),
        optional("tp_address_avenue", "Avenue", seller.address.avenue),
        optional("tp_address_rue", "Rue", seller.address.rue),
        optional("tp_address_number", "Numéro", seller.address.number),
        DocumentField("vat_taxpayer", "Assujetti à la TVA", DocumentText.yesNo(seller.taxes.vat)),
        DocumentField(
            "ct_taxpayer",
            "Assujetti à la taxe de consommation",
            DocumentText.yesNo(seller.taxes.consumptionTax),
        ),
        DocumentField(
            "tl_taxpayer",
            "Assujetti au prélèvement forfaitaire",
            DocumentText.yesNo(seller.taxes.flatRateWithholding),
        ),
    )
}

/** The totals: the taxes that are zero on the whole invoice are left out, the amount to pay is last. */
private fun totals(totals: InvoiceTotals): List<DocumentField> {
    fun optional(label: String, money: Money) =
        money.takeIf { it.amount.signum() != 0 }?.let { DocumentField(null, label, DocumentText.amount(it)) }
    return listOfNotNull(
        optional("Taxe de consommation", totals.consumptionTax),
        optional("OTT", totals.ott),
        optional("TSCE", totals.tsce),
        DocumentField(null, "Total HTVA", DocumentText.amount(totals.priceWithoutVat)),
        DocumentField(null, "TVA", DocumentText.amount(totals.vat)),
        optional("Prélèvement forfaitaire (PFL)", totals.flatRateWithholding),
        DocumentField(null, "Total à payer", DocumentText.amount(totals.total)),
    )
}

/** How a document writes dates, numbers and yes or no: in French, whatever the device. */
internal object DocumentText {
    /** A date and a time, in Bujumbura. */
    val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(BusinessCalendar.ZONE)

    private val symbols = DecimalFormatSymbols(Locale.ROOT).apply {
        groupingSeparator = ' '
        decimalSeparator = ','
    }

    /** @return [value] with a space between thousands and a comma before the decimals: `2 420,4`. */
    fun number(value: BigDecimal): String = DecimalFormat("#,##0.##", symbols).format(value)

    /** @return [money] as [number], with its currency. */
    fun amount(money: Money): String = "${number(money.amount)} ${money.currency}"

    /** @return `Oui` or `Non`. */
    fun yesNo(value: Boolean): String = if (value) "Oui" else "Non"
}

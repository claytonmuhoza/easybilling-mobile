package com.claytonmuhoza.easybilling.testing

import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.PercentageBase
import com.claytonmuhoza.easybilling.domain.configuration.TaxComputation
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.customer.CustomerId
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.DocumentType
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceContent
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceCustomer
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceLine
import com.claytonmuhoza.easybilling.domain.invoicing.LineInput
import com.claytonmuhoza.easybilling.domain.invoicing.ObrLineTaxCalculator
import com.claytonmuhoza.easybilling.domain.invoicing.PaymentType
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.transmission.OutboxEntry
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import com.claytonmuhoza.easybilling.domain.transmission.QueuedDocument
import com.claytonmuhoza.easybilling.domain.transmission.RejectedDocument
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** Documents of the send queue and refused invoices, short to write in the tests. */
internal object TransmissionSamples {

    /** The moment the samples are read at. */
    val NOW: Instant = Instant.parse("2026-10-03T08:15:42Z")

    private val vat = TaxComputation.Percentage(BigDecimal("18"), PercentageBase.PRICE_WITHOUT_VAT)

    /**
     * @param id identifier of the invoice, which also gives its number.
     * @param customer name of the customer.
     * @param price price without taxes of its single line.
     * @param issuedAt when it was issued.
     * @return an invoice of one line.
     */
    fun invoice(
        id: Long,
        customer: String = "Client comptoir",
        price: String = "1000",
        issuedAt: Instant = NOW,
    ): Invoice {
        val unitPrice = Money.of(price, Currency.BIF)
        val computations = mapOf(TaxKind.VAT to vat)
        val input = LineInput(Quantity.of("1"), unitPrice, PriceMode.EXCLUDING_TAXES, computations)
        val number = "FN-%05d/2026".format(id)
        val content = InvoiceContent(
            type = DocumentType.FN,
            number = number,
            identifier = "4000000001/ws400000000100001/20261003101542/$number",
            issuedAt = issuedAt,
            profileVersion = 1,
            environment = EbmsEnvironment.TEST,
            customer = InvoiceCustomer(CustomerId(id), customer, null, "", false),
            paymentType = PaymentType.CASH,
            currency = Currency.BIF,
            lines = listOf(
                InvoiceLine(
                    itemId = ItemId(1),
                    itemCode = "ART-0001",
                    designation = "Savon de Marseille",
                    unit = "pièce",
                    stockTracked = true,
                    quantity = Quantity.of("1"),
                    listUnitPrice = unitPrice,
                    priceMode = PriceMode.EXCLUDING_TAXES,
                    discount = null,
                    vatRate = BigDecimal("18"),
                    amounts = ObrLineTaxCalculator().compute(input, TaxRegistrations(vat = true)),
                ),
            ),
            issuedBy = UserId(1),
        )
        return Invoice(InvoiceId(id), content)
    }

    /**
     * @param id position in the queue, also the identifier of its invoice.
     * @param waiting how long it has been in the queue at [NOW].
     * @param attempts how many times it was tried; the next try is 5 minutes after [NOW] if any.
     * @param uncertain whether a request left without an answer.
     * @param customer name of the customer of its invoice.
     * @return a document of the send queue with its invoice.
     */
    fun queued(
        id: Long,
        waiting: Duration,
        attempts: Int = 0,
        uncertain: Boolean = false,
        customer: String = "Client comptoir",
    ): QueuedDocument {
        val createdAt = NOW - waiting
        val entry = OutboxEntry(
            id = id,
            operation = OutboxOperation.SendInvoice(InvoiceId(id)),
            createdAt = createdAt,
            attempts = attempts,
            nextAttemptAt = if (attempts > 0) NOW + Duration.ofMinutes(5) else null,
            uncertain = uncertain,
            lastError = if (attempts > 0) "Failed to connect to ebms.obr.gov.bi" else "",
        )
        return QueuedDocument(entry, invoice(id, customer, issuedAt = createdAt))
    }

    /**
     * @param id identifier of the invoice.
     * @param message the message of the OBR.
     * @return an invoice the OBR refused.
     */
    fun rejected(
        id: Long,
        message: String = "Le NIF du client n'est pas connu par l'OBR. Veuillez fournir un NIF valide",
    ): RejectedDocument = RejectedDocument(invoice(id, "Inconnu SA", price = "250000"), message)
}

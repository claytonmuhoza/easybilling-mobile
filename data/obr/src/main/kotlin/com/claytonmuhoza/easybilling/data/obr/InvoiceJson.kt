package com.claytonmuhoza.easybilling.data.obr

import com.claytonmuhoza.easybilling.domain.configuration.TaxProfile
import com.claytonmuhoza.easybilling.domain.invoicing.Invoice
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceLine
import com.claytonmuhoza.easybilling.domain.shared.BusinessCalendar
import com.claytonmuhoza.easybilling.domain.shared.Money
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.format.DateTimeFormatter

/**
 * Writes an invoice as the body of `addInvoice_confirm/` (pages 19 to 21 of the specification).
 * Every value is a text, numbers included; a value that does not exist is an empty text, never
 * `null`; dates are written in the time of Bujumbura.
 */
internal object InvoiceJson {
    private val DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(BusinessCalendar.ZONE)

    /**
     * @param invoice the invoice.
     * @param seller the tax profile the invoice was issued with.
     * @return the body to send.
     */
    fun of(invoice: Invoice, seller: TaxProfile): JsonObject = buildJsonObject {
        val content = invoice.content
        put("invoice_number", content.number)
        put("invoice_date", DATE.format(content.issuedAt))
        put("invoice_type", content.type.code)
        put("tp_type", seller.taxpayerType.code)
        put("tp_name", seller.name)
        put("tp_TIN", seller.tin)
        put("tp_trade_number", seller.tradeNumber)
        put("tp_postal_number", seller.postalNumber)
        put("tp_phone_number", seller.phoneNumber)
        put("tp_address_province", seller.address.province)
        put("tp_address_commune", seller.address.commune)
        put("tp_address_quartier", seller.address.quartier)
        put("tp_address_avenue", seller.address.avenue)
        put("tp_address_rue", seller.address.rue)
        put("tp_address_number", seller.address.number)
        put("vat_taxpayer", flag(seller.taxes.vat))
        put("ct_taxpayer", flag(seller.taxes.consumptionTax))
        put("tl_taxpayer", flag(seller.taxes.flatRateWithholding))
        put("tp_fiscal_center", seller.fiscalCenter.code)
        put("tp_activity_sector", seller.activitySector)
        put("tp_legal_form", seller.legalForm)
        put("payment_type", content.paymentType.code)
        put("invoice_currency", content.currency.name)
        put("customer_name", content.customer.name)
        put("customer_TIN", content.customer.tin.orEmpty())
        put("customer_address", content.customer.address)
        put("vat_customer_payer", flag(content.customer.vatPayer))
        // A replacement names the invoice it cancels (UC-E02); the other two are for the credit notes.
        put("cancelled_invoice_ref", content.cancelledInvoiceRef.orEmpty())
        put("invoice_ref", "")
        put("cn_motif", "")
        put("invoice_identifier", content.identifier)
        putJsonArray("invoice_items") { content.lines.forEach { add(line(it)) } }
    }

    private fun line(line: InvoiceLine): JsonObject = buildJsonObject {
        put("item_designation", line.designation)
        put("item_quantity", line.quantity.toPlainString())
        put("item_price", number(line.amounts.unitPrice))
        put("item_ct", number(line.amounts.consumptionTax))
        put("item_tl", number(line.amounts.flatRateWithholding))
        put("item_ott_tax", number(line.amounts.ott))
        put("item_tsce_tax", number(line.amounts.tsce))
        put("item_price_nvat", number(line.amounts.priceWithoutVat))
        put("vat", number(line.amounts.vat))
        put("item_price_wvat", number(line.amounts.priceWithVat))
        put("item_total_amount", number(line.amounts.total))
    }

    /** @return the amount without useless zero nor exponent: `365.4`, `2030`. */
    private fun number(money: Money): String = money.toPlainString()

    private fun flag(value: Boolean): String = if (value) "1" else "0"
}

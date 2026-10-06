package com.claytonmuhoza.easybilling.data.local

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claytonmuhoza.easybilling.data.local.configuration.SecretStoreCredentialsVault
import com.claytonmuhoza.easybilling.domain.audit.ChainedAuditLog
import com.claytonmuhoza.easybilling.domain.catalog.ItemDetails
import com.claytonmuhoza.easybilling.domain.configuration.Address
import com.claytonmuhoza.easybilling.domain.configuration.CheckBillingReadiness
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentials
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettings
import com.claytonmuhoza.easybilling.domain.configuration.FiscalCenter
import com.claytonmuhoza.easybilling.domain.configuration.NumberingCheck
import com.claytonmuhoza.easybilling.domain.configuration.NumberingForm
import com.claytonmuhoza.easybilling.domain.configuration.PercentageBase
import com.claytonmuhoza.easybilling.domain.configuration.TaxComputation
import com.claytonmuhoza.easybilling.domain.configuration.TaxKind
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfile
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileVersion
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxVersion
import com.claytonmuhoza.easybilling.domain.configuration.TaxpayerType
import com.claytonmuhoza.easybilling.domain.identity.AccessControl
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.AllocateInvoiceNumber
import com.claytonmuhoza.easybilling.domain.invoicing.EditSale
import com.claytonmuhoza.easybilling.domain.invoicing.IssueConfiguration
import com.claytonmuhoza.easybilling.domain.invoicing.IssueSale
import com.claytonmuhoza.easybilling.domain.invoicing.IssueStorage
import com.claytonmuhoza.easybilling.domain.invoicing.PriceMode
import com.claytonmuhoza.easybilling.domain.invoicing.PriceSale
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.SecretStore
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * UC-C01 on a device or emulator, with the encrypted database: an invoice of 20 lines is issued in
 * less than a second. The time is written in the log under the tag `InvoiceIssue`.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedInvoiceIssueTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val storage = LocalStorage.encrypted(context, DATABASE_NAME) { ByteArray(KEY_SIZE) { it.toByte() } }
    private val secrets = object : SecretStore {
        private val values = mutableMapOf<String, ByteArray>()

        override fun read(name: String): ByteArray? = values[name]

        override fun write(name: String, value: ByteArray) {
            values[name] = value
        }
    }
    private val now = Instant.parse("2026-10-03T08:15:42Z")
    private val time = TimeProvider { now }
    private val alice = UserId(1)
    private val vault = SecretStoreCredentialsVault(secrets)
    private val edit = EditSale(storage.saleDrafts, time, storage.transactions)
    private val issue = IssueSale(
            AccessControl { _, _ -> },
        IssueConfiguration(
            storage.taxProfiles,
            storage.ebmsSettings,
            vault,
            CheckBillingReadiness(storage.taxProfiles, storage.ebmsSettings, storage.taxes, storage.numbering, time),
        ),
        IssueStorage(
            storage.saleDrafts,
            storage.invoices,
            storage.outbox,
            ChainedAuditLog(storage.auditStore, time, storage.transactions),
        ),
        PriceSale(storage.items, storage.taxes, storage.customers, storage.taxProfiles, time),
        AllocateInvoiceNumber(storage.numbering, time, storage.transactions),
        storage.transactions,
        time,
    )

    @Before
    fun configureAndType() = runBlocking {
        val profile = TaxProfile(
            taxpayerType = TaxpayerType.LEGAL_PERSON,
            name = "Boutique Umuco SPRL",
            tin = "4000000001",
            tradeNumber = "RC 12345",
            postalNumber = "",
            phoneNumber = "+257 22 22 22 22",
            address = Address("Bujumbura Mairie", "Mukaza", "Rohero", "Avenue de l'Industrie", "", ""),
            taxes = TaxRegistrations(vat = true),
            fiscalCenter = FiscalCenter.DMC,
            activitySector = "Commerce general",
            legalForm = "SPRL",
        )
        storage.taxProfiles.append(TaxProfileVersion(1, profile, now, alice))
        vault.write(EbmsEnvironment.TEST, EbmsCredentials("ws400000000100001", "password"))
        storage.ebmsSettings.write(EbmsSettings(lastSuccessfulLogins = mapOf(EbmsEnvironment.TEST to now)))
        storage.numbering.save(assertIs<NumberingCheck.Valid>(NumberingForm().check(null)).settings)
        val rate = TaxComputation.Percentage(BigDecimal("18"), PercentageBase.PRICE_WITHOUT_VAT)
        val vat = storage.taxes.create(TaxKind.VAT, "TVA 18 %", TaxVersion(LocalDate.of(2026, 10, 2), rate))
        storage.transactions.run {
            repeat(LINES) { rank ->
                val details = ItemDetails(
                    designation = "Article $rank",
                    unit = "pièce",
                    price = Money.of("1500", Currency.BIF),
                    priceMode = PriceMode.EXCLUDING_TAXES,
                    taxes = mapOf(TaxKind.VAT to vat.id),
                    stockTracked = true,
                )
                edit.addItem(storage.items.create("ART-%04d".format(rank), details).id)
            }
        }
    }

    @After
    fun deleteDatabase() {
        storage.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun anInvoiceOfTwentyLinesIsIssuedInLessThanASecond() {
        val draft = runBlocking { edit.current() }

        val millis = measureTimeMillis { runBlocking { assertIs<IssueSale.Result.Issued>(issue(draft.id, alice)) } }

        Log.i("InvoiceIssue", "Issue of an invoice of $LINES lines: $millis ms")
        assertTrue(millis < ONE_SECOND_MILLIS, "The issue took $millis ms")
        assertEquals(LINES, runBlocking { storage.invoices.findByNumber("FN-00001/2026") }!!.content.lines.size)
    }

    private companion object {
        const val DATABASE_NAME = "encrypted-invoice-issue-test.db"
        const val KEY_SIZE = 32
        const val LINES = 20
        const val ONE_SECOND_MILLIS = 1_000L
    }
}

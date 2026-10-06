package com.claytonmuhoza.easybilling.domain.document

import com.claytonmuhoza.easybilling.domain.identity.AccessControl
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.invoicing.InvoiceId

/**
 * A printer the device is paired with.
 *
 * @property address its Bluetooth address, which identifies it.
 * @property name its name, as the device shows it.
 */
data class PairedPrinter(val address: String, val name: String)

/**
 * The printer of the tickets and how it prints (UC-A09). One printer at most is associated.
 *
 * @property printer the associated printer, or null while there is none.
 * @property width the paper of the printer.
 * @property accents whether the printer prints accented characters; off, they are replaced by
 * plain letters, which any printer prints.
 * @property autoPrint whether the ticket is printed as soon as a sale is issued.
 */
data class PrinterSettings(
    val printer: PairedPrinter? = null,
    val width: ReceiptWidth = ReceiptWidth.MM_58,
    val accents: Boolean = false,
    val autoPrint: Boolean = false,
)

/** Storage of the settings of the printer. */
interface PrinterSettingsRepository {
    /** @return the settings; the defaults while none was saved. */
    suspend fun read(): PrinterSettings

    /** @param settings the settings, which replace the saved ones. */
    suspend fun write(settings: PrinterSettings)
}

/** How sending a ticket to the printer ended. */
enum class PrintOutcome {
    /** The ticket was sent to the printer. */
    PRINTED,

    /** No printer is associated. */
    NO_PRINTER,

    /** Bluetooth is off, or the device has none. */
    BLUETOOTH_OFF,

    /** The application is not allowed to use Bluetooth. */
    PERMISSION_MISSING,

    /** The printer did not answer: off, out of reach or out of paper. */
    UNREACHABLE,
}

/** What looking for the paired printers gave. */
sealed interface PrinterLookup {
    /** The printers the device is paired with; may be empty. */
    data class Found(val printers: List<PairedPrinter>) : PrinterLookup

    /** Bluetooth is off, or the device has none. */
    data object BluetoothOff : PrinterLookup

    /** The application is not allowed to use Bluetooth. */
    data object PermissionMissing : PrinterLookup
}

/** The printers of tickets. The domain knows nothing of Bluetooth nor of the commands of a printer. */
interface ReceiptPrinter {
    /** @return the printers the device is already paired with; the application searches for none. */
    fun paired(): PrinterLookup

    /**
     * @param address the address of the printer.
     * @param receipt the ticket.
     * @return [PrintOutcome.PRINTED], or why nothing was printed.
     */
    suspend fun print(address: String, receipt: Receipt): PrintOutcome
}

/**
 * Prints the ticket of an invoice on the associated printer (UC-G01). Every ticket printed is
 * traced in the audit log; a copy carries no mark.
 *
 * @param build builds the document of the invoice.
 * @param settings the printer and how it prints.
 * @param printer sends the ticket.
 * @param auditLog log that records each ticket printed.
 */
class PrintInvoice(
    private val access: AccessControl,
    private val build: BuildInvoiceDocument,
    private val settings: PrinterSettingsRepository,
    private val printer: ReceiptPrinter,
    private val auditLog: AuditLog,
) {
    /** Outcome of a print. */
    sealed interface Result {
        /** The ticket was sent to the printer. */
        data object Printed : Result

        /** There is no such invoice. */
        data object NotFound : Result

        /** No printer is associated. */
        data object NoPrinter : Result

        /** The printer could not print, because of [outcome]. */
        data class Failed(val outcome: PrintOutcome) : Result
    }

    /**
     * @param id the invoice.
     * @param actor the user who prints.
     * @return whether the ticket was printed.
     * @throws com.claytonmuhoza.easybilling.domain.identity.PermissionDeniedException if [actor] is not allowed to.
     */
    suspend operator fun invoke(id: InvoiceId, actor: UserId): Result {
        access.require(actor, Permission.SELL)
        return allowed(id, actor)
    }

    private suspend fun allowed(id: InvoiceId, actor: UserId): Result {
        val chosen = settings.read()
        val target = chosen.printer ?: return Result.NoPrinter
        return when (val built = build(id)) {
            BuildInvoiceDocument.Result.NotFound -> Result.NotFound
            is BuildInvoiceDocument.Result.Built -> {
                val receipt = Receipt.of(built.document, chosen.width, chosen.accents)
                when (val outcome = printer.print(target.address, receipt)) {
                    PrintOutcome.PRINTED -> {
                        auditLog.record(AuditEvent.InvoicePrinted(actor, id))
                        Result.Printed
                    }
                    else -> Result.Failed(outcome)
                }
            }
        }
    }
}

/**
 * Prints a page that shows whether the printer is set right (UC-A09).
 *
 * @param settings the printer and how it prints.
 * @param printer sends the page.
 */
class PrintTestPage(private val settings: PrinterSettingsRepository, private val printer: ReceiptPrinter) {
    /** @return [PrintOutcome.PRINTED], or why nothing was printed. */
    suspend operator fun invoke(): PrintOutcome {
        val chosen = settings.read()
        val target = chosen.printer ?: return PrintOutcome.NO_PRINTER
        return printer.print(target.address, Receipt.testPage(target.name, chosen.width, chosen.accents))
    }
}

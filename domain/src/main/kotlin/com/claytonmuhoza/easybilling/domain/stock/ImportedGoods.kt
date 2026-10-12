package com.claytonmuhoza.easybilling.domain.stock

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.catalog.Item
import com.claytonmuhoza.easybilling.domain.catalog.ItemId
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.identity.AccessControl
import com.claytonmuhoza.easybilling.domain.identity.Permission
import com.claytonmuhoza.easybilling.domain.identity.PermissionDeniedException
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.shared.Currency
import com.claytonmuhoza.easybilling.domain.shared.Money
import com.claytonmuhoza.easybilling.domain.shared.Quantity
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import com.claytonmuhoza.easybilling.domain.transmission.DispatchConnection
import com.claytonmuhoza.easybilling.domain.transmission.DmcOutcome
import com.claytonmuhoza.easybilling.domain.transmission.OutboxOperation
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * What an entry of imported goods adds to a stock movement (`AddStockMovementImporters/`, pages 14
 * and 34 to 36 of the specification).
 *
 * @property dmcReference `reference_dmc`: the reference of the customs declaration.
 * @property dmcLine the rank of the line of the declaration the goods come from, from one. It never
 * leaves the device.
 * @property tariffHeading `rubrique_tarifaire`: the code of the goods in the customs tariff.
 * @property piecesPerPacket `nombre_par_paquet`: how many pieces a packet holds.
 * @property packaging `description_paquet`: the kind of packaging.
 */
data class ImportedGoods(
    val dmcReference: String,
    val dmcLine: Int,
    val tariffHeading: String,
    val piecesPerPacket: BigDecimal,
    val packaging: String,
) {
    /** The limits of the specification (page 14). */
    companion object {
        /** Most characters of `reference_dmc`, of `rubrique_tarifaire` and of `description_paquet`. */
        const val MAX_LENGTH = 50
    }
}

/**
 * A line of a customs declaration, as `getDmcItems` gives it (page 33 of the specification).
 *
 * @property rank the rank of the line in the answer of the OBR, from one.
 * @property tariffHeading `rubrique_tarifaire`.
 * @property description `description_article`.
 * @property quantity `quantite`, or null when the OBR did not give a number. The specification does
 * not say in which unit it is counted.
 * @property packaging `description_packet`.
 */
data class DmcLine(
    val rank: Int,
    val tariffHeading: String,
    val description: String,
    val quantity: BigDecimal?,
    val packaging: String,
)

/**
 * A customs declaration ("déclaration de mise en consommation") read from the OBR.
 *
 * @property reference `reference_dmc`.
 * @property tin the NIF it was read for.
 * @property fetchedAt when it was read.
 * @property lines its lines, in the order of the OBR.
 */
data class Dmc(val reference: String, val tin: String, val fetchedAt: Instant, val lines: List<DmcLine>)

/** Storage of the customs declarations read from the OBR, and reading of what was entered of them. */
interface DmcRepository {
    /** @param dmc a declaration; it replaces the one with the same reference. */
    suspend fun save(dmc: Dmc)

    /**
     * @param reference a reference.
     * @return the declaration, or null if it was never read.
     */
    suspend fun find(reference: String): Dmc?

    /** @return the declarations read, the most recently read first. */
    suspend fun all(): List<Dmc>

    /**
     * @param reference the reference of a declaration.
     * @return the entries of imported goods made for it, the oldest first.
     */
    suspend fun entriesOf(reference: String): List<StockMovement>
}

/**
 * Reads a customs declaration from the OBR and keeps it (UC-F03). The call is made at once: it
 * needs the network.
 *
 * @param access checks the rights of the user.
 * @param profiles gives the NIF of the business.
 * @param connection how the OBR is reached.
 * @param dmcs where the declarations are kept.
 * @param time source of the time of the reading.
 */
class FetchDmc(
    private val access: AccessControl,
    private val profiles: TaxProfileRepository,
    private val connection: DispatchConnection,
    private val dmcs: DmcRepository,
    private val time: TimeProvider,
) {
    /** Outcome of a reading. */
    sealed interface Result {
        /** [dmc] is read and kept. */
        data class Fetched(val dmc: Dmc) : Result

        /** The reference is empty or longer than [ImportedGoods.MAX_LENGTH]. */
        data object InvalidReference : Result

        /** The OBR refused, with its [message] (`msg`). */
        data class Refused(val message: String) : Result

        /** The OBR knows the reference and gave no line for it. */
        data object Empty : Result

        /** No usable answer came, because of [reason]. */
        data class Unavailable(val reason: String) : Result

        /** The tax profile or the connection to the EBMS is not configured. */
        data object NotConfigured : Result
    }

    /**
     * @param reference the reference of the declaration, as typed.
     * @param actor the user who asks for it.
     * @return the declaration, or why it was not read.
     * @throws PermissionDeniedException if [actor] may not record a stock movement.
     */
    suspend operator fun invoke(reference: String, actor: UserId): Result {
        access.require(actor, Permission.RECORD_STOCK)
        val asked = reference.trim()
        val tin = profiles.current()?.profile?.tin
        val target = connection.target()
        return when {
            asked.isEmpty() || asked.length > ImportedGoods.MAX_LENGTH -> Result.InvalidReference
            tin == null || target == null -> Result.NotConfigured
            else -> {
                val outcome = connection.withToken({ it is DmcOutcome.TokenRefused }) { token ->
                    connection.gateway.getDmcItems(target, token, tin, asked)
                }
                read(outcome, asked, tin)
            }
        }
    }

    private suspend fun read(outcome: DmcOutcome?, reference: String, tin: String): Result = when (outcome) {
        is DmcOutcome.Found -> if (outcome.lines.isEmpty()) {
            Result.Empty
        } else {
            Result.Fetched(Dmc(reference, tin, time.now(), outcome.lines).also { dmcs.save(it) })
        }
        is DmcOutcome.Refused -> Result.Refused(outcome.message)
        is DmcOutcome.Unavailable -> Result.Unavailable(outcome.reason)
        DmcOutcome.TokenRefused -> Result.Unavailable("TOKEN_REFUSED")
        null -> Result.Unavailable("LOGIN")
    }
}

/**
 * A line of a declaration with what was entered of it.
 *
 * @property line the line.
 * @property entered what was entered into the stock for it so far.
 * @property item the item it was last entered as, or null while nothing was entered.
 */
data class DmcLineProgress(val line: DmcLine, val entered: BigDecimal, val item: Item?) {
    /** What is left to enter, zero at least, or null when the OBR gave no quantity for the line. */
    val remaining: BigDecimal? get() = line.quantity?.let { (it - entered).max(BigDecimal.ZERO) }
}

/**
 * A declaration with what was entered of each line.
 *
 * @property dmc the declaration.
 * @property lines its lines, in the order of the OBR.
 */
data class DmcProgress(val dmc: Dmc, val lines: List<DmcLineProgress>)

/**
 * Reads a declaration kept on the device with what was entered of it (UC-F04).
 *
 * @param dmcs the declarations.
 * @param stock the movements.
 * @param storage the catalogue.
 */
class ReadDmc(private val dmcs: DmcRepository, private val stock: StockRepository, private val storage: StockStorage) {
    /**
     * @param reference the reference of a declaration.
     * @return the declaration with what was entered of each line, or null if it was never read.
     */
    suspend operator fun invoke(reference: String): DmcProgress? {
        val dmc = dmcs.find(reference) ?: return null
        val entries = dmcs.entriesOf(reference).filter { stock.statusOf(it.id)?.counts == true }
        return DmcProgress(
            dmc,
            dmc.lines.map { line ->
                val ofLine = entries.filter { it.content.imported?.dmcLine == line.rank }
                DmcLineProgress(
                    line = line,
                    entered = ofLine.sumOf { it.content.quantity.value },
                    item = ofLine.lastOrNull()?.let { storage.items.find(it.content.itemId) },
                )
            },
        )
    }
}

/**
 * An entry of imported goods as typed, not checked yet. A comma is a decimal point.
 *
 * @property dmcReference the declaration the goods come from.
 * @property dmcLine the rank of its line.
 * @property itemId the item of the catalogue the goods are, or null while none is chosen.
 * @property quantity the quantity entered, in the unit of the item.
 * @property unitCost the unit purchase price, taxes included.
 * @property piecesPerPacket how many pieces a packet holds.
 * @property packaging the kind of packaging.
 * @property description what the user writes of the entry; may be empty.
 */
data class ImportForm(
    val dmcReference: String,
    val dmcLine: Int,
    val itemId: ItemId? = null,
    val quantity: String = "",
    val unitCost: String = "",
    val piecesPerPacket: String = "",
    val packaging: String = "",
    val description: String = "",
)

/** A broken rule of what was typed for an entry of imported goods. */
enum class ImportProblem {
    /** No item is chosen. */
    ITEM_MISSING,

    /** The quantity is not a number above zero with two decimals at most. */
    QUANTITY_INVALID,

    /** The unit purchase price is not a number above zero with two decimals at most. */
    COST_INVALID,

    /** The number of pieces per packet is not a number above zero with two decimals at most. */
    PER_PACKET_INVALID,

    /** The kind of packaging is empty. */
    PACKAGING_MISSING,

    /** The kind of packaging is longer than [ImportedGoods.MAX_LENGTH]. */
    PACKAGING_TOO_LONG,

    /** With the reference before it, the description is longer than the OBR accepts. */
    DESCRIPTION_TOO_LONG,
}

/**
 * Enters imported goods into the stock (UC-F04): an entry EN declared by
 * `AddStockMovementImporters/` with its declaration, its tariff heading and its packaging. The
 * movement, its entry in the send queue and the audit entry are written together or not at all.
 * What is entered of a line never goes beyond the quantity of the declaration (internal rule).
 *
 * @param access checks the rights of the user.
 * @param storage where the movement is read and written.
 * @param dmcs the declarations read from the OBR.
 * @param transactions makes the entry a single write.
 * @param time source of the date of the movement.
 */
class EnterImportedGoods(
    private val access: AccessControl,
    private val storage: StockStorage,
    private val dmcs: DmcRepository,
    private val transactions: TransactionRunner,
    private val time: TimeProvider,
) {
    /** Outcome of an entry. */
    sealed interface Result {
        /** [movement] is recorded, waiting to be declared to the OBR. */
        data class Recorded(val movement: StockMovement) : Result

        /** What was typed breaks rules; nothing was recorded. */
        data class Invalid(val problems: Set<ImportProblem>) : Result

        /** The item cannot move; nothing was recorded. */
        data class Refused(val problem: StockProblem) : Result

        /** The declaration was never read, or has no such line; nothing was recorded. */
        data object LineNotFound : Result

        /** Only [remaining] is left to enter of the line; nothing was recorded. */
        data class ExceedsDmc(val remaining: BigDecimal) : Result
    }

    /** An entry once checked: what is left to write. */
    private class Checked(
        val item: Item,
        val quantity: BigDecimal,
        val cost: BigDecimal,
        val goods: ImportedGoods,
        val reference: String,
        val description: String,
    )

    /**
     * @param form the entry as typed.
     * @param actor the user who records it.
     * @return the movement, or why nothing was recorded.
     * @throws PermissionDeniedException if [actor] may not record a stock movement.
     */
    suspend operator fun invoke(form: ImportForm, actor: UserId): Result {
        access.require(actor, Permission.RECORD_STOCK)
        return transactions.run { entered(form, actor) }
    }

    private suspend fun entered(form: ImportForm, actor: UserId): Result {
        val line = dmcs.find(form.dmcReference)?.lines?.firstOrNull { it.rank == form.dmcLine }
        val item = form.itemId?.let { storage.items.find(it) }
        val refusal = when {
            form.itemId == null || line == null -> null
            item == null -> StockProblem.ITEM_NOT_FOUND
            !item.details.stockTracked -> StockProblem.ITEM_NOT_TRACKED
            item.archived -> StockProblem.ITEM_ARCHIVED
            else -> null
        }
        return when {
            line == null -> Result.LineNotFound
            refusal != null -> Result.Refused(refusal)
            else -> checked(form, line, item, actor)
        }
    }

    private suspend fun checked(form: ImportForm, line: DmcLine, item: Item?, actor: UserId): Result {
        val reference = MovementReference.of(deviceCode(), storage.stock.count() + 1)
        val quantity = form.quantity.positive()
        val cost = form.unitCost.positive()
        val perPacket = form.piecesPerPacket.positive()
        val packaging = form.packaging.trim()
        val description = form.description.trim()
        val problems = setOfNotNull(
            ImportProblem.ITEM_MISSING.takeIf { item == null },
            ImportProblem.QUANTITY_INVALID.takeIf { quantity == null },
            ImportProblem.COST_INVALID.takeIf { cost == null },
            ImportProblem.PER_PACKET_INVALID.takeIf { perPacket == null },
            ImportProblem.PACKAGING_MISSING.takeIf { packaging.isEmpty() },
            ImportProblem.PACKAGING_TOO_LONG.takeIf { packaging.length > ImportedGoods.MAX_LENGTH },
            ImportProblem.DESCRIPTION_TOO_LONG
                .takeIf { description.length > StockMovementContent.maxTypedLength(reference) },
        )
        val remaining = remaining(form.dmcReference, line)
        return when {
            item == null || quantity == null || cost == null || perPacket == null || problems.isNotEmpty() ->
                Result.Invalid(problems)
            remaining != null && quantity > remaining -> Result.ExceedsDmc(remaining)
            else -> {
                val goods = ImportedGoods(form.dmcReference, line.rank, line.tariffHeading, perPacket, packaging)
                stored(Checked(item, quantity, cost, goods, reference, description), actor)
            }
        }
    }

    /** @return what is left to enter of [line], or null when the OBR gave no quantity for it. */
    private suspend fun remaining(reference: String, line: DmcLine): BigDecimal? {
        val entered = dmcs.entriesOf(reference)
            .filter { it.content.imported?.dmcLine == line.rank && storage.stock.statusOf(it.id)?.counts == true }
            .sumOf { it.content.quantity.value }
        return line.quantity?.let { (it - entered).max(BigDecimal.ZERO) }
    }

    private suspend fun stored(checked: Checked, actor: UserId): Result {
        // The OBR reads the date to the second.
        val now = time.now().truncatedTo(ChronoUnit.SECONDS)
        val movement = storage.stock.record(
            StockMovementContent(
                reference = checked.reference,
                itemId = checked.item.id,
                itemCode = checked.item.code,
                designation = checked.item.details.designation,
                unit = checked.item.details.unit,
                type = MovementType.EN,
                quantity = Quantity.of(checked.quantity),
                unitCost = Money.of(checked.cost, Currency.BIF),
                description = checked.description,
                occurredAt = now,
                environment = storage.ebmsSettings.read().activeEnvironment,
                recordedBy = actor,
                imported = checked.goods,
            ),
        )
        storage.outbox.enqueue(OutboxOperation.SendStockMovement(movement.id), now)
        storage.auditLog
            .record(AuditEvent.StockMovementRecorded(actor, movement.id, checked.reference, approver = null))
        return Result.Recorded(movement)
    }

    /** The code of this device when several devices bill, or an empty text. */
    private suspend fun deviceCode(): String =
        storage.numbering.settings()?.takeIf { it.multipleDevices }?.deviceCode.orEmpty()

    /** @return this text as a number above zero with two decimals at most, or null. */
    private fun String.positive(): BigDecimal? = toStockNumber()?.takeIf { it.signum() > 0 }
}

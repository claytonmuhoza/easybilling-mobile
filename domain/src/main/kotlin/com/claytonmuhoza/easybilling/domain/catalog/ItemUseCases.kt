package com.claytonmuhoza.easybilling.domain.catalog

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.configuration.NumberingRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxProfileRepository
import com.claytonmuhoza.easybilling.domain.configuration.TaxRegistrations
import com.claytonmuhoza.easybilling.domain.configuration.TaxRepository
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner

/**
 * Reads what the rules of an item depend on: the tax profile, the numbering and the taxes.
 *
 * @param profiles where the tax profile is kept.
 * @param numbering where the device code is kept.
 * @param taxes where the taxes are kept.
 */
class ReadItemRules(
    private val profiles: TaxProfileRepository,
    private val numbering: NumberingRepository,
    private val taxes: TaxRepository,
) {
    /** @return the rules in force now. */
    suspend operator fun invoke(): ItemRules = ItemRules(
        registrations = profiles.current()?.profile?.taxes ?: TaxRegistrations(),
        devicePrefix = numbering.settings()?.takeIf { it.multipleDevices }?.deviceCode,
        taxes = taxes.all(),
    )
}

/**
 * Proposes the code of a new item: `ART-0001`, `ART-0002`… after the device code if there is one.
 *
 * @param items where the items are kept.
 */
class SuggestItemCode(private val items: ItemRepository) {
    /**
     * @param devicePrefix the code of this device when several devices invoice, or null.
     * @return a code no item has yet.
     */
    suspend operator fun invoke(devicePrefix: String?): String {
        val prefix = devicePrefix?.let { "$it-" }.orEmpty()
        var rank = items.count(archived = false) + items.count(archived = true) + 1
        while (items.findByCode(code(prefix, rank)) != null) rank++
        return code(prefix, rank)
    }

    private fun code(prefix: String, rank: Int) = "$prefix$WORD-${rank.toString().padStart(DIGITS, '0')}"

    private companion object {
        const val WORD = "ART"
        const val DIGITS = 4
    }
}

/**
 * Creates an item (UC-B01).
 *
 * @param items where the items are kept.
 * @param readRules gives what the rules depend on.
 * @param auditLog log that records each creation.
 * @param transactions makes the check of the code, the item and its audit entry a single write.
 */
class CreateItem(
    private val items: ItemRepository,
    private val readRules: ReadItemRules,
    private val auditLog: AuditLog,
    private val transactions: TransactionRunner,
) {
    /** Outcome of a creation. */
    sealed interface Result {
        /** [item] is stored. */
        data class Created(val item: Item) : Result

        /** The form breaks rules; nothing was stored. */
        data class Invalid(val problems: Set<ItemProblem>) : Result
    }

    /**
     * @param form the item as typed.
     * @param actor the user who creates it.
     * @return the created item, or why nothing was stored.
     */
    suspend operator fun invoke(form: ItemForm, actor: UserId): Result = transactions.run {
        when (val check = form.check(readRules())) {
            is ItemCheck.Invalid -> Result.Invalid(check.problems)
            is ItemCheck.Valid -> {
                val taken = setOfNotNull(
                    ItemProblem.CODE_TAKEN.takeIf { items.findByCode(check.code) != null },
                    ItemProblem.BARCODE_TAKEN.takeIf { items.barcodeTaken(check.details.barcode, except = null) },
                )
                if (taken.isEmpty()) created(check, actor) else Result.Invalid(taken)
            }
        }
    }

    private suspend fun created(check: ItemCheck.Valid, actor: UserId): Result {
        val item = items.create(check.code, check.details)
        auditLog.record(AuditEvent.ItemCreated(actor, item.id, item.code))
        return Result.Created(item)
    }
}

/**
 * @param barcode a barcode, or null for none.
 * @param except the item that may already have it, or null.
 * @return whether another item, archived or not, has [barcode].
 */
internal suspend fun ItemRepository.barcodeTaken(barcode: String?, except: ItemId?): Boolean =
    barcode != null && findByBarcode(barcode).let { it != null && it.id != except }

/**
 * Changes an item (UC-B01). Its code never changes; the invoices already issued are not touched.
 *
 * @param items where the items are kept.
 * @param readRules gives what the rules depend on.
 * @param auditLog log that records each change.
 * @param transactions makes the change and its audit entry a single write.
 */
class UpdateItem(
    private val items: ItemRepository,
    private val readRules: ReadItemRules,
    private val auditLog: AuditLog,
    private val transactions: TransactionRunner,
) {
    /** Outcome of a change. */
    sealed interface Result {
        /** The change is stored. */
        data object Updated : Result

        /** The form breaks rules; nothing was stored. */
        data class Invalid(val problems: Set<ItemProblem>) : Result

        /** There is no item with that identifier. */
        data object NotFound : Result
    }

    /**
     * @param id the item.
     * @param form the item as typed; its code is ignored.
     * @param actor the user who changes it.
     * @return whether the change was stored.
     */
    suspend operator fun invoke(id: ItemId, form: ItemForm, actor: UserId): Result = transactions.run {
        val existing = items.find(id) ?: return@run Result.NotFound
        when (val check = form.check(readRules(), existing)) {
            is ItemCheck.Invalid -> Result.Invalid(check.problems)
            is ItemCheck.Valid -> if (items.barcodeTaken(check.details.barcode, except = id)) {
                Result.Invalid(setOf(ItemProblem.BARCODE_TAKEN))
            } else {
                items.update(id, check.details)
                auditLog.record(AuditEvent.ItemUpdated(actor, id))
                Result.Updated
            }
        }
    }
}

/**
 * Archives an item so it can no longer be sold, or brings it back (UC-B04). It is never deleted:
 * the invoices and the stock movements keep referring to it.
 *
 * @param items where the items are kept.
 * @param auditLog log that records each change.
 * @param transactions makes the change and its audit entry a single write.
 */
class ArchiveItem(
    private val items: ItemRepository,
    private val auditLog: AuditLog,
    private val transactions: TransactionRunner,
) {
    /** Outcome of the change. */
    enum class Result {
        /** The item is archived or back. */
        Done,

        /** There is no item with that identifier. */
        NotFound,
    }

    /**
     * @param id the item.
     * @param archived true to archive it, false to bring it back.
     * @param actor the user who asks for it.
     * @return whether the item was found.
     */
    suspend operator fun invoke(id: ItemId, archived: Boolean, actor: UserId): Result = transactions.run {
        if (items.find(id) == null) return@run Result.NotFound
        items.setArchived(id, archived)
        auditLog.record(AuditEvent.ItemArchived(actor, id, archived))
        Result.Done
    }
}

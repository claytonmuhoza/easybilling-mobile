package com.claytonmuhoza.easybilling.domain.audit

import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner
import java.time.temporal.ChronoUnit

/**
 * Audit log whose entries are chained by hash: changing, removing or inserting one entry breaks
 * the chain from that point on.
 *
 * @param store where the entries are kept.
 * @param time source of the timestamps.
 * @param transactions makes reading the last entry and adding the next one a single operation.
 */
class ChainedAuditLog(
    private val store: AuditStore,
    private val time: TimeProvider,
    private val transactions: TransactionRunner,
) : AuditLog {

    override suspend fun record(event: AuditEvent) {
        transactions.run {
            val previous = store.last()
            val unsigned = AuditEntry(
                sequence = (previous?.sequence ?: 0) + 1,
                // Storage keeps milliseconds: hash what will be read back.
                timestamp = time.now().truncatedTo(ChronoUnit.MILLIS),
                actor = event.actor,
                action = event.action,
                details = event.details,
                previousHash = previous?.hash ?: AuditHasher.GENESIS_HASH,
                hash = "",
            )
            store.append(unsigned.copy(hash = AuditHasher.hash(unsigned)))
        }
    }

    override suspend fun verify(): AuditVerification {
        var expectedSequence = 1L
        var expectedPreviousHash = AuditHasher.GENESIS_HASH
        for (entry in store.readAll()) {
            val fitsChain = entry.sequence == expectedSequence &&
                entry.previousHash == expectedPreviousHash &&
                entry.hash == AuditHasher.hash(entry)
            if (!fitsChain) {
                return AuditVerification.Tampered(entry.sequence)
            }
            expectedSequence++
            expectedPreviousHash = entry.hash
        }
        return AuditVerification.Intact
    }
}

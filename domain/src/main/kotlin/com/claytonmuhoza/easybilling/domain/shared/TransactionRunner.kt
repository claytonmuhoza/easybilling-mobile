package com.claytonmuhoza.easybilling.domain.shared

/** Runs several writes as one: either all of them are stored, or none. */
interface TransactionRunner {
    /**
     * Runs [block] inside a storage transaction.
     *
     * @param block the writes to perform together; if it throws, nothing is stored.
     * @return the value returned by [block].
     */
    suspend fun <T> run(block: suspend () -> T): T
}

package com.claytonmuhoza.easybilling.data.local

import androidx.room.withTransaction
import com.claytonmuhoza.easybilling.domain.shared.TransactionRunner

/** [TransactionRunner] backed by a database transaction. Transactions may be nested. */
internal class RoomTransactionRunner(private val database: EasyBillingDatabase) : TransactionRunner {

    override suspend fun <T> run(block: suspend () -> T): T = database.withTransaction { block() }
}

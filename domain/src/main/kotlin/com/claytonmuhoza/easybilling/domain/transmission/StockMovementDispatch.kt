package com.claytonmuhoza.easybilling.domain.transmission

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.stock.MovementStatus
import com.claytonmuhoza.easybilling.domain.stock.StockMovement

/**
 * The stock movements in the send queue (UC-F01 to F07, § 7.8). The OBR gives no identifier to a
 * movement and cannot be asked whether it has one: a movement whose answer never came leaves the
 * queue as "to confirm" and is never sent again by itself.
 *
 * @param storage what the queue reads and writes.
 * @param connection how the OBR is reached.
 * @param time source of the current time.
 */
internal class StockMovementDispatch(
    private val storage: DispatchStorage,
    private val connection: DispatchConnection,
    private val time: TimeProvider,
) {
    /** How sending a movement ended. */
    sealed interface Outcome {
        /** The OBR accepted the movement. */
        data object Declared : Outcome

        /** The operation left the queue without a declaration: refused, uncertain, or nothing to send. */
        data object Dropped : Outcome

        /** Nothing is settled and nothing left: to try again later. */
        data class Later(val reason: String) : Outcome
    }

    /**
     * Sends the movement of [operation].
     *
     * @param entry the entry of the queue.
     * @param operation the movement to declare.
     * @return how it ended; the entry leaves the queue unless it is [Outcome.Later].
     */
    suspend fun send(entry: OutboxEntry, operation: OutboxOperation.SendStockMovement): Outcome {
        val movement = storage.stock.find(operation.movement)
        val target = connection.target()
        return when {
            // Only a pending movement is sent: any other was settled or decided meanwhile.
            movement == null || storage.stock.statusOf(movement.id) != MovementStatus.Pending -> drop(entry)
            target == null -> Outcome.Later(LoginOutcome.NotConfigured.code)
            target.environment != movement.content.environment ->
                Outcome.Later("The movement belongs to ${movement.content.environment}")
            else -> call(target, entry, movement)
        }
    }

    private suspend fun call(target: LoginTarget, entry: OutboxEntry, movement: StockMovement): Outcome {
        val outcome = connection.withToken({ it is MovementOutcome.TokenRefused }) { token ->
            connection.gateway.addStockMovement(target, token, movement)
        }
        return when (outcome) {
            MovementOutcome.Accepted -> Outcome.Declared.also { settle(entry, movement, MovementStatus.Declared) }
            is MovementOutcome.Rejected ->
                Outcome.Dropped.also { settle(entry, movement, MovementStatus.Rejected(outcome.message)) }
            // The OBR may have it: a manager decides, nothing is sent again by itself.
            is MovementOutcome.Uncertain -> Outcome.Dropped.also { settle(entry, movement, MovementStatus.ToConfirm) }
            null -> Outcome.Later("LOGIN")
            MovementOutcome.TokenRefused -> Outcome.Later("TOKEN_REFUSED")
            is MovementOutcome.NotDelivered -> Outcome.Later(outcome.reason)
        }
    }

    /** The movement takes [status] and its operation leaves the queue, in one write. */
    private suspend fun settle(entry: OutboxEntry, movement: StockMovement, status: MovementStatus) {
        storage.transactions.run {
            storage.stock.setStatus(movement.id, status, time.now())
            storage.auditLog.record(AuditEvent.StockMovementSettled(movement.id, status.code))
            storage.outbox.remove(entry.id)
        }
    }

    private suspend fun drop(entry: OutboxEntry): Outcome {
        storage.outbox.remove(entry.id)
        return Outcome.Dropped
    }
}

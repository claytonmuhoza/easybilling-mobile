package com.claytonmuhoza.easybilling.domain.transmission

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.audit.AuditLog
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettings
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import java.time.Duration
import java.time.Instant

/**
 * One call to the EBMS, as kept in the log (UC-D08). Passwords and tokens are masked before the
 * exchange is built: it never holds a secret.
 *
 * @property id identifier in the log; 0 before it is recorded.
 * @property startedAt when the request left.
 * @property environment the environment called.
 * @property service the service called, as named by the specification (`login/`).
 * @property requestBody the body sent, secrets masked.
 * @property statusCode the HTTP code received, or null if no response came.
 * @property responseBody the body received, secrets masked; empty if no response came.
 * @property durationMillis time between the request and the end of the response, or the failure.
 * @property failure why no response came (network, certificate, time out); empty otherwise.
 */
data class ObrExchange(
    val id: Long = 0,
    val startedAt: Instant,
    val environment: EbmsEnvironment,
    val service: String,
    val requestBody: String,
    val statusCode: Int?,
    val responseBody: String,
    val durationMillis: Long,
    val failure: String = "",
)

/** Storage of the exchanges with the EBMS. */
interface ExchangeLog {
    /**
     * @param exchange the exchange to keep.
     * @return its identifier.
     */
    suspend fun record(exchange: ObrExchange): Long

    /**
     * @param limit the largest number of exchanges returned.
     * @return the most recent exchanges, newest first.
     */
    suspend fun recent(limit: Int): List<ObrExchange>

    /**
     * @param id identifier of an exchange.
     * @return the exchange, or null if there is none with that identifier.
     */
    suspend fun find(id: Long): ObrExchange?

    /**
     * @param instant a moment.
     * @return how many exchanges started before [instant] were deleted.
     */
    suspend fun deleteBefore(instant: Instant): Int
}

/** Receives every exchange with the EBMS. */
fun interface ExchangeRecorder {
    /** @param exchange an exchange that just ended. */
    suspend fun record(exchange: ObrExchange)
}

/**
 * The log of the exchanges, kept for the number of days chosen in the [EbmsSettings].
 *
 * @param log where the exchanges are kept.
 * @param settings where the retention is chosen.
 * @param time source of the current time.
 */
class ExchangeJournal(
    private val log: ExchangeLog,
    private val settings: EbmsSettingsRepository,
    private val time: TimeProvider,
) : ExchangeRecorder {

    override suspend fun record(exchange: ObrExchange) {
        log.record(exchange)
        purge()
    }

    /** @return how many exchanges older than the retention were deleted. */
    suspend fun purge(): Int {
        val retention = Duration.ofDays(settings.read().exchangeRetentionDays.toLong())
        return log.deleteBefore(time.now() - retention)
    }
}

/**
 * Chooses how many days the exchanges are kept, and applies it at once.
 *
 * @param settings where the retention is kept.
 * @param journal the log to purge.
 * @param auditLog log that records each change.
 */
class SetExchangeRetention(
    private val settings: EbmsSettingsRepository,
    private val journal: ExchangeJournal,
    private val auditLog: AuditLog,
) {
    /** Outcome of a change. */
    enum class Result {
        /** The retention is saved and applied. */
        Saved,

        /** The number of days is outside the accepted range. */
        OutOfRange,
    }

    /**
     * @param days the new retention, in days.
     * @param actor the user who chooses it.
     * @return whether it was saved.
     */
    suspend operator fun invoke(days: Int, actor: UserId): Result {
        if (days !in EbmsSettings.MIN_RETENTION_DAYS..EbmsSettings.MAX_RETENTION_DAYS) return Result.OutOfRange
        settings.write(settings.read().copy(exchangeRetentionDays = days))
        journal.purge()
        auditLog.record(AuditEvent.ExchangeRetentionChanged(actor, days))
        return Result.Saved
    }
}

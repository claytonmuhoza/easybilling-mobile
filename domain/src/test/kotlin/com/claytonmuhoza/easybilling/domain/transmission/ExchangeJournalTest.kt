package com.claytonmuhoza.easybilling.domain.transmission

import com.claytonmuhoza.easybilling.domain.audit.AuditEvent
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.configuration.EbmsSettings
import com.claytonmuhoza.easybilling.domain.identity.UserId
import com.claytonmuhoza.easybilling.domain.testing.InMemoryEbmsSettingsRepository
import com.claytonmuhoza.easybilling.domain.testing.InMemoryExchangeLog
import com.claytonmuhoza.easybilling.domain.testing.MutableTimeProvider
import com.claytonmuhoza.easybilling.domain.testing.RecordingAuditLog
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** The log of the exchanges with the OBR (UC-D08) keeps them for a chosen number of days. */
class ExchangeJournalTest {

    private val time = MutableTimeProvider(Instant.parse("2026-10-03T08:00:00Z"))
    private val log = InMemoryExchangeLog()
    private val settings = InMemoryEbmsSettingsRepository()
    private val audit = RecordingAuditLog()
    private val journal = ExchangeJournal(log, settings, time)
    private val setRetention = SetExchangeRetention(settings, journal, audit)

    private fun exchange(daysAgo: Long) = ObrExchange(
        startedAt = time.now() - Duration.ofDays(daysAgo),
        environment = EbmsEnvironment.TEST,
        service = "login/",
        requestBody = """{"username":"ws1","password":"••••"}""",
        statusCode = 200,
        responseBody = """{"success":true}""",
        durationMillis = 120,
    )

    @Test
    fun `an exchange is recorded`() = runTest {
        journal.record(exchange(daysAgo = 0))

        assertEquals(1, log.exchanges.size)
    }

    @Test
    fun `recording removes the exchanges older than the retention, 90 days by default`() = runTest {
        log.record(exchange(daysAgo = 91))
        log.record(exchange(daysAgo = 89))

        journal.record(exchange(daysAgo = 0))

        assertEquals(listOf(89L, 0L), log.exchanges.map { Duration.between(it.startedAt, time.now()).toDays() })
    }

    @Test
    fun `the retention can be changed and applies at once`() = runTest {
        log.record(exchange(daysAgo = 10))
        log.record(exchange(daysAgo = 3))

        assertEquals(SetExchangeRetention.Result.Saved, setRetention(7, UserId(1)))

        assertEquals(7, settings.read().exchangeRetentionDays)
        assertEquals(1, log.exchanges.size)
        assertEquals(AuditEvent.ExchangeRetentionChanged(UserId(1), 7), audit.events.single())
        assertEquals("days=7", audit.events.single().details)
    }

    @Test
    fun `the retention stays between one day and ten years`() = runTest {
        assertEquals(SetExchangeRetention.Result.OutOfRange, setRetention(0, UserId(1)))
        assertEquals(SetExchangeRetention.Result.OutOfRange, setRetention(3651, UserId(1)))
        assertEquals(SetExchangeRetention.Result.Saved, setRetention(1, UserId(1)))
        assertEquals(SetExchangeRetention.Result.Saved, setRetention(3650, UserId(1)))
    }

    @Test
    fun `the purge keeps whatever is younger than the retention`() = runTest {
        settings.settings = EbmsSettings(exchangeRetentionDays = 30)
        log.record(exchange(daysAgo = 31))
        log.record(exchange(daysAgo = 29))

        assertEquals(1, journal.purge())
        assertEquals(1, log.exchanges.size)
    }
}

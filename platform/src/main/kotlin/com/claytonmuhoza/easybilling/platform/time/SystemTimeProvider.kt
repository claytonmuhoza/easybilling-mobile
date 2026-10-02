package com.claytonmuhoza.easybilling.platform.time

import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import java.time.Instant

/** [TimeProvider] reading the clock of the device. */
object SystemTimeProvider : TimeProvider {
    override fun now(): Instant = Instant.now()
}

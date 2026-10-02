package com.claytonmuhoza.easybilling.domain.shared

import java.time.Instant

/** Source of the current time, so rules that depend on it can be tested with a chosen clock. */
fun interface TimeProvider {
    /** @return the current instant. */
    fun now(): Instant
}

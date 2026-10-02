package com.claytonmuhoza.easybilling.domain

import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Sentinel test: proves the module builds, runs its tests and stays free of Android. */
class DomainModuleTest {

    @Test
    fun `domain classpath has no Android class`() {
        assertFailsWith<ClassNotFoundException> {
            Class.forName("android.content.Context")
        }
    }
}

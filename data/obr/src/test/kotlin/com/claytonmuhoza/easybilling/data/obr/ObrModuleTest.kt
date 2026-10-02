package com.claytonmuhoza.easybilling.data.obr

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** Sentinel test: proves the module builds, runs its tests and stays free of Android. */
class ObrModuleTest {

    @Test
    fun `obr client classpath has no Android class`() {
        assertFailsWith<ClassNotFoundException> {
            Class.forName("android.content.Context")
        }
    }

    @Test
    fun `obr simulator is available to the tests`() {
        assertNotNull(Class.forName("mockwebserver3.MockWebServer"))
    }
}

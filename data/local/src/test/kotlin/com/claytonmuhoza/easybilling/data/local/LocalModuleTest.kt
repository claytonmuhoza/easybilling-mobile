package com.claytonmuhoza.easybilling.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals

/** Sentinel test: proves the module builds and that Robolectric provides an Android context. */
@RunWith(AndroidJUnit4::class)
class LocalModuleTest {

    @Test
    fun `robolectric provides the module context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals("com.claytonmuhoza.easybilling.data.local.test", context.packageName)
    }
}

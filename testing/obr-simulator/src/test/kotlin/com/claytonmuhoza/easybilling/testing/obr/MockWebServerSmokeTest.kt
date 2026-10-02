package com.claytonmuhoza.easybilling.testing.obr

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals

/** Sentinel test: proves the module builds and that MockWebServer serves a response. */
class MockWebServerSmokeTest {

    @Test
    fun `mock server answers with the enqueued body`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body("""{"success":true}""").build())
            server.start()

            val body = server.url("/login/").toUrl().readText()

            assertEquals("""{"success":true}""", body)
        }
    }
}

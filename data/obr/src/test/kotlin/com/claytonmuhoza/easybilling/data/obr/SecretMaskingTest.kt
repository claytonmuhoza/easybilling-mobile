package com.claytonmuhoza.easybilling.data.obr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** No password nor token reaches the log of the exchanges (UC-D08). */
class SecretMaskingTest {

    @Test
    fun `the password of a request is masked, the user name is kept`() {
        assertEquals(
            """{"username":"ws1","password":"••••"}""",
            SecretMasking.mask("""{"username":"ws1","password":"S3cret"}"""),
        )
    }

    @Test
    fun `a token is masked wherever it is`() {
        assertEquals(
            """{"success":true,"msg":"Opération réussie","result":{"token":"••••"}}""",
            SecretMasking.mask("""{"success":true,"msg":"Opération réussie","result":{"token":"eyJhbGci.abc.def"}}"""),
        )
    }

    @Test
    fun `secrets inside lists are masked too`() {
        assertEquals("""[{"token":"••••"}]""", SecretMasking.mask("""[{"token":"abc"}]"""))
    }

    @Test
    fun `a body without secret is kept as it is`() {
        val body = """{"success":false,"msg":"Nom d'utilisateur ou mot de passe incorrect."}"""

        assertEquals(body, SecretMasking.mask(body))
    }

    @Test
    fun `a broken body is masked as well, even with escaped quotes in the secret`() {
        val masked = SecretMasking.mask("""{"username":"ws1","password":"a\"b c","token":"t0k""")

        assertFalse("a\\\"b" in masked, masked)
        assertFalse("t0k" in masked, masked)
        assertEquals("""{"username":"ws1","password":"••••","token":"••••""", masked)
    }

    @Test
    fun `a body that is not JSON at all is kept`() {
        assertEquals("<html>Maintenance</html>", SecretMasking.mask("<html>Maintenance</html>"))
    }
}

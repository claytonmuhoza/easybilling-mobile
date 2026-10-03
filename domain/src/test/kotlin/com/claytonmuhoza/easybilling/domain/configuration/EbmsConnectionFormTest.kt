package com.claytonmuhoza.easybilling.domain.configuration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** Rules of the EBMS connection settings (UC-A03, UC-A04). */
class EbmsConnectionFormTest {

    private val valid = EbmsConnectionForm(
        serverUrl = "https://ebms.obr.gov.bi:9443/ebms_api/",
        username = "ws400000000100001",
        password = "S3cret pass",
    )

    private fun problemsOf(form: EbmsConnectionForm, storedPassword: String? = null) =
        assertIs<EbmsConnectionCheck.Invalid>(form.check(storedPassword))

    @Test
    fun `the limits are those of the OBR specification`() {
        assertEquals(
            mapOf(
                EbmsCredentialField.USERNAME to 30,
                EbmsCredentialField.PASSWORD to 30,
            ),
            EbmsCredentialField.entries.associateWith { it.maxLength },
        )
    }

    @Test
    fun `the default test address is the one of the specification`() {
        assertEquals("https://ebms.obr.gov.bi:9443/ebms_api/", EbmsServerAddress.DEFAULT_TEST)
    }

    @Test
    fun `a complete form gives the address and the credentials`() {
        val checked = assertIs<EbmsConnectionCheck.Valid>(valid.check(storedPassword = null))

        assertEquals("https://ebms.obr.gov.bi:9443/ebms_api/", checked.serverUrl)
        assertEquals(EbmsCredentials("ws400000000100001", "S3cret pass"), checked.credentials)
    }

    @Test
    fun `the identifier and the address are trimmed, never the password`() {
        val form = valid.copy(
            serverUrl = "  https://ebms.obr.gov.bi:9443/ebms_api/ ",
            username = "  user ",
            password = " pass ",
        )

        val checked = assertIs<EbmsConnectionCheck.Valid>(form.check(storedPassword = null))

        assertEquals("https://ebms.obr.gov.bi:9443/ebms_api/", checked.serverUrl)
        assertEquals(EbmsCredentials("user", " pass "), checked.credentials)
    }

    @Test
    fun `an address without its final slash gets one`() {
        val checked = assertIs<EbmsConnectionCheck.Valid>(
            valid.copy(serverUrl = "https://ebms.obr.gov.bi:9443/ebms_api").check(storedPassword = null),
        )

        assertEquals("https://ebms.obr.gov.bi:9443/ebms_api/", checked.serverUrl)
    }

    @Test
    fun `each field accepts its exact limit and refuses one character more`() {
        for (field in EbmsCredentialField.entries) {
            assertIs<EbmsConnectionCheck.Valid>(valid.with(field, "x".repeat(field.maxLength)).check(null), "$field")
            assertEquals(
                mapOf(field to FieldProblem.TooLong(field.maxLength)),
                problemsOf(valid.with(field, "x".repeat(field.maxLength + 1))).problems,
                "$field",
            )
        }
    }

    @Test
    fun `each field is required`() {
        for (field in EbmsCredentialField.entries) {
            assertEquals(mapOf(field to FieldProblem.Missing), problemsOf(valid.with(field, "")).problems, "$field")
        }
    }

    @Test
    fun `an empty password keeps the stored one`() {
        val checked = assertIs<EbmsConnectionCheck.Valid>(valid.copy(password = "").check(storedPassword = "old"))

        assertEquals("old", checked.credentials.password)
    }

    @Test
    fun `a typed password replaces the stored one`() {
        val checked = assertIs<EbmsConnectionCheck.Valid>(valid.check(storedPassword = "old"))

        assertEquals("S3cret pass", checked.credentials.password)
    }

    @Test
    fun `the address is required, secure and well formed`() {
        assertEquals(UrlProblem.MISSING, problemsOf(valid.copy(serverUrl = " ")).urlProblem)
        assertEquals(UrlProblem.NOT_HTTPS, problemsOf(valid.copy(serverUrl = "http://ebms.obr.gov.bi/")).urlProblem)
        assertEquals(UrlProblem.NOT_HTTPS, problemsOf(valid.copy(serverUrl = "ebms.obr.gov.bi/ebms_api/")).urlProblem)
        assertEquals(UrlProblem.MALFORMED, problemsOf(valid.copy(serverUrl = "https://")).urlProblem)
        assertEquals(UrlProblem.MALFORMED, problemsOf(valid.copy(serverUrl = "https://ebms obr/")).urlProblem)
    }

    @Test
    fun `every problem is reported at once`() {
        val invalid = problemsOf(valid.copy(serverUrl = "", username = "", password = "x".repeat(31)))

        assertEquals(UrlProblem.MISSING, invalid.urlProblem)
        assertEquals(
            mapOf(
                EbmsCredentialField.USERNAME to FieldProblem.Missing,
                EbmsCredentialField.PASSWORD to FieldProblem.TooLong(30),
            ),
            invalid.problems,
        )
    }

    @Test
    fun `the identifier of the system is the user name`() {
        assertEquals("ws400000000100001", EbmsCredentials("ws400000000100001", "pass").systemId)
    }

    @Test
    fun `the password never appears in the text of the credentials`() {
        val text = EbmsCredentials("user", "S3cret pass").toString()

        assertFalse("S3cret" in text, text)
    }
}

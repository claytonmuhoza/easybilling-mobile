package com.claytonmuhoza.easybilling.data.local.configuration

import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentials
import com.claytonmuhoza.easybilling.domain.configuration.EbmsCredentialsVault
import com.claytonmuhoza.easybilling.domain.configuration.EbmsEnvironment
import com.claytonmuhoza.easybilling.domain.shared.SecretStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * [EbmsCredentialsVault] backed by the [SecretStore]: the credentials are encrypted by a key of
 * the Android Keystore, outside the database.
 *
 * @param secretStore where the encrypted credentials are kept.
 * @param dispatcher where the blocking encryption runs.
 */
class SecretStoreCredentialsVault(
    private val secretStore: SecretStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EbmsCredentialsVault {

    override suspend fun read(environment: EbmsEnvironment): EbmsCredentials? = withContext(dispatcher) {
        secretStore.read(nameOf(environment))?.let(::decode)
    }

    override suspend fun write(environment: EbmsEnvironment, credentials: EbmsCredentials) = withContext(dispatcher) {
        secretStore.write(nameOf(environment), encode(credentials))
    }

    private fun nameOf(environment: EbmsEnvironment) = "ebms_credentials_${environment.name.lowercase()}"

    private fun encode(credentials: EbmsCredentials): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(FORMAT_VERSION)
            out.writeUTF(credentials.username)
            out.writeUTF(credentials.password)
        }
        return bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray): EbmsCredentials = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        check(input.readByte().toInt() == FORMAT_VERSION) { "Unknown format of the stored EBMS credentials" }
        EbmsCredentials(username = input.readUTF(), password = input.readUTF())
    }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}

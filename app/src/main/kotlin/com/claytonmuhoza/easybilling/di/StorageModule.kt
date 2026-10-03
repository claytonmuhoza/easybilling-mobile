package com.claytonmuhoza.easybilling.di

import android.content.Context
import com.claytonmuhoza.easybilling.data.local.DatabasePassphrase
import com.claytonmuhoza.easybilling.data.local.LocalStorage
import com.claytonmuhoza.easybilling.domain.shared.SecretStore
import com.claytonmuhoza.easybilling.platform.security.KeystoreSecretStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Opens the encrypted database and the secret store, both protected by the Android Keystore. */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    /** @return the store of the small secrets: the key of the database, the EBMS credentials. */
    @Provides
    @Singleton
    fun secretStore(@ApplicationContext context: Context): SecretStore = KeystoreSecretStore(context)

    /** @return the encrypted local storage. */
    @Provides
    @Singleton
    fun localStorage(@ApplicationContext context: Context, secretStore: SecretStore): LocalStorage {
        val passphrase = DatabasePassphrase(secretStore)
        // Nothing is decrypted here: the key is read when the database is first used.
        return LocalStorage.encrypted(context, passphrase = passphrase::get)
    }
}

package com.claytonmuhoza.easybilling.di

import android.content.Context
import com.claytonmuhoza.easybilling.data.local.DatabasePassphrase
import com.claytonmuhoza.easybilling.data.local.LocalStorage
import com.claytonmuhoza.easybilling.platform.security.KeystoreSecretStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Opens the encrypted database, with its key protected by the Android Keystore. */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    /** @return the encrypted local storage. */
    @Provides
    @Singleton
    fun localStorage(@ApplicationContext context: Context): LocalStorage {
        val passphrase = DatabasePassphrase(KeystoreSecretStore(context))
        // Nothing is decrypted here: the key is read when the database is first used.
        return LocalStorage.encrypted(context, passphrase = passphrase::get)
    }
}

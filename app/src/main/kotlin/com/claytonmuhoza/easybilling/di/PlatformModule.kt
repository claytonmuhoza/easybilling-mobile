package com.claytonmuhoza.easybilling.di

import com.claytonmuhoza.easybilling.domain.identity.RecoveryCodeGenerator
import com.claytonmuhoza.easybilling.domain.identity.SecretHasher
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.platform.security.Pbkdf2SecretHasher
import com.claytonmuhoza.easybilling.platform.security.SecureRecoveryCodeGenerator
import com.claytonmuhoza.easybilling.platform.time.SystemTimeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the domain ports that are served by the device: clock, randomness, cryptography. */
@Module
@InstallIn(SingletonComponent::class)
object PlatformModule {

    /** @return the clock of the device. */
    @Provides
    fun timeProvider(): TimeProvider = SystemTimeProvider

    /** @return the PBKDF2 derivation of PINs and recovery codes. */
    @Provides
    @Singleton
    fun secretHasher(): SecretHasher = Pbkdf2SecretHasher()

    /** @return the generator of random recovery codes. */
    @Provides
    @Singleton
    fun recoveryCodes(): RecoveryCodeGenerator = SecureRecoveryCodeGenerator()
}

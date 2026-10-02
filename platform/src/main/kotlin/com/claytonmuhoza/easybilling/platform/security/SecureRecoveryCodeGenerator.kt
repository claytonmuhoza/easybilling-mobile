package com.claytonmuhoza.easybilling.platform.security

import com.claytonmuhoza.easybilling.domain.identity.RecoveryCode
import com.claytonmuhoza.easybilling.domain.identity.RecoveryCodeGenerator
import java.security.SecureRandom

/**
 * [RecoveryCodeGenerator] drawing from the secure random source of the platform.
 *
 * A code has 12 characters out of 32, about 60 bits: out of reach of guessing, even without the
 * account lock.
 */
class SecureRecoveryCodeGenerator : RecoveryCodeGenerator {

    private val random = SecureRandom()

    override fun generate(): RecoveryCode =
        RecoveryCode(String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }))

    private companion object {
        /** No I, O, 0 or 1: they are confused when a code is copied by hand. */
        const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        const val LENGTH = 12
    }
}

package com.claytonmuhoza.easybilling.ui

import android.database.SQLException
import com.claytonmuhoza.easybilling.domain.identity.PermissionDeniedException
import java.security.GeneralSecurityException

/**
 * Runs a read or a write of the local storage and turns its expected failures into a result:
 * a database error, a key of the encrypted database that the device refuses to give, or an
 * action the roles of the user no longer allow (a role taken away while the screen was shown).
 *
 * @param block the storage call.
 * @return the value of [block], or the failure.
 */
suspend fun <T> storageCall(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (failure: SQLException) {
        Result.failure(failure)
    } catch (failure: GeneralSecurityException) {
        Result.failure(failure)
    } catch (failure: PermissionDeniedException) {
        Result.failure(failure)
    }

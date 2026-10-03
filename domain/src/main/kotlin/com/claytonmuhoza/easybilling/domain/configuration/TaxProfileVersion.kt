package com.claytonmuhoza.easybilling.domain.configuration

import com.claytonmuhoza.easybilling.domain.identity.UserId
import java.time.Instant

/**
 * One saved state of the tax profile. A version is never modified: a change creates the next one,
 * and each invoice will keep the version it was issued with.
 *
 * @property number position in the history, starting at 1, without gap.
 * @property profile the profile.
 * @property savedAt when the version was saved, to the millisecond.
 * @property savedBy the user who saved it.
 */
data class TaxProfileVersion(
    val number: Int,
    val profile: TaxProfile,
    val savedAt: Instant,
    val savedBy: UserId,
)

/** Storage of the tax profile versions. It only ever adds versions. */
interface TaxProfileRepository {
    /** @return the latest version, or null if no profile was ever saved. */
    suspend fun current(): TaxProfileVersion?

    /** @return every version, newest first. */
    suspend fun history(): List<TaxProfileVersion>

    /**
     * @param number the number of a version.
     * @return that version, or null if there is none with that number.
     */
    suspend fun find(number: Int): TaxProfileVersion?

    /**
     * Adds a version at the end of the history.
     *
     * @param version the version to add; its number must not exist yet.
     */
    suspend fun append(version: TaxProfileVersion)
}

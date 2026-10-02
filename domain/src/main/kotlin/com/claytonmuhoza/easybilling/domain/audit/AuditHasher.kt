package com.claytonmuhoza.easybilling.domain.audit

import java.security.MessageDigest

/** Computes the hash that chains the audit entries. */
object AuditHasher {
    /** Value of [AuditEntry.previousHash] for the first entry of the log. */
    val GENESIS_HASH: String = "0".repeat(64)

    /**
     * Hashes every property of an entry except [AuditEntry.hash] itself.
     *
     * Each field is written with its length in front, so content cannot be moved from one field
     * to the next without changing the hash.
     *
     * @param entry the entry to hash.
     * @return the SHA-256 hash, as 64 lower case hexadecimal characters.
     */
    fun hash(entry: AuditEntry): String {
        val fields = listOf(
            entry.previousHash,
            entry.sequence.toString(),
            entry.timestamp.toEpochMilli().toString(),
            entry.actor?.value?.toString().orEmpty(),
            entry.action,
            entry.details,
        )
        val canonical = fields.joinToString(separator = "") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
    }
}

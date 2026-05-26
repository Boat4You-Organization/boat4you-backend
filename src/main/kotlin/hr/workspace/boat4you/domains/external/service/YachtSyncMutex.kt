package hr.workspace.boat4you.domains.external.service

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * Cross-VM per-yacht serialization for the user-triggered yacht offer
 * sync paths (`ExternalSyncService.syncYachtOffers(yachtId, ...)`).
 *
 * F3-037 fix. Before: a `MutableSet<Long>` inside the singleton service
 * deduplicated within ONE JVM but did nothing about a second VM doing
 * the same lookup — both VMs could call the partner for the same yacht
 * concurrently, doubling rate-limit pressure and racing each other on
 * the cache marker write.
 *
 * After: Postgres session-scoped advisory locks. `pg_try_advisory_lock`
 * is non-blocking; if a different VM/JVM already holds the lock, this
 * returns `false` immediately and the caller skips (same observable
 * semantics as the old `Set.add` early-return).
 *
 * The lock key namespaces yacht-sync into the high bits of a 64-bit
 * advisory-lock key so future advisory-lock domains can use other
 * prefixes without collision.
 *
 * Uses `JdbcTemplate.execute(ConnectionCallback)` so the lock acquire,
 * the protected work, and the unlock all run on the SAME JDBC
 * connection — session-scoped locks are bound to the session
 * (= connection), not the transaction.
 */
@Service
class YachtSyncMutex(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(YachtSyncMutex::class.java)

    /**
     * Runs [block] under a yacht-scoped Postgres advisory lock. Returns
     * `null` if the lock could not be acquired (another VM/JVM is
     * already syncing this yachtId), the block's value otherwise.
     */
    fun <T> withYachtLock(yachtId: Long, block: () -> T): T? {
        val key = lockKey(yachtId)
        return jdbcTemplate.execute(
            ConnectionCallback { conn ->
                val acquired = conn.prepareStatement("SELECT pg_try_advisory_lock(?)").use { ps ->
                    ps.setLong(1, key)
                    ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
                }
                if (!acquired) {
                    log.debug("Yacht {} sync already in progress (advisory lock not granted)", yachtId)
                    return@ConnectionCallback null
                }
                try {
                    block()
                } finally {
                    conn.prepareStatement("SELECT pg_advisory_unlock(?)").use { ps ->
                        ps.setLong(1, key)
                        ps.execute()
                    }
                }
            },
        )
    }

    /**
     * Pack a yacht-sync namespace into the high bits so the same
     * advisory-lock space can be reused for other locking concerns
     * (e.g. agency sync, payout reconciliation) without collision.
     * `1L shl 60` reserves the top nibble for the namespace tag.
     */
    private fun lockKey(yachtId: Long): Long = YACHT_SYNC_NAMESPACE or yachtId

    companion object {
        private const val YACHT_SYNC_NAMESPACE: Long = 1L shl 60
    }
}

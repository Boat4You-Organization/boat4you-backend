package hr.workspace.boat4you.domains.external.service

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

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
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(YachtSyncMutex::class.java)

    // REQUIRES_NEW so the per-yacht write is its own short transaction even when a
    // caller carries an ambient one (ExternalSyncService's read-only per-yacht warm).
    private val perYachtWriteTx =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

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
     * Runs [block] in its own REQUIRES_NEW transaction holding a yacht-scoped
     * `pg_try_advisory_xact_lock`. Returns `false` without invoking [block] when
     * another transaction (any VM, any sync path) is currently writing this
     * yacht's offers — the caller skips the yacht and the next sync run heals it.
     *
     * This is the DB-WRITE-phase counterpart of [withYachtLock] (which guards the
     * partner HTTP call on the per-yacht warm path only). The 8.7.2026 Hikari
     * exhaustion came from the paths withYachtLock does NOT cover: the scheduled
     * agency sync and the location-warm sync write the same offer rows in huge
     * multi-minute transactions, and their orphan-removal `delete from
     * offer_extras where id=?` statements convoyed behind each other's row locks
     * (348 s+ waits, pool 35/35). Distinct namespace: withYachtLock holds its
     * SESSION lock on a separate connection for the whole partner call, so the
     * write phase re-locking the same key would deadlock/skip against itself.
     *
     * The xact lock is acquired as the FIRST statement of the transaction (before
     * any row lock) and released automatically on commit/rollback.
     */
    fun runExclusiveYachtWrite(yachtId: Long, block: () -> Unit): Boolean =
        perYachtWriteTx.execute {
            val acquired =
                jdbcTemplate.queryForObject(
                    "SELECT pg_try_advisory_xact_lock(?)",
                    Boolean::class.javaObjectType,
                    OFFER_WRITE_NAMESPACE or yachtId,
                ) ?: false
            if (!acquired) {
                log.info("Yacht {} offer write skipped — another sync is writing this yacht (advisory xact lock busy)", yachtId)
                false
            } else {
                block()
                true
            }
        } ?: false

    /**
     * Pack a yacht-sync namespace into the high bits so the same
     * advisory-lock space can be reused for other locking concerns
     * (e.g. agency sync, payout reconciliation) without collision.
     * `1L shl 60` reserves the top nibble for the namespace tag.
     */
    private fun lockKey(yachtId: Long): Long = YACHT_SYNC_NAMESPACE or yachtId

    companion object {
        private const val YACHT_SYNC_NAMESPACE: Long = 1L shl 60
        private const val OFFER_WRITE_NAMESPACE: Long = 2L shl 60
    }
}

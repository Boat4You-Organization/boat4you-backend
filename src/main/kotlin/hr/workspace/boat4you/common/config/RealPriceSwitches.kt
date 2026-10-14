package hr.workspace.boat4you.common.config

import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.cache.CacheManager
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Runtime switches of the real-price search rule (Mario 26.9.2026, decisions 14.10.2026; DESIGN.md section 1.2).
 *
 * Every behaviour of the real-price work sits behind one `settings` row, so it is flipped with
 * `PUT /admin/settings` (or one SQL update) on both API nodes at once, without a restart or a jar rollback.
 * **A missing row, or a value that is not allowed, means the default, and every default is today's behaviour.**
 *
 * Reads are served from one in-memory snapshot of all switch rows, reloaded at most once per [ttl] (60 s). The read
 * is a plain `SELECT name, value FROM settings` through JDBC: read-only, no JPA entity in the caller's persistence
 * context, and tolerant of rows whose name this jar does not know. A failed load keeps the previous snapshot (or the
 * defaults) until the next TTL. A short poll keeps the snapshot fresh even when no caller reads it (a cache hit never
 * reaches the switch), so a flip is seen within about a minute on every node.
 *
 * When any SEARCH_* value differs between two snapshots, the node clears its own search caches
 * ([SEARCH_CACHES]); [Snapshot.searchKey] is meant to be part of those cache keys as well, so a request that started
 * under the old values can never serve its result under the new ones.
 *
 * Callers that need several switches to agree (one sync batch, one search) read [snapshot] once and use its fields.
 */
@Component
class RealPriceSwitches(
    private val jdbcTemplate: JdbcTemplate,
    private val cacheManager: CacheManager,
    @Value("\${realprice.switches.cache-ttl:PT60S}") private val ttl: Duration,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    /** Monotonic clock in nanoseconds; replaced in tests. */
    internal var ticker: () -> Long = System::nanoTime

    @Volatile private var current: Snapshot? = null

    @Volatile private var loadedAtNanos: Long = 0L

    private val lastWarnNanos = ConcurrentHashMap<String, Long>()

    private val refreshLock = ReentrantLock()

    /** `SEARCH_PREDICATE`: WS-A row predicate (D1 variant B, tiling, requested-period-taken, hull hard block). */
    enum class SearchPredicate { LEGACY, D1 }

    /** Plain two-state switch; `OFF` is always today's behaviour. */
    enum class OnOff {
        OFF,
        ON,
        ;

        val isOn: Boolean get() = this == ON
    }

    /** `PARTNER_PRICE_GUARD`: WS-C2 partner price check between createOption and createReservation. */
    enum class PartnerPriceGuardMode { OFF, ALERT, ENFORCE }

    /** `MMK_OPTION_VERIFY`: WS-B1 steps 2c/2b and the quoted-drift upserts. */
    enum class MmkOptionVerifyMode { OFF, LOG, ON }

    /** `NAUSYS_REVERIFY`: WS-B2 NauSys reverifier including step 0. */
    enum class NauSysReverifyMode { OFF, LOG, WRITE }

    /** `NAUSYS_D3_MODE`: D3 in the nightly / near-term NauSys `syncOffers` disappearance pass. */
    enum class NauSysD3Mode { LEGACY, LOG, WRITE }

    /** All switch values at one moment. The constructor defaults are the defaults of the switches (= today). */
    data class Snapshot(
        val searchPredicate: SearchPredicate = SearchPredicate.LEGACY,
        val searchPriceFloor: OnOff = OnOff.OFF,
        val demoteStaleOptions: Boolean = true,
        val bookingC1: OnOff = OnOff.OFF,
        val partnerPriceGuard: PartnerPriceGuardMode = PartnerPriceGuardMode.OFF,
        val extrasZeroOptionalOnSite: OnOff = OnOff.OFF,
        val mmkOptionVerify: MmkOptionVerifyMode = MmkOptionVerifyMode.OFF,
        val mmkOnDemandStrike: OnOff = OnOff.OFF,
        val nausysReverify: NauSysReverifyMode = NauSysReverifyMode.OFF,
        val nausysD3Mode: NauSysD3Mode = NauSysD3Mode.LEGACY,
        val nausysWarmLongRanges: OnOff = OnOff.OFF,
    ) {
        /** How many switches differ from their default (today's behaviour). */
        val nonDefaultCount: Int
            get() = values().zip(DEFAULT.values()).count { (v, d) -> v != d }

        private fun values(): List<Any> =
            listOf(
                searchPredicate,
                searchPriceFloor,
                demoteStaleOptions,
                bookingC1,
                partnerPriceGuard,
                extrasZeroOptionalOnSite,
                mmkOptionVerify,
                mmkOnDemandStrike,
                nausysReverify,
                nausysD3Mode,
                nausysWarmLongRanges,
            )

        private companion object {
            val DEFAULT = Snapshot()
        }

        /** The SEARCH_* values as one string, to be included in every search cache key. */
        val searchKey: String
            get() = "rp:$searchPredicate:$searchPriceFloor:$demoteStaleOptions"
    }

    /** The current snapshot (reloaded when older than the TTL). */
    fun snapshot(): Snapshot {
        val snap = current
        val fresh = snap != null && ticker() - loadedAtNanos < ttl.toNanos()
        // One thread reloads; while it does, the others keep the previous snapshot instead of waiting on the database.
        val locked = !fresh && (if (snap == null) refreshLock.lock().let { true } else refreshLock.tryLock())
        if (!locked) return snap!!
        try {
            return refresh()
        } finally {
            refreshLock.unlock()
        }
    }

    val searchPredicate: SearchPredicate get() = snapshot().searchPredicate
    val searchPriceFloor: OnOff get() = snapshot().searchPriceFloor
    val demoteStaleOptions: Boolean get() = snapshot().demoteStaleOptions
    val bookingC1: OnOff get() = snapshot().bookingC1
    val partnerPriceGuard: PartnerPriceGuardMode get() = snapshot().partnerPriceGuard
    val extrasZeroOptionalOnSite: OnOff get() = snapshot().extrasZeroOptionalOnSite
    val mmkOptionVerify: MmkOptionVerifyMode get() = snapshot().mmkOptionVerify
    val mmkOnDemandStrike: OnOff get() = snapshot().mmkOnDemandStrike
    val nausysReverify: NauSysReverifyMode get() = snapshot().nausysReverify
    val nausysD3Mode: NauSysD3Mode get() = snapshot().nausysD3Mode
    val nausysWarmLongRanges: OnOff get() = snapshot().nausysWarmLongRanges

    /** The SEARCH_* part of the current snapshot, for search cache keys (see [Snapshot.searchKey]). */
    fun searchCacheKey(): String = snapshot().searchKey

    /** Keeps the snapshot fresh on every node, so search cache hits (which never read a switch) see a flip too. */
    @Scheduled(
        fixedDelayString = "\${realprice.switches.poll-ms:10000}",
        initialDelayString = "\${realprice.switches.poll-ms:10000}",
    )
    fun poll() {
        snapshot()
    }

    /** Called with [refreshLock] held. */
    private fun refresh(): Snapshot {
        val previous = current
        val now = ticker()
        if (previous != null && now - loadedAtNanos < ttl.toNanos()) return previous
        val next =
            try {
                build(loadRows(), now)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // Never let a settings read break a search or a sync: keep what we had (or today's behaviour).
                if (shouldWarn(null, now)) log.warn("Real-price switches: settings read failed, keeping the previous values", e)
                previous ?: Snapshot()
            }
        current = next
        loadedAtNanos = now
        // Once at startup and on every change, INFO. Neutral wording on purpose: the cusma3 restart gate greps the
        // journal for partner names, so this line must not repeat the partner-named switch keys.
        if (previous != next) {
            log.info("Real-price switches effective: {} of {} away from the default, search {}", next.nonDefaultCount, SPECS.size, next.searchKey)
        }
        if (previous != null && previous.searchKey != next.searchKey) clearSearchCaches(previous, next)
        return next
    }

    private fun loadRows(): Map<SettingsKeyEnum, String?> {
        val rows = jdbcTemplate.queryForList("SELECT name, value FROM settings ORDER BY id")
        val byName = SPECS.associateBy { it.key.name }
        val values = HashMap<SettingsKeyEnum, String?>()
        // ORDER BY id: if a key ever has two rows, the newest wins.
        for (row in rows) byName[row["name"]?.toString()]?.let { values[it.key] = row["value"]?.toString() }
        return values
    }

    private fun build(
        raw: Map<SettingsKeyEnum, String?>,
        now: Long,
    ): Snapshot {
        fun <T : Any> v(spec: Spec<T>): T = resolve(spec, raw, now)
        return Snapshot(
            searchPredicate = v(SEARCH_PREDICATE_SPEC),
            searchPriceFloor = v(SEARCH_PRICE_FLOOR_SPEC),
            demoteStaleOptions = v(SEARCH_DEMOTE_STALE_OPTIONS_SPEC),
            bookingC1 = v(BOOKING_C1_SPEC),
            partnerPriceGuard = v(PARTNER_PRICE_GUARD_SPEC),
            extrasZeroOptionalOnSite = v(EXTRAS_ZERO_OPTIONAL_ON_SITE_SPEC),
            mmkOptionVerify = v(MMK_OPTION_VERIFY_SPEC),
            mmkOnDemandStrike = v(MMK_ONDEMAND_STRIKE_SPEC),
            nausysReverify = v(NAUSYS_REVERIFY_SPEC),
            nausysD3Mode = v(NAUSYS_D3_MODE_SPEC),
            nausysWarmLongRanges = v(NAUSYS_WARM_LONG_RANGES_SPEC),
        )
    }

    private fun <T : Any> resolve(
        spec: Spec<T>,
        raw: Map<SettingsKeyEnum, String?>,
        now: Long,
    ): T {
        val value = raw[spec.key]
        val parsed = spec.parse(value)
        // A missing row is the documented default (today's behaviour), not a fault: no WARN for it.
        if (parsed == null && value != null && shouldWarn(spec.key, now)) {
            log.warn(
                "Real-price switch {} has the invalid value '{}' (allowed {}), using the default {}",
                spec.key,
                value,
                spec.allowed,
                spec.defaultText,
            )
        }
        return parsed ?: spec.default
    }

    /** At most one WARN per key per hour (key null = the load failure itself); only invalid values and load failures warn. */
    private fun shouldWarn(
        key: SettingsKeyEnum?,
        now: Long,
    ): Boolean {
        val k = key?.name ?: LOAD_FAILURE
        val last = lastWarnNanos[k]
        if (last != null && now - last < WARN_INTERVAL.toNanos()) return false
        lastWarnNanos[k] = now
        return true
    }

    private fun clearSearchCaches(
        previous: Snapshot,
        next: Snapshot,
    ) {
        SEARCH_CACHES.forEach { name -> cacheManager.getCache(name)?.clear() }
        log.info(
            "Real-price search switches changed ({} -> {}): cleared {}",
            previous.searchKey,
            next.searchKey,
            SEARCH_CACHES,
        )
    }

    /** One switch: its key, the allowed values (default first, as shown to admins) and the parser. */
    class Spec<T : Any>(
        val key: SettingsKeyEnum,
        private val values: List<Pair<String, T>>,
    ) {
        val default: T = values.first().second
        val defaultText: String = values.first().first
        val allowed: List<String> = values.map { it.first }

        fun parse(value: String?): T? {
            val v = value?.trim() ?: return null
            return values.firstOrNull { it.first.equals(v, ignoreCase = true) }?.second
        }
    }

    companion object {
        /** The caches whose entries depend on SEARCH_* values (CacheConfig). */
        val SEARCH_CACHES: List<String> = listOf("yachtSearchListCache", "relaxSuggestCache", "facetDistributionCache")

        private val WARN_INTERVAL: Duration = Duration.ofHours(1)

        /** Rate-limit slot for "the settings read failed". */
        private const val LOAD_FAILURE = "load"

        private inline fun <reified E : Enum<E>> enumSpec(
            key: SettingsKeyEnum,
            default: E,
        ): Spec<E> = Spec(key, (listOf(default) + enumValues<E>().filter { it != default }).map { it.name to it })

        val SEARCH_PREDICATE_SPEC = enumSpec(SettingsKeyEnum.SEARCH_PREDICATE, SearchPredicate.LEGACY)
        val SEARCH_PRICE_FLOOR_SPEC = enumSpec(SettingsKeyEnum.SEARCH_PRICE_FLOOR, OnOff.OFF)
        val SEARCH_DEMOTE_STALE_OPTIONS_SPEC =
            Spec(SettingsKeyEnum.SEARCH_DEMOTE_STALE_OPTIONS, listOf("true" to true, "false" to false))
        val BOOKING_C1_SPEC = enumSpec(SettingsKeyEnum.BOOKING_C1, OnOff.OFF)
        val PARTNER_PRICE_GUARD_SPEC = enumSpec(SettingsKeyEnum.PARTNER_PRICE_GUARD, PartnerPriceGuardMode.OFF)
        val EXTRAS_ZERO_OPTIONAL_ON_SITE_SPEC = enumSpec(SettingsKeyEnum.EXTRAS_ZERO_OPTIONAL_ON_SITE, OnOff.OFF)
        val MMK_OPTION_VERIFY_SPEC = enumSpec(SettingsKeyEnum.MMK_OPTION_VERIFY, MmkOptionVerifyMode.OFF)
        val MMK_ONDEMAND_STRIKE_SPEC = enumSpec(SettingsKeyEnum.MMK_ONDEMAND_STRIKE, OnOff.OFF)
        val NAUSYS_REVERIFY_SPEC = enumSpec(SettingsKeyEnum.NAUSYS_REVERIFY, NauSysReverifyMode.OFF)
        val NAUSYS_D3_MODE_SPEC = enumSpec(SettingsKeyEnum.NAUSYS_D3_MODE, NauSysD3Mode.LEGACY)
        val NAUSYS_WARM_LONG_RANGES_SPEC = enumSpec(SettingsKeyEnum.NAUSYS_WARM_LONG_RANGES, OnOff.OFF)

        val SPECS: List<Spec<*>> =
            listOf(
                SEARCH_PREDICATE_SPEC,
                SEARCH_PRICE_FLOOR_SPEC,
                SEARCH_DEMOTE_STALE_OPTIONS_SPEC,
                BOOKING_C1_SPEC,
                PARTNER_PRICE_GUARD_SPEC,
                EXTRAS_ZERO_OPTIONAL_ON_SITE_SPEC,
                MMK_OPTION_VERIFY_SPEC,
                MMK_ONDEMAND_STRIKE_SPEC,
                NAUSYS_REVERIFY_SPEC,
                NAUSYS_D3_MODE_SPEC,
                NAUSYS_WARM_LONG_RANGES_SPEC,
            )

        /** Allowed values per switch key, default first. Keys absent here are not real-price switches. */
        val ALLOWED_VALUES: Map<SettingsKeyEnum, List<String>> = SPECS.associate { it.key to it.allowed }

        /** True when [value] is an allowed value of the switch [key] (trimmed, case-insensitive). */
        fun isAllowedValue(
            key: SettingsKeyEnum,
            value: String?,
        ): Boolean = SPECS.firstOrNull { it.key == key }?.parse(value) != null
    }
}

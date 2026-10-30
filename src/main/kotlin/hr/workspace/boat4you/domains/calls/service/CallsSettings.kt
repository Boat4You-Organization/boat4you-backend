package hr.workspace.boat4you.domains.calls.service

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeParseException

/**
 * The AI call center switches (design §2.5, §6.4): rows of the `settings` table whose name starts with `calls.` (no
 * SettingsKeyEnum constant, same as the Contact Center's `cc.*`). Read through one [Snapshot] reloaded at most once per
 * [ttl] (10 s); a missing or invalid row means the default, and `calls.enabled` defaults to OFF. [write] validates every value
 * before it stores anything and refreshes this node's snapshot at once (the other node within the TTL).
 */
@Component
class CallsSettings(
    private val jdbc: JdbcTemplate,
    @Value("\${calls.settings.cache-ttl:PT10S}") private val ttl: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    internal var ticker: () -> Long = System::nanoTime

    private class Loaded(
        val snapshot: Snapshot,
        val atNanos: Long,
    )

    @Volatile private var loaded: Loaded? = null
    private val lock = Any()
    private var lastWarnNanos: Long? = null

    data class Snapshot(
        val enabled: Boolean = false,
        val testNumbers: Set<String> = emptySet(),
        val windowStart: LocalTime = LocalTime.of(9, 0),
        val windowEnd: LocalTime = LocalTime.of(19, 0),
        val firstDelay: Duration = Duration.ofMinutes(5),
        val followUpDelay: Duration = Duration.ofHours(48),
        val maxAttempts: Int = 3,
        val transferDays: Set<DayOfWeek> = DayOfWeek.entries.filter { it <= DayOfWeek.FRIDAY }.toSet(),
        val transferStart: LocalTime = LocalTime.of(9, 0),
        val transferEnd: LocalTime = LocalTime.of(16, 0),
        val transferAvailable: Boolean = true,
        val transferNumber: String? = null,
        val dailyLimit: Int = 40,
        val monthlyLimit: Int = 400,
        val audioRetentionDays: Int = 30,
    )

    fun snapshot(): Snapshot {
        loaded?.takeIf { ticker() - it.atNanos < ttl.toNanos() }?.let { return it.snapshot }
        synchronized(lock) {
            loaded?.takeIf { ticker() - it.atNanos < ttl.toNanos() }?.let { return it.snapshot }
            return load()
        }
    }

    /** Every key with the value in effect (stored or default), as the admin settings page shows it. */
    fun effective(): Map<String, String> {
        val s = snapshot()
        return linkedMapOf(
            ENABLED to s.enabled.toString(),
            TEST_NUMBERS to s.testNumbers.joinToString(", "),
            WINDOW_START to s.windowStart.toString(),
            WINDOW_END to s.windowEnd.toString(),
            FIRST_DELAY_MINUTES to s.firstDelay.toMinutes().toString(),
            FOLLOW_UP_DELAY_HOURS to s.followUpDelay.toHours().toString(),
            MAX_ATTEMPTS to s.maxAttempts.toString(),
            TRANSFER_DAYS to s.transferDays.sorted().joinToString(",") { it.name.take(3) },
            TRANSFER_START to s.transferStart.toString(),
            TRANSFER_END to s.transferEnd.toString(),
            TRANSFER_AVAILABLE to s.transferAvailable.toString(),
            TRANSFER_NUMBER to (s.transferNumber ?: ""),
            DAILY_LIMIT to s.dailyLimit.toString(),
            MONTHLY_LIMIT to s.monthlyLimit.toString(),
            AUDIO_RETENTION_DAYS to s.audioRetentionDays.toString(),
        )
    }

    /**
     * Validates all [values] (each key known, each value in range, window start before end), then upserts them and reloads.
     * Throws [ParameterValidationException] naming every bad key and stores nothing when any value is bad.
     */
    fun write(values: Map<String, String>) {
        val errors = LinkedHashMap<String, String>()
        values.forEach { (k, v) -> validate(k, v.trim())?.let { errors[k] = it } }
        if (errors.isEmpty()) {
            val merged = parse(loadRows() + values.mapValues { it.value.trim() })
            if (merged.windowStart >= merged.windowEnd) errors[WINDOW_START] = "window start must be before window end"
            if (merged.transferStart >= merged.transferEnd) errors[TRANSFER_START] = "transfer start must be before transfer end"
        }
        if (errors.isNotEmpty()) throw ParameterValidationException(errors)
        values.forEach { (k, v) ->
            jdbc.update(
                "INSERT INTO settings (name, value, created, entity_status) VALUES (?, ?, now(), 'ACTIVE') " +
                    "ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value, modified = now()",
                k,
                v.trim(),
            )
        }
        synchronized(lock) { load() }
    }

    /** Keeps the snapshot warm so the dispatcher never waits on the settings query. */
    @Scheduled(fixedDelay = 5_000)
    fun refresh() {
        snapshot()
    }

    private fun load(): Snapshot {
        val now = ticker()
        val next =
            try {
                parse(loadRows())
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                if (shouldWarn(now)) log.warn("calls.* settings: read failed, keeping the previous values", e)
                loaded?.snapshot ?: Snapshot()
            }
        loaded = Loaded(next, now)
        return next
    }

    private fun loadRows(): Map<String, String?> {
        val values = HashMap<String, String?>()
        for (row in jdbc.queryForList("SELECT name, value FROM settings WHERE name LIKE 'calls.%' ORDER BY id")) {
            values[row["name"].toString()] = row["value"]?.toString()
        }
        return values
    }

    private fun parse(raw: Map<String, String?>): Snapshot {
        val d = Snapshot()

        fun v(key: String) = raw[key]?.trim()?.takeIf { it.isNotEmpty() }

        fun ok(key: String) = v(key)?.takeIf { validate(key, it) == null }
        return Snapshot(
            enabled = ok(ENABLED)?.toBoolean() ?: d.enabled,
            testNumbers = ok(TEST_NUMBERS)?.let(::numbers) ?: d.testNumbers,
            windowStart = ok(WINDOW_START)?.let(LocalTime::parse) ?: d.windowStart,
            windowEnd = ok(WINDOW_END)?.let(LocalTime::parse) ?: d.windowEnd,
            firstDelay = ok(FIRST_DELAY_MINUTES)?.let { Duration.ofMinutes(it.toLong()) } ?: d.firstDelay,
            followUpDelay = ok(FOLLOW_UP_DELAY_HOURS)?.let { Duration.ofHours(it.toLong()) } ?: d.followUpDelay,
            maxAttempts = ok(MAX_ATTEMPTS)?.toInt() ?: d.maxAttempts,
            transferDays = ok(TRANSFER_DAYS)?.let(::days) ?: d.transferDays,
            transferStart = ok(TRANSFER_START)?.let(LocalTime::parse) ?: d.transferStart,
            transferEnd = ok(TRANSFER_END)?.let(LocalTime::parse) ?: d.transferEnd,
            transferAvailable = ok(TRANSFER_AVAILABLE)?.toBoolean() ?: d.transferAvailable,
            transferNumber = ok(TRANSFER_NUMBER) ?: d.transferNumber,
            dailyLimit = ok(DAILY_LIMIT)?.toInt() ?: d.dailyLimit,
            monthlyLimit = ok(MONTHLY_LIMIT)?.toInt() ?: d.monthlyLimit,
            audioRetentionDays = ok(AUDIO_RETENTION_DAYS)?.toInt() ?: d.audioRetentionDays,
        )
    }

    /** Null when [value] is valid for [key], otherwise the reason. */
    private fun validate(
        key: String,
        value: String,
    ): String? =
        when (key) {
            ENABLED, TRANSFER_AVAILABLE -> if (value == "true" || value == "false") null else "must be true or false"
            TEST_NUMBERS -> if (value.isEmpty() || numbers(value) != null) null else "must be E.164 numbers separated by commas"
            WINDOW_START, WINDOW_END, TRANSFER_START, TRANSFER_END -> if (time(value) != null) null else "must be HH:mm"
            FIRST_DELAY_MINUTES -> range(value, 1, 1440)
            FOLLOW_UP_DELAY_HOURS -> range(value, 1, 336)
            MAX_ATTEMPTS -> range(value, 1, 5)
            TRANSFER_DAYS -> if (days(value) != null) null else "must be days like MON,TUE,WED"
            TRANSFER_NUMBER -> if (value.isEmpty() || PhoneNormalizer.toE164(value) == value) null else "must be an E.164 number"
            DAILY_LIMIT, MONTHLY_LIMIT -> range(value, 1, 10_000)
            AUDIO_RETENTION_DAYS -> range(value, 0, 365)
            else -> "unknown setting"
        }

    private fun range(
        value: String,
        min: Int,
        max: Int,
    ): String? = if (value.toIntOrNull()?.let { it in min..max } == true) null else "must be a whole number from $min to $max"

    private fun time(value: String): LocalTime? =
        try {
            if (Regex("""\d{2}:\d{2}""").matches(value)) LocalTime.parse(value) else null
        } catch (e: DateTimeParseException) {
            null
        }

    private fun numbers(value: String): Set<String>? {
        val parts = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return parts.takeIf { list -> list.all { PhoneNormalizer.toE164(it) == it } }?.toSet()
    }

    private fun days(value: String): Set<DayOfWeek>? {
        val parts = value.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        val parsed = parts.map { p -> DayOfWeek.entries.firstOrNull { it.name.startsWith(p) && p.length >= 3 } }
        return parsed.takeIf { it.isNotEmpty() && it.all { d -> d != null } }?.filterNotNull()?.toSet()
    }

    private fun shouldWarn(now: Long): Boolean {
        val last = lastWarnNanos
        if (last != null && now - last < Duration.ofHours(1).toNanos()) return false
        lastWarnNanos = now
        return true
    }

    companion object {
        const val ENABLED = "calls.enabled"
        const val TEST_NUMBERS = "calls.test.numbers"
        const val WINDOW_START = "calls.window.start"
        const val WINDOW_END = "calls.window.end"
        const val FIRST_DELAY_MINUTES = "calls.first.delayMinutes"
        const val FOLLOW_UP_DELAY_HOURS = "calls.followup.delayHours"
        const val MAX_ATTEMPTS = "calls.attempts.max"
        const val TRANSFER_DAYS = "calls.transfer.days"
        const val TRANSFER_START = "calls.transfer.start"
        const val TRANSFER_END = "calls.transfer.end"
        const val TRANSFER_AVAILABLE = "calls.transfer.available"
        const val TRANSFER_NUMBER = "calls.transfer.number"
        const val DAILY_LIMIT = "calls.limit.daily"
        const val MONTHLY_LIMIT = "calls.limit.monthly"
        const val AUDIO_RETENTION_DAYS = "calls.audio.retentionDays"

        val KEYS =
            setOf(
                ENABLED,
                TEST_NUMBERS,
                WINDOW_START,
                WINDOW_END,
                FIRST_DELAY_MINUTES,
                FOLLOW_UP_DELAY_HOURS,
                MAX_ATTEMPTS,
                TRANSFER_DAYS,
                TRANSFER_START,
                TRANSFER_END,
                TRANSFER_AVAILABLE,
                TRANSFER_NUMBER,
                DAILY_LIMIT,
                MONTHLY_LIMIT,
                AUDIO_RETENTION_DAYS,
            )
    }
}

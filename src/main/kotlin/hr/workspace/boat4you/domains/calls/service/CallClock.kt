package hr.workspace.boat4you.domains.calls.service

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberToTimeZonesMapper
import com.google.i18n.phonenumbers.PhoneNumberUtil
import hr.workspace.boat4you.domains.calls.enums.CallStatus
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Time rules of the call center (design §2.2, §2.4, §6.4), pure functions. The client is called only between the window
 * start and end in their own time zone (from the number; every zone the number may be in), outside it at window start + 30 min
 * (not on the stroke, so nobody is woken); a call must start at least [CALL_FIT] before the window ends. Transfers to Mario
 * follow Croatian time.
 */
object CallClock {
    val ZAGREB: ZoneId = ZoneId.of("Europe/Zagreb")

    /** The longest call (design §2.2: hard stop after 8 min) must fit inside the window. */
    private val CALL_FIT: Duration = Duration.ofMinutes(8)
    private val AFTER_START: Duration = Duration.ofMinutes(30)
    private val util = PhoneNumberUtil.getInstance()
    private val zones = PhoneNumberToTimeZonesMapper.getInstance()

    /** The zone Nika is told for countries whose numbers libphonenumber maps to several zones (listed alphabetically). */
    private val MAIN_ZONE =
        mapOf(
            "ES" to "Europe/Madrid",
            "PT" to "Europe/Lisbon",
            "RU" to "Europe/Moscow",
            "AU" to "Australia/Sydney",
            "US" to "America/New_York",
            "CA" to "America/Toronto",
            "BR" to "America/Sao_Paulo",
            "MX" to "America/Mexico_City",
            "NZ" to "Pacific/Auckland",
            "CL" to "America/Santiago",
        )

    /**
     * Every zone the number may be in (libphonenumber: by country, for the USA, Canada and Australia by prefix), the country's
     * main zone first; Zagreb when the number tells nothing.
     */
    fun clientZones(e164: String): List<ZoneId> =
        try {
            val number = util.parse(e164, "ZZ")
            val all =
                zones
                    .getTimeZonesForNumber(number)
                    .filter { it != PhoneNumberToTimeZonesMapper.getUnknownTimeZone() }
                    .map(ZoneId::of)
            val main = MAIN_ZONE[util.getRegionCodeForNumber(number)]?.let(ZoneId::of)
            when {
                all.isEmpty() -> listOf(ZAGREB)
                main != null && main in all -> listOf(main) + (all - main)
                else -> all
            }
        } catch (e: NumberParseException) {
            listOf(ZAGREB)
        }

    /** The zone Nika is told: the main one of [clientZones]. */
    fun clientZone(e164: String): ZoneId = clientZones(e164).first()

    /**
     * The first moment at or after [earliest] that is inside the window in every one of [zones] (a Spanish mobile may be on the
     * Canaries, so it must suit both); when [earliest] is not, window start + 30 min of the zone that opens last. Null when the
     * zones are too far apart to share a window (a Russian mobile): such a number is never called automatically.
     */
    fun nextSlot(
        earliest: Instant,
        zones: List<ZoneId>,
        s: CallsSettings.Snapshot,
    ): Instant? {
        val openings =
            zones.asSequence().flatMap { zone ->
                val day = earliest.atZone(zone).toLocalDate()
                (0L..2L).asSequence().map { ZonedDateTime.of(day.plusDays(it), s.windowStart.plus(AFTER_START), zone).toInstant() }
            }
        return (sequenceOf(earliest) + openings)
            .filter { !it.isBefore(earliest) && zones.all { zone -> inWindow(it, zone, s) } }
            .minOrNull()
    }

    fun nextSlot(
        earliest: Instant,
        zone: ZoneId,
        s: CallsSettings.Snapshot,
    ): Instant = checkNotNull(nextSlot(earliest, listOf(zone), s))

    /**
     * When to try again after [status] on attempt number [attempt], or null for no more tries. No answer / voicemail: +2 h,
     * then the next morning; busy: +30 min; technical failure: once, +15 min ([previousFailed] = an earlier attempt of the
     * same call already failed technically).
     */
    fun retryAt(
        status: CallStatus,
        attempt: Int,
        now: Instant,
        zones: List<ZoneId>,
        s: CallsSettings.Snapshot,
        previousFailed: Boolean = false,
    ): Instant? {
        if (attempt >= s.maxAttempts) return null
        return when (status) {
            CallStatus.NO_ANSWER, CallStatus.VOICEMAIL ->
                if (attempt == 1) {
                    nextSlot(now.plus(Duration.ofHours(2)), zones, s)
                } else {
                    nextSlot(now.atZone(zones.first()).toLocalDate().plusDays(1).atStartOfDay(zones.first()).toInstant(), zones, s)
                }
            CallStatus.BUSY -> nextSlot(now.plus(Duration.ofMinutes(30)), zones, s)
            CallStatus.FAILED -> if (previousFailed) null else nextSlot(now.plus(Duration.ofMinutes(15)), zones, s)
            else -> null
        }
    }

    fun retryAt(
        status: CallStatus,
        attempt: Int,
        now: Instant,
        zone: ZoneId,
        s: CallsSettings.Snapshot,
        previousFailed: Boolean = false,
    ): Instant? = retryAt(status, attempt, now, listOf(zone), s, previousFailed)

    private fun inWindow(
        t: Instant,
        zone: ZoneId,
        s: CallsSettings.Snapshot,
    ): Boolean {
        val time = t.atZone(zone).toLocalTime()
        return !time.isBefore(s.windowStart) && time.isBefore(s.windowEnd.minus(CALL_FIT))
    }

    fun transferAllowed(
        now: Instant,
        s: CallsSettings.Snapshot,
    ): Boolean {
        val local = now.atZone(ZAGREB)
        val time = local.toLocalTime()
        return s.transferAvailable &&
            s.transferNumber != null &&
            local.dayOfWeek in s.transferDays &&
            !time.isBefore(s.transferStart) &&
            time.isBefore(s.transferEnd)
    }

    fun transferHoursText(s: CallsSettings.Snapshot): String {
        val days = s.transferDays.sorted()
        val contiguous = days.zipWithNext().all { (a, b) -> b.value == a.value + 1 }
        val dayText =
            if (days.size > 1 && contiguous) {
                "${name(days.first())}–${name(days.last())}"
            } else {
                days.joinToString(", ") { name(it) }
            }
        return "$dayText ${s.transferStart}–${s.transferEnd} Croatian time"
    }

    private fun name(d: DayOfWeek) = d.name.lowercase().replaceFirstChar { it.uppercase() }
}

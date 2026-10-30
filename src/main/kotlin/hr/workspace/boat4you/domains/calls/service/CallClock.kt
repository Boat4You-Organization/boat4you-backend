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
 * start and end in their own time zone (from the number), outside it at window start + 30 min (not on the stroke, so nobody
 * is woken); a call must start at least [CALL_FIT] before the window ends. Transfers to Mario follow Croatian time.
 */
object CallClock {
    val ZAGREB: ZoneId = ZoneId.of("Europe/Zagreb")

    /** The longest call (design §2.2: hard stop after 8 min) must fit inside the window. */
    private val CALL_FIT: Duration = Duration.ofMinutes(8)
    private val AFTER_START: Duration = Duration.ofMinutes(30)
    private val util = PhoneNumberUtil.getInstance()
    private val zones = PhoneNumberToTimeZonesMapper.getInstance()

    fun clientZone(e164: String): ZoneId =
        try {
            zones
                .getTimeZonesForNumber(util.parse(e164, "ZZ"))
                .firstOrNull { it != PhoneNumberToTimeZonesMapper.getUnknownTimeZone() }
                ?.let(ZoneId::of) ?: ZAGREB
        } catch (e: NumberParseException) {
            ZAGREB
        }

    fun nextSlot(
        earliest: Instant,
        zone: ZoneId,
        s: CallsSettings.Snapshot,
    ): Instant {
        val local = earliest.atZone(zone)
        val time = local.toLocalTime()
        val lastStart = s.windowEnd.minus(CALL_FIT)
        return when {
            !time.isBefore(s.windowStart) && time.isBefore(lastStart) -> earliest
            time.isBefore(s.windowStart) -> at(local, 0, s.windowStart.plus(AFTER_START))
            else -> at(local, 1, s.windowStart.plus(AFTER_START))
        }
    }

    /**
     * When to try again after [status] on attempt number [attempt], or null for no more tries. No answer / voicemail: +2 h,
     * then the next morning; busy: +30 min; technical failure: once, +15 min ([previousFailed] = an earlier attempt of the
     * same call already failed technically).
     */
    fun retryAt(
        status: CallStatus,
        attempt: Int,
        now: Instant,
        zone: ZoneId,
        s: CallsSettings.Snapshot,
        previousFailed: Boolean = false,
    ): Instant? {
        if (attempt >= s.maxAttempts) return null
        return when (status) {
            CallStatus.NO_ANSWER, CallStatus.VOICEMAIL ->
                if (attempt == 1) {
                    nextSlot(now.plus(Duration.ofHours(2)), zone, s)
                } else {
                    at(now.atZone(zone), 1, s.windowStart.plus(AFTER_START))
                }
            CallStatus.BUSY -> nextSlot(now.plus(Duration.ofMinutes(30)), zone, s)
            CallStatus.FAILED -> if (previousFailed) null else nextSlot(now.plus(Duration.ofMinutes(15)), zone, s)
            else -> null
        }
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

    private fun at(
        local: ZonedDateTime,
        plusDays: Long,
        time: LocalTime,
    ): Instant = ZonedDateTime.of(local.toLocalDate().plusDays(plusDays), time, local.zone).toInstant()
}

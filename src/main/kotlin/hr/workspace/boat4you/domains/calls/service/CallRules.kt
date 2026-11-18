package hr.workspace.boat4you.domains.calls.service

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.TUESDAY
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay

/**
 * Days and hours a country's law allows a sales call, in the client's local time (legal check 18.11.2026,
 * infra/docs/nika-pravna-provjera-2026-11-18.md). They only narrow the `calls.window.*` settings, never widen them.
 * A country without a rule is called inside the settings window on all seven days.
 */
class CallRule(
    private val hours: Map<DayOfWeek, List<ClosedRange<LocalTime>>>,
    private val holiday: (LocalDate) -> Boolean = { false },
) {
    /** The stretches of [day] in which a call may start and end; empty on a day without calls. */
    fun hoursOn(day: LocalDate): List<ClosedRange<LocalTime>> = if (holiday(day)) emptyList() else hours[day.dayOfWeek].orEmpty()
}

object CallRules {
    private val util = PhoneNumberUtil.getInstance()
    private val WEEKDAYS = DayOfWeek.entries.filter { it <= FRIDAY }

    private fun t(
        h: Int,
        m: Int = 0,
    ) = LocalTime.of(h, m)

    private fun days(
        days: List<DayOfWeek>,
        vararg ranges: ClosedRange<LocalTime>,
    ) = days.associateWith { ranges.toList() }

    private val FRANCE = CallRule(days(WEEKDAYS, t(10)..t(13), t(14)..t(20)), ::frenchHoliday)

    private val RULES: Map<String, CallRule> =
        mapOf(
            // France (Code de la consommation L. 223-1 as of 11.8.2026): Monday-Friday 10-13 and 14-20, never on public holidays.
            "FR" to FRANCE,
            // The overseas departments have their own country codes and the same consumer code.
            "GP" to FRANCE,
            "BL" to FRANCE,
            "MF" to FRANCE,
            "MQ" to FRANCE,
            "GF" to FRANCE,
            "RE" to FRANCE,
            "YT" to FRANCE,
            // Australia (Telemarketing and Research Calls Industry Standard 2017): weekdays 9-20, Saturday 9-17, never on
            // Sunday or a national public holiday.
            "AU" to CallRule(days(WEEKDAYS, t(9)..t(20)) + days(listOf(SATURDAY), t(9)..t(17)), ::australianHoliday),
            // Canada (CRTC Unsolicited Telecommunications Rules): weekdays 9-21:30, weekends 10-18.
            "CA" to CallRule(days(WEEKDAYS, t(9)..t(21, 30)) + days(listOf(SATURDAY, SUNDAY), t(10)..t(18))),
            // USA: federal rule 8-21; several states are stricter (shorter evenings, no Sunday calls), so the strictest common
            // frame is used for every US number: Monday-Saturday 9-20, never on Sunday.
            "US" to CallRule(days(WEEKDAYS + SATURDAY, t(9)..t(20))),
        )

    /** The rule of the country [e164] belongs to, or null when it has none (or the number tells nothing). */
    fun forNumber(e164: String): CallRule? =
        try {
            RULES[util.getRegionCodeForNumber(util.parse(e164, "ZZ"))]
        } catch (e: NumberParseException) {
            null
        }

    /** Easter Sunday in the Gregorian calendar (anonymous Gregorian algorithm). */
    internal fun easter(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val h = (19 * a + b - b / 4 - (b - (b + 8) / 25 + 1) / 3 + 15) % 30
        val l = (32 + 2 * (b % 4) + 2 * (c / 4) - h - c % 4) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    private val FRENCH_FIXED =
        setOf(
            MonthDay.of(1, 1),
            MonthDay.of(5, 1),
            MonthDay.of(5, 8),
            MonthDay.of(7, 14),
            MonthDay.of(8, 15),
            MonthDay.of(11, 1),
            MonthDay.of(11, 11),
            MonthDay.of(12, 25),
            MonthDay.of(12, 26),
        )

    /**
     * Easter Monday, Ascension Thursday and Whit Monday move with Easter. Good Friday and 26.12. are holidays in
     * Alsace-Moselle only; a mobile number does not say where its owner lives, so they are closed for every French number.
     */
    private fun frenchHoliday(day: LocalDate): Boolean {
        val easter = easter(day.year)
        return MonthDay.from(day) in FRENCH_FIXED || day in setOf(easter.minusDays(2), easter.plusDays(1), easter.plusDays(39), easter.plusDays(50))
    }

    private val AUSTRALIAN_FIXED =
        setOf(MonthDay.of(1, 1), MonthDay.of(1, 26), MonthDay.of(4, 25), MonthDay.of(12, 25), MonthDay.of(12, 26))

    /**
     * The national public holidays; one that falls on a weekend is also observed on the Monday after it (after Christmas
     * and Boxing Day on a weekend: Monday and Tuesday), so those two days are closed as well.
     */
    private fun australianHoliday(day: LocalDate): Boolean {
        val easter = easter(day.year)
        if (MonthDay.from(day) in AUSTRALIAN_FIXED || day in setOf(easter.minusDays(2), easter.plusDays(1))) return true
        val substitute =
            when (day.dayOfWeek) {
                MONDAY -> listOf(day.minusDays(1), day.minusDays(2))
                TUESDAY -> listOf(day.minusDays(2)).filter { it.monthValue == 12 && it.dayOfMonth in 25..26 }
                else -> emptyList()
            }
        return substitute.any { MonthDay.from(it) in AUSTRALIAN_FIXED }
    }
}

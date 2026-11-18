package hr.workspace.boat4you.domains.calls

import hr.workspace.boat4you.domains.calls.enums.CallStatus
import hr.workspace.boat4you.domains.calls.service.CallClock
import hr.workspace.boat4you.domains.calls.service.CallRules
import hr.workspace.boat4you.domains.calls.service.CallsSettings
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Design §2.2 / §2.4 / §6.4. 3.11.2026 is a Tuesday; Zagreb is UTC+1 in November and UTC+2 in June. */
class CallClockTest {
    private val s = CallsSettings.Snapshot()
    private val zagreb = ZoneId.of("Europe/Zagreb")
    private val newYork = ZoneId.of("America/New_York")

    private fun z(t: String) = Instant.parse(t)

    @Test
    fun `the client time zone comes from the number`() {
        CallClock.clientZone("+385912345678") shouldBe zagreb
        CallClock.clientZone("+12125550100") shouldBe newYork
        CallClock.clientZone("+61412345678").id shouldStartWith "Australia/"
    }

    @Test
    fun `inside the window the first call goes at the earliest moment`() {
        CallClock.nextSlot(z("2026-11-03T13:05:00Z"), zagreb, s) shouldBe z("2026-11-03T13:05:00Z") // 14:05 Zagreb
    }

    @Test
    fun `a late evening inquiry is called the next morning at 09_30`() {
        CallClock.nextSlot(z("2026-11-03T22:15:00Z"), zagreb, s) shouldBe z("2026-11-04T08:30:00Z")
    }

    @Test
    fun `a call that would not fit before 19_00 moves to the next morning`() {
        CallClock.nextSlot(z("2026-11-03T17:55:00Z"), zagreb, s) shouldBe z("2026-11-04T08:30:00Z") // 18:55 Zagreb
    }

    @Test
    fun `a New York client is called at 09_30 New York time, not Croatian time`() {
        // 13:05 Zagreb = 07:05 New York -> 09:30 New York = 14:30 UTC
        CallClock.nextSlot(z("2026-11-03T12:05:00Z"), newYork, s) shouldBe z("2026-11-03T14:30:00Z")
    }

    @Test
    fun `a Spanish mobile may be on the Canaries, so the morning call waits for 09_30 there too`() {
        // November: Madrid UTC+1, Canary UTC+0. 08:15 UTC = 09:15 Madrid but 08:15 Canary -> 09:30 Canary = 10:30 Madrid.
        CallClock.nextSlot(z("2026-11-03T08:15:00Z"), CallClock.clientZones("+34612345678"), s) shouldBe z("2026-11-03T09:30:00Z")
    }

    @Test
    fun `a Spanish mobile is not called after the last start in Madrid even though the Canaries are an hour earlier`() {
        // 17:55 UTC = 18:55 Madrid (past 18:52) -> the next morning, 09:30 Canary = 10:30 Madrid
        CallClock.nextSlot(z("2026-11-03T17:55:00Z"), CallClock.clientZones("+34612345678"), s) shouldBe z("2026-11-04T09:30:00Z")
    }

    @Test
    fun `a Portuguese mobile may be on the Azores, so it waits for 09_30 there`() {
        // November: Lisbon UTC+0, Azores UTC-1. 09:00 UTC = 09:00 Lisbon, 08:00 Azores -> 09:30 Azores = 10:30 UTC
        CallClock.nextSlot(z("2026-11-03T09:00:00Z"), CallClock.clientZones("+351912345678"), s) shouldBe z("2026-11-03T10:30:00Z")
    }

    @Test
    fun `an Australian mobile is called only when it is daytime from Christmas Island to Sydney`() {
        // Australian mobiles may also be on Christmas Island (UTC+7): 22:00 UTC = 09:00 Sydney (AEDT) but 05:00 there
        // -> 09:30 Christmas Island = 02:30 UTC = 13:30 Sydney, 10:30 Perth
        CallClock.nextSlot(z("2026-11-03T22:00:00Z"), CallClock.clientZones("+61412345678"), s) shouldBe z("2026-11-04T02:30:00Z")
    }

    @Test
    fun `a Russian mobile spans too many time zones for one window, so it is never called automatically`() {
        CallClock.nextSlot(z("2026-11-03T09:00:00Z"), CallClock.clientZones("+79161234567"), s).shouldBeNull()
    }

    @Test
    fun `a French number is called on weekdays only, 10-13 and 14-20, never on a public holiday`() {
        val fr = "+33612345678" // November: Paris UTC+1
        // Friday 6.11. 18:55 Paris: too late for the 19:00 settings window -> Monday 10:30
        CallClock.nextSlot(z("2026-11-06T17:55:00Z"), fr, s) shouldBe z("2026-11-09T09:30:00Z")
        // Saturday morning -> Monday 10:30
        CallClock.nextSlot(z("2026-11-07T09:00:00Z"), fr, s) shouldBe z("2026-11-09T09:30:00Z")
        // Tuesday 09:10: France opens at 10 -> 10:30
        CallClock.nextSlot(z("2026-11-03T08:10:00Z"), fr, s) shouldBe z("2026-11-03T09:30:00Z")
        // Tuesday 12:55: the call would run into the 13-14 break -> 14:30
        CallClock.nextSlot(z("2026-11-03T11:55:00Z"), fr, s) shouldBe z("2026-11-03T13:30:00Z")
        // Tuesday 11:00 is fine as it is
        CallClock.nextSlot(z("2026-11-03T10:00:00Z"), fr, s) shouldBe z("2026-11-03T10:00:00Z")
        // Tuesday 10.11. evening -> Wednesday 11.11. is Armistice Day -> Thursday 10:30
        CallClock.nextSlot(z("2026-11-10T19:00:00Z"), fr, s) shouldBe z("2026-11-12T09:30:00Z")
        // Easter Monday 29.3.2027 (Paris UTC+2 from 28.3.) -> Tuesday 10:30
        CallClock.nextSlot(z("2027-03-29T09:00:00Z"), fr, s) shouldBe z("2027-03-30T08:30:00Z")
    }

    @Test
    fun `an Australian number is never called on Sunday or a national holiday, on Saturday only until 17_00`() {
        val perth = "+61892345678" // a Perth landline: one zone, UTC+8
        // Sunday 8.11. 11:00 Perth -> Monday 09:30
        CallClock.nextSlot(z("2026-11-08T03:00:00Z"), perth, s) shouldBe z("2026-11-09T01:30:00Z")
        // Saturday 7.11. 16:55 Perth: would not end by 17:00 -> Monday 09:30
        CallClock.nextSlot(z("2026-11-07T08:55:00Z"), perth, s) shouldBe z("2026-11-09T01:30:00Z")
        // Saturday 7.11. 11:00 Perth is fine
        CallClock.nextSlot(z("2026-11-07T03:00:00Z"), perth, s) shouldBe z("2026-11-07T03:00:00Z")
        // Christmas 2026 is a Friday, Boxing Day a Saturday (observed on Monday 28.12.) -> Tuesday 29.12. 09:30
        CallClock.nextSlot(z("2026-12-25T03:00:00Z"), perth, s) shouldBe z("2026-12-29T01:30:00Z")
    }

    @Test
    fun `a US number is not called on Sunday, a Canadian one on weekends only from 10_00`() {
        val us = "+12125550100" // New York, November UTC-5
        val ca = "+14165550100" // Toronto, same zone
        // Sunday 8.11. 12:00 New York -> Monday 09:30
        CallClock.nextSlot(z("2026-11-08T17:00:00Z"), us, s) shouldBe z("2026-11-09T14:30:00Z")
        // Saturday 7.11. 12:00 New York is fine
        CallClock.nextSlot(z("2026-11-07T17:00:00Z"), us, s) shouldBe z("2026-11-07T17:00:00Z")
        // Sunday 8.11. 09:10 Toronto: weekends open at 10 -> 10:30
        CallClock.nextSlot(z("2026-11-08T14:10:00Z"), ca, s) shouldBe z("2026-11-08T15:30:00Z")
        // Sunday 8.11. 17:55 Toronto: would not end by 18:00 -> Monday 09:30
        CallClock.nextSlot(z("2026-11-08T22:55:00Z"), ca, s) shouldBe z("2026-11-09T14:30:00Z")
    }

    @Test
    fun `a country without a rule keeps the settings window on all seven days, and retries follow the country rule`() {
        // Sunday 8.11. 11:00 Zagreb
        CallClock.nextSlot(z("2026-11-08T10:00:00Z"), "+385912345678", s) shouldBe z("2026-11-08T10:00:00Z")
        // France, no answer on Friday 6.11. at 17:30 Paris: +2 h is past the window -> Monday 10:30, not Saturday
        CallClock.retryAt(CallStatus.NO_ANSWER, 1, z("2026-11-06T16:30:00Z"), "+33612345678", s) shouldBe z("2026-11-09T09:30:00Z")
    }

    @Test
    fun `Easter is where the calendar says`() {
        CallRules.easter(2026) shouldBe LocalDate.of(2026, 4, 5)
        CallRules.easter(2027) shouldBe LocalDate.of(2027, 3, 28)
        CallRules.easter(2028) shouldBe LocalDate.of(2028, 4, 16)
    }

    @Test
    fun `Nika is told the main time zone of a country with several`() {
        CallClock.clientZone("+34612345678") shouldBe ZoneId.of("Europe/Madrid")
        CallClock.clientZone("+351912345678") shouldBe ZoneId.of("Europe/Lisbon")
        CallClock.clientZones("+385912345678") shouldBe listOf(zagreb)
    }

    @Test
    fun `retries keep to the window of every possible zone`() {
        // No answer at 16:00 Madrid on a Spanish mobile: +2 h = 18:00 Madrid / 17:00 Canary -> fits both
        val zones = CallClock.clientZones("+34612345678")
        CallClock.retryAt(CallStatus.NO_ANSWER, 1, z("2026-11-03T15:00:00Z"), zones, s) shouldBe z("2026-11-03T17:00:00Z")
        // the next-day attempt is 09:30 Canary = 10:30 Madrid
        CallClock.retryAt(CallStatus.NO_ANSWER, 2, z("2026-11-03T15:00:00Z"), zones, s) shouldBe z("2026-11-04T09:30:00Z")
    }

    @Test
    fun `no answer retries in 2 hours, then the next morning, then stops`() {
        val now = z("2026-11-03T09:00:00Z") // 10:00 Zagreb
        CallClock.retryAt(CallStatus.NO_ANSWER, 1, now, zagreb, s) shouldBe z("2026-11-03T11:00:00Z")
        CallClock.retryAt(CallStatus.NO_ANSWER, 2, now, zagreb, s) shouldBe z("2026-11-04T08:30:00Z")
        CallClock.retryAt(CallStatus.NO_ANSWER, 3, now, zagreb, s).shouldBeNull()
    }

    @Test
    fun `busy retries in 30 minutes, a technical failure once in 15 minutes`() {
        val now = z("2026-11-03T09:00:00Z")
        CallClock.retryAt(CallStatus.BUSY, 1, now, zagreb, s) shouldBe z("2026-11-03T09:30:00Z")
        CallClock.retryAt(CallStatus.FAILED, 1, now, zagreb, s) shouldBe z("2026-11-03T09:15:00Z")
        CallClock.retryAt(CallStatus.FAILED, 2, now, zagreb, s, previousFailed = true).shouldBeNull()
    }

    @Test
    fun `transfer only on weekdays 09-16 Croatian time, when available and a number is set`() {
        val withNumber = s.copy(transferNumber = "+385981234567")
        CallClock.transferAllowed(z("2026-11-06T14:59:00Z"), withNumber) shouldBe true // Friday 15:59
        CallClock.transferAllowed(z("2026-11-06T15:00:00Z"), withNumber) shouldBe false // Friday 16:00
        CallClock.transferAllowed(z("2026-11-07T09:00:00Z"), withNumber) shouldBe false // Saturday 10:00
        CallClock.transferAllowed(z("2026-11-02T08:30:00Z"), withNumber) shouldBe true // Monday 09:30 (winter time)
        CallClock.transferAllowed(z("2027-06-07T07:30:00Z"), withNumber) shouldBe true // Monday 09:30 (summer time)
        CallClock.transferAllowed(z("2027-06-07T06:30:00Z"), withNumber) shouldBe false // Monday 08:30
        CallClock.transferAllowed(z("2026-11-06T14:59:00Z"), s) shouldBe false // no number
        CallClock.transferAllowed(z("2026-11-06T14:59:00Z"), withNumber.copy(transferAvailable = false)) shouldBe false
    }

    @Test
    fun `transfer hours read as one sentence for Nika`() {
        CallClock.transferHoursText(s) shouldBe "Monday–Friday 09:00–16:00 Croatian time"
    }
}

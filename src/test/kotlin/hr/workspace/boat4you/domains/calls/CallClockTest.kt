package hr.workspace.boat4you.domains.calls

import hr.workspace.boat4you.domains.calls.enums.CallStatus
import hr.workspace.boat4you.domains.calls.service.CallClock
import hr.workspace.boat4you.domains.calls.service.CallsSettings
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.time.Instant
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

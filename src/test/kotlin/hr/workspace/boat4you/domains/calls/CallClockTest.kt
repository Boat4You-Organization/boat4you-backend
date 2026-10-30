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

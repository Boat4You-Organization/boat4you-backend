package hr.workspace.boat4you.domains.calls

import hr.workspace.boat4you.domains.calls.enums.CallKind
import hr.workspace.boat4you.domains.calls.enums.CallOutcome
import hr.workspace.boat4you.domains.calls.enums.CallStatus
import hr.workspace.boat4you.domains.calls.enums.SkipReason
import hr.workspace.boat4you.domains.calls.service.CallEligibility
import hr.workspace.boat4you.domains.calls.service.CallsSettings
import hr.workspace.boat4you.domains.catalouge.enums.InquiryStatus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

/** Every rule checked right before dialling (design §2.1-§2.3, §4): one test per skip reason, plus the limits. */
class CallEligibilityTest : CallsJpaTest() {
    private val eligibility by lazy { CallEligibility(calls, jdbc) }
    private val s = CallsSettings.Snapshot()
    private val now: Instant = Instant.now()

    private fun check(
        inquiry: hr.workspace.boat4you.domains.catalouge.jpa.Inquiry,
        kind: CallKind = CallKind.CONFIRMATION,
        scheduledAt: Instant = now,
        attempt: Int = 1,
        snapshot: CallsSettings.Snapshot = s,
    ) = eligibility.check(call(inquiry, kind = kind, scheduledAt = scheduledAt, attempt = attempt), inquiry, now, snapshot)

    @Test
    fun `a fresh inquiry with consent and a valid phone may be called`() {
        check(inquiry()).shouldBeNull()
    }

    @Test
    fun `skips NO_CONSENT when the checkbox was not ticked`() {
        check(inquiry(consent = false)) shouldBe SkipReason.NO_CONSENT
    }

    @Test
    fun `skips INVALID_PHONE when the number is not international`() {
        check(inquiry(phone = null)) shouldBe SkipReason.INVALID_PHONE
    }

    @Test
    fun `skips DO_NOT_CALL`() {
        check(inquiry { doNotCall = true }) shouldBe SkipReason.DO_NOT_CALL
    }

    @Test
    fun `skips HUMAN_TAKEOVER for AI calls but not for Mario's manual call`() {
        val i = inquiry { humanTakeoverAt = now }
        check(i) shouldBe SkipReason.HUMAN_TAKEOVER
        check(i, kind = CallKind.MANUAL).shouldBeNull()
    }

    @Test
    fun `skips ARCHIVED`() {
        check(inquiry { status = InquiryStatus.ARCHIVED }) shouldBe SkipReason.ARCHIVED
    }

    @Test
    fun `skips NOT_ON_TEST_LIST while the test list is set`() {
        val snap = s.copy(testNumbers = setOf("+385981111111"))
        check(inquiry(), snapshot = snap) shouldBe SkipReason.NOT_ON_TEST_LIST
        check(inquiry(phone = "+385981111111"), snapshot = snap).shouldBeNull()
    }

    @Test
    fun `a confirmation scheduled 25 h ago while calls were disabled is skipped as STALE`() {
        check(inquiry(), scheduledAt = now.minus(Duration.ofHours(25))) shouldBe SkipReason.STALE
        check(inquiry(phone = "+385981111111"), scheduledAt = now.minus(Duration.ofHours(23))).shouldBeNull() // another person
    }

    @Test
    fun `skips MAX_ATTEMPTS beyond the configured attempts`() {
        check(inquiry(), attempt = 4) shouldBe SkipReason.MAX_ATTEMPTS
    }

    @Test
    fun `skips OFFER_ALREADY_SENT for a confirmation after Mario sent the offer`() {
        check(inquiry { offerSentAt = now }) shouldBe SkipReason.OFFER_ALREADY_SENT
    }

    @Test
    fun `the second inquiry of the same phone is skipped after a successful confirmation within 7 days`() {
        val first = inquiry()
        call(first, status = CallStatus.DONE) { endedAt = now.minus(Duration.ofDays(2)) }
        check(inquiry()) shouldBe SkipReason.RECENT_CONFIRMATION
        jdbc.update("UPDATE inquiry_call SET status = 'SKIPPED' WHERE status = 'SCHEDULED'") // as the dialler leaves that probe
        jdbc.update("UPDATE inquiry_call SET ended_at = ? WHERE inquiry_id = ?", java.sql.Timestamp.from(now.minus(Duration.ofDays(8))), first.id)
        check(inquiry()).shouldBeNull()
    }

    @Test
    fun `skips BOOKED for a follow-up when a booking with the same e-mail started after the inquiry`() {
        val i = inquiry(email = "Ana@Example.hr", createdAt = LocalDateTime.now().minusDays(3))
        reservationFlow("ana@example.hr", "+385 91 000 0000")
        check(i, kind = CallKind.FOLLOW_UP) shouldBe SkipReason.BOOKED
    }

    @Test
    fun `skips NOT_INTERESTED for a follow-up after the client said no`() {
        val i = inquiry()
        call(i, status = CallStatus.DONE) { outcome = CallOutcome.NOT_INTERESTED }
        check(i, kind = CallKind.FOLLOW_UP) shouldBe SkipReason.NOT_INTERESTED
    }

    @Test
    fun `limitReached counts calls of the current Zagreb day only`() {
        val i = inquiry()
        call(i, status = CallStatus.DONE) { startedAt = now }
        call(i, status = CallStatus.DONE) { startedAt = now.minus(Duration.ofDays(2)) }
        eligibility.limitReached(now, s.copy(dailyLimit = 1)) shouldBe true
        eligibility.limitReached(now, s.copy(dailyLimit = 2)) shouldBe false
        eligibility.limitReached(now, s.copy(dailyLimit = 40, monthlyLimit = 1)) shouldBe true
    }

    @Test
    fun `do not call said on an earlier inquiry also stops a new inquiry of the same phone`() {
        inquiry { doNotCall = true }
        check(inquiry()) shouldBe SkipReason.DO_NOT_CALL
    }

    @Test
    fun `a second inquiry is not called while a confirmation of an earlier inquiry of the same phone is open`() {
        val first = inquiry()
        val firstCall = call(first)
        val second = inquiry()
        val secondCall = call(second)
        eligibility.check(secondCall, second, now, s) shouldBe SkipReason.SAME_PERSON
        eligibility.check(firstCall, first, now, s).shouldBeNull()
    }

    @Test
    fun `a number whose possible time zones share no window is skipped as TIMEZONE_UNCLEAR`() {
        check(inquiry(phone = "+79161234567")) shouldBe SkipReason.TIMEZONE_UNCLEAR
    }

    @Test
    fun `Mario's manual call that waited more than 24 h is STALE too`() {
        check(inquiry(), kind = CallKind.MANUAL, scheduledAt = now.minus(Duration.ofHours(25))) shouldBe SkipReason.STALE
    }
}

package hr.workspace.boat4you.domains.calls.service

import hr.workspace.boat4you.domains.calls.enums.CallKind
import hr.workspace.boat4you.domains.calls.enums.CallOutcome
import hr.workspace.boat4you.domains.calls.enums.SkipReason
import hr.workspace.boat4you.domains.calls.jpa.InquiryCall
import hr.workspace.boat4you.domains.calls.jpa.InquiryCallRepository
import hr.workspace.boat4you.domains.catalouge.enums.InquiryStatus
import hr.workspace.boat4you.domains.catalouge.jpa.Inquiry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The rules checked right before a call is dialled (design §2.1-§2.3, §4: never on schedule time only). [check] returns why
 * the call must not happen, or null when it may. "Do not call" and "already being called" are rules of the person (the phone),
 * not of one inquiry; a call that waited more than [STALE_AFTER] (calls were switched off) never rings late.
 */
@Component
class CallEligibility(
    private val calls: InquiryCallRepository,
    private val jdbc: JdbcTemplate,
) {
    fun check(
        call: InquiryCall,
        inquiry: Inquiry,
        now: Instant,
        s: CallsSettings.Snapshot,
    ): SkipReason? {
        val kind = call.kind
        val phone = inquiry.phoneE164
        return when {
            !inquiry.contactConsent -> SkipReason.NO_CONSENT
            phone == null -> SkipReason.INVALID_PHONE
            inquiry.doNotCall || calls.numberSaidDoNotCall(phone) -> SkipReason.DO_NOT_CALL
            kind != CallKind.MANUAL && inquiry.humanTakeoverAt != null -> SkipReason.HUMAN_TAKEOVER
            inquiry.status == InquiryStatus.ARCHIVED -> SkipReason.ARCHIVED
            s.testNumbers.isNotEmpty() && phone !in s.testNumbers -> SkipReason.NOT_ON_TEST_LIST
            CallClock.nextSlot(now, CallClock.clientZones(phone), s) == null -> SkipReason.TIMEZONE_UNCLEAR
            Duration.between(call.scheduledAt, now) > STALE_AFTER -> SkipReason.STALE
            call.attempt > s.maxAttempts -> SkipReason.MAX_ATTEMPTS
            kind == CallKind.CONFIRMATION -> confirmationRule(call, inquiry, phone, now)
            kind == CallKind.FOLLOW_UP -> followUpRule(inquiry)
            else -> null
        }
    }

    /** The daily (Zagreb day) or monthly (Zagreb month) call limit is used up. */
    fun limitReached(
        now: Instant,
        s: CallsSettings.Snapshot,
    ): Boolean {
        val day = now.atZone(CallClock.ZAGREB).toLocalDate()
        val dayStart = day.atStartOfDay(CallClock.ZAGREB).toInstant()
        val monthStart = day.withDayOfMonth(1).atStartOfDay(CallClock.ZAGREB).toInstant()
        val monthEnd = day.withDayOfMonth(1).plusMonths(1).atStartOfDay(CallClock.ZAGREB).toInstant()
        return calls.countStartedBetween(dayStart, dayStart.plus(Duration.ofDays(1))) >= s.dailyLimit ||
            calls.countStartedBetween(monthStart, monthEnd) >= s.monthlyLimit
    }

    /** Another call to the same number is dialling or talking right now: this one waits (design §4, never two at once). */
    fun anotherCallActive(call: InquiryCall): Boolean = calls.existsActiveCallToNumber(call.toNumber!!, call.id!!)

    private fun confirmationRule(
        call: InquiryCall,
        inquiry: Inquiry,
        phone: String,
        now: Instant,
    ): SkipReason? =
        when {
            inquiry.offerSentAt != null -> SkipReason.OFFER_ALREADY_SENT
            calls.existsDoneConfirmationForPhoneSince(phone, now.minus(RECENT), inquiry.id!!) -> SkipReason.RECENT_CONFIRMATION
            calls.existsOpenConfirmationOfOtherInquiry(phone, inquiry.id!!, call.id ?: Long.MAX_VALUE) -> SkipReason.SAME_PERSON
            else -> null
        }

    private fun followUpRule(inquiry: Inquiry): SkipReason? {
        if (booked(inquiry)) return SkipReason.BOOKED
        val said = calls.findByInquiryIdOrderByCreatedAtDesc(inquiry.id!!).mapNotNull { it.outcome }
        return if (said.any { it in STOP_OUTCOMES }) SkipReason.NOT_INTERESTED else null
    }

    /** A booking (reservation_flow) with the same e-mail or phone started after the inquiry. */
    private fun booked(inquiry: Inquiry): Boolean {
        val since = inquiry.createdAt?.atZone(ZoneId.systemDefault())?.toInstant() ?: return false
        val count =
            jdbc.queryForObject(
                "SELECT count(*) FROM reservation_flow WHERE (lower(email) = lower(?) OR phone IN (?, ?)) AND created_at > ?",
                Long::class.java,
                inquiry.email,
                inquiry.phone,
                inquiry.phoneE164,
                Timestamp.from(since),
            ) ?: 0
        return count > 0
    }

    companion object {
        val STALE_AFTER: Duration = Duration.ofHours(24)
        val RECENT: Duration = Duration.ofDays(7)
        private val STOP_OUTCOMES = setOf(CallOutcome.NOT_INTERESTED, CallOutcome.DO_NOT_CALL, CallOutcome.BOOKED_ELSEWHERE)
    }
}

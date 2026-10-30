package hr.workspace.boat4you.domains.calls.service

import hr.workspace.boat4you.domains.calls.enums.CallKind
import hr.workspace.boat4you.domains.calls.enums.CallStatus
import hr.workspace.boat4you.domains.calls.enums.SkipReason
import hr.workspace.boat4you.domains.calls.jpa.InquiryCall
import hr.workspace.boat4you.domains.calls.jpa.InquiryCallRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Inquiry
import hr.workspace.boat4you.domains.catalouge.jpa.InquiryRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

/**
 * Writes the call rows (design §2.2-§2.5, §6.2): it never dials. The first call is planned when an inquiry with consent is
 * saved, the follow-up when Mario marks the offer as sent; Mario's admin actions cancel or add calls; outcomes add retries
 * and callback tasks. Every row is checked again by [CallEligibility] right before dialling.
 */
@Service
class CallPlanner(
    private val calls: InquiryCallRepository,
    private val inquiries: InquiryRepository,
    private val settings: CallsSettings,
    private val jdbc: JdbcTemplate,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Plans the confirmation call for a new inquiry (consent, readable phone); planned even while calls are off. A person who
     * said "do not call" on any inquiry gets the mark on this one too and no call. When the same person was confirmed in the
     * last 7 days or is already being called about another inquiry, the row is SKIPPED: that call mentions this inquiry
     * (design §2.2). A number whose possible zones share no window is SKIPPED too.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onInquiryCreated(inquiry: Inquiry) {
        val phone = inquiry.phoneE164
        val id = inquiry.id!!
        if (!inquiry.contactConsent || phone == null) return
        if (inquiry.doNotCall || calls.numberSaidDoNotCall(phone)) {
            jdbc.update("UPDATE inquiry SET do_not_call = TRUE WHERE id = ?", id)
            return
        }
        val s = settings.snapshot()
        val now = now()
        val at = CallClock.nextSlot(now.plus(s.firstDelay), CallClock.clientZones(phone), s)
        val skip =
            when {
                at == null -> SkipReason.TIMEZONE_UNCLEAR
                calls.existsDoneConfirmationForPhoneSince(phone, now.minus(CallEligibility.RECENT), id) -> SkipReason.RECENT_CONFIRMATION
                calls.existsOpenConfirmationOfOtherInquiry(phone, id, Long.MAX_VALUE) -> SkipReason.SAME_PERSON
                else -> null
            }
        calls.save(newCall(id, CallKind.CONFIRMATION, phone, inquiry.locale, at ?: now).skipped(skip))
    }

    @Transactional
    fun onOfferSent(
        inquiryId: Long,
        note: String?,
    ) {
        val inquiry = inquiries.findById(inquiryId).orElseThrow()
        val text = note?.trim()?.takeIf { it.isNotEmpty() }
        require((text?.length ?: 0) <= OFFER_NOTE_MAX) { "offer note longer than $OFFER_NOTE_MAX characters" }
        val now = now()
        inquiry.offerSentAt = now
        inquiry.offerNote = text
        inquiries.save(inquiry)
        // Conditional updates, never entity saves: the dialler may have just moved the row to DIALING, and that must stand.
        jdbc.update(
            "UPDATE inquiry_call SET status = 'CANCELLED', updated_at = now() WHERE inquiry_id = ? AND kind = 'CONFIRMATION' AND status = 'SCHEDULED'",
            inquiryId,
        )
        val phone = inquiry.phoneE164 ?: return
        val s = settings.snapshot()
        val at = CallClock.nextSlot(now.plus(s.followUpDelay), CallClock.clientZones(phone), s)
        if (at == null) {
            calls.save(newCall(inquiryId, CallKind.FOLLOW_UP, phone, inquiry.locale, now).skipped(SkipReason.TIMEZONE_UNCLEAR))
            return
        }
        val moved =
            jdbc.update(
                "UPDATE inquiry_call SET scheduled_at = ?, updated_at = now() WHERE inquiry_id = ? AND kind = 'FOLLOW_UP' AND status = 'SCHEDULED'",
                Timestamp.from(at),
                inquiryId,
            )
        if (moved == 0) calls.save(newCall(inquiryId, CallKind.FOLLOW_UP, phone, inquiry.locale, at))
    }

    /** Mario's "Nazvat ću sam": no more AI calls for this inquiry; his callback tasks stay. */
    @Transactional
    fun onHumanTakeover(inquiryId: Long) {
        val inquiry = inquiries.findById(inquiryId).orElseThrow()
        inquiry.humanTakeoverAt = now()
        inquiries.save(inquiry)
        jdbc.update(
            "UPDATE inquiry_call SET status = 'CANCELLED', updated_at = now() " +
                "WHERE inquiry_id = ? AND status = 'SCHEDULED' AND kind <> 'HUMAN_CALLBACK'",
            inquiryId,
        )
    }

    /** "Do not call" for a person: every inquiry with this phone, and all their scheduled AI calls. */
    @Transactional
    fun markDoNotCall(phoneE164: String) {
        jdbc.update("UPDATE inquiry SET do_not_call = TRUE WHERE phone_e164 = ?", phoneE164)
        jdbc.update(
            "UPDATE inquiry_call SET status = 'CANCELLED', updated_at = now() WHERE status = 'SCHEDULED' AND kind <> 'HUMAN_CALLBACK' " +
                "AND inquiry_id IN (SELECT id FROM inquiry WHERE phone_e164 = ?)",
            phoneE164,
        )
    }

    /** Mario's "Nazovi sad": now when the client's window is open, else the client's next slot (never outside it). */
    @Transactional
    fun scheduleManual(
        inquiryId: Long,
        now: Instant,
    ): InquiryCall {
        val inquiry = inquiries.findById(inquiryId).orElseThrow()
        val phone = inquiry.phoneE164 ?: throw IllegalStateException("inquiry $inquiryId has no callable phone")
        val at = checkNotNull(CallClock.nextSlot(now, CallClock.clientZones(phone), settings.snapshot())) { "no calling window for inquiry $inquiryId" }
        return calls.save(newCall(inquiryId, CallKind.MANUAL, phone, inquiry.locale, at))
    }

    /** The next attempt after [status], as a new row (attempt + 1), or nothing when the rules say stop. */
    @Transactional
    fun scheduleRetry(
        previous: InquiryCall,
        status: CallStatus,
        now: Instant,
    ) {
        val phone = previous.toNumber ?: return
        val at = CallClock.retryAt(status, previous.attempt, now, CallClock.clientZones(phone), settings.snapshot(), earlierAttemptFailed(previous)) ?: return
        calls.save(
            newCall(previous.inquiryId!!, previous.kind!!, phone, previous.language, at).apply { attempt = previous.attempt + 1 },
        )
    }

    /** An earlier attempt of the same call (same inquiry and kind) already failed technically. */
    fun earlierAttemptFailed(call: InquiryCall): Boolean =
        calls
            .findByInquiryIdOrderByCreatedAtDesc(call.inquiryId!!)
            .any { it.id != call.id && it.kind == call.kind && it.status == CallStatus.FAILED }

    /** A callback task for Mario (never dialled by Nika); [note] says why. */
    @Transactional
    fun createHumanTask(
        inquiry: Inquiry,
        from: InquiryCall,
        note: String,
    ): InquiryCall =
        calls.save(
            newCall(inquiry.id!!, CallKind.HUMAN_CALLBACK, inquiry.phoneE164 ?: from.toNumber!!, inquiry.locale, now()).apply {
                summary = note
            },
        )

    private fun newCall(
        inquiryId: Long,
        kind: CallKind,
        phone: String,
        locale: String?,
        at: Instant,
    ) = InquiryCall().apply {
        this.inquiryId = inquiryId
        this.kind = kind
        status = CallStatus.SCHEDULED
        attempt = 1
        scheduledAt = at
        toNumber = phone
        language = locale ?: "en"
    }

    private fun InquiryCall.skipped(reason: SkipReason?) =
        apply {
            if (reason != null) {
                status = CallStatus.SKIPPED
                skipReason = reason
            }
        }

    private fun now(): Instant = clock.instant()

    companion object {
        const val OFFER_NOTE_MAX = 300
    }
}

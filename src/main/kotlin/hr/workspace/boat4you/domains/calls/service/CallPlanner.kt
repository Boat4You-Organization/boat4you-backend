package hr.workspace.boat4you.domains.calls.service

import hr.workspace.boat4you.domains.calls.enums.CallKind
import hr.workspace.boat4you.domains.calls.enums.CallStatus
import hr.workspace.boat4you.domains.calls.jpa.InquiryCall
import hr.workspace.boat4you.domains.calls.jpa.InquiryCallRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Inquiry
import hr.workspace.boat4you.domains.catalouge.jpa.InquiryRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
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
    /** Plans the confirmation call for a new inquiry (consent, readable phone, not "do not call"); planned even while calls are off. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onInquiryCreated(inquiry: Inquiry) {
        val phone = inquiry.phoneE164
        if (!inquiry.contactConsent || phone == null || inquiry.doNotCall) return
        val s = settings.snapshot()
        val at = CallClock.nextSlot(now().plus(s.firstDelay), CallClock.clientZone(phone), s)
        calls.save(newCall(inquiry.id!!, CallKind.CONFIRMATION, phone, inquiry.locale, at))
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
        val existing = calls.findByInquiryIdOrderByCreatedAtDesc(inquiryId)
        existing.filter { it.kind == CallKind.CONFIRMATION && it.status == CallStatus.SCHEDULED }.forEach {
            it.status = CallStatus.CANCELLED
            calls.save(it)
        }
        val phone = inquiry.phoneE164 ?: return
        val s = settings.snapshot()
        val at = CallClock.nextSlot(now.plus(s.followUpDelay), CallClock.clientZone(phone), s)
        val pending = existing.firstOrNull { it.kind == CallKind.FOLLOW_UP && it.status == CallStatus.SCHEDULED }
        if (pending != null) {
            pending.scheduledAt = at
            calls.save(pending)
        } else {
            calls.save(newCall(inquiryId, CallKind.FOLLOW_UP, phone, inquiry.locale, at))
        }
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

    /** Mario's "Nazovi sad": now when the client's window is open (or he confirmed outside it), else the next slot. */
    @Transactional
    fun scheduleManual(
        inquiryId: Long,
        now: Instant,
        confirmedOutsideWindow: Boolean,
    ): InquiryCall {
        val inquiry = inquiries.findById(inquiryId).orElseThrow()
        val phone = inquiry.phoneE164 ?: throw IllegalStateException("inquiry $inquiryId has no callable phone")
        val at = if (confirmedOutsideWindow) now else CallClock.nextSlot(now, CallClock.clientZone(phone), settings.snapshot())
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
        val previousFailed =
            calls
                .findByInquiryIdOrderByCreatedAtDesc(previous.inquiryId!!)
                .any { it.id != previous.id && it.kind == previous.kind && it.status == CallStatus.FAILED }
        val at = CallClock.retryAt(status, previous.attempt, now, CallClock.clientZone(phone), settings.snapshot(), previousFailed) ?: return
        calls.save(
            newCall(previous.inquiryId!!, previous.kind!!, phone, previous.language, at).apply { attempt = previous.attempt + 1 },
        )
    }

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

    private fun now(): Instant = clock.instant()

    companion object {
        const val OFFER_NOTE_MAX = 300
    }
}

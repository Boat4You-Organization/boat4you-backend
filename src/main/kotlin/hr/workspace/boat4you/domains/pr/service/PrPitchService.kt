package hr.workspace.boat4you.domains.pr.service

import hr.workspace.boat4you.domains.campaign.jpa.EmailSuppressionRepository
import hr.workspace.boat4you.domains.pr.dto.PrPitchDraftRequest
import hr.workspace.boat4you.domains.pr.dto.PrPitchDto
import hr.workspace.boat4you.domains.pr.dto.PrPitchUpdateRequest
import hr.workspace.boat4you.domains.pr.jpa.PrContact
import hr.workspace.boat4you.domains.pr.jpa.PrContactRepository
import hr.workspace.boat4you.domains.pr.jpa.PrOutlet
import hr.workspace.boat4you.domains.pr.jpa.PrOutletRepository
import hr.workspace.boat4you.domains.pr.jpa.PrPitch
import hr.workspace.boat4you.domains.pr.jpa.PrPitchRepository
import hr.workspace.boat4you.domains.pr.jpa.PrPitchStatus
import hr.workspace.boat4you.domains.pr.jpa.PrPitchTemplate
import hr.workspace.boat4you.domains.pr.jpa.PrStudyRepository
import hr.workspace.boat4you.domains.pr.rules.PitchCheckInput
import hr.workspace.boat4you.domains.pr.rules.PitchDecision
import hr.workspace.boat4you.domains.pr.rules.PrComplianceRules
import jakarta.persistence.criteria.Predicate
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * Pitch drafts (written by Mario in the admin or by a Claude session through PrApiController), the per-pitch send
 * decision, sending ([send], the one way out of DRAFT; FAILED -> DRAFT by hand is the retry), follow-up drafts and the
 * statuses typed in by hand after sending. One main pitch per address and study (across contact rows; a study-less pitch
 * is not limited, and a consent request never has a study), at most one follow-up per main pitch, written only after it
 * was SENT and sendable only while it is still SENT. TripAdvisor is refused in every subject and body we store.
 */
@Service
class PrPitchService(
    private val pitches: PrPitchRepository,
    private val contacts: PrContactRepository,
    private val outlets: PrOutletRepository,
    private val studies: PrStudyRepository,
    private val suppressions: EmailSuppressionRepository,
    private val contactService: PrContactService,
    private val props: PrProperties,
    private val mail: PrMailSender,
    txManager: PlatformTransactionManager,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(PrPitchService::class.java)

    /** The outcome of a send is saved in its own transaction, after SMTP (which runs outside any transaction). */
    private val newTx = TransactionTemplate(txManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    /** Newest first; both filters optional. */
    @Transactional(readOnly = true)
    fun list(
        studyId: Long?,
        status: PrPitchStatus?,
    ): List<PrPitchDto> {
        val spec =
            Specification<PrPitch> { root, _, cb ->
                val where = mutableListOf<Predicate>()
                studyId?.let { where += cb.equal(root.get<Long>("studyId"), it) }
                status?.let { where += cb.equal(root.get<PrPitchStatus>("status"), it) }
                cb.and(*where.toTypedArray())
            }
        return dtos(pitches.findAll(spec, Sort.by(Sort.Direction.DESC, "id")))
    }

    @Transactional
    fun createDraft(req: PrPitchDraftRequest): PrPitchDto {
        val contactId = req.contactId ?: throw PrException.badRequest("CONTACT_REQUIRED", "contactId is required")
        val template = req.template ?: throw PrException.badRequest("TEMPLATE_REQUIRED", "template is required")
        if (template == PrPitchTemplate.FOLLOW_UP) {
            throw PrException.badRequest("TEMPLATE_NOT_ALLOWED", "A follow-up is written from its sent pitch (POST .../pitches/{id}/follow-up)")
        }
        // A consent request is about the contact, not a study: tied to one, it would take the study's one main pitch.
        if (template == PrPitchTemplate.CONSENT_REQUEST && req.studyId != null) {
            throw PrException.badRequest("CONSENT_REQUEST_NO_STUDY", "A consent request is not tied to a study; leave studyId empty")
        }
        val subject = subject(req.subject)
        val body = body(req.body)
        val contact = contacts.findById(contactId).orElseThrow { PrException.notFound("CONTACT_NOT_FOUND", "Contact $contactId not found") }
        req.studyId?.let { studyId ->
            if (!studies.existsById(studyId)) throw PrException.notFound("STUDY_NOT_FOUND", "Study $studyId not found")
            // One main pitch per study per address, across every contact row with it (one person, two outlets). A race
            // this check loses on the same contact row hits the partial unique index; PrErrorAdvice answers 409 CONFLICT.
            if (pitches.existsMainPitchForStudyAndEmail(studyId, PrComplianceRules.normaliseEmail(contact.email))) {
                throw PrException.conflict("PITCH_EXISTS", "${contact.email} already has a pitch for study $studyId")
            }
        }
        val pitch =
            PrPitch().apply {
                studyId = req.studyId
                this.contactId = contactId
                this.template = template
                this.subject = subject
                this.body = body
            }
        return dto(pitches.save(pitch))
    }

    /** Subject and/or body of a DRAFT; anything past DRAFT is 409 PITCH_NOT_DRAFT. */
    @Transactional
    fun updateDraft(
        pitchId: Long,
        req: PrPitchUpdateRequest,
    ): PrPitchDto {
        val pitch = pitch(pitchId)
        if (pitch.status != PrPitchStatus.DRAFT) throw notDraft(pitchId)
        val subject = req.subject?.let { subject(it) } ?: pitch.subject
        val body = req.body?.let { body(it) } ?: pitch.body
        if (pitches.updateDraft(pitchId, subject, body) == 0) throw notDraft(pitchId)
        return dto(pitch(pitchId))
    }

    /** The one follow-up of a SENT main pitch: same contact and study, subject "Re: <original>". */
    @Transactional
    fun createFollowUpDraft(
        pitchId: Long,
        body: String?,
    ): PrPitchDto {
        val text = body(body)
        val parent = pitch(pitchId)
        if (parent.parentPitchId != null || parent.status != PrPitchStatus.SENT) {
            throw PrException.conflict("FOLLOWUP_NOT_ALLOWED", "Only a sent main pitch can get a follow-up (pitch $pitchId is ${parent.status})")
        }
        // A race this check loses hits the unique index on parent_pitch_id; PrErrorAdvice answers 409 CONFLICT.
        if (pitches.existsByParentPitchId(pitchId)) throw PrException.conflict("FOLLOWUP_EXISTS", "Pitch $pitchId already has its follow-up")
        val followUp =
            PrPitch().apply {
                studyId = parent.studyId
                contactId = parent.contactId
                parentPitchId = parent.id
                template = PrPitchTemplate.FOLLOW_UP
                subject = "Re: ${parent.subject}".take(SUBJECT)
                this.body = text
            }
        return dto(pitches.save(followUp))
    }

    /**
     * Sends one pitch to its contact from press@, 1:1, plain text with the footer. Deliberately NOT one transaction:
     * (1) kill switch, (1b) SMTP settings, (2) the send decision, all before anything changes; (3) the atomic
     * DRAFT -> SENDING commits on its own, so a second call (double click, second admin) gets 409 and the pitch can
     * never be sent twice; (4) SMTP outside any transaction; (5) SENT, or (6) FAILED + error, saved in a new
     * transaction. A pitch whose outcome cannot be saved stays SENDING: never re-sendable, logged for a manual fix.
     */
    fun send(pitchId: Long): PrPitchDto {
        val m = props.mail
        if (!m.enabled) throw PrException.conflict("MAIL_DISABLED", "Sending is switched off (PR_MAIL_ENABLED=false)")
        if (m.host.isBlank() || m.username.isBlank() || m.password.isBlank()) {
            throw PrException.conflict("MAIL_NOT_CONFIGURED", "PR mail is on but PR_MAIL_HOST, PR_MAIL_USERNAME or PR_MAIL_PASSWORD is empty")
        }
        val decision = decision(pitchId)
        if (decision is PitchDecision.Blocked) throw PrException.conflict(decision.code, "Pitch $pitchId may not be sent: ${decision.code}")
        if (pitches.markSending(pitchId) == 0) {
            throw PrException.conflict("ALREADY_SENDING_OR_SENT", "Pitch $pitchId is no longer a draft: it is being sent or was sent")
        }
        // Only this call owns the pitch now. Re-read what was frozen and check it again: an opt-out or bounce that landed
        // between the check above and the freeze must still stop it (FAILED, retry by hand after a look).
        val pitch = pitch(pitchId)
        val l = lookups(listOf(pitch))
        val frozen = decide(pitch, l)
        if (frozen is PitchDecision.Blocked) {
            recordFailed(pitchId, "Blocked at send time: ${frozen.code}")
            throw PrException.conflict(frozen.code, "Pitch $pitchId may not be sent: ${frozen.code}")
        }
        val contact = l.contacts.getValue(pitch.contactId!!)
        val inReplyTo = pitch.parentPitchId?.let { pitch(it).messageId }
        val messageId =
            try {
                mail.send(contact.email, pitch.subject, PrFooter.appendTo(pitch.body, props.privacyUrl), inReplyTo)
            } catch (e: Exception) {
                log.warn("PR pitch {} not sent: {}", pitchId, e.javaClass.simpleName)
                recordFailed(pitchId, shortError(e))
                return dto(pitch(pitchId))
            }
        try {
            recordSent(pitchId, messageId)
        } catch (e: Exception) {
            log.error("PR pitch {} WAS SENT (Message-ID {}) but saving SENT failed; it stays SENDING and is never re-sent. Fix it by hand.", pitchId, messageId, e)
            throw PrException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "SENT_NOT_RECORDED",
                "Pitch $pitchId was sent, but its status could not be saved: it stays SENDING and will not be sent again",
            )
        }
        log.info("PR pitch {} sent, Message-ID {}", pitchId, messageId)
        return dto(pitch(pitchId))
    }

    /**
     * What happened after sending, typed in by hand (the module never reads the inbox): REPLIED, DECLINED, PUBLISHED or
     * BOUNCED, from SENT or one of the first three. BOUNCED also suppresses the address (PrContactService.markBounced).
     * DRAFT only from FAILED (clears the error): the retry. SENDING is never changed here. [optOut] with DECLINED or
     * REPLIED also opts the address out (PrContactService.optOut: suppression row, every contact with it OPTED_OUT), as
     * the footer promises ("Just reply and I won't email you again"); it is ignored with any other status.
     */
    @Transactional
    fun setStatus(
        pitchId: Long,
        status: PrPitchStatus,
        replyNote: String?,
        optOut: Boolean = false,
    ): PrPitchDto {
        if (status !in MANUAL_TARGETS) throw PrException.badRequest("STATUS_NOT_ALLOWED", "Status can be set by hand only to ${MANUAL_TARGETS.joinToString()}")
        val pitch = pitch(pitchId)
        val sources = if (status == PrPitchStatus.DRAFT) setOf(PrPitchStatus.FAILED) else MANUAL_SOURCES
        if (pitch.status !in sources) {
            throw PrException.conflict("STATUS_TRANSITION_NOT_ALLOWED", "Pitch $pitchId is ${pitch.status}: it cannot become $status")
        }
        if (status == PrPitchStatus.DRAFT) pitch.error = null
        pitch.status = status
        replyNote?.trim()?.ifEmpty { null }?.let { pitch.replyNote = it }
        val saved = pitches.save(pitch)
        if (status == PrPitchStatus.BOUNCED) contactService.markBounced(saved.contactId!!)
        if (optOut && status in OPT_OUT_TARGETS) contactService.optOut(saved.contactId!!)
        return dto(saved)
    }

    /** May this pitch be sent now? PrComplianceRules on what the database holds at this moment. */
    @Transactional(readOnly = true)
    fun decision(pitchId: Long): PitchDecision = pitch(pitchId).let { decide(it, lookups(listOf(it))) }

    // ---- internals ----

    /**
     * SENDING -> SENT with its Message-ID; the contact was contacted now, and a consent request moves ACTIVE to
     * CONSENT_REQUESTED. The contact gets a targeted UPDATE, so a DO_NOT_CONTACT or opt-out saved during the send stays.
     */
    private fun recordSent(
        pitchId: Long,
        messageId: String,
    ) = newTx.executeWithoutResult {
        val now = clock.instant()
        val pitch = pitch(pitchId)
        check(pitch.status == PrPitchStatus.SENDING) { "Pitch $pitchId is ${pitch.status}, expected SENDING" }
        pitch.status = PrPitchStatus.SENT
        pitch.sentAt = now
        pitch.messageId = messageId
        pitch.error = null
        pitches.save(pitch)
        // Consent already given (CONSENTED) is never taken back by a consent request: the UPDATE moves ACTIVE only.
        contacts.recordContacted(pitch.contactId!!, now, pitch.template == PrPitchTemplate.CONSENT_REQUEST)
    }

    /** SENDING -> FAILED with a short reason. If even this cannot be saved, the pitch stays SENDING (never re-sendable). */
    private fun recordFailed(
        pitchId: Long,
        error: String,
    ) = newTx.executeWithoutResult {
        val pitch = pitch(pitchId)
        check(pitch.status == PrPitchStatus.SENDING) { "Pitch $pitchId is ${pitch.status}, expected SENDING" }
        pitch.status = PrPitchStatus.FAILED
        pitch.error = error
        pitches.save(pitch)
    }

    /** Type and message of the SMTP error, cut short; never the body, and the password masked should a server echo it. */
    private fun shortError(e: Exception): String {
        val text = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
        val masked = props.mail.password.takeIf { it.isNotEmpty() }?.let { text.replace(it, "***") } ?: text
        return masked.take(ERROR) + " Check the press@ copy (BCC) before retrying."
    }

    /** Everything a set of pitches needs for their DTOs and decisions, loaded once per set (no query per pitch). */
    private class Lookups(
        val contacts: Map<Long, PrContact>,
        val outlets: Map<Long, PrOutlet>,
        val parents: Map<Long, PrPitch>,
        val suppressed: Set<String>,
    )

    private fun lookups(list: List<PrPitch>): Lookups {
        val contactMap = contacts.findAllById(list.mapNotNull { it.contactId }.toSet()).associateBy { it.id!! }
        val outletMap = outlets.findAllById(contactMap.values.mapNotNull { it.outletId }.toSet()).associateBy { it.id!! }
        val parents = pitches.findAllById(list.mapNotNull { it.parentPitchId }.toSet()).associateBy { it.id!! }
        // Few distinct addresses per list; one exists-query each keeps the shared suppression repository untouched.
        val suppressed =
            contactMap.values
                .map { PrComplianceRules.normaliseEmail(it.email) }
                .toSet()
                .filter { suppressions.existsByEmailIgnoreCase(it) }
                .toSet()
        return Lookups(contactMap, outletMap, parents, suppressed)
    }

    private fun decide(
        pitch: PrPitch,
        l: Lookups,
    ): PitchDecision {
        val contact = l.contacts.getValue(pitch.contactId!!)
        val outlet = contact.outletId?.let { l.outlets[it] }
        val parent = pitch.parentPitchId?.let { l.parents[it] }
        return PrComplianceRules.check(
            PitchCheckInput(
                market = contact.market ?: outlet?.market,
                contactStatus = contact.status,
                emailSuppressed = PrComplianceRules.normaliseEmail(contact.email) in l.suppressed,
                template = pitch.template!!,
                emailSourceUrl = contact.emailSourceUrl,
                emailFoundOn = contact.emailFoundOn,
                subject = pitch.subject,
                body = pitch.body,
                clearedMarkets = props.clearedMarkets(),
                parentSentAt = parent?.sentAt,
                parentStatus = parent?.status,
                now = clock.instant(),
            ),
        )
    }

    private fun dto(pitch: PrPitch): PrPitchDto = dtos(listOf(pitch)).single()

    private fun dtos(list: List<PrPitch>): List<PrPitchDto> {
        val l = lookups(list)
        val footer = PrFooter.text(props.privacyUrl)
        return list.map { p ->
            val contact = l.contacts.getValue(p.contactId!!)
            val decision = decide(p, l)
            PrPitchDto(
                id = p.id!!,
                studyId = p.studyId,
                contactId = p.contactId!!,
                contactEmail = contact.email,
                contactName = contact.name,
                outletName = contact.outletId?.let { l.outlets[it]?.name },
                parentPitchId = p.parentPitchId,
                template = p.template!!,
                subject = p.subject,
                body = p.body,
                status = p.status,
                decision = if (decision is PitchDecision.Blocked) decision.code else ALLOWED,
                footer = footer,
                messageId = p.messageId,
                sentAt = p.sentAt,
                error = p.error,
                replyNote = p.replyNote,
                createdAt = p.createdAt,
                updatedAt = p.updatedAt,
            )
        }
    }

    private fun pitch(id: Long): PrPitch = pitches.findById(id).orElseThrow { PrException.notFound("PITCH_NOT_FOUND", "Pitch $id not found") }

    private fun subject(raw: String?): String {
        val subject = raw?.trim()?.ifEmpty { null } ?: throw PrException.badRequest("SUBJECT_REQUIRED", "Subject is required")
        if (subject.length > SUBJECT) throw PrException.badRequest("SUBJECT_TOO_LONG", "Subject is ${subject.length} characters; at most $SUBJECT")
        noTripAdvisor(subject)
        return subject
    }

    private fun body(raw: String?): String {
        if (raw.isNullOrBlank()) throw PrException.badRequest("BODY_REQUIRED", "Body is required")
        noTripAdvisor(raw)
        return raw
    }

    private fun noTripAdvisor(text: String) {
        if (PrComplianceRules.mentionsTripAdvisor(text)) throw PrException.badRequest("TRIPADVISOR_FORBIDDEN", "A pitch must not mention TripAdvisor")
    }

    private fun notDraft(id: Long) = PrException.conflict("PITCH_NOT_DRAFT", "Pitch $id is no longer a draft: it cannot be edited")

    companion object {
        const val ALLOWED = "ALLOWED"
        private const val SUBJECT = 300
        private const val ERROR = 500
        private val MANUAL_TARGETS =
            setOf(PrPitchStatus.REPLIED, PrPitchStatus.DECLINED, PrPitchStatus.PUBLISHED, PrPitchStatus.BOUNCED, PrPitchStatus.DRAFT)
        private val OPT_OUT_TARGETS = setOf(PrPitchStatus.REPLIED, PrPitchStatus.DECLINED)
        private val MANUAL_SOURCES = setOf(PrPitchStatus.SENT, PrPitchStatus.REPLIED, PrPitchStatus.DECLINED, PrPitchStatus.PUBLISHED)
    }
}

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
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * Pitch drafts (written by Mario in the admin or by a Claude session through PrApiController), the per-pitch send
 * decision, follow-up drafts and the statuses typed in by hand after sending. Nothing here sends: a pitch leaves DRAFT
 * only through sending (Task 6). One main pitch per contact and study (a study-less pitch is not limited), at most one
 * follow-up per main pitch, and only after it was SENT. TripAdvisor is refused in every subject and body we store.
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
    private val clock: Clock = Clock.systemUTC(),
) {
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
        val subject = subject(req.subject)
        val body = body(req.body)
        if (!contacts.existsById(contactId)) throw PrException.notFound("CONTACT_NOT_FOUND", "Contact $contactId not found")
        req.studyId?.let { studyId ->
            if (!studies.existsById(studyId)) throw PrException.notFound("STUDY_NOT_FOUND", "Study $studyId not found")
            // A race this check loses hits the partial unique index; PrErrorAdvice answers 409 CONFLICT.
            if (pitches.existsByStudyIdAndContactIdAndParentPitchIdIsNull(studyId, contactId)) {
                throw PrException.conflict("PITCH_EXISTS", "This contact already has a pitch for study $studyId")
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
     * What happened after sending, typed in by hand (the module never reads the inbox): REPLIED, DECLINED, PUBLISHED or
     * BOUNCED, from SENT or one of the first three. BOUNCED also suppresses the address (PrContactService.markBounced).
     */
    @Transactional
    fun setStatus(
        pitchId: Long,
        status: PrPitchStatus,
        replyNote: String?,
    ): PrPitchDto {
        if (status !in MANUAL_TARGETS) throw PrException.badRequest("STATUS_NOT_ALLOWED", "Status can be set by hand only to ${MANUAL_TARGETS.joinToString()}")
        val pitch = pitch(pitchId)
        if (pitch.status !in MANUAL_SOURCES) {
            throw PrException.conflict("STATUS_TRANSITION_NOT_ALLOWED", "Pitch $pitchId is ${pitch.status}: it cannot become $status")
        }
        pitch.status = status
        replyNote?.trim()?.ifEmpty { null }?.let { pitch.replyNote = it }
        val saved = pitches.save(pitch)
        if (status == PrPitchStatus.BOUNCED) contactService.markBounced(saved.contactId!!)
        return dto(saved)
    }

    /** May this pitch be sent now? PrComplianceRules on what the database holds at this moment. */
    @Transactional(readOnly = true)
    fun decision(pitchId: Long): PitchDecision = pitch(pitchId).let { decide(it, lookups(listOf(it))) }

    // ---- internals ----

    /** Everything a set of pitches needs for their DTOs and decisions, loaded once per set (no query per pitch). */
    private class Lookups(
        val contacts: Map<Long, PrContact>,
        val outlets: Map<Long, PrOutlet>,
        val parentSentAt: Map<Long, Instant?>,
        val suppressed: Set<String>,
    )

    private fun lookups(list: List<PrPitch>): Lookups {
        val contactMap = contacts.findAllById(list.mapNotNull { it.contactId }.toSet()).associateBy { it.id!! }
        val outletMap = outlets.findAllById(contactMap.values.mapNotNull { it.outletId }.toSet()).associateBy { it.id!! }
        val parents = pitches.findAllById(list.mapNotNull { it.parentPitchId }.toSet()).associate { it.id!! to it.sentAt }
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
                parentSentAt = pitch.parentPitchId?.let { l.parentSentAt[it] },
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
        private val MANUAL_TARGETS = setOf(PrPitchStatus.REPLIED, PrPitchStatus.DECLINED, PrPitchStatus.PUBLISHED, PrPitchStatus.BOUNCED)
        private val MANUAL_SOURCES = setOf(PrPitchStatus.SENT, PrPitchStatus.REPLIED, PrPitchStatus.DECLINED, PrPitchStatus.PUBLISHED)
    }
}

package hr.workspace.boat4you.domains.calls.service

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.calls.dto.CallDetailDto
import hr.workspace.boat4you.domains.calls.dto.CallInquiryDto
import hr.workspace.boat4you.domains.calls.dto.CallInquiryListItemDto
import hr.workspace.boat4you.domains.calls.dto.CallListItemDto
import hr.workspace.boat4you.domains.calls.enums.CallKind
import hr.workspace.boat4you.domains.calls.enums.CallOutcome
import hr.workspace.boat4you.domains.calls.enums.CallStatus
import hr.workspace.boat4you.domains.calls.enums.SkipReason
import hr.workspace.boat4you.domains.calls.jpa.InquiryCall
import hr.workspace.boat4you.domains.calls.jpa.InquiryCallRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Inquiry
import hr.workspace.boat4you.domains.catalouge.jpa.InquiryRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

/** The rules forbid the call Mario asked for; the reason goes back to the admin (HTTP 409). */
class CallRefusedException(
    val reason: SkipReason,
) : RuntimeException(reason.name)

/**
 * Everything the "Call centar" admin section reads and does (design §2.5): the call list, a call with its transcript and
 * inquiry, the inquiries page, Mario's actions (call now, I'll call myself, offer sent, do not call, task resolved),
 * the `calls.*` settings and the recordings. SYSTEM_ADMIN only (AdminCallController).
 */
@Service
class AdminCallService(
    private val calls: InquiryCallRepository,
    private val inquiries: InquiryRepository,
    private val jdbc: JdbcTemplate,
    private val planner: CallPlanner,
    private val eligibility: CallEligibility,
    private val settings: CallsSettings,
    private val audio: CallAudioStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun list(
        status: CallStatus?,
        outcome: CallOutcome?,
        site: String?,
        kind: CallKind?,
        from: Instant?,
        to: Instant?,
        q: String?,
        page: Int,
        size: Int,
    ): Page<CallListItemDto> {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()
        status?.let {
            where += "c.status = ?"
            args += it.name
        }
        outcome?.let {
            where += "c.outcome = ?"
            args += it.name
        }
        site?.takeIf { it.isNotBlank() }?.let {
            where += "i.site = ?"
            args += it
        }
        kind?.let {
            where += "c.kind = ?"
            args += it.name
        }
        from?.let {
            where += "c.scheduled_at >= ?"
            args += Timestamp.from(it)
        }
        to?.let {
            where += "c.scheduled_at < ?"
            args += Timestamp.from(it)
        }
        q?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            where += "(i.name ILIKE ? OR i.surname ILIKE ? OR i.email ILIKE ? OR i.phone ILIKE ? OR i.phone_e164 ILIKE ?)"
            repeat(5) { args += "%$text%" }
        }
        val filter = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        val from0 = "FROM inquiry_call c JOIN inquiry i ON i.id = c.inquiry_id $filter"
        val (p, s) = page.coerceAtLeast(0) to size.coerceIn(1, MAX_PAGE)
        val total = jdbc.queryForObject("SELECT count(*) $from0", Long::class.java, *args.toTypedArray()) ?: 0
        val ids =
            jdbc.queryForList(
                "SELECT c.id $from0 ORDER BY c.scheduled_at DESC, c.id DESC LIMIT ? OFFSET ?",
                Long::class.java,
                *(args + s + p * s).toTypedArray(),
            )
        return PageImpl(items(ids), PageRequest.of(p, s), total)
    }

    @Transactional(readOnly = true)
    fun detail(id: Long): CallDetailDto {
        val call = calls.findById(id).orElseThrow { NoSuchElementException("call $id") }
        val inquiry = inquiries.findById(call.inquiryId!!).orElseThrow()
        val all = calls.findByInquiryIdOrderByCreatedAtDesc(inquiry.id!!)
        return CallDetailDto(
            call = item(call, inquiry),
            data = call.data,
            transcript = call.transcript,
            quality = call.quality,
            terminationReason = call.terminationReason,
            costCredits = call.costCredits,
            conversationId = call.elevenlabsConversationId,
            callSid = call.twilioCallSid,
            hasAudio = call.audioPath != null,
            inquiry = inquiryDto(inquiry),
            calls = all.map { item(it, inquiry) },
        )
    }

    @Transactional(readOnly = true)
    fun inquiryPage(
        site: String?,
        q: String?,
        page: Int,
        size: Int,
    ): Page<CallInquiryListItemDto> {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()
        site?.takeIf { it.isNotBlank() }?.let {
            where += "site = ?"
            args += it
        }
        q?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            where += "(name ILIKE ? OR surname ILIKE ? OR email ILIKE ? OR phone ILIKE ? OR phone_e164 ILIKE ?)"
            repeat(5) { args += "%$text%" }
        }
        val filter = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        val (p, s) = page.coerceAtLeast(0) to size.coerceIn(1, MAX_PAGE)
        val total = jdbc.queryForObject("SELECT count(*) FROM inquiry $filter", Long::class.java, *args.toTypedArray()) ?: 0
        val ids =
            jdbc.queryForList(
                "SELECT id FROM inquiry $filter ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                Long::class.java,
                *(args + s + p * s).toTypedArray(),
            )
        val byId = inquiries.findAllById(ids).associateBy { it.id }
        val rows =
            ids.mapNotNull { byId[it] }.map { inquiry ->
                val own = calls.findByInquiryIdOrderByCreatedAtDesc(inquiry.id!!)
                val last = own.firstOrNull { it.status !in setOf(CallStatus.SCHEDULED, CallStatus.CANCELLED) }
                val next = own.filter { it.status == CallStatus.SCHEDULED && it.kind != CallKind.HUMAN_CALLBACK }.mapNotNull { it.scheduledAt }.minOrNull()
                CallInquiryListItemDto(inquiryDto(inquiry), last?.status, last?.outcome, next)
            }
        return PageImpl(rows, PageRequest.of(p, s), total)
    }

    /**
     * Mario's "Nazovi sad": now when the client's window is open, else the client's next slot (the answer says which); refused
     * with the reason when calls are switched off or the rules forbid the call.
     */
    @Transactional
    fun callNow(inquiryId: Long): CallListItemDto {
        val inquiry = inquiries.findById(inquiryId).orElseThrow { NoSuchElementException("inquiry $inquiryId") }
        val now = clock.instant()
        val probe =
            InquiryCall().apply {
                this.inquiryId = inquiryId
                kind = CallKind.MANUAL
                scheduledAt = now
                toNumber = inquiry.phoneE164 ?: ""
            }
        val s = settings.snapshot()
        if (!s.enabled) throw CallRefusedException(SkipReason.CALLS_OFF)
        eligibility.check(probe, inquiry, now, s)?.let { throw CallRefusedException(it) }
        return item(planner.scheduleManual(inquiryId, now), inquiry)
    }

    @Transactional
    fun humanTakeover(inquiryId: Long) = planner.onHumanTakeover(exists(inquiryId))

    @Transactional
    fun offerSent(
        inquiryId: Long,
        note: String?,
    ) {
        if ((note?.trim()?.length ?: 0) > CallPlanner.OFFER_NOTE_MAX) {
            throw ParameterValidationException(mapOf("note" to "at most ${CallPlanner.OFFER_NOTE_MAX} characters"))
        }
        planner.onOfferSent(exists(inquiryId), note)
    }

    @Transactional
    fun doNotCall(inquiryId: Long) {
        val inquiry = inquiries.findById(inquiryId).orElseThrow { NoSuchElementException("inquiry $inquiryId") }
        val phone = inquiry.phoneE164
        if (phone != null) {
            planner.markDoNotCall(phone)
        } else {
            inquiry.doNotCall = true
            inquiries.save(inquiry)
            jdbc.update(
                "UPDATE inquiry_call SET status = 'CANCELLED', updated_at = now() WHERE inquiry_id = ? AND status = 'SCHEDULED' AND kind <> 'HUMAN_CALLBACK'",
                inquiryId,
            )
        }
    }

    /** Closes one of Mario's callback tasks; any other call kind is refused. */
    @Transactional
    fun resolve(callId: Long) {
        val call = calls.findById(callId).orElseThrow { NoSuchElementException("call $callId") }
        check(call.kind == CallKind.HUMAN_CALLBACK) { "only a callback task can be resolved" }
        call.status = CallStatus.DONE
        call.endedAt = clock.instant()
        calls.save(call)
    }

    /** The settings in effect; the transfer number shows only its last three digits. */
    fun settingsView(): Map<String, String> =
        settings.effective().mapValues { (k, v) -> if (k == CallsSettings.TRANSFER_NUMBER && v.length > 3) "*".repeat(v.length - 3) + v.takeLast(3) else v }

    fun updateSettings(values: Map<String, String>) = settings.write(values)

    /** The recording's file, or null when the call has none (or it was deleted by retention). */
    @Transactional(readOnly = true)
    fun audioFile(callId: Long): Path? {
        val path = calls.findById(callId).orElseThrow { NoSuchElementException("call $callId") }.audioPath ?: return null
        return audio.resolve(path).takeIf { Files.exists(it) }
    }

    private fun exists(inquiryId: Long): Long = inquiryId.also { if (!inquiries.existsById(it)) throw NoSuchElementException("inquiry $it") }

    private fun items(ids: List<Long>): List<CallListItemDto> {
        val byId = calls.findAllById(ids).associateBy { it.id }
        val inq = inquiries.findAllById(byId.values.mapNotNull { it.inquiryId }.toSet()).associateBy { it.id }
        return ids.mapNotNull { id -> byId[id]?.let { c -> inq[c.inquiryId]?.let { item(c, it) } } }
    }

    private fun item(
        c: InquiryCall,
        i: Inquiry,
    ) = CallListItemDto(
        id = c.id!!,
        inquiryId = i.id!!,
        kind = c.kind,
        attempt = c.attempt,
        status = c.status,
        outcome = c.outcome,
        skipReason = c.skipReason,
        site = i.site,
        clientName = listOfNotNull(i.name, i.surname).joinToString(" ").trim(),
        phone = c.toNumber,
        language = c.language,
        scheduledAt = c.scheduledAt,
        startedAt = c.startedAt,
        durationSecs = c.durationSecs,
        summary = c.summary,
        qualityFailed = c.quality.orEmpty().values.any { (it as? Map<*, *>)?.get("result") == "failure" },
    )

    private fun inquiryDto(i: Inquiry) =
        CallInquiryDto(
            id = i.id!!,
            createdAt = i.createdAt,
            site = i.site,
            locale = i.locale,
            yachtLabel = CallContextBuilder.boatLabel(i.yacht),
            dateFrom = i.dateFrom,
            dateTo = i.dateTo,
            email = i.email,
            phone = i.phone,
            phoneE164 = i.phoneE164,
            message = i.message,
            details = i.details,
            contactConsent = i.contactConsent,
            consentTextVersion = i.consentTextVersion,
            doNotCall = i.doNotCall,
            offerSentAt = i.offerSentAt,
            offerNote = i.offerNote,
            humanTakeoverAt = i.humanTakeoverAt,
        )

    companion object {
        private const val MAX_PAGE = 200
    }
}

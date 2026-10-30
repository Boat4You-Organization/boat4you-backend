package hr.workspace.boat4you.domains.calls.dto

import hr.workspace.boat4you.domains.calls.enums.CallKind
import hr.workspace.boat4you.domains.calls.enums.CallOutcome
import hr.workspace.boat4you.domains.calls.enums.CallStatus
import hr.workspace.boat4you.domains.calls.enums.SkipReason
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** One row of the Call centar list (admin phase 4 reads exactly these names). */
data class CallListItemDto(
    val id: Long,
    val inquiryId: Long,
    val kind: CallKind?,
    val attempt: Int,
    val status: CallStatus?,
    val outcome: CallOutcome?,
    val skipReason: SkipReason?,
    val site: String?,
    val clientName: String,
    val phone: String?,
    val language: String?,
    val scheduledAt: Instant?,
    val startedAt: Instant?,
    val durationSecs: Int?,
    val summary: String?,
    val qualityFailed: Boolean,
)

data class CallInquiryDto(
    val id: Long,
    val createdAt: LocalDateTime?,
    val site: String?,
    val locale: String?,
    val yachtLabel: String?,
    val dateFrom: LocalDate?,
    val dateTo: LocalDate?,
    val email: String?,
    val phone: String?,
    val phoneE164: String?,
    val message: String?,
    val details: Map<String, Any?>?,
    val contactConsent: Boolean,
    val consentTextVersion: String?,
    val doNotCall: Boolean,
    val offerSentAt: Instant?,
    val offerNote: String?,
    val humanTakeoverAt: Instant?,
)

data class CallDetailDto(
    val call: CallListItemDto,
    val data: Map<String, Any?>?,
    val transcript: List<Map<String, Any?>>?,
    val quality: Map<String, Any?>?,
    val terminationReason: String?,
    val costCredits: Int?,
    val conversationId: String?,
    val callSid: String?,
    val hasAudio: Boolean,
    val inquiry: CallInquiryDto,
    /** Every call row of the same inquiry, newest first (this one included). */
    val calls: List<CallListItemDto>,
)

data class CallInquiryListItemDto(
    val inquiry: CallInquiryDto,
    val lastCallStatus: CallStatus?,
    val lastOutcome: CallOutcome?,
    val nextScheduledAt: Instant?,
)

data class OfferSentRequest(
    val note: String? = null,
)

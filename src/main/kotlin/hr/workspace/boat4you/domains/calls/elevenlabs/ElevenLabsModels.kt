package hr.workspace.boat4you.domains.calls.elevenlabs

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming

/** The outbound-call response we keep. */
data class OutboundCallResult(
    val conversationId: String?,
    val callSid: String?,
)

/**
 * The `data` of a `post_call_transcription` webhook, the same shape `GET /v1/convai/conversations/{id}` returns (only the
 * fields the call center reads; everything else is ignored).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ConversationData(
    val conversationId: String? = null,
    val status: String? = null,
    val transcript: List<TranscriptTurn> = emptyList(),
    val metadata: ConversationMetadata? = null,
    val analysis: ConversationAnalysis? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class TranscriptTurn(
    val role: String? = null,
    val message: String? = null,
    val timeInCallSecs: Int? = null,
    val toolCalls: List<Map<String, Any?>>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ConversationMetadata(
    val callDurationSecs: Int? = null,
    val cost: Int? = null,
    val terminationReason: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ConversationAnalysis(
    val callSuccessful: String? = null,
    val transcriptSummary: String? = null,
    val dataCollectionResults: Map<String, DataCollectionResult>? = null,
    val evaluationCriteriaResults: Map<String, CriterionResult>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DataCollectionResult(
    val value: Any? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CriterionResult(
    val result: String? = null,
    val rationale: String? = null,
)

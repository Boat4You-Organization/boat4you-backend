package hr.workspace.boat4you.domains.calls.elevenlabs

/** The two ElevenLabs Agents calls the call center makes (design §5, §6.2). */
interface ElevenLabsApi {
    /**
     * Starts Nika's outbound call through the native Twilio integration; never retried (a retry could call twice). Throws
     * [CallNotStartedException] when the call was certainly not placed; any other exception leaves that unclear.
     */
    fun startOutboundCall(
        toNumber: String,
        language: String,
        variables: Map<String, String>,
    ): OutboundCallResult

    /** The finished (or running) conversation, for calls whose webhook never arrived. */
    fun getConversation(conversationId: String): ConversationData
}

/** ElevenLabs certainly did not place the call (it refused or rejected the request, or was never reached): safe to try again. */
class CallNotStartedException(
    message: String,
) : RuntimeException(message)

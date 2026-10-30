package hr.workspace.boat4you.domains.calls.elevenlabs

/** The two ElevenLabs Agents calls the call center makes (design §5, §6.2). */
interface ElevenLabsApi {
    /** Starts Nika's outbound call through the native Twilio integration; never retried (a retry could call twice). */
    fun startOutboundCall(
        toNumber: String,
        language: String,
        variables: Map<String, String>,
    ): OutboundCallResult

    /** The finished (or running) conversation, for calls whose webhook never arrived. */
    fun getConversation(conversationId: String): ConversationData
}

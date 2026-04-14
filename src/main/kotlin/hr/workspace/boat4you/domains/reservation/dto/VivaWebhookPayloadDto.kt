package hr.workspace.boat4you.domains.reservation.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class VivaWebhookPayloadDto(
    @get:JsonProperty("EventTypeId") val eventTypeId: Int? = null,
    @get:JsonProperty("EventData") val eventData: VivaWebhookEventDataDto? = null,
    @get:JsonProperty("RetryCount") val retryCount: Int? = null,
) {
    fun getReservationIdTag(): Long? =
        eventData
            ?.tags
            ?.firstOrNull { it.startsWith("reservationId:") }
            ?.substringAfter("reservationId:")
            ?.toLongOrNull()

    fun getReservationFlowIdTag(): Long? =
        eventData
            ?.tags
            ?.firstOrNull { it.startsWith("reservationFlowId:") }
            ?.substringAfter("reservationFlowId:")
            ?.toLongOrNull()

    fun getPayFullAmountTag(): Boolean? =
        eventData
            ?.tags
            ?.firstOrNull { it.startsWith("payFullAmount:") }
            ?.substringAfter("payFullAmount:")
            ?.toBoolean()

    fun getPaymentPhaseIdTag(): Long? =
        eventData
            ?.tags
            ?.firstOrNull { it.startsWith("paymentPhaseId:") }
            ?.substringAfter("paymentPhaseId:")
            ?.toLongOrNull()
}

data class VivaWebhookEventDataDto(
    @get:JsonProperty("OrderCode") val orderCode: String?,
    @get:JsonProperty("TransactionId") val transactionId: String?,
    @get:JsonProperty("Amount") val amount: Long?,
    @get:JsonProperty("MerchantTrns") val merchantTrns: String?,
    @get:JsonProperty("Tags") val tags: List<String>?,
    @get:JsonProperty("SourceCode") val sourceCode: String?,
)

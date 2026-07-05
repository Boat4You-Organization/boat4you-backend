package hr.workspace.boat4you.domains.reservation.dto

/**
 * PushSubscription payload from the /trip/{token} hub. `ownerKey` is the
 * OWNER participant's secret key — the backend verifies it before marking
 * the device as an owner device (which gates the amount-free installment
 * reminders). A bare boolean claim was spoofable (review 5.7).
 */
data class TripPushSubscribeRequest(
    val endpoint: String,
    val p256dh: String,
    val auth: String,
    val ownerKey: String? = null,
    val userAgent: String? = null,
) {
    fun isValid(): Boolean = endpoint.startsWith("https://") &&
        endpoint.length <= MAX_ENDPOINT_LENGTH &&
        p256dh.isNotBlank() && p256dh.length <= MAX_KEY_LENGTH &&
        auth.isNotBlank() && auth.length <= MAX_KEY_LENGTH

    companion object {
        private const val MAX_ENDPOINT_LENGTH = 2048
        private const val MAX_KEY_LENGTH = 255
    }
}

data class TripEventRequest(
    val type: String,
    val meta: String? = null,
)

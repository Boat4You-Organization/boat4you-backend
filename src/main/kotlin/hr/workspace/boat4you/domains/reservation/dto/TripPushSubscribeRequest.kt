package hr.workspace.boat4you.domains.reservation.dto

/**
 * PushSubscription payload from the /trip/{token} hub. `isOwner` is the
 * hub's claim that the subscribing browser also carried the booking owner's
 * authenticated web session — it only gates whether the (amount-free)
 * installment reminders reach the device, so a spoofed claim leaks nothing.
 */
data class TripPushSubscribeRequest(
    val endpoint: String,
    val p256dh: String,
    val auth: String,
    val isOwner: Boolean = false,
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

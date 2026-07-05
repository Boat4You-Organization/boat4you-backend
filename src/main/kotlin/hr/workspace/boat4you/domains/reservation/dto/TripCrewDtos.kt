package hr.workspace.boat4you.domains.reservation.dto

import java.time.Instant

/** Returned once on join/claim — the key is the device's secret. */
data class TripParticipantCredentialsDto(
    val participantId: Long,
    val participantKey: String,
    val name: String,
    val role: String,
)

data class TripParticipantDto(
    val id: Long,
    val name: String,
    val role: String,
    val removed: Boolean = false,
)

data class TripJoinRequest(
    val name: String,
)

data class TripChatMessageDto(
    val id: Long,
    val participantId: Long?,
    val senderName: String,
    val senderRole: String,
    val body: String,
    val createdAt: Instant,
)

data class TripChatPostRequest(
    val key: String,
    val body: String,
)

data class TripPhotoDto(
    val id: Long,
    val participantId: Long?,
    val uploaderName: String?,
    val marketingConsent: Boolean,
    val createdAt: Instant,
)

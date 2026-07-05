package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.reservation.dto.TripChatMessageDto
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.jpa.TripChatMessage
import hr.workspace.boat4you.domains.reservation.jpa.TripChatMessageRepository
import hr.workspace.boat4you.domains.reservation.jpa.TripParticipantRole
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime

/**
 * Trip group chat. PG rows are the truth, [TripChatStreamRegistry] delivers
 * live. Windows (locked 4.7.2026): writable from booking until dateTo+14
 * days, read-only afterwards (memory mode). Concierge posts (admin or
 * automation) also push-notify the crew's subscribed devices.
 */
@Service
@Transactional(readOnly = true)
class TripChatService(
    private val chatRepository: TripChatMessageRepository,
    private val reservationRepository: ReservationRepository,
    private val crewService: TripCrewService,
    private val streamRegistry: TripChatStreamRegistry,
    private val tripPushService: TripPushService,
    @Value("\${application.trip-push.web-base-url}") private val webBaseUrl: String,
) {
    @Transactional
    fun post(token: String, participantKey: String, rawBody: String): TripChatMessageDto? {
        val (reservation, participant) = crewService.findActiveParticipant(token, participantKey) ?: return null
        val body = rawBody.trim().take(MAX_BODY_LENGTH)
        if (body.isEmpty()) return null
        val readOnlyFrom = reservation.dateTo?.plusDays(READ_ONLY_AFTER_DAYS)
        if (readOnlyFrom != null && LocalDateTime.now().isAfter(readOnlyFrom)) return null
        val saved = chatRepository.save(
            TripChatMessage().apply {
                this.reservationId = reservation.id
                this.participantId = participant.id
                this.senderName = participant.name
                this.senderRole = participant.role.name
                this.body = body
            },
        )
        val dto = saved.toDto()
        streamRegistry.publish(reservation.id!!, dto)
        return dto
    }

    /**
     * Concierge post — admin console or scheduled automation. Also pushes to
     * every subscribed device unless [push] is off. [automationTag] makes
     * scheduled posts idempotent (skips when the tag already exists).
     */
    @Transactional
    fun postAsConcierge(
        reservationId: Long,
        body: String,
        automationTag: String? = null,
        push: Boolean = true,
    ): TripChatMessageDto? {
        if (automationTag != null && chatRepository.existsByReservationIdAndAutomationTag(reservationId, automationTag)) {
            return null
        }
        val reservation = reservationRepository.findById(reservationId).orElse(null) ?: return null
        val trimmed = body.trim().take(MAX_BODY_LENGTH)
        if (trimmed.isEmpty()) return null
        val saved = chatRepository.save(
            TripChatMessage().apply {
                this.reservationId = reservationId
                this.senderName = CONCIERGE_NAME
                this.senderRole = TripParticipantRole.CONCIERGE.name
                this.body = trimmed
                this.automationTag = automationTag
            },
        )
        val dto = saved.toDto()
        streamRegistry.publish(reservationId, dto)
        if (push) {
            reservation.tripToken?.let { token ->
                tripPushService.sendToReservation(
                    reservationId = reservationId,
                    title = "⚓ $CONCIERGE_NAME",
                    body = trimmed.take(PUSH_PREVIEW_LENGTH),
                    url = "$webBaseUrl/trip/$token?push=chat",
                    tag = "chat",
                )
            }
        }
        return dto
    }

    fun history(token: String, participantKey: String, sinceId: Long): List<TripChatMessageDto>? {
        val (reservation, _) = crewService.findActiveParticipant(token, participantKey) ?: return null
        return chatRepository.findTop200ByReservationIdAndIdGreaterThanOrderByIdAsc(reservation.id!!, sinceId)
            .map { it.toDto() }
    }

    fun subscribe(token: String, participantKey: String) =
        crewService.findActiveParticipant(token, participantKey)?.let { (reservation, _) ->
            streamRegistry.subscribe(reservation.id!!)
        }

    /** Admin view — full history + stamps the unread-badge marker. */
    @Transactional
    fun adminHistory(reservationId: Long): List<TripChatMessageDto> {
        reservationRepository.findById(reservationId).orElse(null)?.let {
            it.adminChatSeenAt = LocalDateTime.now()
            reservationRepository.save(it)
        }
        return chatRepository.findTop200ByReservationIdAndIdGreaterThanOrderByIdAsc(reservationId, 0)
            .map { it.toDto() }
    }

    private fun TripChatMessage.toDto() = TripChatMessageDto(
        id = id!!,
        participantId = participantId,
        senderName = senderName ?: "",
        senderRole = senderRole ?: "GUEST",
        body = body ?: "",
        createdAt = createdAt,
    )

    companion object {
        const val CONCIERGE_NAME = "Boat4You Concierge"
        private const val MAX_BODY_LENGTH = 2000
        private const val PUSH_PREVIEW_LENGTH = 120
        private const val READ_ONLY_AFTER_DAYS = 14L
    }
}

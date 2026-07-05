package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.reservation.dto.TripParticipantCredentialsDto
import hr.workspace.boat4you.domains.reservation.dto.TripParticipantDto
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.jpa.Reservation
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhaseRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.jpa.TripParticipant
import hr.workspace.boat4you.domains.reservation.jpa.TripParticipantRepository
import hr.workspace.boat4you.domains.reservation.jpa.TripParticipantRole
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/** Why a join attempt was refused — the hub explains each case differently. */
enum class TripJoinRefusal { NOT_FOUND, CANCELLED, FINISHED, LOCKED, FULL }

sealed class TripJoinResult {
    data class Joined(val credentials: TripParticipantCredentialsDto) : TripJoinResult()
    data class Refused(val reason: TripJoinRefusal) : TripJoinResult()
}

/**
 * The trip's closed group (Mario's locked rules, 4.7.2026): the link is the
 * group's credential, joining hands the device a secret participant key,
 * guests register with just a name, the crew invite unlocks only after the
 * first payment, and the leader (booking owner) or the admin can remove
 * members. Roles: OWNER / GUEST / SKIPPER (admin-assigned later) / CONCIERGE.
 */
@Service
@Transactional(readOnly = true)
class TripCrewService(
    private val reservationRepository: ReservationRepository,
    private val participantRepository: TripParticipantRepository,
    private val paymentPhaseRepository: ReservationPaymentPhaseRepository,
) {
    @Transactional
    fun join(token: String, rawName: String): TripJoinResult {
        val reservation = reservationRepository.findByTripToken(token)
            ?: return TripJoinResult.Refused(TripJoinRefusal.NOT_FOUND)
        val name = rawName.trim().take(MAX_NAME_LENGTH)
        if (name.length < MIN_NAME_LENGTH) return TripJoinResult.Refused(TripJoinRefusal.NOT_FOUND)
        if (reservation.sysStatus == ReservationStatus.CANCELLED) {
            return TripJoinResult.Refused(TripJoinRefusal.CANCELLED)
        }
        if (reservation.dateTo?.isBefore(LocalDateTime.now()) == true) {
            return TripJoinResult.Refused(TripJoinRefusal.FINISHED)
        }
        if (!inviteUnlocked(reservation)) return TripJoinResult.Refused(TripJoinRefusal.LOCKED)
        if (participantRepository.countByReservationIdAndRemovedFalse(reservation.id!!) >= MAX_ACTIVE_PARTICIPANTS) {
            return TripJoinResult.Refused(TripJoinRefusal.FULL)
        }
        val participant = participantRepository.save(
            newParticipant(reservation.id!!, name, TripParticipantRole.GUEST),
        )
        return TripJoinResult.Joined(participant.toCredentials())
    }

    /**
     * The booking owner enters through his authenticated web session — the
     * SSR page calls this with the JWT user id. Re-claims return the same
     * stored key so every owner device converges on one OWNER identity.
     */
    @Transactional
    fun claimOwner(token: String, userId: Long, fallbackName: String?): TripParticipantCredentialsDto? {
        val reservation = reservationRepository.findByTripToken(token) ?: return null
        if (reservation.reservationFlow?.user?.id != userId) {
            throw AccessDeniedException("Reservation does not belong to user $userId")
        }
        val existing = participantRepository.findFirstByReservationIdAndRoleAndRemovedFalse(
            reservation.id!!,
            TripParticipantRole.OWNER,
        )
        if (existing != null) return existing.toCredentials()
        val name = reservation.reservationFlow?.name?.trim()?.takeIf { it.isNotBlank() }
            ?: fallbackName?.trim()?.takeIf { it.isNotBlank() }
            ?: "Trip leader"
        val participant = participantRepository.save(
            newParticipant(reservation.id!!, name.take(MAX_NAME_LENGTH), TripParticipantRole.OWNER),
        )
        return participant.toCredentials()
    }

    /** Resolve an active participant of THIS trip, or null. */
    fun findActiveParticipant(token: String, participantKey: String): Pair<Reservation, TripParticipant>? {
        val reservation = reservationRepository.findByTripToken(token) ?: return null
        val participant = participantRepository.findByParticipantKey(participantKey) ?: return null
        if (participant.reservationId != reservation.id || participant.removed) return null
        return reservation to participant
    }

    fun listActive(token: String, participantKey: String): List<TripParticipantDto>? {
        val (reservation, _) = findActiveParticipant(token, participantKey) ?: return null
        return participantRepository.findAllByReservationIdOrderByCreatedAtAsc(reservation.id!!)
            .filter { !it.removed }
            .map { it.toDto() }
    }

    /** Leader removes a crew member (never himself/the owner). */
    @Transactional
    fun removeByOwner(token: String, ownerKey: String, participantId: Long): Boolean {
        val (reservation, requester) = findActiveParticipant(token, ownerKey) ?: return false
        if (requester.role != TripParticipantRole.OWNER) return false
        return removeInternal(reservation.id!!, participantId)
    }

    @Transactional
    fun removeByAdmin(reservationId: Long, participantId: Long): Boolean = removeInternal(reservationId, participantId)

    fun listForAdmin(reservationId: Long): List<TripParticipantDto> =
        participantRepository.findAllByReservationIdOrderByCreatedAtAsc(reservationId).map { it.toDto() }

    fun inviteUnlocked(reservation: Reservation): Boolean {
        if (reservation.sysStatus == ReservationStatus.RESERVATION) return true
        val flowId = reservation.reservationFlow?.id ?: return false
        return paymentPhaseRepository.findByReservationFlowIdIn(setOf(flowId)).any { it.paidOn != null }
    }

    private fun removeInternal(reservationId: Long, participantId: Long): Boolean {
        val target = participantRepository.findById(participantId).orElse(null) ?: return false
        if (target.reservationId != reservationId) return false
        if (target.role == TripParticipantRole.OWNER) return false
        target.removed = true
        participantRepository.save(target)
        return true
    }

    private fun newParticipant(reservationId: Long, name: String, role: TripParticipantRole) =
        TripParticipant().apply {
            this.reservationId = reservationId
            this.participantKey = UUID.randomUUID().toString().replace("-", "")
            this.name = name
            this.role = role
        }

    private fun TripParticipant.toCredentials() = TripParticipantCredentialsDto(
        participantId = id!!,
        participantKey = participantKey!!,
        name = name!!,
        role = role.name,
    )

    private fun TripParticipant.toDto() = TripParticipantDto(
        id = id!!,
        name = name!!,
        role = role.name,
        removed = removed,
    )

    companion object {
        private const val MAX_ACTIVE_PARTICIPANTS = 20L
        private const val MAX_NAME_LENGTH = 60
        private const val MIN_NAME_LENGTH = 2
    }
}

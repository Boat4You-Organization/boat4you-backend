package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.mmk.client.MmkRetryableClient
import hr.workspace.boat4you.domains.external.nausys.client.NauSysRetryableClient
import hr.workspace.boat4you.domains.external.nausys.config.NauSysAuthProvider
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMappingRepository
import hr.workspace.boat4you.domains.reservation.dto.YachtSwapInfoDto
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.jpa.Reservation
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationYachtSwapAudit
import hr.workspace.boat4you.domains.reservation.jpa.ReservationYachtSwapAuditRepository
import hr.workspace.boat4you.domains.reservation.jpa.YachtSwapAction
import org.openapitools.client.nausys.model.RestYachtReservationsRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime

@Service
class ReservationSyncService(
    private val reservationRepository: ReservationRepository,
    private val externalMappingRepository: ExternalMappingRepository,
    private val nauSysRetryableClient: NauSysRetryableClient,
    private val nauSysAuthProvider: NauSysAuthProvider,
    private val mmkRetryableClient: MmkRetryableClient,
    private val reservationYachtSwapAuditRepository: ReservationYachtSwapAuditRepository,
    private val reservationEmailService: ReservationEmailService,
    private val yachtRepository: YachtRepository,
    @Value("\${application.reservation-sync.auto-update:true}")
    private val autoUpdateEnabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(ReservationSyncService::class.java)

    private val activeStatuses =
        listOf(ReservationStatus.OPTION, ReservationStatus.RESERVATION, ReservationStatus.OPTION_WAITING)

    fun syncActiveReservations() {
        val cutoff = LocalDateTime.now().minusDays(CUTOFF_DAYS_BEFORE_CHARTER_START)
        val reservations = reservationRepository.findActiveForPartnerSync(activeStatuses, cutoff)
        log.info("Starting yacht-swap sync for ${reservations.size} active reservations")

        var checked = 0
        var skipped = 0
        var swapsDetected = 0
        var errors = 0

        for (reservation in reservations) {
            try {
                val result = processReservation(reservation)
                when (result) {
                    ProcessResult.CHECKED -> checked++
                    ProcessResult.SKIPPED -> skipped++
                    ProcessResult.SWAP_DETECTED -> swapsDetected++
                }
            } catch (e: Exception) {
                errors++
                log.warn(
                    "Yacht-swap sync failed for reservation id=${reservation.id} number=${reservation.reservationNumber}: ${e.message}",
                )
            }
        }

        log.info(
            "Yacht-swap sync complete: checked=$checked skipped=$skipped swaps=$swapsDetected errors=$errors",
        )
    }

    @Transactional
    internal fun processReservation(reservation: Reservation): ProcessResult {
        val flow = reservation.reservationFlow ?: return ProcessResult.SKIPPED
        val localYacht = flow.yacht ?: return ProcessResult.SKIPPED
        val agency = localYacht.agency ?: return ProcessResult.SKIPPED
        val externalId = reservation.externalId ?: return ProcessResult.SKIPPED

        val externalSystem = agency.primarySource?.externalSystem ?: return ProcessResult.SKIPPED
        val partnerYachtId =
            when (externalSystem.id) {
                ExternalSystemEnum.NAUSYS.value -> fetchNausysYachtId(externalId)
                ExternalSystemEnum.MMK.value -> fetchMmkYachtId(externalId)
                else -> null
            } ?: return ProcessResult.SKIPPED

        val localMapping =
            externalMappingRepository.findBySystemIdAndExternalSystemAndType(
                localYacht.id!!,
                externalSystem,
                Yacht::class.simpleName.toString(),
            )
        val localExternalYachtId = localMapping?.externalId

        if (localExternalYachtId == null) {
            log.warn(
                "Missing yacht external mapping for reservation id=${reservation.id} yacht_id=${localYacht.id} — cannot compare",
            )
            return ProcessResult.SKIPPED
        }

        if (partnerYachtId == localExternalYachtId) {
            return ProcessResult.CHECKED
        }

        val newInternalYachtId =
            externalMappingRepository.findByExternalIdAndExternalSystemAndType(
                partnerYachtId,
                externalSystem,
                Yacht::class.simpleName.toString(),
            )?.systemId

        log.warn(
            "DETECTED YACHT SWAP: reservation_id=${reservation.id} number=${reservation.reservationNumber} " +
                "agency=${agency.id} old_yacht_id=${localYacht.id} old_external=$localExternalYachtId " +
                "new_external=$partnerYachtId new_internal_yacht_id=$newInternalYachtId " +
                "external_system=${externalSystem.name}",
        )

        val action =
            if (!autoUpdateEnabled) {
                YachtSwapAction.LOGGED_ONLY
            } else if (newInternalYachtId == null) {
                YachtSwapAction.MANUAL_REVIEW
            } else {
                YachtSwapAction.AUTO_UPDATED
            }

        val audit =
            ReservationYachtSwapAudit().apply {
                this.reservationId = reservation.id
                this.reservationFlowId = flow.id
                this.previousYachtId = localYacht.id
                this.previousExternalYachtId = localExternalYachtId
                this.newYachtId = newInternalYachtId
                this.newExternalYachtId = partnerYachtId
                this.externalSystemId = externalSystem.id
                this.detectedAt = Instant.now()
                this.action = action
                this.notes =
                    if (action == YachtSwapAction.MANUAL_REVIEW) {
                        "Partner yacht has no local mapping — catalogue sync required before auto-update"
                    } else {
                        null
                    }
            }
        reservationYachtSwapAuditRepository.save(audit)

        if (action == YachtSwapAction.AUTO_UPDATED && newInternalYachtId != null) {
            applyYachtSwap(reservation, flow.id!!, newInternalYachtId)
            safelySendNotifications(reservation, audit)
        }

        return ProcessResult.SWAP_DETECTED
    }

    private fun applyYachtSwap(
        reservation: Reservation,
        reservationFlowId: Long,
        newInternalYachtId: Long,
    ) {
        val updated =
            reservationRepository.updateYachtOnSwap(
                reservationFlowId = reservationFlowId,
                newYachtId = newInternalYachtId,
            )
        log.info(
            "Applied yacht swap: reservation_flow_id=$reservationFlowId rows_updated=$updated new_yacht_id=$newInternalYachtId",
        )
    }

    private fun safelySendNotifications(
        reservation: Reservation,
        audit: ReservationYachtSwapAudit,
    ) {
        try {
            reservationEmailService.sendYachtSwapNotification(reservation, audit)
        } catch (e: Exception) {
            log.warn(
                "Yacht swap email failed for reservation id=${reservation.id}: ${e.message}",
            )
        }
    }

    private fun fetchNausysYachtId(externalReservationId: Long): Long? {
        val request =
            RestYachtReservationsRequest(
                credentials = nauSysAuthProvider.auth,
                reservations = listOf(externalReservationId),
            )
        val response = nauSysRetryableClient.getReservation(request)
        return response.yachtId
    }

    private fun fetchMmkYachtId(externalReservationId: Long): Long? {
        val response = mmkRetryableClient.getReservation(externalReservationId)
        return response.yachtId
    }

    internal enum class ProcessResult {
        CHECKED,
        SKIPPED,
        SWAP_DETECTED,
    }

    @Transactional(readOnly = true)
    fun getLatestSwap(
        reservationId: Long,
        unacknowledgedOnly: Boolean,
    ): YachtSwapInfoDto? {
        val audit =
            if (unacknowledgedOnly) {
                reservationYachtSwapAuditRepository.findFirstByReservationIdAndAcknowledgedAtIsNullOrderByDetectedAtDesc(reservationId)
            } else {
                reservationYachtSwapAuditRepository.findAllByReservationIdOrderByDetectedAtDesc(reservationId).firstOrNull()
            } ?: return null
        return toDto(audit)
    }

    @Transactional
    fun acknowledgeLatestSwap(reservationId: Long): Boolean {
        val audit =
            reservationYachtSwapAuditRepository.findFirstByReservationIdAndAcknowledgedAtIsNullOrderByDetectedAtDesc(reservationId)
                ?: return false
        audit.acknowledgedAt = Instant.now()
        reservationYachtSwapAuditRepository.save(audit)
        return true
    }

    private fun toDto(audit: ReservationYachtSwapAudit): YachtSwapInfoDto {
        val previousYachtName = audit.previousYachtId?.let { yachtRepository.findById(it).orElse(null)?.name }
        val newYachtName = audit.newYachtId?.let { yachtRepository.findById(it).orElse(null)?.name }
        return YachtSwapInfoDto(
            detectedAt = audit.detectedAt!!,
            previousYachtId = audit.previousYachtId!!,
            previousYachtName = previousYachtName,
            newYachtId = audit.newYachtId,
            newYachtName = newYachtName,
            action = audit.action!!,
            acknowledged = audit.acknowledgedAt != null,
            notes = audit.notes,
        )
    }

    companion object {
        private const val CUTOFF_DAYS_BEFORE_CHARTER_START = 7L
    }
}

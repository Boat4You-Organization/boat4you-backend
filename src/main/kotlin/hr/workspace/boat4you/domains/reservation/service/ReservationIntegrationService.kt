package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.catalouge.enums.ExtrasType
import hr.workspace.boat4you.domains.catalouge.exceptions.YachtDoesNotExistException
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtra
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.mmk.service.MmkReservationIntegrationService
import hr.workspace.boat4you.domains.external.model.ReservationData
import hr.workspace.boat4you.domains.external.nausys.service.NausysReservationIntegrationService
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMappingRepository
import hr.workspace.boat4you.domains.reservation.exceptions.ReservationFlowNotExists
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlow
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlowRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.model.ReservationResponseWrapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.LocalTime

@Service
@Transactional(readOnly = true)
class ReservationIntegrationService(
    private val externalMappingRepository: ExternalMappingRepository,
    private val mmkReservationIntegrationService: MmkReservationIntegrationService,
    private val nausysReservationIntegrationService: NausysReservationIntegrationService,
    private val reservationRepository: ReservationRepository,
    private val reservationFlowRepository: ReservationFlowRepository,
    @Value("\${application.test.enabled}")
    private val testModeEnabled: Boolean,
) {
    private fun checkTestMode(agency: Agency) {
        if (testModeEnabled) {
            if (!(agency.id == 9000L || agency.id == 9001L)) {
                throw IllegalStateException("Operation not allowed in test mode for agency ${agency.name}")
            }
        }
    }

    fun createExternalReservation(reservationFlowId: Long): ReservationResponseWrapper {
        val reservationFlow =
            reservationFlowRepository
                .findById(reservationFlowId)
                .orElseThrow { ReservationFlowNotExists() }
        val yacht = reservationFlow.yacht!!
        val externalSystem = reservationFlow.yacht!!.agency!!.primarySource!!.externalSystem!!
        val externalYachtId =
            externalMappingRepository.findBySystemIdAndExternalSystemAndType(
                yacht.id!!,
                externalSystem,
                Yacht::class.simpleName.toString(),
            ) ?: throw YachtDoesNotExistException()
        val offer = reservationFlow.offer!!

        // TODO remove
        checkTestMode(yacht.agency!!)

        if (offer.yacht!!.id != yacht.id) {
            throw IllegalArgumentException("Offer does not belong to the selected yacht")
        }

        val reservationExtras = getSelectedYachtExtras(reservationFlow, externalSystem)

        val startDateTime = LocalDateTime.of(offer.dateFrom, LocalTime.MIN)
        val endDateTime = LocalDateTime.of(offer.dateTo, LocalTime.MAX)
        val reservationData =
            ReservationData(
                startDate = startDateTime,
                endDate = endDateTime,
                externalYachtId = externalYachtId.externalId!!,
                externalAgencyId = yacht.agency!!.getExternalId()!!,
                name = reservationFlow.name!!,
                surname = reservationFlow.surname!!,
                selectedServices = reservationExtras,
                selectedEquipment = emptyList(),
            )
        val externalReservation =
            when (externalSystem.id!!) {
                ExternalSystemEnum.MMK.value -> {
                    mmkReservationIntegrationService.createOption(reservationData)
                }

                ExternalSystemEnum.NAUSYS.value -> {
                    nausysReservationIntegrationService.createOption(reservationData)
                }

                else -> {
                    throw RuntimeException()
                }
            }

        return externalReservation
    }

    fun confirmExternalReservation(reservationId: Long): ReservationResponseWrapper {
        val reservation = reservationRepository.findById(reservationId).orElseThrow()
        val externalSystem = reservation.reservationFlow!!.yacht!!.agency!!.primarySource!!.externalSystem!!

        // TODO remove
        checkTestMode(reservation.reservationFlow!!.yacht!!.agency!!)

        return when (externalSystem.id!!) {
            ExternalSystemEnum.MMK.value -> {
                mmkReservationIntegrationService.confirmReservation(
                    reservation.externalId!!,
                )
            }

            ExternalSystemEnum.NAUSYS.value -> {
                nausysReservationIntegrationService.confirmReservation(
                    reservation.externalId!!,
                    reservation.externalReservationCode!!,
                )
            }

            else -> {
                throw RuntimeException()
            }
        }
    }

    fun deleteExternalReservation(reservationId: Long): ReservationResponseWrapper {
        val reservation = reservationRepository.findById(reservationId).orElseThrow()
        val externalSystem = reservation.reservationFlow!!.yacht!!.agency!!.primarySource!!.externalSystem!!

        // TODO remove
        checkTestMode(reservation.reservationFlow!!.yacht!!.agency!!)

        return when (externalSystem.id!!) {
            ExternalSystemEnum.MMK.value -> {
                mmkReservationIntegrationService.cancelOption(
                    reservation.externalId!!,
                )
            }

            ExternalSystemEnum.NAUSYS.value -> {
                nausysReservationIntegrationService.cancelOption(
                    reservation.externalId!!,
                    reservation.externalReservationCode!!,
                )
            }

            else -> {
                throw RuntimeException()
            }
        }
    }

    fun getExternalReservation(reservationId: Long): ReservationResponseWrapper {
        val reservation = reservationRepository.findById(reservationId).orElseThrow()
        val externalSystem = reservation.reservationFlow!!.yacht!!.agency!!.primarySource!!.externalSystem!!

        return when (externalSystem.id!!) {
            ExternalSystemEnum.MMK.value -> {
                mmkReservationIntegrationService.getReservation(
                    reservation.externalId!!,
                )
            }

            ExternalSystemEnum.NAUSYS.value -> {
                nausysReservationIntegrationService.getReservation(
                    reservation.externalId!!,
                )
            }

            else -> {
                throw RuntimeException()
            }
        }
    }

    fun getExternalReservation(
        externalSystem: ExternalSystemEnum,
        externalReservationId: Long,
    ): ReservationResponseWrapper {
        return when (externalSystem) {
            ExternalSystemEnum.MMK -> {
                mmkReservationIntegrationService.getReservation(
                    externalReservationId,
                )
            }

            ExternalSystemEnum.NAUSYS -> {
                nausysReservationIntegrationService.getReservation(
                    externalReservationId,
                )
            }

            else -> {
                throw RuntimeException()
            }
        }
    }

    private fun getSelectedYachtExtras(
        reservationFlow: ReservationFlow,
        externalSystem: ExternalSystem,
    ): List<Long> {
        return when (externalSystem.id!!) {
            ExternalSystemEnum.MMK.value -> {
                emptyList()
            }

            ExternalSystemEnum.NAUSYS.value -> {
                reservationFlow.reservationExtras.map { it.externalId!! }
            }

            else -> {
                throw RuntimeException("Unsupported external system: ${externalSystem.id}")
            }
        }
    }
}

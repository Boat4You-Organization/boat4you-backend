package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservation
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping.Companion.RESERVATION_YACHT_EXTERNAL_MAPPING_KEY
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping.Companion.YACHT_AGENCY_EXTERNAL_MAPPING_KEY
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMappingRepository
import org.openapitools.client.mmk.model.AvailabilityResponse
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class MmkAvailabilitySyncService(
    private val externalSystemService: ExternalSystemService,
    private val externalMappingRepository: ExternalMappingRepository,
    private val externalMappingService: ExternalMappingService,
    private val yachtRepository: YachtRepository,
    private val externalReservationRepository: ExternalReservationRepository,
    private val offersRepository: OfferRepository,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    @Transactional
    fun syncYachtAvailability(
        agencyId: Long,
        mmkResponse: List<AvailabilityResponse>,
    ) {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.MMK.value.toLong())
        val yachtMappings =
            externalMappingService.getAllMappingsByTypeAndExtendedType(
                Yacht::class.simpleName.toString(),
                externalSystem,
                YACHT_AGENCY_EXTERNAL_MAPPING_KEY + agencyId,
            )
        val agencyYachts = yachtRepository.findAllByAgencyId(agencyId)

        mmkResponse.forEach { mmkReservation ->
            val yacht =
                getYacht(
                    yachtMappings,
                    agencyYachts,
                    mmkReservation,
                ) ?: return@forEach

            val yachtReservations = externalReservationRepository.findAllByYacht(yacht!!)

            val externalReservationMappings =
                externalMappingService.getAllMappingsByTypeAndExtendedType(
                    ExternalReservation::class.simpleName.toString(),
                    externalSystem,
                    RESERVATION_YACHT_EXTERNAL_MAPPING_KEY + yacht!!.id,
                )
            val reservationMapping =
                externalReservationMappings.find { mapping -> mapping.externalId == mmkReservation.id!! }
//            val existingYachtOffers =
//                offersRepository.findAllAvailableByYacht(yacht!!, setOf(OfferStatus.FREE, OfferStatus.OPTION, OfferStatus.OPTION_WAITING))

            val externalReservation =
                if (reservationMapping != null) {
                    val res = yachtReservations.find { reservation -> reservation.id == reservationMapping.systemId }
                    if (res == null && mmkReservation.dateTo?.value?.isBefore(LocalDateTime.now()) == true) {
                        return@forEach
                    }
                    res ?: ExternalReservation()
                } else {
                    ExternalReservation()
                }

            updateReservation(externalReservation, mmkReservation, yacht)
//            updateOffer(existingYachtOffers, externalReservation) TODO: Implement offer update logic

            if (reservationMapping == null) {
                externalMappingRepository.save(
                    ExternalMapping(
                        externalId = mmkReservation.id!!,
                        externalSystem = externalSystem,
                        systemId = externalReservation.id!!,
                        type = ExternalReservation::class.simpleName.toString(),
                        extendedType = RESERVATION_YACHT_EXTERNAL_MAPPING_KEY + yacht.id,
                    ),
                )
            }
        }
    }

    private fun updateReservation(
        externalReservation: ExternalReservation,
        mmkReservation: AvailabilityResponse,
        yacht: Yacht,
    ) {
        externalReservation.yacht = yacht
        externalReservation.dateFrom = mmkReservation.dateFrom?.value?.toLocalDate()
        externalReservation.dateTo = mmkReservation.dateTo?.value?.toLocalDate()
        externalReservation.status = ExternalReservationStatus.fromMmkValue(mmkReservation.status)
        externalReservation.optionExpiration = mmkReservation.optionExpirationDate?.value
        externalReservationRepository.saveAndFlush(externalReservation)
    }

    private fun getYacht(
        yachtMappings: List<ExternalMapping>,
        agencyYachts: List<Yacht>,
        mmkReservation: AvailabilityResponse,
    ): Yacht? {
        val yachtMapping = yachtMappings.find { mapping -> mapping.externalId == mmkReservation.yachtId!! }
        return agencyYachts.find { yacht -> yacht.id == yachtMapping?.systemId }
    }
}

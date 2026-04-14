package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservation
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
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
import org.openapitools.client.nausys.model.RestYachtReservationOccupancy
import org.openapitools.client.nausys.model.RestYachtReservationOccupancyList
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

@Service
class NauSysAvailabilitySyncService(
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
        nausysResponse: RestYachtReservationOccupancyList,
    ) {
        val externalSystem = externalSystemService.findById(ExternalSystemEnum.NAUSYS.value.toLong())
        val yachtMappings =
            externalMappingService.getAllMappingsByTypeAndExtendedType(
                Yacht::class.simpleName.toString(),
                externalSystem,
                YACHT_AGENCY_EXTERNAL_MAPPING_KEY + agencyId,
            )
        val agencyYachts = yachtRepository.findAllByAgencyId(agencyId)

        nausysResponse.reservations?.forEach { nausysReservation ->
            val yacht =
                getYacht(
                    yachtMappings,
                    agencyYachts,
                    nausysReservation,
                ) ?: return@forEach

            val yachtReservations = externalReservationRepository.findAllByYacht(yacht!!)

            val externalReservationMappings =
                externalMappingService.getAllMappingsByTypeAndExtendedType(
                    ExternalReservation::class.simpleName.toString(),
                    externalSystem,
                    RESERVATION_YACHT_EXTERNAL_MAPPING_KEY + yacht!!.id,
                )
            val reservationMapping =
                externalReservationMappings.find { mapping -> mapping.externalId == nausysReservation.id!! }
            val existingYachtOffers =
                offersRepository.findAllAvailableByYacht(yacht!!, setOf(OfferStatus.FREE, OfferStatus.OPTION))

            val externalReservation =
                if (reservationMapping != null) {
                    val res = yachtReservations.find { reservation -> reservation.id == reservationMapping.systemId }
                    if (res == null && nausysReservation.periodTo?.value?.isBefore(LocalDate.now()) == true) {
                        return@forEach
                    }
                    res ?: ExternalReservation()
                } else {
                    ExternalReservation()
                }

            updateReservation(externalReservation, nausysReservation, yacht)
            updateOffer(existingYachtOffers, externalReservation)

            if (reservationMapping == null) {
                externalMappingRepository.save(
                    ExternalMapping(
                        externalId = nausysReservation.id!!,
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
        nausysReservation: RestYachtReservationOccupancy,
        yacht: Yacht,
    ) {
        externalReservation.yacht = yacht
        externalReservation.dateFrom = nausysReservation.periodFrom?.value
        externalReservation.dateTo = nausysReservation.periodTo?.value
        externalReservation.status = ExternalReservationStatus.fromNausysValue(nausysReservation.reservationType)
        externalReservation.optionExpiration = nausysReservation.optionValidTill?.value
        externalReservationRepository.saveAndFlush(externalReservation)
    }

    private fun updateOffer(
        existingYachtOffers: List<Offer>,
        externalReservation: ExternalReservation,
    ) {
        if (existingYachtOffers.isNotEmpty()) {
            existingYachtOffers.forEach { offer ->
                if (offer.dateFrom == externalReservation.dateFrom && offer.dateTo == externalReservation.dateTo) {
                    offer.status = OfferStatus.UNAVAILABLE
                    offersRepository.save(offer)
                    log.warn("Offer with id ${offer.id} is set to UNAVAILABLE")
                }
            }
        }
    }

    private fun getYacht(
        yachtMappings: List<ExternalMapping>,
        agencyYachts: List<Yacht>,
        nausysReservation: RestYachtReservationOccupancy,
    ): Yacht? {
        val yachtMapping = yachtMappings.find { mapping -> mapping.externalId == nausysReservation.yachtId!! }
        return agencyYachts.find { yacht -> yacht.id == yachtMapping?.systemId }
    }
}

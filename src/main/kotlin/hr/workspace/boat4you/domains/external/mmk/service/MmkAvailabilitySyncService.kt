package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.enums.OfferType
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
import org.openapitools.client.mmk.model.AvailabilityResponse
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

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

    companion object {
        /** Mirrors Nausys's marker so the offer sync cleanup can recognize
         * synthetic OPTION rows created from MMK availability and leave them alone. */
        const val SYNTHETIC_OPTION_EXT_STATUS = "SYNTHETIC_OPTION"
    }

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
            val existingYachtOffers =
                offersRepository.findAllAvailableByYacht(yacht!!, setOf(OfferStatus.FREE, OfferStatus.OPTION))

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
            updateOffer(existingYachtOffers, externalReservation, yacht)

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

    /**
     * Reconciles `offer` rows with what MMK's /availability endpoint says about this yacht.
     * The MMK /offers endpoint (used by the offer sync) only returns weekly Sat-Sat offers,
     * so options held by other agents on shifted weekdays (Mon-Mon, Thu-Thu, …) never get
     * an offer row. Here we synthesise one from the closest FREE template so the listing
     * can surface those yachts with a "pre-reserved / closest day" badge.
     *
     * Mirrors [NauSysAvailabilitySyncService.updateOffer].
     */
    private fun updateOffer(
        existingYachtOffers: List<Offer>,
        externalReservation: ExternalReservation,
        yacht: Yacht,
    ) {
        val dateFrom = externalReservation.dateFrom ?: return
        val dateTo = externalReservation.dateTo ?: return

        val matchingOffers = offersRepository.findAllByYachtAndDateFromAndDateTo(yacht, dateFrom, dateTo)

        when (externalReservation.status) {
            ExternalReservationStatus.OPTION -> {
                if (matchingOffers.isNotEmpty()) {
                    matchingOffers.forEach { offer ->
                        if (offer.status != OfferStatus.OPTION) {
                            offer.status = OfferStatus.OPTION
                            offersRepository.save(offer)
                            log.info(
                                "MMK offer ${offer.id} (yacht ${yacht.id} $dateFrom→$dateTo) flipped to OPTION " +
                                    "from external_reservation ${externalReservation.id}",
                            )
                        }
                    }
                } else {
                    synthesizeOptionOffer(yacht, externalReservation, existingYachtOffers)
                }
            }

            ExternalReservationStatus.RESERVATION, ExternalReservationStatus.SERVICE -> {
                // Overlap, NOT exact-date match: a reservation blocks EVERY week it touches —
                // including multi-week and non-Saturday-aligned bookings that exact matching
                // silently left bookable, the over-availability bug.
                offersRepository.findAllByYachtAndDateRangeOverlap(yacht, dateFrom, dateTo).forEach { offer ->
                    if (offer.status != OfferStatus.UNAVAILABLE) {
                        offer.status = OfferStatus.UNAVAILABLE
                        offersRepository.save(offer)
                        log.info(
                            "MMK offer ${offer.id} (yacht ${yacht.id} ${offer.dateFrom}→${offer.dateTo}) set to " +
                                "UNAVAILABLE (overlaps ${externalReservation.status} $dateFrom→$dateTo)",
                        )
                    }
                }
            }

            ExternalReservationStatus.FREE, ExternalReservationStatus.UNKNOWN, null -> {
                // no-op: offer sync owns FREE; UNKNOWN = unparseable
            }
        }
    }

    /**
     * Mirrors [NauSysAvailabilitySyncService.synthesizeOptionOffer] but for MMK inputs.
     * Finds the closest FREE offer on the same yacht (prefers same duration, then nearest
     * dateFrom) and clones its price/location fields into a new OPTION offer marked with
     * [SYNTHETIC_OPTION_EXT_STATUS] so the offer sync cleanup leaves it alone.
     */
    private fun synthesizeOptionOffer(
        yacht: Yacht,
        externalReservation: ExternalReservation,
        existingYachtOffers: List<Offer>,
    ) {
        val dateFrom = externalReservation.dateFrom!!
        val dateTo = externalReservation.dateTo!!
        val targetDuration = ChronoUnit.DAYS.between(dateFrom, dateTo)

        val template = existingYachtOffers
            .filter { it.status == OfferStatus.FREE && it.dateFrom != null && it.dateTo != null }
            .minByOrNull { offer ->
                val offerDuration = ChronoUnit.DAYS.between(offer.dateFrom, offer.dateTo)
                val durationPenalty = abs(offerDuration - targetDuration) * 1000
                val datePenalty = abs(ChronoUnit.DAYS.between(offer.dateFrom, dateFrom))
                durationPenalty + datePenalty
            }

        if (template == null) {
            log.warn(
                "Cannot synthesize MMK OPTION offer for yacht ${yacht.id} $dateFrom→$dateTo: " +
                    "no FREE template offer exists. Yacht will not surface in search for this week.",
            )
            return
        }

        val synthetic =
            Offer().apply {
                this.yacht = yacht
                this.locationFrom = template.locationFrom
                this.locationTo = template.locationTo
                this.dateFrom = dateFrom
                this.dateTo = dateTo
                this.checkin = template.checkin
                this.checkout = template.checkout
                this.type = OfferType.getFromDates(dateFrom, dateTo)
                this.product = template.product
                this.status = OfferStatus.OPTION
                this.extStatus = SYNTHETIC_OPTION_EXT_STATUS
                this.clientPrice = template.clientPrice
                this.totalPrice = template.totalPrice
                this.deposit = template.deposit
                this.depositInsured = template.depositInsured
                this.obligatoryExtrasPrice = template.obligatoryExtrasPrice
                this.totalDiscount = template.totalDiscount
                this.extBasePrice = template.extBasePrice
                this.extClientPrice = template.extClientPrice
                this.extTotalPrice = template.extTotalPrice
                this.extTotalDiscount = template.extTotalDiscount
                this.extDiscountPerc = template.extDiscountPerc
                this.agencyCommission = template.agencyCommission
            }
        offersRepository.save(synthetic)
        log.info(
            "Synthesized MMK SYNTHETIC_OPTION offer for yacht ${yacht.id} $dateFrom→$dateTo " +
                "(template offer ${template.id}, external_reservation ${externalReservation.id})",
        )
    }
}

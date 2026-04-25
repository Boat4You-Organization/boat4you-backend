package hr.workspace.boat4you.domains.external.nausys.service

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
import org.openapitools.client.nausys.model.RestYachtReservationOccupancy
import org.openapitools.client.nausys.model.RestYachtReservationOccupancyList
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

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

    companion object {
        /** Marks offer rows that were created by availability sync as a stand-in for an external
         * agent's option. Offer sync cleanup must skip rows with this marker, otherwise it would
         * flip them back to UNAVAILABLE on the next pass (NauSys doesn't return them via
         * getFreeYachts). */
        const val SYNTHETIC_OPTION_EXT_STATUS = "SYNTHETIC_OPTION"
    }

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
            updateOffer(existingYachtOffers, externalReservation, yacht)

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

    /**
     * Reconciles `offer` rows against reservations that came from `getOccupancyByYear`.
     *
     * `getFreeYachts` (used by the offer sync) silently omits yachts that are under an option
     * held by another agent, so without this reconciliation those weeks would either never get
     * an offer row or would be flipped to UNAVAILABLE by the offer sync cleanup — making the
     * yacht disappear from search even though we want to surface it with a pre-reserved badge.
     *
     * Rules:
     *  - OPTION  → ensure an offer row with status=OPTION exists (synthesize from a FREE template
     *              if one doesn't); the frontend renders it as "pre-reserved".
     *  - RESERVATION / SERVICE → matching offers → UNAVAILABLE (boat truly not bookable).
     *  - FREE / UNKNOWN → no-op; offer sync is the source of truth for available weeks.
     */
    private fun updateOffer(
        existingYachtOffers: List<Offer>,
        externalReservation: ExternalReservation,
        yacht: Yacht,
    ) {
        val dateFrom = externalReservation.dateFrom ?: return
        val dateTo = externalReservation.dateTo ?: return

        // Fetch ALL offers for these exact dates regardless of status — covers stale UNAVAILABLE rows
        // that existingYachtOffers (which only carries FREE + OPTION) would miss.
        val matchingOffers = offersRepository.findAllByYachtAndDateFromAndDateTo(yacht, dateFrom, dateTo)

        when (externalReservation.status) {
            ExternalReservationStatus.OPTION -> {
                if (matchingOffers.isNotEmpty()) {
                    matchingOffers.forEach { offer ->
                        if (offer.status != OfferStatus.OPTION) {
                            offer.status = OfferStatus.OPTION
                            offersRepository.save(offer)
                            log.info(
                                "Offer ${offer.id} (yacht ${yacht.id} $dateFrom→$dateTo) flipped to OPTION " +
                                    "from external_reservation ${externalReservation.id}",
                            )
                        }
                    }
                } else {
                    synthesizeOptionOffer(yacht, externalReservation, existingYachtOffers)
                }
            }

            ExternalReservationStatus.RESERVATION, ExternalReservationStatus.SERVICE -> {
                matchingOffers.forEach { offer ->
                    if (offer.status != OfferStatus.UNAVAILABLE) {
                        offer.status = OfferStatus.UNAVAILABLE
                        offersRepository.save(offer)
                        log.info(
                            "Offer ${offer.id} (yacht ${yacht.id} $dateFrom→$dateTo) set to UNAVAILABLE " +
                                "(${externalReservation.status})",
                        )
                    }
                }
            }

            ExternalReservationStatus.FREE, ExternalReservationStatus.UNKNOWN, null -> {
                // no-op: offer sync owns FREE state; UNKNOWN means we can't interpret the signal
            }
        }
    }

    /**
     * Creates a synthetic offer row so a yacht under another agent's option still surfaces in search.
     *
     * NauSys's `getOccupancyByYear` gives us only (yacht, dateFrom, dateTo, status, optionExpiration)
     * — no price, no locations, no checkin. To build a valid offer row we copy those fields from the
     * closest matching FREE offer on the same yacht, preferring the same duration and then the nearest
     * dateFrom. The offer is tagged with extStatus=SYNTHETIC_OPTION so the offer sync cleanup can skip it.
     */
    private fun synthesizeOptionOffer(
        yacht: Yacht,
        externalReservation: ExternalReservation,
        existingYachtOffers: List<Offer>,
    ) {
        val dateFrom = externalReservation.dateFrom!!
        val dateTo = externalReservation.dateTo!!
        val targetDuration = ChronoUnit.DAYS.between(dateFrom, dateTo)

        // existingYachtOffers is filtered to FREE + OPTION by the caller; only FREE rows are valid templates
        val template = existingYachtOffers
            .filter { it.status == OfferStatus.FREE && it.dateFrom != null && it.dateTo != null }
            .minByOrNull { offer ->
                val offerDuration = ChronoUnit.DAYS.between(offer.dateFrom, offer.dateTo)
                // Heavy weight on duration mismatch so a 7-day template wins over a 14-day one
                val durationPenalty = abs(offerDuration - targetDuration) * 1000
                val datePenalty = abs(ChronoUnit.DAYS.between(offer.dateFrom, dateFrom))
                durationPenalty + datePenalty
            }

        if (template == null) {
            log.warn(
                "Cannot synthesize OPTION offer for yacht ${yacht.id} $dateFrom→$dateTo: " +
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
            "Synthesized SYNTHETIC_OPTION offer for yacht ${yacht.id} $dateFrom→$dateTo " +
                "(template offer ${template.id}, external_reservation ${externalReservation.id})",
        )
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

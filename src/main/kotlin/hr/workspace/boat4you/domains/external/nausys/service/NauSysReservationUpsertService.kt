package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.enums.OfferType
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservation
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping.Companion.RESERVATION_YACHT_EXTERNAL_MAPPING_KEY
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMappingRepository
import org.openapitools.client.nausys.model.RestYachtReservationOccupancy
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * One SHORT transaction per reservation for the NauSYS availability sync.
 *
 * Extracted from [NauSysAvailabilitySyncService] so the per-(agency,year) sync no longer holds a
 * single DB connection for many minutes (see [MmkReservationUpsertService] for the full rationale
 * — a 54-min hold once blocked a deploy's ALTER TABLE). Separate bean because Spring
 * `@Transactional` only applies through the proxy (a self-call would not open a new tx).
 *
 * INVARIANT: the yacht is RE-LOADED here by id so it is managed inside this transaction — never
 * pass a detached `Yacht` across the boundary (the orchestrator runs with no open session).
 */
@Service
class NauSysReservationUpsertService(
    private val externalMappingRepository: ExternalMappingRepository,
    private val externalMappingService: ExternalMappingService,
    private val externalReservationRepository: ExternalReservationRepository,
    private val offersRepository: OfferRepository,
    private val yachtRepository: YachtRepository,
) {
    private val log: Logger = LoggerFactory.getLogger(this.javaClass)

    @Transactional
    fun upsertReservation(
        externalSystem: ExternalSystem,
        yachtId: Long,
        nausysReservation: RestYachtReservationOccupancy,
    ) {
        val yacht = yachtRepository.findById(yachtId).orElse(null) ?: return

        val yachtReservations = externalReservationRepository.findAllByYacht(yacht)

        val externalReservationMappings =
            externalMappingService.getAllMappingsByTypeAndExtendedType(
                ExternalReservation::class.simpleName.toString(),
                externalSystem,
                RESERVATION_YACHT_EXTERNAL_MAPPING_KEY + yacht.id,
            )
        val reservationMapping =
            externalReservationMappings.find { mapping -> mapping.externalId == nausysReservation.id!! }
        val existingYachtOffers =
            offersRepository.findAllAvailableByYacht(yacht, setOf(OfferStatus.FREE, OfferStatus.OPTION))

        val externalReservation =
            if (reservationMapping != null) {
                val res = yachtReservations.find { reservation -> reservation.id == reservationMapping.systemId }
                if (res == null && nausysReservation.periodTo?.value?.isBefore(LocalDate.now()) == true) {
                    return
                }
                res ?: ExternalReservation()
            } else {
                ExternalReservation()
            }

        updateReservation(externalReservation, nausysReservation, yacht)
        updateOffer(existingYachtOffers, externalReservation, yacht)

        if (reservationMapping == null) {
            val reservationType = ExternalReservation::class.simpleName.toString()
            val partnerReservationId = nausysReservation.id!!
            val newReservationId = externalReservation.id!!
            val existing =
                externalMappingRepository.findAllByExternalIdAndExternalSystemAndType(
                    partnerReservationId,
                    externalSystem,
                    reservationType,
                )
            if (existing.isEmpty()) {
                externalMappingRepository.save(
                    ExternalMapping(
                        externalId = partnerReservationId,
                        externalSystem = externalSystem,
                        systemId = newReservationId,
                        type = reservationType,
                        extendedType = RESERVATION_YACHT_EXTERNAL_MAPPING_KEY + yacht.id,
                    ),
                )
            } else {
                // This partner id is already mapped to another reservation (a different yacht / stale
                // twin — the Vi La Ut defect). Repoint ONE mapping onto the row we just wrote and drop
                // the extra duplicate mappings: prevents a NEW duplicate WITHOUT deleting any
                // reservation (the now-mapping-less stale row is cleared safely by the natural-key
                // reconcile in its own agency's sync). Also drains the legacy duplicate-mapping backlog.
                val keep = existing.first()
                keep.systemId = newReservationId
                keep.extendedType = RESERVATION_YACHT_EXTERNAL_MAPPING_KEY + yacht.id
                externalMappingRepository.save(keep)
                existing.drop(1).forEach { externalMappingRepository.delete(it) }
                log.warn(
                    "Repointed reservation mapping ext=$partnerReservationId to yacht ${yacht.id} " +
                        "res $newReservationId; dropped ${existing.size - 1} duplicate mapping(s)",
                )
            }
        }
    }

    private fun updateReservation(
        externalReservation: ExternalReservation,
        nausysReservation: RestYachtReservationOccupancy,
        yacht: Yacht,
    ) {
        val status = ExternalReservationStatus.fromNausysValue(nausysReservation.reservationType)
        externalReservation.yacht = yacht
        externalReservation.dateFrom = nausysReservation.periodFrom?.value
        externalReservation.dateTo = nausysReservation.periodTo?.value
        externalReservation.status = status
        // Clamp to OPTION only: NauSys keeps optionValidTill populated after an OPTION is confirmed
        // into a RESERVATION, so copying it unconditionally was the ROOT CAUSE of zombie RESERVATION
        // rows (see clampOptionExpiration).
        externalReservation.optionExpiration = status.clampOptionExpiration(nausysReservation.optionValidTill?.value)
        externalReservationRepository.saveAndFlush(externalReservation)
    }

    /**
     * Reconciles `offer` rows against reservations that came from `getOccupancyByYear`.
     *  - OPTION  → ensure an offer row with status=OPTION exists (synthesize from a FREE template).
     *  - RESERVATION / SERVICE → overlapping offers → UNAVAILABLE.
     *  - FREE / UNKNOWN → no-op; offer sync owns available weeks.
     */
    private fun updateOffer(
        existingYachtOffers: List<Offer>,
        externalReservation: ExternalReservation,
        yacht: Yacht,
    ) {
        val dateFrom = externalReservation.dateFrom ?: return
        val dateTo = externalReservation.dateTo ?: return

        when (externalReservation.status) {
            ExternalReservationStatus.OPTION -> {
                // Overlap-aware (A3): flip EVERY overlapping FREE offer week to OPTION, else a
                // 6-day option leaves the overlapping 7-day offer falsely FREE. Synthesize a visible
                // OPTION row only when NO offer overlaps at all.
                val overlapping = offersRepository.findAllByYachtAndDateRangeOverlap(yacht, dateFrom, dateTo)
                if (overlapping.isEmpty()) {
                    synthesizeOptionOffer(yacht, externalReservation, existingYachtOffers)
                } else {
                    overlapping.filter { it.status == OfferStatus.FREE }.forEach { offer ->
                        offer.status = OfferStatus.OPTION
                        offersRepository.save(offer)
                        log.info(
                            "Offer ${offer.id} (yacht ${yacht.id} ${offer.dateFrom}→${offer.dateTo}) flipped to " +
                                "OPTION (overlaps OPTION $dateFrom→$dateTo, external_reservation ${externalReservation.id})",
                        )
                    }
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
                            "Offer ${offer.id} (yacht ${yacht.id} ${offer.dateFrom}→${offer.dateTo}) set to " +
                                "UNAVAILABLE (overlaps ${externalReservation.status} $dateFrom→$dateTo)",
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
     * Copies price/location/checkin from the closest FREE offer on the same yacht (prefer same
     * duration, then nearest dateFrom). Tagged extStatus=SYNTHETIC_OPTION so offer-sync cleanup skips it.
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
                this.extStatus = NauSysAvailabilitySyncService.SYNTHETIC_OPTION_EXT_STATUS
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
}

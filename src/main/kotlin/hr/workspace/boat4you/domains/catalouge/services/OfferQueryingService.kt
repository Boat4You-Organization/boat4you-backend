package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.OfferDto
import hr.workspace.boat4you.domains.catalouge.dto.PriceCalcDto
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus
import hr.workspace.boat4you.domains.catalouge.enums.OfferType
import hr.workspace.boat4you.domains.catalouge.exceptions.AgencyNotActiveException
import hr.workspace.boat4you.domains.catalouge.exceptions.YachtDoesNotExistException
import hr.workspace.boat4you.domains.catalouge.exceptions.YachtNotActiveException
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservation
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.mapper.OfferMapper
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.security.ANONYMOUS_USER_ID
import hr.workspace.boat4you.security.getAuthenticatedUserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

@Service
@Transactional(readOnly = true)
class OfferQueryingService(
    private val yachtRepository: YachtRepository,
    private val offerRepository: OfferRepository,
    private val offerMapper: OfferMapper,
    private val userRepository: UserRepository,
    private val priceCalculationService: PriceCalculationService,
    private val externalReservationRepository: ExternalReservationRepository,
) {
    fun getYachtStandardOffers(
        yachtId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        currency: CurrencyEnum?,
    ): List<OfferDto> {
        getValidYacht(yachtId) // Validates yacht existence and active status

        val offers =
            offerRepository.findOffersByYachtIdAndDateFromAndDateToAndOfferType(
                yachtId,
                dateFrom,
                dateTo,
                OfferType.STANDARD,
            )

        // Live options (optionExpiration > now) overlapping the window — used to
        // demote stale OPTION offer rows to FREE. The finder is half-open
        // (date_from < end AND date_to > start) so a turnaround day never counts.
        val liveOptions =
            externalReservationRepository.findOptionsByYachtIdsAndPeriod(
                listOf(yachtId),
                ExternalReservationStatus.OPTION,
                dateFrom,
                dateTo,
            )

        val mappedOffers =
            offers.map { offer ->
                val hasLiveOption =
                    liveOptions.any { r -> halfOpenOverlap(offer.dateFrom, offer.dateTo, r.dateFrom, r.dateTo) }
                offerMapper.toDto(offer, currency, hasLiveOption)
            }

        // Gap-fill (7.6.2026): a reserved week that never got a priced offer row
        // is absent from `offers`, leaving a hole in the detail calendar (a week
        // booked before the partner ever published an offer for it — MMK /offers
        // only returns bookable weeks). Surface each RESERVATION/SERVICE interval
        // that NO offer overlaps as a non-clickable "booked" cell so the strip
        // reads continuously. Uses the REAL reservation intervals (no synthetic
        // Sat-Sat scaffold), so non-Sat-Sat fleets stay honest.
        val offerIntervals = offers.mapNotNull { o -> o.dateFrom?.let { f -> o.dateTo?.let { t -> f to t } } }
        val bookedGaps =
            externalReservationRepository
                .findYachtAvailabilityByYear(yachtId, dateFrom, dateTo)
                .filter {
                    it.status == ExternalReservationStatus.RESERVATION ||
                        it.status == ExternalReservationStatus.SERVICE
                }
                .filter { it.dateFrom != null && it.dateTo != null }
                .filter { res ->
                    offerIntervals.none { (oFrom, oTo) -> halfOpenOverlap(oFrom, oTo, res.dateFrom, res.dateTo) }
                }
                .distinctBy { it.dateFrom to it.dateTo }
                .map { res -> toBookedReservationDto(res, offers) }

        return mappedOffers + bookedGaps
    }

    /**
     * Non-clickable "booked" cell for a reserved interval that has no priced
     * offer row (gap-fill, 7.6.2026). Borrows the nearest offer's weekly price so
     * it renders a greyed price like the offer-backed booked weeks rather than a
     * "0 €"; keyed by its dates on the FE (id = null).
     */
    private fun toBookedReservationDto(
        res: ExternalReservation,
        offers: List<Offer>,
    ): OfferDto {
        val from = res.dateFrom!!
        val to = res.dateTo!!
        val nearestPrice =
            offers
                .filter { it.dateFrom != null && it.clientPrice != null }
                .minByOrNull { abs(ChronoUnit.DAYS.between(it.dateFrom, from)) }
                ?.clientPrice
        val status =
            if (res.status == ExternalReservationStatus.SERVICE) {
                ExternalReservationStatus.SERVICE
            } else {
                ExternalReservationStatus.RESERVATION
            }
        return OfferDto(
            id = null,
            dateFrom = from,
            dateTo = to,
            clientPriceEur = nearestPrice,
            status = status,
            locationFrom = null,
            locationTo = null,
            checkin = null,
            checkout = null,
            clientPricePerDayEur = BigDecimal.ZERO,
            clientPricePerDayInfo = null,
            numberOfDays = ChronoUnit.DAYS.between(from, to).toShort(),
            listPriceEur = null,
        )
    }

    fun getYachtOffers(
        yachtId: Long,
        dateFrom: LocalDate,
        dateTo: LocalDate,
        currency: CurrencyEnum?,
    ): List<OfferDto> {
        val yacht = getValidYacht(yachtId)

        val offers = offerRepository.findAllByYachtAndDateFromAndDateTo(yacht, dateFrom, dateTo)

        val user =
            getAuthenticatedUserId()
                .takeIf { it != ANONYMOUS_USER_ID }
                ?.let { userRepository.findById(it).orElse(null) }

        return offers.map { offerMapper.toDto(it, currency) }
    }

    fun getPriceForOfferWithExtras(
        yachtId: Long,
        offerId: Long,
        currency: CurrencyEnum?,
        selectedExtras: Set<String>,
    ): PriceCalcDto {
        getValidYacht(yachtId) // Validates yacht existence and active status
        val offer = offerRepository.findByIdWithEagerLoad(offerId) ?: throw YachtDoesNotExistException()
        if (offer.yacht?.id != yachtId) {
            throw YachtDoesNotExistException()
        }

        val yacht = yachtRepository.findById(yachtId).orElseThrow { YachtDoesNotExistException() }

        return priceCalculationService.calculatePrice(yacht, offer, currency, selectedExtras)
    }

    private fun getValidYacht(yachtId: Long): Yacht {
        val yacht =
            yachtRepository
                .findById(yachtId)
                .orElseThrow { YachtDoesNotExistException() }

        if (!yacht.sysActive!!) {
            throw YachtNotActiveException()
        }

        val agency = yacht.agency
        if (yacht.entryType == EntryType.EXTERNAL && (agency == null || !agency.active!!)) {
            throw AgencyNotActiveException()
        }

        return yacht
    }
}

/**
 * Half-open interval overlap (turnaround-safe): does [aFrom, aTo) intersect
 * [bFrom, bTo)? A charter ending the morning the next begins (aTo == bFrom) is
 * NOT a conflict. Null-safe: a missing bound never overlaps.
 */
internal fun halfOpenOverlap(
    aFrom: LocalDate?,
    aTo: LocalDate?,
    bFrom: LocalDate?,
    bTo: LocalDate?,
): Boolean =
    aFrom != null && aTo != null && bFrom != null && bTo != null &&
        aFrom.isBefore(bTo) && aTo.isAfter(bFrom)

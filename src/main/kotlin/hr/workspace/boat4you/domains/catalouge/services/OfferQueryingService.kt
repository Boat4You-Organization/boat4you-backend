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
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.mapper.OfferMapper
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import hr.workspace.boat4you.security.ANONYMOUS_USER_ID
import hr.workspace.boat4you.security.getAuthenticatedUserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

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

        return offers.map { offer ->
            val hasLiveOption =
                liveOptions.any { r ->
                    // Half-open overlap of THIS offer's exact period with a live option row.
                    val oFrom = offer.dateFrom
                    val oTo = offer.dateTo
                    val rFrom = r.dateFrom
                    val rTo = r.dateTo
                    oFrom != null && oTo != null && rFrom != null && rTo != null &&
                        rFrom.isBefore(oTo) && rTo.isAfter(oFrom)
                }
            offerMapper.toDto(offer, currency, hasLiveOption)
        }
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

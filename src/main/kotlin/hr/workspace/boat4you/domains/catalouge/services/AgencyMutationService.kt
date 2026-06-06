package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.services.PriceCalculations
import hr.workspace.boat4you.domains.catalouge.dto.AgencyDto
import hr.workspace.boat4you.domains.catalouge.dto.AgencyYachtDto
import hr.workspace.boat4you.domains.catalouge.exceptions.AgencyDoesNotExistException
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import kotlin.jvm.optionals.getOrElse

@Service
@Transactional
class AgencyMutationService(
    private val agencyRepository: AgencyRepository,
    private val yachtRepository: YachtRepository,
    private val offerRepository: OfferRepository,
    private val searchViewRefreshService: SearchViewRefreshService,
) {
    fun updateAgency(
        id: Long,
        agency: AgencyDto,
    ): AgencyDto {
        val dbAgency = agencyRepository.findById(id).getOrElse { throw AgencyDoesNotExistException() }

        dbAgency.apply {
            updateBlockWithModel(agency)
        }

        val dto = agencyRepository.save(dbAgency).toDto()
        // Agency mutation may change discount/name/active and thus the listing
        // matview rows for every yacht of this agency — schedule a refresh.
        searchViewRefreshService.requestRefresh()
        return dto
    }

    fun toggleActive(
        id: Long,
        isActive: Boolean,
    ): AgencyDto {
        val dbAgency = agencyRepository.findById(id).getOrElse { throw AgencyDoesNotExistException() }

        dbAgency.active = isActive

        val dto = agencyRepository.save(dbAgency).toDto()
        searchViewRefreshService.requestRefresh()
        return dto
    }

    fun updateYachtsDiscount(
        agencyId: Long,
        yachtDtos: List<AgencyYachtDto>,
    ) {
        val dbAgency = agencyRepository.findById(agencyId).getOrElse { throw AgencyDoesNotExistException() }

        // Not optimized as its used only in an admin panel
        val allYachts = yachtRepository.findAllByAgencyId(dbAgency.id!!)
        allYachts.forEach { yacht ->
            val dto = yachtDtos.firstOrNull { it.id == yacht.id }
            if (dto != null) {
                yacht.excludeDiscount = dto.excludeDiscount
            } else {
                return@forEach
            }
            yachtRepository.save(yacht)
        }
        searchViewRefreshService.requestRefresh()
    }

    /**
     * Recalculate offer.client_price + agency_commission + total_price for every existing
     * offer of the agency, using the current agency.discount and yacht.exclude_discount flags.
     *
     * Why: changing agency.discount in admin only updates the field on Agency. Existing offers
     * keep their persisted client_price (snapshotted in MMK/NauSys YachtOfferSyncService at last
     * sync). Stale offer rows whose (yacht_id, date_from, date_to, location_from, location_to)
     * key no longer matches partner output are never overwritten by sync — only this manual
     * trigger or the 30-day expiry cleanup can fix them.
     *
     * Returns the number of offers whose client_price actually changed.
     */
    fun recalculatePricesForAgency(agencyId: Long): Int {
        val agency = agencyRepository.findById(agencyId).getOrElse { throw AgencyDoesNotExistException() }
        val discount = agency.getDiscountOrZero()
        val offers = offerRepository.findAllByYachtAgencyId(agencyId)
        var updated = 0
        offers.forEach { offer ->
            // Mirror the sync EXACTLY (NauSysYachtOfferSyncService): the agency discount
            // applies to the boat client price (extClientPrice, after partner OWN discounts),
            // NEVER to the total-with-extras. Using extTotalPrice here double-counted obligatory
            // extras and inflated the client price vs what the next sync produces (audit B4).
            val extClientPrice = offer.extClientPrice ?: return@forEach
            val applyDiscount = offer.yacht?.excludeDiscount != true
            val newClientPrice = PriceCalculations.calculateClientPrice(extClientPrice, discount, applyDiscount)
            if (offer.clientPrice?.compareTo(newClientPrice) != 0) {
                offer.clientPrice = newClientPrice
                offer.agencyCommission = extClientPrice.minus(newClientPrice)
                offer.totalPrice = newClientPrice + (offer.obligatoryExtrasPrice ?: BigDecimal.ZERO)
                offerRepository.save(offer)
                updated++
            }
        }
        // Recalc rewrites offer.client_price for every offer of the agency —
        // the listing matview must catch up so admins/end-users see the new
        // prices without waiting for the 2-min cron tick.
        searchViewRefreshService.requestRefresh()
        return updated
    }
}

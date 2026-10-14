package hr.workspace.boat4you.domains.catalouge.mapper

import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.services.ExchangeRateCalculationService
import hr.workspace.boat4you.domains.catalouge.services.SearchSwitchesTestSupport
import hr.workspace.boat4you.domains.exchange.jpa.ExchangeRateRepository
import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * A2 (SEARCH_DEMOTE_STALE_OPTIONS, DESIGN.md WS-A test 11) on the offer DTO that `/standard-offers` and getYacht return:
 * default true = today's demotion of an OPTION / OPTION_WAITING without a live hold to FREE; false = the stored status.
 */
class OfferMapperStaleOptionTest {
    private val settings = SearchSwitchesTestSupport.SearchSwitches()
    private val exchangeRates = ExchangeRateCalculationService(mock(ExchangeRateRepository::class.java))
    private val mapper = OfferMapper(exchangeRates, YachtExtrasMapper(exchangeRates), settings.switches)

    private fun offer(status: OfferStatus) =
        Offer().apply {
            this.status = status
            dateFrom = LocalDate.of(2031, 7, 5)
            dateTo = LocalDate.of(2031, 7, 12)
            clientPrice = BigDecimal("2800")
        }

    private fun statusOf(
        status: OfferStatus,
        hasLiveOption: Boolean,
    ) = mapper.toDto(offer(status), CurrencyEnum.EUR, hasLiveOption).status

    @Test
    fun `default - a stale option reads FREE, a live one OPTION (today)`() {
        assertEquals(ExternalReservationStatus.FREE, statusOf(OfferStatus.OPTION, hasLiveOption = false))
        assertEquals(ExternalReservationStatus.FREE, statusOf(OfferStatus.OPTION_WAITING, hasLiveOption = false))
        assertEquals(ExternalReservationStatus.OPTION, statusOf(OfferStatus.OPTION, hasLiveOption = true))
    }

    @Test
    fun `switch false - the stored option is kept with or without a live hold, nothing else changes`() {
        settings.set(SettingsKeyEnum.SEARCH_DEMOTE_STALE_OPTIONS, "false")
        assertEquals(ExternalReservationStatus.OPTION, statusOf(OfferStatus.OPTION, hasLiveOption = false))
        assertEquals(ExternalReservationStatus.OPTION, statusOf(OfferStatus.OPTION_WAITING, hasLiveOption = false))
        assertEquals(ExternalReservationStatus.OPTION, statusOf(OfferStatus.OPTION, hasLiveOption = true))
        assertEquals(ExternalReservationStatus.FREE, statusOf(OfferStatus.FREE, hasLiveOption = false))
        assertEquals(ExternalReservationStatus.RESERVATION, statusOf(OfferStatus.RESERVED, hasLiveOption = false))
    }
}

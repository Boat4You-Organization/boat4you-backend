package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.common.config.RealPriceSwitches.OnOff
import hr.workspace.boat4you.domains.catalouge.jpa.AgencyRepository
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.external.config.SyncConfigurationProperties
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.evidence.PartnerPeriodCheckStore
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckPriority
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckResult
import hr.workspace.boat4you.domains.external.mmk.client.MmkRetryableClient
import hr.workspace.boat4you.domains.external.mmk.model.MmkDateTimeWrapper
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.openapitools.client.mmk.model.Flexibility
import org.openapitools.client.mmk.model.Offer
import org.springframework.transaction.PlatformTransactionManager
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Real-price B1 path 4, MMK half (DESIGN.md WS-B): the per-yacht on-demand warm asks with `showOptions = true`, and
 * with MMK_ONDEMAND_STRIKE=ON an exact-date (`flexibility = 1`) empty answer for a period we show is a first strike
 * plus bookkeeping, never a status write; a `flexibility = 6` answer is never evidence.
 */
class MmkOnDemandStrikeTests {
    private companion object {
        const val SHOW_OPTIONS = 21 // position of showOptions in MmkRetryableClient.getOffers
    }

    private val from = LocalDate.of(2027, 7, 3)
    private val to = from.plusDays(7)
    private val ext = 4711L

    private val getOffersArgs = CopyOnWriteArrayList<List<Any?>>()
    private var answer: List<Offer> = emptyList()
    private val client =
        mock(MmkRetryableClient::class.java) { inv ->
            if (inv.method.name != "getOffers") return@mock null
            getOffersArgs += inv.arguments.toList()
            answer
        }
    private val utils = mock(MmkOfferIntegrationUtils::class.java)
    private val switches = mock(RealPriceSwitches::class.java)
    private val offers = mock(OfferRepository::class.java)
    private val reverify = mock(MmkFreeOfferReverifyService::class.java)
    private val checks = mock(PartnerPeriodCheckStore::class.java)
    private val sync = mock(MmkYachtOfferSyncService::class.java)

    private val service =
        MmkYachtOfferIntegrationService(
            mock(AgencyRepository::class.java),
            SyncConfigurationProperties(offerMaxYears = 1, minDurationDays = 3),
            sync,
            mock(MmkYachtOfferIntegrationServiceAsync::class.java),
            utils,
            client,
            switches,
            offers,
            reverify,
            checks,
            mock(PlatformTransactionManager::class.java),
        )

    private fun given(
        flexibility: Flexibility,
        strike: OnOff,
    ) {
        `when`(utils.getFlexibility(from, to)).thenReturn(flexibility)
        `when`(switches.mmkOnDemandStrike).thenReturn(strike)
        `when`(offers.findYachtIdsShowingMmkPeriod(ExternalSystemEnum.MMK.value.toLong(), ext, from, to)).thenReturn(listOf(12210L))
    }

    @Test
    fun `the on-demand call sends showOptions = true`() {
        given(Flexibility._1, OnOff.OFF)

        service.syncOffersForYachtIdAndDateRage(ext, from, to)

        // getOffers(dateFrom, dateTo, flexibility, companyId, country, productName, baseFromId, baseToId, sailingAreaId,
        //           yachtId, modelId, currency, tripDuration, minCabins, ..., showOptions, ...)
        val args = getOffersArgs.single()
        (args[0] as MmkDateTimeWrapper).value!!.toLocalDate() shouldBe from
        args[2] shouldBe Flexibility._1
        args[9] shouldBe listOf(ext)
        args[SHOW_OPTIONS] shouldBe true
    }

    @Test
    fun `switch ON, exact dates, empty answer and a shown row - first strike plus bookkeeping, no status write`() {
        given(Flexibility._1, OnOff.ON)

        service.syncOffersForYachtIdAndDateRage(ext, from, to)

        verify(reverify).recordFirstStrike(12210L, from, to, LocalDate.now())
        verify(checks).recordChecked(
            ExternalSystemEnum.MMK,
            12210L,
            from,
            to,
            PeriodCheckPriority.ON_DEMAND_OR_BOOKING,
            PeriodCheckResult.EMPTY,
            false,
            LocalDate.now(),
        )
        // the only repository call is the read; nothing from this request path writes an offer status
        verify(offers).findYachtIdsShowingMmkPeriod(ExternalSystemEnum.MMK.value.toLong(), ext, from, to)
        verifyNoMoreInteractions(offers)
        verifyNoInteractions(sync)
    }

    @Test
    fun `a flexibility 6 answer is never evidence`() {
        given(Flexibility._6, OnOff.ON)

        service.syncOffersForYachtIdAndDateRage(ext, from, to)

        verifyNoInteractions(reverify, checks, offers)
    }

    @Test
    fun `switch OFF (default) records nothing`() {
        given(Flexibility._1, OnOff.OFF)

        service.syncOffersForYachtIdAndDateRage(ext, from, to)

        verifyNoInteractions(reverify, checks, offers)
    }

    @Test
    fun `a quoted answer is synced as today and never struck`() {
        given(Flexibility._1, OnOff.ON)
        answer = listOf(mock(Offer::class.java))

        service.syncOffersForYachtIdAndDateRage(ext, from, to)

        verify(sync).syncOffers(answer)
        verifyNoInteractions(reverify, checks, offers)
    }

    @Test
    fun `a period we do not show records nothing`() {
        given(Flexibility._1, OnOff.ON)
        `when`(offers.findYachtIdsShowingMmkPeriod(ExternalSystemEnum.MMK.value.toLong(), ext, from, to)).thenReturn(emptyList())

        service.syncOffersForYachtIdAndDateRage(ext, from, to)

        verifyNoInteractions(reverify, checks)
    }
}

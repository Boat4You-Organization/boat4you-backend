package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.CountryRepository
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtDetailRepository
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtViewRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalBaseRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservation
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.LocationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.RegionRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtraRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtTranslationRepository
import hr.workspace.boat4you.domains.catalouge.mapper.OfferMapper
import hr.workspace.boat4you.domains.catalouge.mapper.YachtMapper
import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManager
import org.mockito.Mockito
import org.mockito.Mockito.mock
import java.time.LocalDate
import java.util.Optional
import kotlin.test.Test

/**
 * WS-C path 11 on the boat page: getYacht passes the live-option answer of [LiveOptionLookup] to OfferMapper (where
 * A2 keeps or demotes the stored OPTION) only with BOOKING_C1=ON; OFF keeps today's `false` without a query.
 */
class YachtGetYachtLiveOptionTest {
    private val yachtId = 12204L
    private val week1 = LocalDate.of(2027, 7, 3)
    private val week2 = week1.plusDays(7)

    private val settings = SearchSwitchesTestSupport.SearchSwitches()
    private val finderCalls = mutableListOf<Any>()
    private val mapped = mutableListOf<Pair<Long?, Boolean>>()

    private val yacht =
        Yacht().apply {
            id = yachtId
            sysActive = true
            entryType = EntryType.EXTERNAL
            agency = Agency().apply { active = true }
        }

    private val offers =
        listOf(offer(1, week1, OfferStatus.OPTION), offer(2, week2, OfferStatus.OPTION_WAITING))

    private val reservations: ExternalReservationRepository =
        mock(ExternalReservationRepository::class.java) { inv ->
            if (inv.method.name == "findOptionsByYachtIdsAndPeriod") {
                finderCalls += inv
                listOf(
                    ExternalReservation().apply {
                        dateFrom = week1
                        dateTo = week1.plusDays(7)
                        status = ExternalReservationStatus.OPTION
                    },
                )
            } else {
                Mockito.RETURNS_DEFAULTS.answer(inv)
            }
        }

    private val offerRepository: OfferRepository =
        mock(OfferRepository::class.java) { inv ->
            when (inv.method.name) {
                "findAllByYachtAndDateFromAndDateTo" -> offers.filter { it.dateFrom == inv.arguments[1] }
                "findAllByYachtAndDateFromGreaterThanEqualAndDateToLessThanEqual" -> offers
                else -> Mockito.RETURNS_DEFAULTS.answer(inv)
            }
        }

    /** Records (offer id, hasLiveOption); the null DTO ends getYacht right after the mapping, which is all we need. */
    private val offerMapper: OfferMapper =
        mock(OfferMapper::class.java) { inv ->
            if (inv.method.name == "toDto" && inv.arguments.size == 3) {
                mapped += (inv.arguments[0] as Offer).id to (inv.arguments[2] as Boolean)
            }
            null
        }

    private val service =
        YachtQueryingService(
            mock(EntityManager::class.java),
            mock(YachtRepository::class.java) { inv ->
                if (inv.method.name == "findById") Optional.of(yacht) else Mockito.RETURNS_DEFAULTS.answer(inv)
            },
            mock(LocationRepository::class.java),
            reservations,
            mock(YachtMapper::class.java),
            offerRepository,
            mock(CustomYachtViewRepository::class.java),
            mock(CustomYachtDetailRepository::class.java),
            mock(YachtTranslationRepository::class.java),
            offerMapper,
            mock(FileSystemService::class.java),
            mock(ExchangeRateCalculationService::class.java),
            mock(YachtExtraRepository::class.java),
            mock(ExternalBaseRepository::class.java),
            mock(RegionRepository::class.java),
            mock(CountryRepository::class.java),
            PassThroughHeavyQueries,
            settings.switches,
            LiveOptionLookup(reservations),
        )

    private fun offer(
        id: Long,
        from: LocalDate,
        status: OfferStatus,
    ) = Offer().apply {
        this.id = id
        dateFrom = from
        dateTo = from.plusDays(7)
        this.status = status
    }

    private fun load(dated: Boolean) {
        runCatching {
            if (dated) {
                service.getYacht(yachtId, week1, week1.plusDays(7), null, LanguageEnum.EN)
            } else {
                service.getYacht(yachtId, null, null, null, LanguageEnum.EN)
            }
        }
    }

    @Test
    fun `BOOKING_C1 OFF - every offer maps without a live option and no lookup runs`() {
        load(dated = false)
        mapped shouldBe listOf(1L to false, 2L to false)
        finderCalls.size shouldBe 0
    }

    @Test
    fun `BOOKING_C1 ON undated - only the week a live option holds is passed as live, one query`() {
        settings.set(SettingsKeyEnum.BOOKING_C1, "ON")
        load(dated = false)
        mapped shouldBe listOf(1L to true, 2L to false)
        finderCalls.size shouldBe 1
    }

    @Test
    fun `BOOKING_C1 ON dated - the requested week carries the live option`() {
        settings.set(SettingsKeyEnum.BOOKING_C1, "ON")
        load(dated = true)
        mapped shouldBe listOf(1L to true)
        finderCalls.size shouldBe 1
    }
}

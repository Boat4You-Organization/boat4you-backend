package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.OfferDto
import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus
import hr.workspace.boat4you.domains.catalouge.enums.OPTION_ECHO_GRACE_HOURS
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.enums.OfferType
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservation
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.mapper.OfferMapper
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional

/**
 * WS-C path 11: one live-option rule for the detail calendar (/standard-offers), the /offers booking panel and (via
 * the integration hint) getYacht. The repository is a mock that applies the same expiry cutoff the JPQL query does,
 * so "lapsed more than 48 h" is exercised end to end through the lookup.
 */
class LiveOptionLookupTests {
    private val yachtId = 12204L
    private val w1From = LocalDate.of(2027, 7, 3)
    private val w2From = w1From.plusDays(7)
    private val w3From = w1From.plusDays(14)
    private val now = LocalDateTime.of(2026, 10, 14, 12, 0)

    private val options = mutableListOf<ExternalReservation>()
    private val finderCalls = mutableListOf<InvocationOnMock>()

    @Suppress("UNCHECKED_CAST")
    private val repository: ExternalReservationRepository =
        Mockito.mock(ExternalReservationRepository::class.java) { inv ->
            when (inv.method.name) {
                "findOptionsByYachtIdsAndPeriod" -> {
                    finderCalls += inv
                    val from = inv.arguments[2] as LocalDate
                    val to = inv.arguments[3] as LocalDate
                    val cutoff = inv.arguments[4] as LocalDateTime
                    options.filter { r ->
                        r.status == inv.arguments[1] &&
                            (r.optionExpiration == null || r.optionExpiration!!.isAfter(cutoff)) &&
                            r.dateFrom!!.isBefore(to) && r.dateTo!!.isAfter(from)
                    }
                }
                "findYachtAvailabilityByYear" -> emptyList<ExternalReservation>()
                else -> Mockito.RETURNS_DEFAULTS.answer(inv)
            }
        }
    private val lookup = LiveOptionLookup(repository)

    private fun option(
        from: LocalDate,
        nights: Long = 7,
        expires: LocalDateTime? = null,
    ) = ExternalReservation().apply {
        dateFrom = from
        dateTo = from.plusDays(nights)
        status = ExternalReservationStatus.OPTION
        optionExpiration = expires
    }

    private fun week(
        id: Long,
        from: LocalDate,
    ) = Offer().apply {
        this.id = id
        dateFrom = from
        dateTo = from.plusDays(7)
        status = OfferStatus.OPTION
        clientPrice = BigDecimal("4250.00")
    }

    @Test
    fun `cutoff is now minus the 48 h echo grace and the finder gets exactly the window`() {
        lookup.optionCutoff(now) shouldBe now.minusHours(OPTION_ECHO_GRACE_HOURS)
        Duration.between(lookup.optionCutoff(now), now).toHours() shouldBe 48
        lookup.forYachtWindow(yachtId, w1From, w3From, now)
        val args = finderCalls.single().arguments.toList()
        args shouldBe listOf(listOf(yachtId), ExternalReservationStatus.OPTION, w1From, w3From, now.minusHours(48))
    }

    @Test
    fun `a live option holds its week, a turnaround neighbour does not, a lapsed one beyond 48 h does not`() {
        options += option(w1From) // no expiry: always live
        options += option(w3From, expires = now.minusHours(47)) // inside the grace
        val live = lookup.forYachtWindow(yachtId, w1From, w3From.plusDays(7), now)
        live(week(1, w1From)) shouldBe true
        live(week(2, w2From)) shouldBe false // option ends the morning this week starts
        live(week(3, w3From)) shouldBe true

        options.clear()
        options += option(w3From, expires = now.minusHours(49))
        lookup.forYachtWindow(yachtId, w1From, w3From.plusDays(7), now)(week(3, w3From)) shouldBe false
    }

    @Test
    fun `forOffers spans the offers' envelope and makes no query without offers`() {
        options += option(w3From.plusDays(3), nights = 7) // overlaps only the tail of week 3
        lookup.forOffers(yachtId, listOf(week(1, w1From), week(3, w3From)), w1From, w1From.plusDays(1), now)(week(3, w3From)) shouldBe true
        finderCalls.single().arguments[3] shouldBe w3From.plusDays(7)

        finderCalls.clear()
        lookup.forOffers(yachtId, emptyList(), w1From, w2From, now)(week(1, w1From)) shouldBe false
        finderCalls.shouldBeEmpty()
    }

    private val mapped = mutableListOf<Pair<Long, Boolean>>()

    /** OfferMapper stand-in: records the hasLiveOption it was given and maps it to the customer status. */
    private fun recordingMapper(): OfferMapper =
        Mockito.mock(OfferMapper::class.java) { inv ->
            if (inv.method.name == "toDto") {
                val o = inv.arguments[0] as Offer
                val live = inv.arguments[2] as Boolean
                mapped += o.id!! to live
                OfferDto(
                    id = o.id,
                    dateFrom = o.dateFrom,
                    dateTo = o.dateTo,
                    status = if (live) ExternalReservationStatus.OPTION else ExternalReservationStatus.FREE,
                    locationFrom = null,
                    locationTo = null,
                    checkin = null,
                    checkout = null,
                    clientPricePerDayEur = BigDecimal.ZERO,
                    clientPricePerDayInfo = null,
                    numberOfDays = 7,
                )
            } else {
                Mockito.RETURNS_DEFAULTS.answer(inv)
            }
        }

    private fun offerQueryingService(offers: List<Offer>): OfferQueryingService {
        val yacht =
            Yacht().apply {
                id = yachtId
                sysActive = true
            }
        val yachts: YachtRepository =
            Mockito.mock(YachtRepository::class.java) { inv ->
                if (inv.method.name == "findById") Optional.of(yacht) else Mockito.RETURNS_DEFAULTS.answer(inv)
            }
        val offerRepo: OfferRepository =
            Mockito.mock(OfferRepository::class.java) { inv ->
                when (inv.method.name) {
                    "findOffersByYachtIdAndDateFromAndDateToAndOfferType" -> offers.also { inv.arguments[3] shouldBe OfferType.STANDARD }
                    "findAllByYachtAndDateRangeOverlap" -> offers
                    else -> Mockito.RETURNS_DEFAULTS.answer(inv)
                }
            }
        return OfferQueryingService(
            yachts,
            offerRepo,
            recordingMapper(),
            Mockito.mock(hr.workspace.boat4you.domains.users.jpa.UserRepository::class.java),
            Mockito.mock(PriceCalculationService::class.java),
            repository,
            lookup,
        )
    }

    @Test
    fun `standard-offers and offers give the mapper the same answer as the shared lookup`() {
        val offers = listOf(week(1, w1From), week(2, w2From), week(3, w3From))
        options += option(w2From)
        val service = offerQueryingService(offers)
        val expected = lookup.forYachtWindow(yachtId, w1From, w3From.plusDays(7))
        val expectedById = offers.associate { it.id!! to expected(it) }
        expectedById shouldBe mapOf(1L to false, 2L to true, 3L to false)

        val calendar = service.getYachtStandardOffers(yachtId, w1From, w3From.plusDays(7), null)
        mapped.toMap() shouldBe expectedById
        calendar.map { it.status } shouldBe
            listOf(ExternalReservationStatus.FREE, ExternalReservationStatus.OPTION, ExternalReservationStatus.FREE)

        mapped.clear()
        val panel = service.getYachtOffers(yachtId, w2From, w2From.plusDays(1), null)
        mapped.toMap() shouldBe expectedById
        panel shouldHaveSize 3
    }
}

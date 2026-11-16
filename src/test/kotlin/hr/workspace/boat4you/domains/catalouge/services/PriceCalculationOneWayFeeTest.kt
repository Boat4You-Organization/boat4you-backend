package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.PriceCalcDto
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.ExtraPaymentType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasZeroPriceSwitchFixture
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalBaseRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Location
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.jpa.OfferExtra
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtra
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtraRepository
import hr.workspace.boat4you.domains.catalouge.mapper.YachtExtrasMapper
import hr.workspace.boat4you.domains.catalouge.utils.OneWayFeeRules
import hr.workspace.boat4you.domains.external.nausys.service.NauSysObligatoryExtrasService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * Mario 16.11.2026 ("ne smiju biti ovi one way kao obligatory"): Prestige M48 "Diamond Si" (18077), a week from Marina
 * Frapa Rogoznica back to Rogoznica, listed four one-way fees (Tivat, Dubrovnik, Split, Zadar -> Rogoznica, 7,850 EUR)
 * as payable at the base next to the charter pack and the tourist tax.
 */
class PriceCalculationOneWayFeeTest {
    private val exchangeRates = mock(ExchangeRateCalculationService::class.java)
    private var nextId = 1L
    private val rogoznica =
        Location().apply {
            id = 2029
            name = "Marina Frapa | Rogoznica"
            city = "Rogoznica"
        }
    private val split =
        Location().apply {
            id = 1500
            name = "ACI Marina Split | Split"
            city = "Split"
        }
    private val lefkas =
        Location().apply {
            id = 1600
            name = "D-Marin Lefkas"
            city = "Lefkada"
        }

    private val yacht =
        Yacht().apply {
            id = 18077
            entryType = EntryType.EXTERNAL
            location = rogoznica
        }

    private fun offer(to: Location) =
        Offer().apply {
            id = 1497400
            yacht = this@PriceCalculationOneWayFeeTest.yacht
            dateFrom = LocalDate.of(2027, 7, 3)
            dateTo = LocalDate.of(2027, 7, 10)
            clientPrice = BigDecimal("11000.00")
            locationFrom = rogoznica
            locationTo = to
        }

    private fun boatRow(
        name: String,
        price: String,
        obligatory: Boolean = true,
    ) = YachtExtra().apply {
        id = nextId++
        this.name = name
        this.price = BigDecimal(price)
        unit = ExtrasUnitType.PER_BOOKING
        this.obligatory = obligatory
        payableInBase = false
        paymentType = ExtraPaymentType.ON_SITE
    }

    private fun offerRow(
        offer: Offer,
        name: String,
        price: String,
    ): OfferExtra {
        val row =
            OfferExtra().apply {
                id = nextId++
                this.name = name
                this.price = BigDecimal(price)
                unit = ExtrasUnitType.PER_BOOKING
                obligatory = true
                payableInBase = false
                paymentType = ExtraPaymentType.ON_SITE
                this.offer = offer
            }
        offer.offerExtras.add(row)
        return row
    }

    private val diamondSi =
        listOf(
            boatRow("Charter pack", "680"),
            boatRow("One way fee Tivat-Marina Frapa Rogoznica", "3500"),
            boatRow("One way fee Dubrovnik-Marina Frapa Rogoznica", "2000"),
            boatRow("One way fee Split-Marina Frapa Rogoznica", "350"),
            boatRow("One way fee Zadar- Marina Frapa Rogoznica", "2000"),
            boatRow("Airport Transfer 4 pax (one way)", "120", obligatory = false),
        )

    private fun calculate(
        offer: Offer,
        selected: Set<String> = emptySet(),
        rows: List<YachtExtra> = diamondSi,
    ): PriceCalcDto {
        val yachtExtras = mock(YachtExtraRepository::class.java) { inv -> if (inv.method.name == "findAllByYacht") rows else null }
        val nauSys =
            mock(NauSysObligatoryExtrasService::class.java) { inv ->
                if (inv.method.name == "obligatoryWithSelectedServices") emptyList<Any>() else null
            }
        val service =
            PriceCalculationService(
                yachtExtraRepository = yachtExtras,
                yachtExtrasMapper = YachtExtrasMapper(exchangeRates),
                exchangeRateCalculationService = exchangeRates,
                externalBaseRepository = mock(ExternalBaseRepository::class.java),
                nauSysObligatoryExtrasService = nauSys,
                realPriceSwitches = ExtrasZeroPriceSwitchFixture.switches(false),
            )
        return service.calculatePrice(yacht, offer, null, selected)
    }

    @Test
    fun `a round trip lists no one-way fee from the boat, only the charter pack`() {
        val price = calculate(offer(to = rogoznica))

        assertEquals(listOf("Charter pack"), price.selectedExtrasAtBase.map { it.name })
    }

    @Test
    fun `a one-way fee the partner attached to a round-trip offer is not charged either`() {
        val roundTrip = offer(to = rogoznica)
        offerRow(roundTrip, "One Way Fee", "450")

        assertEquals(listOf("Charter pack"), calculate(roundTrip).selectedExtrasAtBase.map { it.name })
    }

    @Test
    fun `an offer that really ends in another marina keeps its own one-way fee`() {
        val oneWay = offer(to = split)
        offerRow(oneWay, "One Way Fee", "450")

        assertEquals(setOf("Charter pack", "One Way Fee"), calculate(oneWay).selectedExtrasAtBase.map { it.name }.toSet())
    }

    @Test
    fun `a one-way offer without a fee of its own takes the boat's fee for that route only`() {
        val price = calculate(offer(to = split))

        assertEquals(setOf("Charter pack", "One way fee Split-Marina Frapa Rogoznica"), price.selectedExtrasAtBase.map { it.name }.toSet())
    }

    @Test
    fun `a one-way offer to a marina no fee names takes none of several fees`() {
        assertEquals(listOf("Charter pack"), calculate(offer(to = lefkas)).selectedExtrasAtBase.map { it.name })
    }

    @Test
    fun `a dropped Marina Fee (One-Way) row does not hide the boat's own Marina Fee on a round trip (review 16-11)`() {
        val rows = listOf(boatRow("Göcek Marina Fee", "250"))
        val roundTrip = offer(to = rogoznica)
        offerRow(roundTrip, "Göcek Marina Fee (One-Way)", "900")

        assertEquals(listOf("Göcek Marina Fee"), calculate(roundTrip, rows = rows).selectedExtrasAtBase.map { it.name })
    }

    @Test
    fun `an airport transfer priced one way stays a service the customer can pick`() {
        val transfer = diamondSi.last()

        val price = calculate(offer(to = rogoznica), setOf(transfer.extrasKey()))

        assertEquals(setOf("Charter pack", "Airport Transfer 4 pax (one way)"), price.selectedExtrasAtBase.map { it.name }.toSet())
    }

    @Test
    fun `what counts as a one-way fee`() {
        listOf(
            "One way fee Split-Marina Frapa Rogoznica",
            "One Way Fee",
            "One Way - Porto - Lisbon (L)",
            "one-way",
            "Oneway fee",
            "Göcek Marina Fee (One-Way)",
            "One - Way fee",
            "One–way Athens-Cyclades",
            "One way fee incl. transfer of crew",
            "One Way charterMarina Kremik - Dubrovnik (costs of transfer crew , fuel for transfer)",
            "One-Way Lefkas Corfu (fuel refill+delivery fee)",
        ).forEach { assertEquals(true, OneWayFeeRules.isOneWayFee(it), it) }
        listOf(
            "APA (includes 2.900,00 EUR as a crew fee, one way fee 800 eur - if applicable)",
            "Airport Transfer 4 pax (one way)",
            "One way transfer Mykonos",
            "Transfer - One way",
            "Transfer - One-Way, each additional person (Guadaloupe Les Abymes Airport - VPM Base)",
            "TRANSFER Aktion Airport>Preveza Marina | Taxi 4 pax (one way/normal time)",
            "Taxi (one way)",
            "Corfu - Free one way transfer from the airport to the marina",
            "Charter pack",
            "Someone's way",
            null,
        ).forEach { assertEquals(false, OneWayFeeRules.isOneWayFee(it), "$it") }
    }
}

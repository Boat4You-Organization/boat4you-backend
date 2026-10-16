package hr.workspace.boat4you.domains.catalouge.mapper

import hr.workspace.boat4you.domains.catalouge.capacity.CapacityFixtures
import hr.workspace.boat4you.domains.catalouge.capacity.YachtCapacityMapper
import hr.workspace.boat4you.domains.catalouge.dto.ExtrasPriceDto
import hr.workspace.boat4you.domains.catalouge.dto.InternalCalcDto
import hr.workspace.boat4you.domains.catalouge.dto.PriceCalcDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtExtrasDto
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.ExtraPaymentType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasType
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.enums.OfferStatus
import hr.workspace.boat4you.domains.catalouge.enums.TranslationType
import hr.workspace.boat4you.domains.catalouge.jpa.Country
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtDetail
import hr.workspace.boat4you.domains.catalouge.jpa.Language
import hr.workspace.boat4you.domains.catalouge.jpa.Model
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.catalouge.jpa.OfferExtra
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtra
import hr.workspace.boat4you.domains.catalouge.jpa.YachtTranslation
import hr.workspace.boat4you.domains.catalouge.services.ExchangeRateCalculationService
import hr.workspace.boat4you.domains.catalouge.services.SearchSwitchesTestSupport
import hr.workspace.boat4you.domains.catalouge.services.YachtGalleryFixture
import hr.workspace.boat4you.domains.exchange.jpa.ExchangeRateRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationExtra
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * R01 at read time (DESIGN WS-R01 "Where it is applied" 2): an EXTERNAL yacht's extras reach the DTO with a cleaned
 * `name` / `description`, while `key`, `paymentType` (classify) and the price calculation keep the RAW stored name (a
 * join key). A CUSTOM yacht's description, highlights and extras come back byte-identical.
 */
class YachtExtrasMapperTest {
    private val exchangeRates = ExchangeRateCalculationService(mock(ExchangeRateRepository::class.java))
    private val mapper = YachtExtrasMapper(exchangeRates)

    private val noteName = "Half board 1-3 pax (number of guests to update manually)"
    private val confirmName = "Early Check-in (14:00pm - to be reconfirmed by NSS office)"
    private val partnerDescription =
        "Mooring fees (first and last day at the Athenian's Pier). " +
            "Athenian Yachts shall not be liable for the crew. To be confirmed by NSS."

    private fun yachtExtra(
        name: String,
        description: String? = partnerDescription,
        paymentType: ExtraPaymentType? = null,
    ) = YachtExtra().apply {
        id = 11
        this.name = name
        price = BigDecimal("1540")
        payableInBase = true
        obligatory = false
        unit = ExtrasUnitType.PER_BOOKING
        type = ExtrasType.EXTRAS
        this.description = description
        this.paymentType = paymentType
    }

    /** No String field of a public extras DTO may carry partner text - except the key, which is the raw join key. */
    private fun assertNoPartnerText(dto: Any) {
        dto.javaClass.declaredFields
            .filter { it.type == String::class.java && it.name != "key" }
            .forEach { field ->
                field.isAccessible = true
                val value = field.get(dto) as String? ?: return@forEach
                listOf("NSS", "manually", "Athenian", "liable").forEach { word ->
                    assertFalse(value.contains(word), "${dto.javaClass.simpleName}.${field.name} = '$value' still shows '$word'")
                }
            }
    }

    @Test
    fun `EXTERNAL - the DTO name and description are cleaned, the key stays the raw name`() {
        val row = yachtExtra(noteName)
        val dto = mapper.toDto(row, CurrencyEnum.EUR, partnerCopy = true)

        assertEquals("Half board 1-3 pax", dto.name)
        assertEquals("Mooring fees (first and last day at the charter base pier). To be confirmed by Boat4You.", dto.description)
        assertEquals(noteName, dto.key, "the key is the raw extrasKey()")
        assertEquals(row.extrasKey(), dto.key)
        assertNoPartnerText(dto)
        assertNoPartnerText(mapper.toBasicDto(row, CurrencyEnum.EUR, partnerCopy = true))
        assertEquals("Early Check-in (14:00pm - to be confirmed by Boat4You)", mapper.toDto(yachtExtra(confirmName), null, partnerCopy = true).name)
    }

    @Test
    fun `CUSTOM - name and description are byte-identical`() {
        val row = yachtExtra(noteName)
        val dto = mapper.toDto(row, CurrencyEnum.EUR, partnerCopy = false)
        assertEquals(noteName, dto.name)
        assertEquals(partnerDescription, dto.description)
        assertEquals(noteName, mapper.toBasicDto(row, null, partnerCopy = false).name)
    }

    @Test
    fun `paymentType is the same with and without the sanitizer - stored and classified`() {
        val skipperName = "Skipper - to be updated manually"
        // stored type
        val stored = yachtExtra(skipperName, paymentType = ExtraPaymentType.ADVANCE_TO_OPERATOR)
        assertEquals(ExtraPaymentType.ADVANCE_TO_OPERATOR, mapper.toDto(stored, null, partnerCopy = true).paymentType)

        // booked row without a stored type: classify runs on the RAW name
        val booked =
            ReservationExtra().apply {
                id = 3
                name = skipperName
                price = BigDecimal("1540")
                unitPrice = BigDecimal("1540")
                obligatory = false
                payableAtBase = true
                unit = ExtrasUnitType.PER_BOOKING
                yachtExtrasKey = skipperName
            }
        val clean = mapper.toExtrasPriceDto(booked, null, partnerCopy = true)
        val raw = mapper.toExtrasPriceDto(booked, null, partnerCopy = false)
        assertEquals(raw.paymentType, clean.paymentType)
        assertEquals(ExtraPaymentType.ADVANCE_TO_OPERATOR, clean.paymentType)
        assertEquals("Skipper", clean.name)
        assertEquals(skipperName, clean.key)
        assertEquals(skipperName, raw.name)
    }

    @Test
    fun `the price calculation keeps raw names, the public response shows clean ones with the same keys and types`() {
        val calc =
            InternalCalcDto(
                id = 7,
                name = noteName,
                labelCode = null,
                unitPriceEur = BigDecimal("90"),
                unit = ExtrasUnitType.PER_BOOKING,
                obligatory = true,
                payableInBase = true,
                offerPriceEur = null,
                offerUnit = null,
                calcPriceEur = null,
                extrasId = null,
                externalId = 5L,
                isStartingPrice = null,
                paymentType = null,
            )
        val internal = mapper.toExtrasPriceDto(calc, null, optionalOnSite = false)
        assertEquals(noteName, internal.name, "the internal row (NauSys re-quote dedupe, booking snapshot) is raw")

        val price = priceCalc(listOf(internal))
        val shown = YachtExtrasMapper.forDisplay(price, partnerCopy = true)
        val row: ExtrasPriceDto = shown.selectedExtrasAtBase.single()
        assertEquals("Half board 1-3 pax", row.name)
        assertEquals(internal.key, row.key)
        assertEquals(internal.paymentType, row.paymentType)
        assertEquals(price.totalPriceEur, shown.totalPriceEur)
        assertEquals(price, YachtExtrasMapper.forDisplay(price, partnerCopy = false), "CUSTOM: unchanged")
    }

    @Test
    fun `offer extras of an EXTERNAL yacht are cleaned, of a CUSTOM yacht unchanged`() {
        val offerMapper = OfferMapper(exchangeRates, mapper, SearchSwitchesTestSupport.SearchSwitches().switches)

        fun offer(entry: EntryType) =
            Offer().apply {
                id = 1
                status = OfferStatus.FREE
                dateFrom = LocalDate.of(2031, 7, 5)
                dateTo = LocalDate.of(2031, 7, 12)
                clientPrice = BigDecimal("2800")
                yacht = Yacht().apply { entryType = entry }
                offerExtras.add(
                    OfferExtra().apply {
                        id = 21
                        name = confirmName
                        price = BigDecimal("50")
                        payableInBase = true
                        obligatory = true
                        unit = ExtrasUnitType.PER_BOOKING
                        description = "Hermes Yachting shall not be liable. Paid at the base."
                    },
                )
            }

        val external = offerMapper.toDto(offer(EntryType.EXTERNAL), CurrencyEnum.EUR).extras!!.single()
        assertEquals("Early Check-in (14:00pm - to be confirmed by Boat4You)", external.name)
        assertEquals("Paid at the base.", external.description)
        assertEquals(confirmName, external.key)
        assertNoPartnerText(external)

        val custom = offerMapper.toDto(offer(EntryType.CUSTOM), CurrencyEnum.EUR).extras!!.single()
        assertEquals(confirmName, custom.name)
        assertEquals("Hermes Yachting shall not be liable. Paid at the base.", custom.description)
    }

    @Test
    fun `boat page - EXTERNAL prose and services cleaned, CUSTOM description, highlights and extras byte-identical`() {
        val yachtMapper = YachtMapper(exchangeRates, mapper, YachtCapacityMapper(CapacityFixtures.sanitizer()), YachtGalleryFixture.off())
        val english = Language().apply { locale = LanguageEnum.EN.locale }

        fun yacht(entry: EntryType) =
            Yacht().apply {
                id = 9365
                name = "Cassiopeia"
                entryType = entry
                model = Model().apply { name = "Lagoon 40" }
                listOf(TranslationType.DESCRIPTION to partnerDescription, TranslationType.HIGHLIGHTS to "Skipper provided by NSS").forEach { (t, v) ->
                    yachtTranslations.add(
                        YachtTranslation().apply {
                            language = english
                            type = t
                            value = v
                        },
                    )
                }
                if (entry == EntryType.CUSTOM) {
                    customYachtDetails.add(
                        CustomYachtDetail().apply {
                            lowPrice = BigDecimal("1000")
                            country = Country().apply {
                                id = 1
                                name = "Croatia"
                                code2 = "HR"
                            }
                        },
                    )
                }
            }

        val externalYacht = yacht(EntryType.EXTERNAL)
        val external = yachtMapper.toDetailsDto(externalYacht, emptyList(), listOf(yachtExtra(noteName)), null, LanguageEnum.EN)
        assertEquals("Mooring fees (first and last day at the charter base pier). To be confirmed by Boat4You.", external.description)
        assertEquals("Skipper provided by Boat4You", external.highlights)
        assertEquals("Half board 1-3 pax", external.services!!.single().name)

        val customYacht = yacht(EntryType.CUSTOM)
        val custom = yachtMapper.toDetailsDto(customYacht, emptyList(), listOf(yachtExtra(noteName)), null, LanguageEnum.EN)
        assertEquals(partnerDescription, custom.description)
        assertEquals("Skipper provided by NSS", custom.highlights)
        val service: YachtExtrasDto = assertNotNull(custom.services).single()
        assertEquals(noteName, service.name)
        assertEquals(partnerDescription, service.description)
    }

    private fun priceCalc(atBase: List<ExtrasPriceDto>) =
        PriceCalcDto(
            offerId = 1,
            yachtId = 2,
            dateFrom = LocalDate.of(2031, 7, 5),
            dateTo = LocalDate.of(2031, 7, 12),
            clientPricePerDayEur = BigDecimal("400"),
            clientPricePerDayInfo = null,
            numberOfDays = 7,
            clientPriceEur = BigDecimal("2800"),
            clientPriceInfo = null,
            totalPriceEur = BigDecimal("2800"),
            totalPriceInfo = null,
            totalDiscountEur = BigDecimal.ZERO,
            totalDiscountInfo = null,
            selectedExtrasAtBase = atBase,
            selectedExtrasInPrice = emptyList(),
            inquire = false,
            securityDeposit = null,
            insuredSecurityDeposit = null,
            depositCurrency = null,
            multipleExtrasInPriceOptionsAvailable = false,
            multipleExtrasInBaseOptionsAvailable = false,
            extrasPriceCalcInquire = false,
        )
}

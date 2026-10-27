package hr.workspace.boat4you.domains.contactcenter.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** What the one-off backfill can recover of an old IRIS search: the cards' dates and count, and the visitor's own words. */
class IrisCriteriaEstimatorTest {
    private val mapper = ObjectMapper()
    private val cards = """{"yachts":[{"startDate":"2026-07-25","endDate":"2026-08-01","dates":"exact"}]}"""

    private fun estimate(
        payload: String?,
        text: String?,
    ) = IrisCriteriaEstimator.estimate(payload, text, mapper)

    @Test
    fun `cards give dates and count, the visitor's words the rest`() {
        estimate(
            """{"yachts":[{"startDate":"2026-08-01","endDate":"2026-08-08","dates":"nearby"},{"startDate":"2026-07-25","endDate":"2026-08-01","dates":"exact"}]}""",
            "Catamaran in Croatia for 8 people, 4 cabins, budget 12.000 €",
        ) shouldBe
            SearchCriteria(
                "HR",
                null,
                null,
                LocalDate.of(2026, 7, 25),
                LocalDate.of(2026, 8, 1),
                8,
                4,
                null,
                12000,
                "CATAMARAN",
                emptyList(),
                2,
                null,
                null,
            )
    }

    @Test
    fun `a card from before 14 October carries the asked dates, nearby-only carries none`() {
        estimate("""{"yachts":[{"startDate":"2026-07-04","endDate":"2026-07-11"}]}""", null)!!.startDate shouldBe LocalDate.of(2026, 7, 4)
        estimate("""{"yachts":[{"startDate":"2026-07-04","endDate":"2026-07-11","dates":"nearby"}]}""", null)!!.startDate.shouldBeNull()
    }

    @Test
    fun `a card date outside 2000-2100 or unparsable is dropped, so a stored search can never be refused`() {
        fun start(date: String) = estimate("""{"yachts":[{"startDate":"$date","endDate":"2026-08-01"}]}""", null)!!.startDate
        start("1999-12-31").shouldBeNull()
        start("2000-01-01") shouldBe LocalDate.of(2000, 1, 1)
        start("2100-12-31") shouldBe LocalDate.of(2100, 12, 31)
        start("2101-01-01").shouldBeNull()
        start("-999999999-01-01").shouldBeNull()
        start("+999999999-01-01").shouldBeNull()
        start("tomorrow").shouldBeNull()
    }

    @Test
    fun `languages and units`() {
        estimate(cards, "Tražimo katamaran u Grčkoj za 6 osoba, do 9000 eur")!!.let {
            it.countryCode shouldBe "GR"
            it.minPersons shouldBe 6
            it.maxPriceEur shouldBe 9000
            it.vesselType shouldBe "CATAMARAN"
        }
        estimate(cards, "Segelyacht in Kroatien, 10 Personen, 5 Kabinen")!!.let {
            it.countryCode shouldBe "HR"
            it.vesselType shouldBe "SAILING_YACHT"
            it.minPersons shouldBe 10
            it.minCabins shouldBe 5
        }
        estimate(cards, "gulet turkey € 25,000")!!.let {
            it.countryCode shouldBe "TR"
            it.vesselType shouldBe "GULET"
            it.maxPriceEur shouldBe 25000
        }
    }

    @Test
    fun `out-of-range and off-topic text gives nothing`() {
        estimate(cards, "2 + 2 is? 99 people in July 2027, 40 cabins")!!.let {
            it.minPersons.shouldBeNull()
            it.minCabins.shouldBeNull()
            it.maxPriceEur.shouldBeNull()
            it.countryCode.shouldBeNull()
            it.vesselType.shouldBeNull()
        }
    }

    @Test
    fun `no cards, no estimate`() {
        listOf("""{"blogs":[{"title":"x","url":"/blog/x"}]}""", "not json", null, """{"yachts":[]}""").forEach {
            estimate(it, "Croatia").shouldBeNull()
        }
    }
}

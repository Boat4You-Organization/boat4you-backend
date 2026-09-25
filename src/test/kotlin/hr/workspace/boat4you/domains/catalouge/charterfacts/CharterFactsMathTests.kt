package hr.workspace.boat4you.domains.catalouge.charterfacts

import hr.workspace.boat4you.domains.catalouge.charterfacts.CharterFactsMath.BaseCount
import hr.workspace.boat4you.domains.catalouge.charterfacts.CharterFactsMath.BoatStats
import hr.workspace.boat4you.domains.catalouge.charterfacts.CharterFactsMath.Inputs
import hr.workspace.boat4you.domains.catalouge.charterfacts.CharterFactsMath.ModelCount
import hr.workspace.boat4you.domains.catalouge.charterfacts.CharterFactsMath.MonthStats
import hr.workspace.boat4you.domains.catalouge.enums.ExtrasUnitType
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class CharterFactsMathTests {
    @Test
    fun `did must be c-, r- or l- followed by digits`() {
        listOf("c-54", "r-5", "l-9001", "l-123456789012").forEach { CharterFactsMath.isValidDid(it) shouldBe true }
        listOf(null, "", "c", "c-", "x-5", "C-54", "c-5a", "l-l-19", "c-54,r-5", " c-54", "c-54 ", "r--5", "l-1234567890123", "c-٣")
            .forEach { CharterFactsMath.isValidDid(it) shouldBe false }
    }

    @Test
    fun `weekly multiplier - per night x7, per week and one-time x1, per-person and quantity units unknown`() {
        CharterFactsMath.weeklyMultiplier(ExtrasUnitType.PER_NIGHT) shouldBe 7
        listOf(ExtrasUnitType.PER_WEEK, ExtrasUnitType.PER_BOOKING, ExtrasUnitType.PER_BOAT, ExtrasUnitType.AMOUNT)
            .forEach { CharterFactsMath.weeklyMultiplier(it) shouldBe 1 }
        listOf(
            ExtrasUnitType.UNKNOWN,
            ExtrasUnitType.PERCENTAGE,
            ExtrasUnitType.PER_NIGHT_PERSON,
            ExtrasUnitType.PER_WEEK_PERSON,
            ExtrasUnitType.PER_BOOKING_PERSON,
            ExtrasUnitType.PER_HOUR,
            ExtrasUnitType.PER_LITRE,
            ExtrasUnitType.PER_CABIN,
        ).forEach { CharterFactsMath.weeklyMultiplier(it) shouldBe null }
    }

    @Test
    fun `weekly multiplier SQL mirrors the Kotlin rule on the enum names`() {
        val sql = CharterFactsMath.weeklyMultiplierSql("ye.unit")
        sql shouldBe
            "(CASE ye.unit WHEN 'AMOUNT' THEN 1 WHEN 'PER_WEEK' THEN 1 WHEN 'PER_BOOKING' THEN 1 " +
            "WHEN 'PER_NIGHT' THEN 7 WHEN 'PER_BOAT' THEN 1 END)"
        sql shouldNotContain "PERSON"
        sql shouldContain "ye.unit"
    }

    @Test
    fun `share and euro rounding`() {
        CharterFactsMath.share(5, 8) shouldBe BigDecimal("0.625")
        CharterFactsMath.share(1, 3) shouldBe BigDecimal("0.333")
        CharterFactsMath.share(2, 3) shouldBe BigDecimal("0.667")
        CharterFactsMath.share(0, 0) shouldBe BigDecimal("0.000")
        CharterFactsMath.euros(BigDecimal("1277.5")) shouldBe 1278L
        CharterFactsMath.euros(BigDecimal("1277.49")) shouldBe 1277L
        CharterFactsMath.euros(null) shouldBe null
    }

    private fun stats(
        boats: Long = 20,
        n: Long = 20,
    ) = BoatStats(
        boats = boats,
        buildYearMedian = 2018,
        buildYearN = n,
        depositMin = BigDecimal("1000"),
        depositMedian = BigDecimal("2500.4"),
        depositMax = BigDecimal("6000"),
        depositN = n,
        skipperP25 = BigDecimal("1277.5"),
        skipperMedian = BigDecimal("1500"),
        skipperP75 = BigDecimal("1775"),
        skipperN = n,
        obligatoryMedian = BigDecimal("350"),
        obligatoryN = n,
    )

    private fun month(
        m: String,
        weeks: Long,
        free: Long,
        priced: Long,
        median: Int,
    ) = MonthStats(m, weeks, free, priced, BigDecimal(median - 500), BigDecimal(median), BigDecimal(median + 500))

    private val from = LocalDate.of(2026, 9, 25)
    private val to = LocalDate.of(2027, 9, 24)

    @Test
    fun `full payload - months sorted, cheapest, priciest and most booked month, check-in shares`() {
        val p =
            CharterFactsMath.buildPayload(
                Inputs(
                    boats = stats(),
                    months =
                        listOf(
                            month("2027-08", weeks = 100, free = 10, priced = 90, median = 5200),
                            month("2026-10", weeks = 100, free = 70, priced = 95, median = 2100),
                            month("2027-07", weeks = 100, free = 20, priced = 80, median = 5200),
                            // too small for either list
                            month("2026-12", weeks = 4, free = 4, priced = 4, median = 900),
                        ),
                    checkInDays = mapOf(6 to 75L, 7 to 20L, 3 to 5L),
                    topModels = (1..10).map { ModelCount("Lagoon", "M$it", it.toLong()) },
                    topBases = listOf(BaseCount(7, "Marina Kaštela", 12), BaseCount(3, "ACI Split", 30)),
                    boatTypeMix = mapOf("SAILING_YACHT" to 12L, "CATAMARAN" to 8L),
                    windowFrom = from,
                    windowTo = to,
                ),
            )

        p["activeBoats"] shouldBe 20L
        @Suppress("UNCHECKED_CAST")
        val months = p["priceByMonth"] as List<Map<String, Any?>>
        months.map { it["month"] } shouldContainExactly listOf("2026-10", "2027-07", "2027-08")
        months[0] shouldBe mapOf("month" to "2026-10", "p25" to 1600L, "median" to 2100L, "p75" to 2600L, "offers" to 95L)
        p["cheapestMonth"] shouldBe "2026-10"
        // tie on the median (2027-07 / 2027-08) -> the earlier month, same tie rule as cheapestMonth
        p["priciestMonth"] shouldBe "2027-07"
        p["mostBookedMonth"] shouldBe "2027-08"
        @Suppress("UNCHECKED_CAST")
        val avail = p["availableShareByMonth"] as List<Map<String, Any?>>
        avail.map { it["share"] } shouldContainExactly listOf(BigDecimal("0.700"), BigDecimal("0.200"), BigDecimal("0.100"))

        p["skipperWeekly"] shouldBe mapOf("median" to 1500L, "p25" to 1278L, "p75" to 1775L, "n" to 20L)
        p["obligatoryExtrasWeekly"] shouldBe mapOf("median" to 350L, "n" to 20L)
        p["deposit"] shouldBe mapOf("min" to 1000L, "median" to 2500L, "max" to 6000L, "n" to 20L)
        p["checkInDays"] shouldBe
            listOf(
                mapOf("day" to "WEDNESDAY", "share" to BigDecimal("0.050")),
                mapOf("day" to "SATURDAY", "share" to BigDecimal("0.750")),
                mapOf("day" to "SUNDAY", "share" to BigDecimal("0.200")),
            )
        p["medianBuildYear"] shouldBe 2018

        @Suppress("UNCHECKED_CAST")
        val models = p["topModels"] as List<Map<String, Any?>>
        models.size shouldBe CharterFactsMath.TOP_MODELS
        models.first()["model"] shouldBe "M10"
        @Suppress("UNCHECKED_CAST")
        val bases = p["topBases"] as List<Map<String, Any?>>
        bases.first() shouldBe mapOf("locationId" to 3L, "name" to "ACI Split", "count" to 30L, "did" to "l-3")
        p["boatTypeMix"] shouldBe
            listOf(mapOf("vesselType" to "SAILING_YACHT", "count" to 12L), mapOf("vesselType" to "CATAMARAN", "count" to 8L))
        p["currency"] shouldBe "EUR"
        p["windowFrom"] shouldBe "2026-09-25"
        p["windowTo"] shouldBe "2027-09-24"
    }

    @Test
    fun `small samples are omitted, not emitted as noise`() {
        val p =
            CharterFactsMath.buildPayload(
                Inputs(
                    boats = stats(boats = 4, n = 4),
                    months = listOf(month("2026-10", weeks = 4, free = 1, priced = 4, median = 2000)),
                    checkInDays = mapOf(6 to 4L),
                    topModels = listOf(ModelCount("Lagoon", "Lagoon 42", 4)),
                    topBases = listOf(BaseCount(1, "Marina", 4)),
                    boatTypeMix = null,
                    windowFrom = from,
                    windowTo = to,
                ),
            )
        p["activeBoats"] shouldBe 4L
        listOf(
            "priceByMonth", "cheapestMonth", "priciestMonth", "availableShareByMonth", "mostBookedMonth", "skipperWeekly",
            "obligatoryExtrasWeekly", "deposit", "checkInDays", "medianBuildYear", "topModels", "topBases", "boatTypeMix",
        ).forEach { p shouldNotContainKey it }
        p shouldContainKey "currency"
    }

    @Test
    fun `a single qualifying month gets its figures but no cheapest-priciest-most-booked comparison`() {
        val p =
            CharterFactsMath.buildPayload(
                Inputs(
                    boats = stats(),
                    months = listOf(month("2026-10", weeks = 50, free = 10, priced = 40, median = 2000)),
                    windowFrom = from,
                    windowTo = to,
                ),
            )
        p shouldContainKey "priceByMonth"
        p shouldContainKey "availableShareByMonth"
        listOf("cheapestMonth", "priciestMonth", "mostBookedMonth", "checkInDays").forEach { p shouldNotContainKey it }
    }
}

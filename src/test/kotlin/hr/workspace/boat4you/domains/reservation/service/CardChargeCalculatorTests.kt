package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlow
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Golden values of the card charge and every phase-selection rule. The card provider charges
 * [CardChargeCalculator.CardCharge.totalMinor].
 */
class CardChargeCalculatorTests {
    private val five = CardChargeCalculator.surchargePercentage("5")

    private fun phase(
        id: Long,
        amount: String,
        deadline: String,
        paid: Boolean = false,
    ) = ReservationPaymentPhase().apply {
        this.id = id
        this.amount = BigDecimal(amount)
        this.deadline = LocalDate.parse(deadline)
        this.paidOn = if (paid) Instant.parse("2026-10-01T10:00:00Z") else null
    }

    private fun flow(
        vararg phases: ReservationPaymentPhase,
        total: String? = null,
    ) = ReservationFlow().apply {
        id = 99
        paymentPhases = phases.toMutableSet()
        calculatedTotalPrice = total?.let { BigDecimal(it) }
    }

    private fun totalMinor(
        base: String,
        percentage: String?,
    ): Long {
        val amount = BigDecimal(base)
        return CardChargeCalculator.toCentsLong(amount + CardChargeCalculator.surcharge(amount, CardChargeCalculator.surchargePercentage(percentage)))
    }

    private fun badParameter(block: () -> Unit): Map<String, String> = shouldThrow<ParameterValidationException> { block() }.badOrMissingParameters

    @Test
    fun `golden values - multiply first, divide last, whole-euro HALF_UP surcharge, HALF_UP cents`() {
        // Reservation 1441002 (Mario 12.7.2026): 2677.60 + 5 % = 2677.60 + 134 (133.88 rounded) = 2811.60.
        totalMinor("2677.60", "5") shouldBe 281_160L
        CardChargeCalculator.surcharge(BigDecimal("2677.60"), five).compareTo(BigDecimal(134)) shouldBe 0
        // 0.50 rounds up, 0.495 rounds down (whole euros only).
        totalMinor("10.00", "5") shouldBe 1_100L
        totalMinor("9.90", "5") shouldBe 990L
        // Decimal percentage: 24.99975 -> 25.
        totalMinor("999.99", "2.5") shouldBe 102_499L
    }

    @Test
    fun `no surcharge - percentage 0 or setting missing`() {
        totalMinor("2677.60", "0") shouldBe 267_760L
        totalMinor("2677.60", null) shouldBe 267_760L
        CardChargeCalculator.surchargePercentage(null).compareTo(BigDecimal.ZERO) shouldBe 0
        CardChargeCalculator.surcharge(BigDecimal("2677.60"), CardChargeCalculator.surchargePercentage(null)).compareTo(BigDecimal.ZERO) shouldBe 0
    }

    @Test
    fun `cents - HALF_UP to two decimals before scaling`() {
        CardChargeCalculator.toCentsLong(BigDecimal("2811.60")) shouldBe 281_160L
        CardChargeCalculator.toCentsLong(BigDecimal("10.005")) shouldBe 1_001L
        CardChargeCalculator.toCentsLong(BigDecimal("10.004")) shouldBe 1_000L
        CardChargeCalculator.toCentsLong(BigDecimal("11")) shouldBe 1_100L
    }

    @Test
    fun `calculate - single phase golden case end to end`() {
        val p = phase(1, "2677.60", "2026-11-01")
        val charge = CardChargeCalculator.calculate(flow(p), payFullAmount = null, paymentPhaseId = 1, surchargePercentage = five)
        charge.baseAmount.compareTo(BigDecimal("2677.60")) shouldBe 0
        charge.surchargeAmount.compareTo(BigDecimal(134)) shouldBe 0
        charge.totalAmount.compareTo(BigDecimal("2811.60")) shouldBe 0
        charge.totalMinor shouldBe 281_160L
        charge.phases shouldContainExactly listOf(p)
    }

    @Test
    fun `calculate - pay full rounds the surcharge on the SUM of unpaid phases`() {
        val first = phase(1, "1000.00", "2026-11-01")
        val second = phase(2, "1677.60", "2027-05-01")
        val charge = CardChargeCalculator.calculate(flow(first, second), payFullAmount = true, paymentPhaseId = null, surchargePercentage = five)
        charge.totalMinor shouldBe 281_160L
        charge.phases shouldContainExactlyInAnyOrder listOf(first, second)
    }

    @Test
    fun `neither payFullAmount nor paymentPhaseId - 400`() {
        badParameter { CardChargeCalculator.select(flow(phase(1, "100", "2026-11-01")), null, null) } shouldBe
            mapOf("payFullAmount" to "Provide either payFullAmount or paymentPhaseId parameter")
    }

    @Test
    fun `paymentPhaseId wins over payFullAmount`() {
        val first = phase(1, "100.00", "2026-11-01")
        val second = phase(2, "250.00", "2027-05-01")
        listOf(true, false, null).forEach { payFull ->
            val selection = CardChargeCalculator.select(flow(first, second), payFull, 2)
            selection.baseAmount.compareTo(BigDecimal("250.00")) shouldBe 0
            selection.phases shouldContainExactly listOf(second)
        }
    }

    @Test
    fun `paymentPhaseId unknown or already paid - 400`() {
        val paid = phase(1, "100.00", "2026-11-01", paid = true)
        val open = phase(2, "250.00", "2027-05-01")
        badParameter { CardChargeCalculator.select(flow(paid, open), null, 3) } shouldBe mapOf("paymentPhaseId" to "Payment phase not found")
        badParameter { CardChargeCalculator.select(flow(paid, open), true, 1) } shouldBe mapOf("paymentPhaseId" to "Payment phase is already paid")
    }

    @Test
    fun `payFullAmount=true - every unpaid phase, paid ones excluded`() {
        val paid = phase(1, "100.00", "2026-11-01", paid = true)
        val second = phase(2, "200.00", "2027-01-01")
        val third = phase(3, "300.50", "2027-05-01")
        val selection = CardChargeCalculator.select(flow(paid, second, third, total = "600.50"), true, null)
        selection.baseAmount.compareTo(BigDecimal("500.50")) shouldBe 0
        selection.phases shouldContainExactlyInAnyOrder listOf(second, third)
    }

    @Test
    fun `payFullAmount=true - unpaid sum 0 falls back to calculatedTotalPrice with no phases, 400 without it`() {
        val noRows = CardChargeCalculator.select(flow(total = "1234.56"), true, null)
        noRows.baseAmount.compareTo(BigDecimal("1234.56")) shouldBe 0
        noRows.phases.shouldBeEmpty()

        val allPaid = CardChargeCalculator.select(flow(phase(1, "100.00", "2026-11-01", paid = true), total = "100.00"), true, null)
        allPaid.baseAmount.compareTo(BigDecimal("100.00")) shouldBe 0
        allPaid.phases.shouldBeEmpty()

        badParameter { CardChargeCalculator.select(flow(), true, null) } shouldBe
            mapOf("calculatedTotalPrice" to "Reservation has no total price and no payment phases")
    }

    @Test
    fun `payFullAmount=false - the unpaid phase with the earliest deadline`() {
        val paidEarliest = phase(1, "100.00", "2026-10-01", paid = true)
        val later = phase(2, "300.00", "2027-05-01")
        val next = phase(3, "200.00", "2026-12-01")
        val selection = CardChargeCalculator.select(flow(paidEarliest, later, next), false, null)
        selection.baseAmount.compareTo(BigDecimal("200.00")) shouldBe 0
        selection.phases shouldContainExactly listOf(next)
    }

    @Test
    fun `payFullAmount=false - no unpaid phase left - 400`() {
        badParameter { CardChargeCalculator.select(flow(phase(1, "100.00", "2026-10-01", paid = true)), false, null) } shouldBe
            mapOf("paymentPhaseId" to "No unpaid payment phases remain")
        badParameter { CardChargeCalculator.select(flow(total = "100.00"), false, null) } shouldBe
            mapOf("paymentPhaseId" to "No unpaid payment phases remain")
    }

    @Test
    fun `select by flow equals select by its phases and total`() {
        val p = phase(1, "100.00", "2026-10-01")
        val f = flow(p, total = "100.00")
        CardChargeCalculator.select(f, true, null) shouldBe CardChargeCalculator.select(f.paymentPhases, f.calculatedTotalPrice, true, null)
    }
}

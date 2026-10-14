package hr.workspace.boat4you.domains.reservation.guard

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.common.config.RealPriceSwitches.PartnerPriceGuardMode
import hr.workspace.boat4you.domains.catalouge.jpa.Offer
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.evidence.PartnerPeriodCheckStore
import hr.workspace.boat4you.domains.external.evidence.PeriodCheckPriority
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.exceptions.PartnerPriceChangedException
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.FLOW_ID
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.WEEK_FROM
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.WEEK_TO
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.YACHT_ID
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.flow
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.item
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.offer
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.stub
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.wrapper
import hr.workspace.boat4you.domains.reservation.guard.BookingGuardFixtures.yacht
import hr.workspace.boat4you.domains.reservation.jpa.Reservation
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlow
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlowRepository
import hr.workspace.boat4you.domains.reservation.model.ReservationResponseWrapper
import hr.workspace.boat4you.domains.reservation.service.PartnerPriceGuard
import hr.workspace.boat4you.domains.reservation.service.PartnerPriceGuard.Outcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.springframework.transaction.PlatformTransactionManager
import java.math.BigDecimal
import java.util.Optional

/**
 * WS-C2. The MMK / NauSys fixtures are built from the partner DTO shapes (MmkReservationIntegrationService /
 * NausysReservationIntegrationService wrappers) and the reservation price formula documented in
 * ExtraPaymentType.fromMmkOfferObligatory (clientPrice = basePrice x (1 - discount%) + items billed with the booking),
 * because no stored partner reservation bodies exist in the repository. They must be re-checked read-only against
 * production `reservation.response` bodies before PARTNER_PRICE_GUARD=ENFORCE.
 */
class PartnerPriceGuardTests {
    private var mode = PartnerPriceGuardMode.ENFORCE
    private val switchCalls = mutableListOf<InvocationOnMock>()
    private val switches: RealPriceSwitches =
        stub(switchCalls) { inv -> if (inv.method.name == "getPartnerPriceGuard") mode else null }

    private var currentFlow: ReservationFlow? = null
    private val flowCalls = mutableListOf<InvocationOnMock>()
    private val flows: ReservationFlowRepository =
        stub(flowCalls) { inv -> if (inv.method.name == "findById") Optional.ofNullable(currentFlow) else null }

    private val storeCalls = mutableListOf<InvocationOnMock>()
    private val store: PartnerPeriodCheckStore = stub(storeCalls)
    private val txManager: PlatformTransactionManager = BookingGuardFixtures.txManager()
    private val guard = PartnerPriceGuard(switches, flows, store, txManager)

    private fun mmkFlow(extTotalPrice: String?) {
        currentFlow = flow(offer(yacht(ExternalSystemEnum.MMK), extTotalPrice = extTotalPrice?.let(::BigDecimal)))
    }

    private fun nausysFlow(extClientPrice: String?) {
        currentFlow = flow(offer(yacht(ExternalSystemEnum.NAUSYS), extClientPrice = extClientPrice?.let(::BigDecimal)))
    }

    // MMK clean booking with a 15 % discount: base 5,000, net 4,250 = mmkOffer.price; one obligatory item billed with
    // the booking (150) and one paid at the base (20, not in clientPrice).
    private val mmkDiscounted =
        wrapper(
            basePrice = "5000.00",
            discount = "15",
            clientPrice = "4400.00",
            extras = listOf(item("Additional fixed part", "150.00", false), item("Tourist tax", "20.00", true)),
        )

    // ---- calibration fixtures (partnerNet == snapshot on clean bookings)

    @Test
    fun `MMK with discount above 0 - basePrice x (1 - discount pct) equals extTotalPrice and the cross-check agrees`() {
        mmkFlow("4250.00")
        val v = guard.check(FLOW_ID, mmkDiscounted).shouldNotBeNull()
        v.outcome shouldBe Outcome.WITHIN
        v.partnerNet shouldBe BigDecimal("4250.00")
        v.crossCheckNet shouldBe BigDecimal("4250.00")
        v.crossCheckMismatch shouldBe false
    }

    @Test
    fun `MMK with discount 0 - basePrice equals extTotalPrice`() {
        mmkFlow("3200.00")
        val v = guard.check(FLOW_ID, wrapper("3200.00", "0", "3200.00", extras = emptyList())).shouldNotBeNull()
        v.outcome shouldBe Outcome.WITHIN
        v.partnerNet shouldBe BigDecimal("3200.00")
        v.crossCheckMismatch shouldBe false
    }

    @Test
    fun `MMK discount read as an amount would alert every discounted booking - the percentage reading does not`() {
        mmkFlow("4250.00")
        // the rejected v1 formula basePrice - discount = 4,985 would be far above 4,250
        (BigDecimal("5000.00") - BigDecimal("15")) shouldBe BigDecimal("4985.00")
        guard.check(FLOW_ID, mmkDiscounted)!!.outcome shouldBe Outcome.WITHIN
    }

    @Test
    fun `NauSys clientPrice equals extClientPrice, cross-check base minus discounts agrees`() {
        nausysFlow("2890.50")
        val v = guard.check(FLOW_ID, wrapper("3400.00", "509.50", "2890.50")).shouldNotBeNull()
        v.outcome shouldBe Outcome.WITHIN
        v.partnerNet shouldBe BigDecimal("2890.50")
        v.crossCheckNet shouldBe BigDecimal("2890.50")
    }

    @Test
    fun `a cross-check disagreement is flagged for calibration but does not change the outcome`() {
        mmkFlow("4250.00")
        val v = guard.check(FLOW_ID, wrapper("5000.00", "15", "4900.00", extras = emptyList()))!!
        v.outcome shouldBe Outcome.WITHIN
        v.crossCheckMismatch shouldBe true
    }

    // ---- tolerance max(1 %, 10 EUR)

    @Test
    fun `tolerance is 10 EUR on a small snapshot and 1 pct on a large one`() {
        mmkFlow("500.00")
        guard.check(FLOW_ID, wrapper("510.00", "0", "510.00"))!!.outcome shouldBe Outcome.WITHIN
        shouldThrow<PartnerPriceChangedException> { guard.check(FLOW_ID, wrapper("510.01", "0", "510.01")) }

        mmkFlow("5000.00")
        guard.check(FLOW_ID, wrapper("5050.00", "0", "5050.00"))!!.tolerance shouldBe BigDecimal("50.00")
        guard.check(FLOW_ID, wrapper("5050.00", "0", "5050.00"))!!.outcome shouldBe Outcome.WITHIN
        shouldThrow<PartnerPriceChangedException> { guard.check(FLOW_ID, wrapper("5050.01", "0", "5050.01")) }
    }

    // ---- modes

    @Test
    fun `higher in ENFORCE throws 3007 and queues the exact period for a re-check`() {
        mmkFlow("4250.00")
        val e = shouldThrow<PartnerPriceChangedException> { guard.check(FLOW_ID, wrapper("5000.00", "0", "5000.00")) }
        e.partnerNet shouldBe BigDecimal("5000.00")
        e.snapshotNet shouldBe BigDecimal("4250.00")
        val upsert = storeCalls.single { it.method.name == "upsertPending" }
        upsert.arguments.toList() shouldBe listOf(ExternalSystemEnum.MMK, YACHT_ID, WEEK_FROM, WEEK_TO, PeriodCheckPriority.ON_DEMAND_OR_BOOKING)
    }

    @Test
    fun `higher in ALERT logs and proceeds`() {
        mode = PartnerPriceGuardMode.ALERT
        mmkFlow("4250.00")
        val v = guard.check(FLOW_ID, wrapper("5000.00", "0", "5000.00"))!!
        v.outcome shouldBe Outcome.ABOVE
        v.alertOnlyReason.shouldBeNull()
        storeCalls.shouldBeEmpty()
    }

    @Test
    fun `lower proceeds in ENFORCE`() {
        nausysFlow("2890.50")
        guard.check(FLOW_ID, wrapper("3000.00", "300.00", "2700.00"))!!.outcome shouldBe Outcome.LOWER
    }

    @Test
    fun `OFF does nothing - no flow read, no switch beyond the mode, no verdict`() {
        mode = PartnerPriceGuardMode.OFF
        mmkFlow("4250.00")
        guard.check(FLOW_ID, wrapper("9000.00", "0", "9000.00")).shouldBeNull()
        flowCalls.shouldBeEmpty()
        storeCalls.shouldBeEmpty()
    }

    // ---- always alert-only

    @Test
    fun `non-EUR is never compared, even far above in ENFORCE`() {
        mmkFlow("4250.00")
        val v = guard.check(FLOW_ID, wrapper("9000.00", "0", "9000.00").copy(currency = "USD"))!!
        v.outcome shouldBe Outcome.NOT_COMPARED
        v.alertOnlyReason shouldBe PartnerPriceGuard.ALERT_ONLY_CURRENCY
    }

    @Test
    fun `partner auto-confirmed RESERVATION above tolerance is alert-only`() {
        mmkFlow("4250.00")
        val v = guard.check(FLOW_ID, wrapper("9000.00", "0", "9000.00").copy(calculatedSysStatus = ReservationStatus.RESERVATION))!!
        v.outcome shouldBe Outcome.ABOVE
        v.alertOnlyReason shouldBe PartnerPriceGuard.ALERT_ONLY_CONFIRMED
    }

    @Test
    fun `admin override above tolerance is alert-only`() {
        mmkFlow("4250.00")
        val v = guard.check(FLOW_ID, wrapper("9000.00", "0", "9000.00"), adminOverride = true)!!
        v.outcome shouldBe Outcome.ABOVE
        v.alertOnlyReason shouldBe PartnerPriceGuard.ALERT_ONLY_ADMIN
    }

    @Test
    fun `synthetic skipExternalSystem or kill-switch wrapper is never compared`() {
        mmkFlow("4250.00")
        val skip = wrapper("9000.00", "0", "9000.00").copy(externalId = null, externalCode = null, externalStatus = "SKIP-OPTION")
        guard.check(FLOW_ID, skip)!!.alertOnlyReason shouldBe PartnerPriceGuard.ALERT_ONLY_SYNTHETIC

        currentFlow = flow(offer(yacht(ExternalSystemEnum.MMK), extTotalPrice = BigDecimal("4250.00")))
        currentFlow!!.yacht!!.agency!!.skipExternalSystem = true
        guard.check(FLOW_ID, wrapper("9000.00", "0", "9000.00"))!!.outcome shouldBe Outcome.NOT_COMPARED
    }

    @Test
    fun `no snapshot, unknown system or a non-percentage MMK discount is not compared`() {
        mmkFlow(null)
        guard.check(FLOW_ID, wrapper("9000.00", "0", "9000.00"))!!.outcome shouldBe Outcome.NOT_COMPARED
        mmkFlow("4250.00")
        guard.check(FLOW_ID, wrapper("9000.00", "150", "9000.00"))!!.outcome shouldBe Outcome.NOT_COMPARED
        currentFlow = flow(offer(yacht(ExternalSystemEnum.UNKNOWN), extTotalPrice = BigDecimal("4250.00")))
        guard.check(FLOW_ID, wrapper("9000.00", "0", "9000.00"))!!.outcome shouldBe Outcome.NOT_COMPARED
    }

    @Test
    fun `a failing fact load never blocks the booking`() {
        val broken: ReservationFlowRepository = stub { throw IllegalStateException("db down") }
        PartnerPriceGuard(switches, broken, store, txManager).check(FLOW_ID, mmkDiscounted).shouldBeNull()
    }

    // ---- confirmed-price rule

    @Test
    fun `the guard never calls a price setter - flow and offer are only read, and no method takes a Reservation`() {
        val writes = mutableListOf<String>()
        val offerRead: Offer =
            stub { inv ->
                if (inv.method.name.startsWith("set")) writes += "offer.${inv.method.name}"
                when (inv.method.name) {
                    "getId" -> 595995L
                    "getDateFrom" -> WEEK_FROM
                    "getDateTo" -> WEEK_TO
                    "getExtTotalPrice" -> BigDecimal("4250.00")
                    else -> null
                }
            }
        val yachtWithAgency = yacht(ExternalSystemEnum.MMK)
        currentFlow =
            stub { inv ->
                if (inv.method.name.startsWith("set")) writes += "flow.${inv.method.name}"
                when (inv.method.name) {
                    "getOffer" -> offerRead
                    "getYacht" -> yachtWithAgency
                    "getCalculatedTotalPrice" -> BigDecimal("4800.00")
                    else -> null
                }
            }
        guard.check(FLOW_ID, mmkDiscounted)!!.outcome shouldBe Outcome.WITHIN
        shouldThrow<PartnerPriceChangedException> { guard.check(FLOW_ID, wrapper("9000.00", "0", "9000.00")) }
        writes.shouldBeEmpty()

        PartnerPriceGuard::class.java.declaredMethods
            .filter { m -> m.parameterTypes.any { Reservation::class.java.isAssignableFrom(it) } }
            .shouldBeEmpty()
        // the wrapper is a value: the guard cannot have changed the price createReservation will copy
        val before: ReservationResponseWrapper = mmkDiscounted.copy()
        mmkFlow("4250.00")
        guard.check(FLOW_ID, mmkDiscounted)
        mmkDiscounted shouldBe before
        Mockito.mockingDetails(switches).invocations.none { it.method.name.startsWith("set") } shouldBe true
    }
}

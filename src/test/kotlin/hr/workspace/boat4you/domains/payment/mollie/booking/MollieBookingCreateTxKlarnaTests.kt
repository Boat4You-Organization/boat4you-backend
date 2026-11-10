package hr.workspace.boat4you.domains.payment.mollie.booking

import hr.workspace.boat4you.domains.payment.mollie.KlarnaNotAvailableException
import hr.workspace.boat4you.domains.payment.mollie.MollieKlarnaProperties
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.BookingCheckoutMethod
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrder
import hr.workspace.boat4you.domains.payment.mollie.booking.jpa.ReservationCardOrderRepository
import hr.workspace.boat4you.domains.payment.mollie.klarnaProperties
import hr.workspace.boat4you.domains.payment.mollie.mollieProperties
import hr.workspace.boat4you.domains.reservation.dto.KlarnaBillingRequest
import hr.workspace.boat4you.domains.reservation.enums.ReservationStatus
import hr.workspace.boat4you.domains.reservation.jpa.Reservation
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlow
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhase
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhaseRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.settings.dto.SettingsDto
import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import hr.workspace.boat4you.domains.settings.services.AdminSettingsService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.util.Optional

/**
 * The Klarna branch of the booking create transaction over mocks: which amount is charged (surcharge), which limit
 * applies (the billing country's, instead of the card limit), and when an open checkout is handed out again.
 */
class MollieBookingCreateTxKlarnaTests {
    private val reservations: ReservationRepository = mock(ReservationRepository::class.java)
    private val phases: ReservationPaymentPhaseRepository = mock(ReservationPaymentPhaseRepository::class.java)
    private val orders: ReservationCardOrderRepository = mock(ReservationCardOrderRepository::class.java)
    private val settings: AdminSettingsService = mock(AdminSettingsService::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun <T> any(): T = ArgumentMatchers.any<T>() ?: null as T

    /** Mockito's eq() returns null, which Kotlin refuses for a non-null String parameter. */
    private fun eq(value: String): String = ArgumentMatchers.eq(value) ?: value

    private fun tx(klarna: MollieKlarnaProperties = klarnaProperties()) =
        MollieBookingCreateTx(reservations, phases, orders, settings, mollieProperties(), MollieBookingProperties(Duration.ofMinutes(15)), klarna)

    private fun billing(
        country: String = "DE",
        street: String = "Hellersbergstrasse 14",
    ) = KlarnaBillingRequest("Hans", "Mueller", null, street, "41460", "Neuss", country)

    /** Reservation 42 with ONE unpaid instalment 7 of [amount] EUR; card surcharge setting 5 %. */
    private fun given(amount: String = "2677.60") {
        val phase =
            ReservationPaymentPhase().apply {
                id = 7
                this.amount = BigDecimal(amount)
                deadline = LocalDate.parse("2026-12-01")
            }
        val flow =
            ReservationFlow().apply {
                id = 900
                email = "booking@example.com"
                paymentPhases = mutableSetOf(phase)
            }
        val reservation =
            Reservation().apply {
                id = 42
                reservationFlow = flow
                reservationNumber = "1441002/2026"
                sysStatus = ReservationStatus.RESERVATION
            }
        `when`(reservations.findById(42L)).thenReturn(Optional.of(reservation))
        `when`(settings.getSetting(SettingsKeyEnum.CARD_PAYMENT_SURCHARGE)).thenReturn(SettingsDto(SettingsKeyEnum.CARD_PAYMENT_SURCHARGE, "5"))
        `when`(phases.countByIdInAndPaidOnIsNotNull(listOf(7L))).thenReturn(0)
        `when`(orders.findPendingByReservationFlow(900)).thenReturn(emptyList())
    }

    private fun plan(outcome: CreateOutcome): BookingOrderPlan = outcome.shouldBeInstanceOf<CreateOutcome.Create>().plan

    @Test
    fun `Klarna plan - method KLARNA, the card surcharge by default, billing with the booking e-mail, a token of its own`() {
        given()
        val klarna = plan(tx().prepare(42, null, 7, "web-key-1", billing()))
        klarna.method shouldBe BookingCheckoutMethod.KLARNA
        klarna.baseAmount shouldBe BigDecimal("2677.60")
        klarna.surchargeAmount.compareTo(BigDecimal("134")) shouldBe 0
        klarna.amountMinor shouldBe 281_160L
        klarna.klarna!!.email shouldBe "booking@example.com"
        klarna.klarna!!.country shouldBe "DE"

        val card = plan(tx().prepare(42, null, 7, "web-key-1"))
        card.method shouldBe BookingCheckoutMethod.CARD
        card.klarna shouldBe null
        card.amountMinor shouldBe 281_160L
        // Same web key, same cents, different method: never the same Mollie Idempotency-Key.
        card.checkoutToken shouldNotBe klarna.checkoutToken
        card.checkoutToken shouldBe MollieBookingCreateTx.checkoutToken(900, "7", 281_160, "web-key-1", 0)
        // A corrected address is a new request too.
        plan(tx().prepare(42, null, 7, "web-key-1", billing(street = "Hellersbergstrasse 15"))).checkoutToken shouldNotBe klarna.checkoutToken
    }

    @Test
    fun `Klarna surcharge override - 0 percent charges the bare instalment, the card path keeps 5 percent`() {
        given()
        val noFee = tx(klarnaProperties(surchargePercent = "0"))
        with(plan(noFee.prepare(42, null, 7, "k", billing()))) {
            surchargeAmount.compareTo(BigDecimal.ZERO) shouldBe 0
            amountMinor shouldBe 267_760L
        }
        plan(noFee.prepare(42, null, 7, "k")).amountMinor shouldBe 281_160L
    }

    @Test
    fun `Klarna limits - the billing country decides, 422 before anything is looked up or locked for reuse`() {
        // 3 900 EUR + 5 % = 4 095 EUR: above Italy's 4 000, fine for Germany.
        given("3900.00")
        shouldThrow<KlarnaNotAvailableException> { tx().prepare(42, null, 7, "k", billing("IT")) }.reason shouldBe "amount"
        plan(tx().prepare(42, null, 7, "k", billing("DE"))).amountMinor shouldBe 409_500L
        shouldThrow<KlarnaNotAvailableException> { tx().prepare(42, null, 7, "k", billing("HR")) }.reason shouldBe "country"
        shouldThrow<KlarnaNotAvailableException> { tx(klarnaProperties(enabled = false)).prepare(42, null, 7, "k", billing()) }.reason shouldBe "disabled"

        // Above every Klarna limit (10 000 EUR): Klarna is refused; the same amount by card answers "above the card limit".
        given("9800.00")
        shouldThrow<KlarnaNotAvailableException> { tx().prepare(42, null, 7, "k", billing("DE")) }.reason shouldBe "amount"
        tx().prepare(42, null, 7, "k") shouldBe CreateOutcome.AboveCardLimit
    }

    @Test
    fun `reuse - a Klarna checkout only for the very same request, a card checkout never for Klarna`() {
        given()
        val billing = billing()
        val firstToken = plan(tx().prepare(42, null, 7, "web-key-1", billing)).checkoutToken
        val open =
            ReservationCardOrder().apply {
                id = 5
                providerOrderId = "tr_klarnaOpen1"
                checkoutUrl = "https://www.mollie.com/checkout/klarna/1"
                checkoutToken = firstToken
                method = "KLARNA"
            }
        // The first checkout exists now: the same key was used once, and the finder (method KLARNA) returns its row.
        `when`(orders.countByReservationFlowIdAndIdempotencyKey(900, "web-key-1")).thenReturn(1)
        `when`(orders.findReusable(ArgumentMatchers.eq(900L), eq("7"), ArgumentMatchers.eq(281_160L), ArgumentMatchers.eq(7L), any(), eq("KLARNA")))
            .thenReturn(open)

        // Same key + same address (a second click after the first answer): the open checkout again.
        tx().prepare(42, null, 7, "web-key-1", billing).shouldBeInstanceOf<CreateOutcome.Reuse>().orderId shouldBe "tr_klarnaOpen1"

        // A corrected address (or another page load = another key): NOT reused - it is stale and gets cleaned up first.
        `when`(orders.findPendingByReservationFlow(900)).thenReturn(listOf(open))
        tx().prepare(42, null, 7, "web-key-1", billing(street = "Hellersbergstrasse 15")).shouldBeInstanceOf<CreateOutcome.StalePending>().orders.single().orderId shouldBe
            "tr_klarnaOpen1"
        tx().prepare(42, null, 7, "web-key-2", billing).shouldBeInstanceOf<CreateOutcome.StalePending>()

        // The payer switches to card: the finder is asked for CARD (nothing), so the open Klarna payment is stale as well.
        tx().prepare(42, null, 7, "web-key-1").shouldBeInstanceOf<CreateOutcome.StalePending>()
        verify(orders, never()).findReusable(ArgumentMatchers.anyLong(), ArgumentMatchers.anyString(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong(), any(), eq("PAYPAL"))
    }
}

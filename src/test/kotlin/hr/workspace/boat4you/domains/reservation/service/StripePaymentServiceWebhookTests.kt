package hr.workspace.boat4you.domains.reservation.service

import com.stripe.model.Event
import com.stripe.model.EventDataObjectDeserializer
import com.stripe.model.checkout.Session
import hr.workspace.boat4you.domains.reservation.events.ReservationPaymentRecordedEvent
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhase
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhaseRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional

/**
 * `checkout.session.completed` for a phase that something else (a Mollie card payment, the office) already paid: the
 * phase is left exactly as it is, the office is alerted, the booking is not confirmed or announced again.
 */
class StripePaymentServiceWebhookTests {
    private val phases: ReservationPaymentPhaseRepository = mock(ReservationPaymentPhaseRepository::class.java)
    private val reservations: ReservationRepository = mock(ReservationRepository::class.java)
    private val idempotency: StripeEventIdempotencyService = mock(StripeEventIdempotencyService::class.java)
    private val alert: CardDoublePaymentAlert = mock(CardDoublePaymentAlert::class.java)
    private val publisher: ApplicationEventPublisher = mock(ApplicationEventPublisher::class.java)

    private val service =
        StripePaymentService(
            reservations,
            phases,
            mock(hr.workspace.boat4you.domains.settings.services.AdminSettingsService::class.java),
            mock(hr.workspace.boat4you.domains.reservation.service.ReservationMutationService::class.java),
            mock(hr.workspace.boat4you.domains.reservation.service.ReservationIntegrationService::class.java),
            mock(hr.workspace.boat4you.domains.reservation.service.ReservationEmailService::class.java),
            idempotency,
            mock(hr.workspace.boat4you.domains.voucher.service.VoucherService::class.java),
            publisher,
            mock(StripeSessionExpirer::class.java),
            alert,
            "https://www.boat4you.com",
            true,
            "eur",
        )

    private fun phase(
        id: Long,
        paidOn: Instant?,
        intent: String?,
    ) = ReservationPaymentPhase().also {
        it.id = id
        it.paidOn = paidOn
        it.stripePaymentIntentId = intent
        it.amount = BigDecimal("1000")
    }

    private fun event(metadata: Map<String, String>): Event {
        val session = Session()
        session.id = "cs_test_a1B2c3"
        session.paymentIntent = "pi_new"
        session.metadata = metadata
        val deserializer = mock(EventDataObjectDeserializer::class.java)
        `when`(deserializer.`object`).thenReturn(Optional.of(session))
        val event = mock(Event::class.java)
        `when`(event.id).thenReturn("evt_1")
        `when`(event.type).thenReturn("checkout.session.completed")
        `when`(event.dataObjectDeserializer).thenReturn(deserializer)
        `when`(idempotency.claimEventIfNew("evt_1", "checkout.session.completed")).thenReturn(true)
        return event
    }

    @Test
    fun `an already paid target phase is left untouched, the office is alerted, nothing is confirmed or announced`() {
        val paidAt = Instant.parse("2026-11-09T10:00:00Z")
        val paid = phase(7, paidAt, intent = null)
        `when`(phases.findByStripeSessionIdOrderByDeadlineAsc("cs_test_a1B2c3")).thenReturn(listOf(paid))
        // The guard decides on a fresh count taken under the row locks, not on the loaded entity.
        `when`(phases.countByIdInAndPaidOnIsNotNull(listOf(7L))).thenReturn(1L)

        // The first payment path (no pinned phase) would promote the reservation; the pinned path just records.
        service.handleWebhookEvent(event(mapOf("reservationId" to "42", "payFullAmount" to "true")))

        paid.paidOn shouldBe paidAt
        paid.stripePaymentIntentId.shouldBeNull()
        verify(alert).stripeAfterAnotherPayment(42, "cs_test_a1B2c3", "pi_new", listOf(7L))
        verify(publisher, never()).publishEvent(ArgumentMatchers.any(ReservationPaymentRecordedEvent::class.java))
        verifyNoInteractions(reservations)
    }

    @Test
    fun `pinned phase - only the pinned phase counts, and an unpaid one is still recorded as before`() {
        val otherPaid = phase(8, Instant.parse("2026-11-09T10:00:00Z"), intent = null)
        val pinned = phase(7, null, intent = null)
        `when`(phases.findByStripeSessionIdOrderByDeadlineAsc("cs_test_a1B2c3")).thenReturn(listOf(otherPaid, pinned))

        service.handleWebhookEvent(event(mapOf("reservationId" to "42", "paymentPhaseId" to "7")))

        pinned.paidOn?.let { true } shouldBe true
        pinned.stripePaymentIntentId shouldBe "pi_new"
        otherPaid.stripePaymentIntentId.shouldBeNull()
        verify(alert, never()).stripeAfterAnotherPayment(
            ArgumentMatchers.anyLong(),
            ArgumentMatchers.anyString(),
            ArgumentMatchers.any(),
            ArgumentMatchers.anyList(),
        )
        verify(publisher).publishEvent(ReservationPaymentRecordedEvent(42))
    }
}

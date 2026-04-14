package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlowRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationPaymentPhaseRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.LocalDate
import java.time.Month

class ReservationPaymentPhasesServiceTest {
    private val reservationRepository = mock<ReservationRepository>()
    private val reservationFlowRepository = mock<ReservationFlowRepository>()
    private val paymentPhasesRepository = mock<ReservationPaymentPhaseRepository>()
    private val service = ReservationPaymentPhasesService(reservationRepository, reservationFlowRepository, paymentPhasesRepository)

    @Nested
    inner class ReservingOnJan1 {
        @Test
        fun `10 days until startDate`() {
            val now = LocalDate.of(2025, Month.JANUARY, 1)
            val reservationStartDate = LocalDate.of(2025, Month.JANUARY, 11)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(1)
            result.first() shouldBe Pair(now, 1000.0)
        }

        @Test
        fun `one month until startDate`() {
            val now = LocalDate.of(2025, Month.JANUARY, 1)
            val reservationStartDate = LocalDate.of(2025, Month.FEBRUARY, 1)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(1)
            result.first() shouldBe Pair(now, 1000.0)
        }

        @Test
        fun `one month plus a day until startDate`() {
            val now = LocalDate.of(2025, Month.JANUARY, 1)
            val reservationStartDate = LocalDate.of(2025, Month.FEBRUARY, 2)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(2)
            result[0] shouldBe Pair(now, 500.0)
            result[1] shouldBe Pair(LocalDate.of(2025, Month.JANUARY, 2), 500.0)
        }

        @Test
        fun `one year until startDate`() {
            val now = LocalDate.of(2025, Month.JANUARY, 1)
            val reservationStartDate = LocalDate.of(2026, Month.JANUARY, 1)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(2)
            result[0] shouldBe Pair(now, 500.0)
            result[1] shouldBe Pair(LocalDate.of(2025, Month.DECEMBER, 1), 500.0)
        }

        @Test
        fun `start date Jan 2 next year`() {
            val now = LocalDate.of(2025, Month.JANUARY, 1)
            val reservationStartDate = LocalDate.of(2026, Month.JANUARY, 2)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(2)
            result[0] shouldBe Pair(now, 500.0)
            result[1] shouldBe Pair(LocalDate.of(2025, Month.DECEMBER, 2), 500.0)
        }

        @Test
        fun `start date Jan 15 next year`() {
            val now = LocalDate.of(2025, Month.JANUARY, 1)
            val reservationStartDate = LocalDate.of(2026, Month.JANUARY, 15)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(2)
            result[0] shouldBe Pair(now, 500.0)
            result[1] shouldBe Pair(LocalDate.of(2025, Month.DECEMBER, 15), 500.0)
        }

        @Test
        fun `start date Feb 15 next year`() {
            val now = LocalDate.of(2025, Month.JANUARY, 1)
            val reservationStartDate = LocalDate.of(2026, Month.FEBRUARY, 15)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(2)
            result[0] shouldBe Pair(now, 500.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 500.0)
        }

        @Test
        fun `start date Feb 16 next year`() {
            val now = LocalDate.of(2025, Month.JANUARY, 1)
            val reservationStartDate = LocalDate.of(2026, Month.FEBRUARY, 16)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 16), 500.0)
        }

        @Test
        fun `start date Dec 31 next year`() {
            val now = LocalDate.of(2025, Month.JANUARY, 1)
            val reservationStartDate = LocalDate.of(2026, Month.DECEMBER, 31)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2026, Month.NOVEMBER, 30), 500.0)
        }

        @Test
        fun `start date Jan 1 in two years`() {
            val now = LocalDate.of(2025, Month.JANUARY, 1)
            val reservationStartDate = LocalDate.of(2027, Month.JANUARY, 1)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2026, Month.DECEMBER, 1), 500.0)
        }

        @Test
        fun `start date Jan 15 in two years`() {
            val now = LocalDate.of(2025, Month.JANUARY, 1)
            val reservationStartDate = LocalDate.of(2027, Month.JANUARY, 15)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2026, Month.DECEMBER, 15), 500.0)
        }

        @Test
        fun `start date Feb 15 in two years`() {
            val now = LocalDate.of(2025, Month.JANUARY, 1)
            val reservationStartDate = LocalDate.of(2027, Month.FEBRUARY, 15)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2027, Month.JANUARY, 15), 500.0)
        }

        @Test
        fun `start date Feb 16 in two years`() {
            val now = LocalDate.of(2025, Month.JANUARY, 1)
            val reservationStartDate = LocalDate.of(2027, Month.FEBRUARY, 16)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2027, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2027, Month.JANUARY, 16), 500.0)
        }

        @Test
        fun `start date Dec 31 in two years`() {
            val now = LocalDate.of(2025, Month.JANUARY, 1)
            val reservationStartDate = LocalDate.of(2027, Month.DECEMBER, 31)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2027, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2027, Month.NOVEMBER, 30), 500.0)
        }
    }

    @Nested
    inner class ReservingOnJan15 {
        @Test
        fun `10 days until startDate`() {
            val now = LocalDate.of(2025, Month.JANUARY, 15)
            val reservationStartDate = LocalDate.of(2025, Month.JANUARY, 25)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(1)
            result.first() shouldBe Pair(now, 1000.0)
        }

        @Test
        fun `one month until startDate`() {
            val now = LocalDate.of(2025, Month.JANUARY, 15)
            val reservationStartDate = LocalDate.of(2025, Month.FEBRUARY, 15)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(1)
            result.first() shouldBe Pair(now, 1000.0)
        }

        @Test
        fun `one month plus a day until startDate`() {
            val now = LocalDate.of(2025, Month.JANUARY, 15)
            val reservationStartDate = LocalDate.of(2025, Month.FEBRUARY, 16)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(2)
            result[0] shouldBe Pair(now, 500.0)
            result[1] shouldBe Pair(LocalDate.of(2025, Month.JANUARY, 16), 500.0)
        }

        @Test
        fun `one year until startDate`() {
            val now = LocalDate.of(2025, Month.JANUARY, 15)
            val reservationStartDate = LocalDate.of(2026, Month.JANUARY, 15)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(2)
            result[0] shouldBe Pair(now, 500.0)
            result[1] shouldBe Pair(LocalDate.of(2025, Month.DECEMBER, 15), 500.0)
        }

        @Test
        fun `start date Jan 16 next year`() {
            val now = LocalDate.of(2025, Month.JANUARY, 15)
            val reservationStartDate = LocalDate.of(2026, Month.JANUARY, 16)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(2)
            result[0] shouldBe Pair(now, 500.0)
            result[1] shouldBe Pair(LocalDate.of(2025, Month.DECEMBER, 16), 500.0)
        }

        @Test
        fun `start date Feb 15 next year`() {
            val now = LocalDate.of(2025, Month.JANUARY, 15)
            val reservationStartDate = LocalDate.of(2026, Month.FEBRUARY, 15)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(2)
            result[0] shouldBe Pair(now, 500.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 500.0)
        }

        @Test
        fun `start date Feb 16 next year`() {
            val now = LocalDate.of(2025, Month.JANUARY, 15)
            val reservationStartDate = LocalDate.of(2026, Month.FEBRUARY, 16)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 16), 500.0)
        }

        @Test
        fun `start date Dec 31 next year`() {
            val now = LocalDate.of(2025, Month.JANUARY, 15)
            val reservationStartDate = LocalDate.of(2026, Month.DECEMBER, 31)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2026, Month.NOVEMBER, 30), 500.0)
        }

        @Test
        fun `start date Jan 1 in two years`() {
            val now = LocalDate.of(2025, Month.JANUARY, 15)
            val reservationStartDate = LocalDate.of(2027, Month.JANUARY, 1)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2026, Month.DECEMBER, 1), 500.0)
        }

        @Test
        fun `start date Jan 15 in two years`() {
            val now = LocalDate.of(2025, Month.JANUARY, 15)
            val reservationStartDate = LocalDate.of(2027, Month.JANUARY, 15)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2026, Month.DECEMBER, 15), 500.0)
        }

        @Test
        fun `start date Feb 15 in two years`() {
            val now = LocalDate.of(2025, Month.JANUARY, 15)
            val reservationStartDate = LocalDate.of(2027, Month.FEBRUARY, 15)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2027, Month.JANUARY, 15), 500.0)
        }

        @Test
        fun `start date Feb 16 in two years`() {
            val now = LocalDate.of(2025, Month.JANUARY, 15)
            val reservationStartDate = LocalDate.of(2027, Month.FEBRUARY, 16)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2027, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2027, Month.JANUARY, 16), 500.0)
        }

        @Test
        fun `start date Dec 31 in two years`() {
            val now = LocalDate.of(2025, Month.JANUARY, 15)
            val reservationStartDate = LocalDate.of(2027, Month.DECEMBER, 31)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2027, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2027, Month.NOVEMBER, 30), 500.0)
        }
    }

    @Nested
    inner class ReservingOnFeb15 {
        @Test
        fun `10 days until startDate`() {
            val now = LocalDate.of(2025, Month.FEBRUARY, 15)
            val reservationStartDate = LocalDate.of(2025, Month.FEBRUARY, 25)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(1)
            result.first() shouldBe Pair(now, 1000.0)
        }

        @Test
        fun `one month until startDate`() {
            val now = LocalDate.of(2025, Month.FEBRUARY, 15)
            val reservationStartDate = LocalDate.of(2025, Month.MARCH, 15)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(1)
            result.first() shouldBe Pair(now, 1000.0)
        }

        @Test
        fun `one month plus a day until startDate`() {
            val now = LocalDate.of(2025, Month.FEBRUARY, 15)
            val reservationStartDate = LocalDate.of(2025, Month.MARCH, 16)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(2)
            result[0] shouldBe Pair(now, 500.0)
            result[1] shouldBe Pair(LocalDate.of(2025, Month.FEBRUARY, 16), 500.0)
        }

        @Test
        fun `one year until startDate`() {
            val now = LocalDate.of(2025, Month.FEBRUARY, 15)
            val reservationStartDate = LocalDate.of(2026, Month.FEBRUARY, 15)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(2)
            result[0] shouldBe Pair(now, 500.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 500.0)
        }

        @Test
        fun `start date Feb 15 next year`() {
            val now = LocalDate.of(2025, Month.FEBRUARY, 15)
            val reservationStartDate = LocalDate.of(2026, Month.FEBRUARY, 15)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(2)
            result[0] shouldBe Pair(now, 500.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 500.0)
        }

        @Test
        fun `start date Feb 16 next year`() {
            val now = LocalDate.of(2025, Month.FEBRUARY, 15)
            val reservationStartDate = LocalDate.of(2026, Month.FEBRUARY, 16)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 16), 500.0)
        }

        @Test
        fun `start date Dec 31 next year`() {
            val now = LocalDate.of(2025, Month.FEBRUARY, 15)
            val reservationStartDate = LocalDate.of(2026, Month.DECEMBER, 31)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2026, Month.NOVEMBER, 30), 500.0)
        }

        @Test
        fun `start date Jan 1 in two years`() {
            val now = LocalDate.of(2025, Month.FEBRUARY, 15)
            val reservationStartDate = LocalDate.of(2027, Month.JANUARY, 1)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2026, Month.DECEMBER, 1), 500.0)
        }

        @Test
        fun `start date Jan 15 in two years`() {
            val now = LocalDate.of(2025, Month.FEBRUARY, 15)
            val reservationStartDate = LocalDate.of(2027, Month.JANUARY, 15)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2026, Month.DECEMBER, 15), 500.0)
        }

        @Test
        fun `start date Feb 15 in two years`() {
            val now = LocalDate.of(2025, Month.FEBRUARY, 15)
            val reservationStartDate = LocalDate.of(2027, Month.FEBRUARY, 15)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2027, Month.JANUARY, 15), 500.0)
        }

        @Test
        fun `start date Feb 16 in two years`() {
            val now = LocalDate.of(2025, Month.FEBRUARY, 15)
            val reservationStartDate = LocalDate.of(2027, Month.FEBRUARY, 16)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2027, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2027, Month.JANUARY, 16), 500.0)
        }

        @Test
        fun `start date Dec 31 in two years`() {
            val now = LocalDate.of(2025, Month.FEBRUARY, 15)
            val reservationStartDate = LocalDate.of(2027, Month.DECEMBER, 31)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2027, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2027, Month.NOVEMBER, 30), 500.0)
        }
    }

    @Nested
    inner class ReservingOnFeb16 {
        @Test
        fun `10 days until startDate`() {
            val now = LocalDate.of(2025, Month.FEBRUARY, 16)
            val reservationStartDate = LocalDate.of(2025, Month.FEBRUARY, 26)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(1)
            result.first() shouldBe Pair(now, 1000.0)
        }

        @Test
        fun `one month until startDate`() {
            val now = LocalDate.of(2025, Month.FEBRUARY, 16)
            val reservationStartDate = LocalDate.of(2025, Month.MARCH, 16)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(1)
            result.first() shouldBe Pair(now, 1000.0)
        }

        @Test
        fun `one month plus a day until startDate`() {
            val now = LocalDate.of(2025, Month.FEBRUARY, 16)
            val reservationStartDate = LocalDate.of(2025, Month.MARCH, 17)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(2)
            result[0] shouldBe Pair(now, 500.0)
            result[1] shouldBe Pair(LocalDate.of(2025, Month.FEBRUARY, 17), 500.0)
        }

        @Test
        fun `one year until startDate`() {
            val now = LocalDate.of(2025, Month.FEBRUARY, 16)
            val reservationStartDate = LocalDate.of(2026, Month.FEBRUARY, 16)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(2)
            result[0] shouldBe Pair(now, 500.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 16), 500.0)
        }

        @Test
        fun `start date Feb 17 next year`() {
            val now = LocalDate.of(2025, Month.FEBRUARY, 16)
            val reservationStartDate = LocalDate.of(2026, Month.FEBRUARY, 17)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 17), 500.0)
        }

        @Test
        fun `start date Dec 31 next year`() {
            val now = LocalDate.of(2025, Month.FEBRUARY, 16)
            val reservationStartDate = LocalDate.of(2026, Month.DECEMBER, 31)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2026, Month.NOVEMBER, 30), 500.0)
        }

        @Test
        fun `start date Jan 1 in two years`() {
            val now = LocalDate.of(2025, Month.FEBRUARY, 16)
            val reservationStartDate = LocalDate.of(2027, Month.JANUARY, 1)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2026, Month.DECEMBER, 1), 500.0)
        }

        @Test
        fun `start date Jan 15 in two years`() {
            val now = LocalDate.of(2025, Month.FEBRUARY, 16)
            val reservationStartDate = LocalDate.of(2027, Month.JANUARY, 15)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2026, Month.DECEMBER, 15), 500.0)
        }

        @Test
        fun `start date Feb 15 in two years`() {
            val now = LocalDate.of(2025, Month.FEBRUARY, 16)
            val reservationStartDate = LocalDate.of(2027, Month.FEBRUARY, 15)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2027, Month.JANUARY, 15), 500.0)
        }

        @Test
        fun `start date Feb 16 in two years`() {
            val now = LocalDate.of(2025, Month.FEBRUARY, 16)
            val reservationStartDate = LocalDate.of(2027, Month.FEBRUARY, 16)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2027, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2027, Month.JANUARY, 16), 500.0)
        }

        @Test
        fun `start date Dec 31 in two years`() {
            val now = LocalDate.of(2025, Month.FEBRUARY, 16)
            val reservationStartDate = LocalDate.of(2027, Month.DECEMBER, 31)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2027, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2027, Month.NOVEMBER, 30), 500.0)
        }
    }

    @Nested
    inner class ReservingOnDec31 {
        @Test
        fun `10 days until startDate`() {
            val now = LocalDate.of(2025, Month.DECEMBER, 31)
            val reservationStartDate = LocalDate.of(2026, Month.JANUARY, 11)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(1)
            result.first() shouldBe Pair(now, 1000.0)
        }

        @Test
        fun `one month until startDate`() {
            val now = LocalDate.of(2025, Month.DECEMBER, 31)
            val reservationStartDate = LocalDate.of(2026, Month.JANUARY, 31)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(1)
            result.first() shouldBe Pair(now, 1000.0)
        }

        @Test
        fun `one month plus a day until startDate`() {
            val now = LocalDate.of(2025, Month.DECEMBER, 31)
            val reservationStartDate = LocalDate.of(2026, Month.FEBRUARY, 1)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(2)
            result[0] shouldBe Pair(now, 500.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 1), 500.0)
        }

        @Test
        fun `one year until startDate`() {
            val now = LocalDate.of(2025, Month.DECEMBER, 31)
            val reservationStartDate = LocalDate.of(2026, Month.DECEMBER, 31)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(2)
            result[0] shouldBe Pair(now, 500.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.NOVEMBER, 30), 500.0)
        }

        @Test
        fun `start date Jan 1 in two years`() {
            val now = LocalDate.of(2025, Month.DECEMBER, 31)
            val reservationStartDate = LocalDate.of(2027, Month.JANUARY, 1)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2026, Month.DECEMBER, 1), 500.0)
        }

        @Test
        fun `start date Jan 15 in two years`() {
            val now = LocalDate.of(2025, Month.DECEMBER, 31)
            val reservationStartDate = LocalDate.of(2027, Month.JANUARY, 15)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2026, Month.DECEMBER, 15), 500.0)
        }

        @Test
        fun `start date Feb 15 in two years`() {
            val now = LocalDate.of(2025, Month.DECEMBER, 31)
            val reservationStartDate = LocalDate.of(2027, Month.FEBRUARY, 15)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2026, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2027, Month.JANUARY, 15), 500.0)
        }

        @Test
        fun `start date Feb 16 in two years`() {
            val now = LocalDate.of(2025, Month.DECEMBER, 31)
            val reservationStartDate = LocalDate.of(2027, Month.FEBRUARY, 16)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2027, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2027, Month.JANUARY, 16), 500.0)
        }

        @Test
        fun `start date Dec 31 in two years`() {
            val now = LocalDate.of(2025, Month.DECEMBER, 31)
            val reservationStartDate = LocalDate.of(2027, Month.DECEMBER, 31)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 1000.0)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.0)
            result[1] shouldBe Pair(LocalDate.of(2027, Month.JANUARY, 15), 250.0)
            result[2] shouldBe Pair(LocalDate.of(2027, Month.NOVEMBER, 30), 500.0)
        }
    }

    @Nested
    inner class PriceRoundup {
        @Test
        fun `should round prices to two decimals`() {
            val now = LocalDate.of(2025, Month.DECEMBER, 31)
            val reservationStartDate = LocalDate.of(2027, Month.DECEMBER, 31)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 999.95)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 249.99)
            result[1] shouldBe Pair(LocalDate.of(2027, Month.JANUARY, 15), 249.99)
            result[2] shouldBe Pair(LocalDate.of(2027, Month.NOVEMBER, 30), 499.97)
        }

        @Test
        fun `should round prices to two decimals (2)`() {
            val now = LocalDate.of(2025, Month.DECEMBER, 31)
            val reservationStartDate = LocalDate.of(2027, Month.DECEMBER, 31)
            val result = service.calculatePaymentPhases(now, reservationStartDate, 999.99)
            result.shouldHaveSize(3)
            result[0] shouldBe Pair(now, 250.00)
            result[1] shouldBe Pair(LocalDate.of(2027, Month.JANUARY, 15), 250.00)
            result[2] shouldBe Pair(LocalDate.of(2027, Month.NOVEMBER, 30), 499.99)
        }
    }
}

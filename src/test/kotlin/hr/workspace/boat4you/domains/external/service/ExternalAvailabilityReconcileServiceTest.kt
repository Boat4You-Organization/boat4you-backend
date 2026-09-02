package hr.workspace.boat4you.domains.external.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import hr.workspace.boat4you.domains.catalouge.enums.ExternalReservationStatus
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservation
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMappingRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExternalAvailabilityReconcileServiceTest {
    private val reservationRepository = mock<ExternalReservationRepository>()
    private val mappingRepository = mock<ExternalMappingRepository>()
    private val offerRepository = mock<OfferRepository>()

    private val year = 2026
    private val yacht = Yacht().apply { id = 1L }
    private val agencyYachts = listOf(yacht)
    private val label = "agency=42 (Test Charter) companyId=7"

    private val logAppender = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(ExternalAvailabilityReconcileService::class.java) as Logger

    @BeforeEach
    fun attachLog() {
        logAppender.start()
        logger.addAppender(logAppender)
    }

    @AfterEach
    fun detachLog() {
        logger.detachAppender(logAppender)
    }

    private fun service(shadow: Boolean) =
        ExternalAvailabilityReconcileService(reservationRepository, mappingRepository, offerRepository, shadow)

    /** `count` consecutive one-week RESERVATION rows on [yacht] starting 2026-04-04. */
    private fun rows(count: Int): List<ExternalReservation> =
        (0 until count).map { i ->
            ExternalReservation().apply {
                id = 100L + i
                this.yacht = this@ExternalAvailabilityReconcileServiceTest.yacht
                dateFrom = LocalDate.of(year, 4, 4).plusWeeks(i.toLong())
                dateTo = dateFrom!!.plusWeeks(1)
                status = ExternalReservationStatus.RESERVATION
            }
        }

    private fun keysOf(rows: List<ExternalReservation>) =
        rows.mapNotNull { ReservationNaturalKey.of(it.yacht?.id, it.dateFrom, it.dateTo, it.status) }.toSet()

    private fun stubInScope(rows: List<ExternalReservation>) {
        // Exact arguments (no matchers): Mockito's any() yields null, which Kotlin rejects for the
        // non-null LocalDate parameters of this Kotlin-declared repository method.
        `when`(
            reservationRepository.findAllByYachtIdsAndYearOverlap(
                listOf(1L),
                LocalDate.of(year, 1, 1),
                LocalDate.of(year + 1, 1, 1),
            ),
        ).thenReturn(rows)
    }

    @Test
    fun `empty partner response is Empty and touches nothing`() {
        val outcome = service(shadow = false).reconcileAbsent(agencyYachts, emptySet(), setOf(1L), year, label)

        assertEquals(ReconcileOutcome.Empty, outcome)
        verifyNoInteractions(reservationRepository, mappingRepository)
    }

    @Test
    fun `no agency yacht present in the response is NoPresentYachts`() {
        val rows = rows(3)
        val outcome = service(shadow = false).reconcileAbsent(agencyYachts, keysOf(rows), setOf(999L), year, label)

        assertEquals(ReconcileOutcome.NoPresentYachts, outcome)
        verifyNoInteractions(reservationRepository)
    }

    @Test
    fun `everything still returned by the partner is NothingAbsent`() {
        val rows = rows(5)
        stubInScope(rows)

        val outcome = service(shadow = false).reconcileAbsent(agencyYachts, keysOf(rows), setOf(1L), year, label)

        assertEquals(ReconcileOutcome.NothingAbsent, outcome)
        verify(reservationRepository, never()).delete(any())
    }

    @Test
    fun `absent fraction over the cap trips the breaker, deletes nothing and names the agency`() {
        val rows = rows(20) // cap = max(10, 30% of 20) = 10
        stubInScope(rows)
        val seen = keysOf(rows.take(8)) // 12 absent > 10

        val outcome = service(shadow = false).reconcileAbsent(agencyYachts, seen, setOf(1L), year, label)

        assertEquals(ReconcileOutcome.Breaker(toRemove = 12, inScope = 20, cap = 10), outcome)
        verify(reservationRepository, never()).delete(any())
        val warn = logAppender.list.single { it.level.levelStr == "WARN" }
        assertTrue(warn.formattedMessage.contains(label), "breaker WARN must carry the agency label: ${warn.formattedMessage}")
        assertTrue(warn.formattedMessage.contains("would delete 12 of 20"))
    }

    @Test
    fun `absent rows within the cap are removed one by one when shadow mode is off`() {
        val rows = rows(20)
        stubInScope(rows)
        val seen = keysOf(rows.drop(2)) // rows 0 and 1 absent

        val outcome = service(shadow = false).reconcileAbsent(agencyYachts, seen, setOf(1L), year, label)

        assertEquals(ReconcileOutcome.Removed(removed = 2, inScope = 20), outcome)
        verify(reservationRepository, times(2)).delete(any())
        verify(reservationRepository).delete(rows[0])
        verify(reservationRepository).delete(rows[1])
    }

    @Test
    fun `shadow mode reports what it would remove and deletes nothing`() {
        val rows = rows(20)
        stubInScope(rows)
        val seen = keysOf(rows.drop(3))

        val outcome = service(shadow = true).reconcileAbsent(agencyYachts, seen, setOf(1L), year, label)

        assertEquals(ReconcileOutcome.Shadow(wouldRemove = 3, inScope = 20), outcome)
        verify(reservationRepository, never()).delete(any())
    }

    @Test
    fun `rows starting in another year are never candidates (start-year ownership)`() {
        val rows = rows(5)
        val multiYearBlock =
            ExternalReservation().apply {
                id = 900L
                this.yacht = this@ExternalAvailabilityReconcileServiceTest.yacht
                dateFrom = LocalDate.of(year - 1, 12, 1)
                dateTo = LocalDate.of(year, 3, 1)
                status = ExternalReservationStatus.SERVICE
            }
        stubInScope(rows + multiYearBlock)

        val outcome = service(shadow = false).reconcileAbsent(agencyYachts, keysOf(rows), setOf(1L), year, label)

        assertEquals(ReconcileOutcome.NothingAbsent, outcome)
        verify(reservationRepository, never()).delete(any())
    }
}

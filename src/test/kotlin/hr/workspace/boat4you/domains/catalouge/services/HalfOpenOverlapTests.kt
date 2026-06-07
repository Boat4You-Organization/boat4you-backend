package hr.workspace.boat4you.domains.catalouge.services

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives the gap-fill in getYachtStandardOffers (7.6.2026): a RESERVATION/SERVICE
 * interval is surfaced as a "booked" cell only when NO offer overlaps it. The
 * overlap must be HALF-OPEN so an adjacent turnaround week is treated as a real
 * gap (a reservation that ends exactly when an offer starts is NOT covered).
 */
class HalfOpenOverlapTests {
    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun `overlapping intervals overlap`() {
        assertTrue(halfOpenOverlap(d("2026-07-04"), d("2026-07-11"), d("2026-07-04"), d("2026-07-11"))) // identical week
        assertTrue(halfOpenOverlap(d("2026-07-18"), d("2026-08-01"), d("2026-07-25"), d("2026-08-01"))) // partial
        assertTrue(halfOpenOverlap(d("2026-07-01"), d("2026-07-31"), d("2026-07-10"), d("2026-07-12"))) // contained
    }

    @Test
    fun `adjacent turnaround does not overlap`() {
        // GIN TONIC gap: offer 06-20->06-27 must NOT cover reservation 06-27->07-04,
        // so that reserved week is surfaced as a booked cell instead of leaving a hole.
        assertFalse(halfOpenOverlap(d("2026-06-20"), d("2026-06-27"), d("2026-06-27"), d("2026-07-04")))
        assertFalse(halfOpenOverlap(d("2026-07-04"), d("2026-07-11"), d("2026-06-27"), d("2026-07-04")))
    }

    @Test
    fun `disjoint and null bounds do not overlap`() {
        assertFalse(halfOpenOverlap(d("2026-06-20"), d("2026-06-27"), d("2026-08-01"), d("2026-08-08")))
        assertFalse(halfOpenOverlap(null, d("2026-06-27"), d("2026-06-20"), d("2026-06-27")))
        assertFalse(halfOpenOverlap(d("2026-06-20"), d("2026-06-27"), d("2026-06-21"), null))
    }
}

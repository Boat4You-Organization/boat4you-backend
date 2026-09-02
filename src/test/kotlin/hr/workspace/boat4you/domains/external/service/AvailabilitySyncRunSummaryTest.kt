package hr.workspace.boat4you.domains.external.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AvailabilitySyncRunSummaryTest {
    private fun result(
        partnerRows: Int,
        mappedRows: Int,
        outcome: ReconcileOutcome,
        upsert: AvailabilityUpsertCounters = AvailabilityUpsertCounters(),
    ) = AvailabilitySyncResult(partnerRows, mappedRows, outcome, upsert)

    @Test
    fun `unmapped means partner returned rows but none mapped to our yachts`() {
        assertTrue(result(8, 0, ReconcileOutcome.Empty).unmappedNonEmpty)
        assertFalse(result(0, 0, ReconcileOutcome.Empty).unmappedNonEmpty)
        assertFalse(result(8, 8, ReconcileOutcome.NothingAbsent).unmappedNonEmpty)
        assertFalse(result(8, 1, ReconcileOutcome.NothingAbsent).unmappedNonEmpty)
    }

    @Test
    fun `summary line carries counters, per-year empties (ids only for current year) and failures`() {
        val summary = AvailabilitySyncRunSummary("MMK", currentYear = 2026).apply { agencies = 3 }
        val counters =
            AvailabilityUpsertCounters().apply {
                flippedToOption = 2
                setUnavailable = 5
                synthesized = 1
                cannotSynthesize(4711L)
            }

        summary.calls = 6
        summary.record(7698L, 2026, result(12, 0, ReconcileOutcome.Empty))
        summary.record(7698L, 2027, result(0, 0, ReconcileOutcome.Empty))
        summary.record(3867L, 2027, result(0, 0, ReconcileOutcome.Empty))
        summary.record(385L, 2027, result(111, 74, ReconcileOutcome.Breaker(37, 111, 33)))
        summary.record(2069L, 2026, result(40, 40, ReconcileOutcome.Removed(3, 40), counters))
        // 6th call failed → not recorded

        val line = summary.toLogLine()
        assertTrue(line.startsWith("MMK availability run summary: agencies=3 calls=6 failures=1"), line)
        assertTrue(line.contains("empty=[2026=1 [7698], 2027=2]"), line)
        assertTrue(line.contains("unmappedNonEmpty=[7698/2026]"), line)
        assertTrue(line.contains("removed=3"), line)
        assertTrue(line.contains("breakerTripped=[385/2027: 37 of 111 (cap 33)]"), line)
        assertTrue(line.contains("flippedToOption=2 setUnavailable=5 synthesized=1 cannotSynthesize=1"), line)
        assertEquals(setOf(4711L), summary.upsert.cannotSynthesizeYachtIds)
    }

    @Test
    fun `shadow outcomes accumulate separately from real removals`() {
        val summary = AvailabilitySyncRunSummary("NauSYS", currentYear = 2026)
        summary.calls = 2
        summary.record(1L, 2026, result(10, 10, ReconcileOutcome.Shadow(4, 10)))
        summary.record(2L, 2026, result(10, 10, ReconcileOutcome.Shadow(1, 10)))

        assertEquals(0, summary.failures)
        assertEquals(5, summary.shadowWouldRemove)
        assertEquals(0, summary.removed)
    }

    @Test
    fun `cannot-synthesize yacht id sample is capped but the count is not`() {
        val counters = AvailabilityUpsertCounters()
        repeat(40) { counters.cannotSynthesize(it.toLong()) }

        assertEquals(40, counters.cannotSynthesize)
        assertEquals(AvailabilityUpsertCounters.YACHT_ID_SAMPLE_LIMIT, counters.cannotSynthesizeYachtIds.size)

        val total = AvailabilityUpsertCounters()
        total.addAll(counters)
        total.addAll(counters)
        assertEquals(80, total.cannotSynthesize)
        assertEquals(AvailabilityUpsertCounters.YACHT_ID_SAMPLE_LIMIT, total.cannotSynthesizeYachtIds.size)
    }
}

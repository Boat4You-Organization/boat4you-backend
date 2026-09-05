package hr.workspace.boat4you.domains.external.mmk.service

import kotlin.test.Test
import kotlin.test.assertEquals

class MmkOfferSyncRunSummaryTest {
    @Test
    fun `record folds agency results and the log line carries every counter`() {
        val summary =
            MmkOfferSyncRunSummary("near-term").apply {
                agencies = 3
                timedOutBatches = 1
            }
        summary.record(
            MmkAgencyOfferSyncResult(
                calls = 2,
                failures = 0,
                upsert =
                    MmkOfferUpsertCounters().apply {
                        upserted = 10
                        skippedMissingLocation = 1
                    },
            ),
        )
        summary.record(
            MmkAgencyOfferSyncResult(
                calls = 1,
                failures = 1,
                upsert =
                    MmkOfferUpsertCounters().apply {
                        upserted = 5
                        skippedMissingYachtMapping = 2
                        skippedLocked = 3
                    },
            ),
        )

        assertEquals(3, summary.calls)
        assertEquals(1, summary.failures)
        assertEquals(15, summary.upsert.upserted)
        assertEquals(
            "MMK offer sync (near-term): agencies=3 calls=3 failures=1 timedOutBatches=1 " +
                "upserted=15 removed=0 skippedMissingLocation=1 skippedMissingYachtMapping=2 skippedLocked=3",
            summary.toLogLine(),
        )
    }
}

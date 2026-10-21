package hr.workspace.boat4you.domains.external.job

import hr.workspace.boat4you.common.config.RealPriceSwitches
import hr.workspace.boat4you.common.config.RealPriceSwitches.MmkOptionVerifyMode
import hr.workspace.boat4you.common.config.RealPriceSwitches.NauSysD3Mode
import hr.workspace.boat4you.common.config.RealPriceSwitches.NauSysReverifyMode
import hr.workspace.boat4you.domains.external.evidence.ReverifyJobs
import hr.workspace.boat4you.domains.external.job.ReverifyDigestJob.Companion.digestTriggers
import hr.workspace.boat4you.domains.external.job.ReverifyDigestJob.DigestFacts
import hr.workspace.boat4you.domains.external.job.ReverifyDigestJob.EscalationFact
import hr.workspace.boat4you.domains.external.job.ReverifyDigestJob.FlipFact
import hr.workspace.boat4you.domains.external.job.ReverifyDigestJob.RunFact
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The mail-or-log rule of [ReverifyDigestJob] (Mario 21.10.2026: mail only on an error or an anomaly), without a database. */
class ReverifyDigestTriggersTest {
    private val today: LocalDate = LocalDate.of(2026, 10, 21)

    private val allDailyJobs = setOf(ReverifyJobs.MMK_FREE, ReverifyJobs.MMK_OPTION, ReverifyJobs.NAUSYS_REVERIFY, ReverifyJobs.NAUSYS_D3)

    @Suppress("LongParameterList") // one reverify_run row: every call names its counters
    private fun run(
        id: Long,
        job: String,
        mode: String,
        calls: Long = 0,
        quoted: Long = 0,
        hidden: Long = 0,
        wouldFlip: Long = 0,
        note: String? = null,
        ended: Boolean = true,
    ) = RunFact(id, job, mode, ended, calls, quoted, hidden, wouldFlip, note)

    private fun d3Note(
        label: String,
        routeGone: Int,
        agencies: Int,
    ) = "D3 LOG $label: ROUTE_GONE=$routeGone UNPRICED=0 MAPPING_GAP=0 NOT_RETURNED=0 rows, $agencies agencies"

    private fun facts(
        runs: List<RunFact> = listOf(run(1, ReverifyJobs.MMK_FREE, "WRITE", calls = 20_000, quoted = 18_000, hidden = 100)),
        flips: List<FlipFact> = emptyList(),
        escalations: List<EscalationFact> = emptyList(),
        expectedJobs: Set<String> = setOf(ReverifyJobs.MMK_FREE),
        a2ByDay: Map<LocalDate, Long> = mapOf(today to 2_000L),
    ) = DigestFacts(runs, flips, escalations, expectedJobs, a2ByDay, today)

    /**
     * The digest mailed on 21.10.2026 07:00 UTC, as the fixture: runs #36-#41 with NAUSYS_REVERIFY LOG wouldFlip=2093,
     * agency 1194 escalated on 7 of the last 7 days, flips 190 + 317 rows. The trigger values are the real ones; the
     * other counters are illustrative.
     */
    private val digestOf21October =
        facts(
            runs =
                listOf(
                    run(36, ReverifyJobs.MMK_FREE, "WRITE", calls = 21_950, quoted = 18_800, hidden = 131),
                    run(37, ReverifyJobs.MMK_OPTION, "WRITE", calls = 3_690, quoted = 3_010, hidden = 214),
                    run(38, ReverifyJobs.NAUSYS_D3, "LOG", calls = 412, wouldFlip = 8, note = d3Note("near-term", 8, 3)),
                    run(39, ReverifyJobs.NAUSYS_REVERIFY, "LOG", calls = 6_000, quoted = 14_420, wouldFlip = 2_093),
                    run(40, ReverifyJobs.NAUSYS_D3, "LOG", calls = 9_850, wouldFlip = 458, note = d3Note("nightly", 458, 5)),
                    run(41, ReverifyJobs.NAUSYS_D3, "LOG", calls = 14, wouldFlip = 3, note = d3Note("retry drain", 3, 1)),
                ),
            flips = listOf(FlipFact(ReverifyJobs.MMK_FREE, 190), FlipFact(ReverifyJobs.MMK_OPTION, 317)),
            escalations =
                listOf(
                    EscalationFact(1194, days = 7, inLast24h = true, inPreviousDays = true),
                    EscalationFact(1701, days = 5, inLast24h = true, inPreviousDays = true),
                    EscalationFact(1504, days = 2, inLast24h = false, inPreviousDays = true),
                ),
            expectedJobs = allDailyJobs,
            a2ByDay = mapOf(today to 2_140L),
        )

    @Test
    fun `the digest of 21 October mails for the LOG would-flips, the persistent agency and the 507 flipped rows`() {
        digestTriggers(digestOf21October) shouldContainExactly
            listOf(
                "TRAJNA ESKALACIJA: agencija 1194, 7 dana u zadnjih 7 - treba odluka",
                "SKRIVANJE: 507 redaka -> UNAVAILABLE u zadnja 24 h (prag 500)",
                "LOG RUN: run #39 NAUSYS_REVERIFY LOG wouldFlip=2093 uz 6000 poziva (prag: više od 500 redaka ili 10 % poziva)",
            )
    }

    @Test
    fun `a quiet day has no reason, so no mail and the body stays as it is`() {
        val quiet =
            facts(
                runs =
                    listOf(
                        run(50, ReverifyJobs.MMK_FREE, "WRITE", calls = 22_000, quoted = 19_000, hidden = 120),
                        run(51, ReverifyJobs.MMK_OPTION, "WRITE", calls = 3_700, quoted = 3_000, hidden = 40),
                        run(52, ReverifyJobs.NAUSYS_REVERIFY, "LOG", calls = 6_000, quoted = 14_000, wouldFlip = 25),
                        run(53, ReverifyJobs.NAUSYS_D3, "LOG", calls = 9_850, wouldFlip = 460, note = d3Note("nightly", 460, 5)),
                        run(54, ReverifyJobs.UNDO, "WRITE", note = "undo of run #50: 12 offers restored"),
                    ),
                flips = listOf(FlipFact(ReverifyJobs.MMK_FREE, 170), FlipFact(ReverifyJobs.MMK_OPTION, 60), FlipFact(ReverifyJobs.UNDO, 400)),
                escalations =
                    listOf(
                        EscalationFact(1701, days = 3, inLast24h = true, inPreviousDays = true),
                        EscalationFact(1504, days = 2, inLast24h = false, inPreviousDays = true),
                    ),
                expectedJobs = allDailyJobs,
                a2ByDay = mapOf(today to 1_500L, today.minusDays(1) to 1_600L, today.minusDays(2) to 900L),
            )

        digestTriggers(quiet).shouldBeEmpty()
        ReverifyDigestJob.withReasons("BODY\n", emptyList()) shouldBe "BODY\n"
    }

    @Test
    fun `a mailed digest starts with the reasons, the report follows unchanged`() {
        ReverifyDigestJob.withReasons("PROVJERE KOD PARTNERA\n  x\n", listOf("A", "B")) shouldBe
            "ZAŠTO OVAJ MAIL\n  A\n  B\n\nPROVJERE KOD PARTNERA\n  x\n"
        ReverifyDigestJob.SUBJECT_PREFIX shouldBe "⚠️ "
    }

    // ---- 1: interruptions and missing runs

    @Test
    fun `a breaker, a stop or an unfinished run mails, the D3 summary and an admin undo do not`() {
        val f =
            facts(
                runs =
                    listOf(
                        run(1, ReverifyJobs.MMK_FREE, "WRITE", note = "too few decided week groups: 12 < 50"),
                        run(2, ReverifyJobs.MMK_OPTION, "WRITE", note = "stopped at the 100-min time budget, the rest runs tomorrow"),
                        run(3, ReverifyJobs.NAUSYS_REVERIFY, "LOG", ended = false),
                        run(4, ReverifyJobs.NAUSYS_D3, "WRITE", note = "D3 WRITE nightly: ROUTE_GONE=3 UNPRICED=0 MAPPING_GAP=0 NOT_RETURNED=0 rows, 1 agencies"),
                        run(5, ReverifyJobs.NAUSYS_D3, "LOG", note = d3Note("retry drain", 0, 0)),
                        run(6, ReverifyJobs.UNDO, "WRITE", note = "undo of run #1: 4 offers restored"),
                    ),
                expectedJobs = setOf(ReverifyJobs.MMK_FREE, ReverifyJobs.MMK_OPTION, ReverifyJobs.NAUSYS_REVERIFY, ReverifyJobs.NAUSYS_D3),
            )

        digestTriggers(f) shouldContainExactly
            listOf(
                "PREKID: run #1 MMK_FREE WRITE: too few decided week groups: 12 < 50",
                "PREKID: run #2 MMK_OPTION WRITE: stopped at the 100-min time budget, the rest runs tomorrow",
                "PREKID: run #3 NAUSYS_REVERIFY LOG nije završio (još traje ili je prekinut)",
            )
    }

    @Test
    fun `a job the switches run every day without a run in the last 24 h mails`() {
        val f = facts(expectedJobs = setOf(ReverifyJobs.MMK_FREE, ReverifyJobs.NAUSYS_REVERIFY, ReverifyJobs.NAUSYS_D3))

        digestTriggers(f) shouldContainExactly
            listOf(
                "NEMA RUNA: NAUSYS_D3 se nije pokrenuo u zadnja 24 h (prekidači ga pokreću svaki dan)",
                "NEMA RUNA: NAUSYS_REVERIFY se nije pokrenuo u zadnja 24 h (prekidači ga pokreću svaki dan)",
            )
    }

    @Test
    fun `the expected daily jobs follow the switches`() {
        ReverifyDigestJob.expectedDailyJobs(RealPriceSwitches.Snapshot()) shouldBe setOf(ReverifyJobs.MMK_FREE)
        ReverifyDigestJob.expectedDailyJobs(
            RealPriceSwitches.Snapshot(
                mmkOptionVerify = MmkOptionVerifyMode.ON,
                nausysReverify = NauSysReverifyMode.LOG,
                nausysD3Mode = NauSysD3Mode.LOG,
            ),
        ) shouldBe allDailyJobs
    }

    // ---- 2: escalations

    @Test
    fun `a new escalation mails, a repeated one below 7 days does not`() {
        val f =
            facts(
                escalations =
                    listOf(
                        EscalationFact(77, days = 1, inLast24h = true, inPreviousDays = false),
                        EscalationFact(1701, days = 6, inLast24h = true, inPreviousDays = true),
                        EscalationFact(1504, days = 1, inLast24h = false, inPreviousDays = true),
                    ),
            )

        digestTriggers(f) shouldContainExactly listOf("NOVA ESKALACIJA: agencija 77 (prethodnih 6 dana nije bila eskalirana)")
    }

    // ---- 3: A2

    @Test
    fun `A2 below 1000 mails on the third consecutive day only`() {
        fun on(vararg back: Pair<Long, Long>) = digestTriggers(facts(a2ByDay = back.associate { (days, total) -> today.minusDays(days) to total }))

        on(0L to 800L, 1L to 900L, 2L to 990L) shouldContainExactly
            listOf("A2: 800, ispod 1000 3. dan zaredom - dan za SEARCH_DEMOTE_STALE_OPTIONS=false")
        on(0L to 800L, 1L to 900L).shouldBeEmpty()
        on(0L to 800L, 1L to 900L, 2L to 990L, 3L to 950L).shouldBeEmpty()
        on(0L to 800L, 1L to 900L, 3L to 950L).shouldBeEmpty() // a day without a digest breaks the run
        on(0L to 800L, 1L to 900L, 2L to 1000L).shouldBeEmpty()
    }

    @Test
    fun `A2 back from below to above 1000 mails`() {
        digestTriggers(facts(a2ByDay = mapOf(today to 1_000L, today.minusDays(1) to 999L))) shouldContainExactly
            listOf("A2: ponovno iznad 1000 (jučer 999, danas 1000)")
        digestTriggers(facts(a2ByDay = mapOf(today to 1_200L, today.minusDays(1) to 1_100L))).shouldBeEmpty()
        digestTriggers(facts(a2ByDay = mapOf(today to 1_200L))).shouldBeEmpty()
    }

    // ---- 4: flips

    @Test
    fun `a WRITE run that hid more than 10 percent of what it quoted mails`() {
        val f =
            facts(
                runs =
                    listOf(
                        run(1, ReverifyJobs.MMK_FREE, "WRITE", quoted = 1_000, hidden = 101),
                        run(2, ReverifyJobs.MMK_OPTION, "WRITE", quoted = 1_000, hidden = 100), // exactly 10 %: not more
                        run(3, ReverifyJobs.NAUSYS_D3, "WRITE", quoted = 0, hidden = 40, note = "D3 WRITE nightly: x"), // D3 counts no quotes
                        run(4, ReverifyJobs.NAUSYS_REVERIFY, "WRITE", quoted = 99, hidden = 50), // too few quotes for a share
                    ),
                expectedJobs = emptySet(),
            )

        digestTriggers(f) shouldContainExactly listOf("SKRIVANJE: run #1 MMK_FREE WRITE sakrio 101 termina = 10,1 % od 1000 kvotiranih (prag 10 %)")
    }

    @Test
    fun `more than 500 flipped rows in 24 h mail, admin undos do not count`() {
        digestTriggers(facts(flips = listOf(FlipFact(ReverifyJobs.MMK_FREE, 300), FlipFact(ReverifyJobs.MMK_OPTION, 200)))).shouldBeEmpty()
        digestTriggers(facts(flips = listOf(FlipFact(ReverifyJobs.MMK_FREE, 300), FlipFact(ReverifyJobs.UNDO, 900)))).shouldBeEmpty()
        digestTriggers(facts(flips = listOf(FlipFact(ReverifyJobs.MMK_FREE, 301), FlipFact(ReverifyJobs.NAUSYS_D3, 200)))) shouldContainExactly
            listOf("SKRIVANJE: 501 redaka -> UNAVAILABLE u zadnja 24 h (prag 500)")
    }

    // ---- 5: LOG runs

    @Test
    fun `a LOG run over 500 would-flips or over 10 percent of its calls mails`() {
        val f =
            facts(
                runs =
                    listOf(
                        run(1, ReverifyJobs.MMK_FREE, "WRITE", calls = 20_000, quoted = 18_000),
                        run(2, ReverifyJobs.NAUSYS_REVERIFY, "LOG", calls = 1_000, wouldFlip = 101),
                        run(3, ReverifyJobs.NAUSYS_D3, "LOG", calls = 1_000, wouldFlip = 100, note = d3Note("nightly", 100, 2)),
                        run(4, ReverifyJobs.NAUSYS_D3, "LOG", calls = 14, wouldFlip = 9, note = d3Note("retry drain", 9, 1)),
                        run(5, ReverifyJobs.NAUSYS_D3, "LOG", calls = 0, wouldFlip = 501, note = d3Note("near-term", 501, 4)),
                        run(6, ReverifyJobs.MMK_OPTION, "LOG", calls = 50_000, wouldFlip = 500),
                    ),
                expectedJobs = emptySet(),
            )

        digestTriggers(f) shouldContainExactly
            listOf(
                "LOG RUN: run #2 NAUSYS_REVERIFY LOG wouldFlip=101 uz 1000 poziva (prag: više od 500 redaka ili 10 % poziva)",
                "LOG RUN: run #5 NAUSYS_D3 LOG wouldFlip=501 uz 0 poziva (prag: više od 500 redaka ili 10 % poziva)",
            )
    }
}

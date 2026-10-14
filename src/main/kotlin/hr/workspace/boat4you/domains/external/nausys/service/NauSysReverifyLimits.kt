package hr.workspace.boat4you.domains.external.nausys.service

import hr.workspace.boat4you.domains.external.evidence.PeriodCheckPriority
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Caps of the NauSys free-offer reverifier (DESIGN.md B2; `nausys.reverify.*`, defaults below). Size them from the
 * read-only candidate count per class on cusma4 before NAUSYS_REVERIFY leaves OFF. [maxCallsPerRun] bounds the partner
 * calls of one run (grouped + single-yacht); the class caps bound the candidate periods read per class.
 */
@Component
@ConfigurationProperties(prefix = "nausys.reverify")
class NauSysReverifyLimits {
    var maxCallsPerRun: Int = 6000
    var capP1: Int = 3000
    var capP2: Int = 3000
    var capP2b: Int = 1500
    var capP3: Int = 2000
    var capP4: Int = 3000
    var capP5: Int = 3000
    var capP6: Int = 1500

    /** Step 0 (no partner call) periods per run. */
    var step0MaxPeriods: Int = 5000

    /** Grid-sample (P5) groups asked first every run: the healthy baseline the fleet / agency breakers read. */
    var baselineGroups: Int = 400
}

/**
 * The candidate classes of the NauSys reverifier (DESIGN.md B2). [code] = the `cls` column of
 * `OfferRepository.findNauSysVerifyCandidates`; [order] = which class spends the call budget first (after the P5
 * baseline); [flippable] false = evidence only (a quote is upserted, an empty answer never flips anything).
 */
enum class NauSysVerifyClass(
    val code: Int,
    val priority: Int,
    val flippable: Boolean,
    val order: Int,
) {
    /** Our SYNTHETIC_DISAPPEARANCE marker (D3 legacy). */
    P1(1, PeriodCheckPriority.SYNTHETIC, true, 2),

    /** OPTION / OPTION_WAITING without a live hold or booking in progress (not UNDER_OPTION, not our markers). */
    P2(2, PeriodCheckPriority.STALE_OPTION, true, 3),

    /** A real UNDER_OPTION row the partner has not quoted for 7 days: evidence only, never flipped. */
    P2B(3, PeriodCheckPriority.STALE_OPTION, false, 4),

    /** An open strike (path 4, D3, an earlier run) or a never-checked period queued with priority <= 3. */
    P3(4, PeriodCheckPriority.ON_DEMAND_OR_BOOKING, true, 1),

    /** A FREE row off the nightly grid. */
    P4(5, PeriodCheckPriority.OFF_GRID_OR_STALE_UNAVAILABLE, true, 6),

    /** A grid FREE sample (first / middle / last per yacht and year). */
    P5(6, PeriodCheckPriority.SAMPLE, true, 7),

    /** A future UNAVAILABLE row no live reservation backs (the stale-UNAVAILABLE healer): evidence only. */
    P6(7, PeriodCheckPriority.OFF_GRID_OR_STALE_UNAVAILABLE, false, 5),
    ;

    companion object {
        fun fromCode(code: Int): NauSysVerifyClass = entries.first { it.code == code }
    }
}

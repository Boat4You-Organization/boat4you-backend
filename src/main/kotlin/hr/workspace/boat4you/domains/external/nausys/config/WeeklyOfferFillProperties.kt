package hr.workspace.boat4you.domains.external.nausys.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.ConstructorBinding

/**
 * NauSys weekly 7-day offer "fill".
 *
 * The reservation-options-driven offer sync only generates intervals for the
 * operator's per-season `minimalDuration` (often 14/28 days in shoulder season),
 * so free boats can show only ~one week per month. NauSys quotes a 7-day price
 * for every free week, so this fill asks NauSys week-by-week and upserts STANDARD
 * offers. See `NauSysYachtOfferIntegrationService.syncWeeklyOffersForYacht`.
 *
 * Pilot phase: runs on startup (data-sync node) only for `pilotNausysYachtIds`.
 * No Kotlin defaults — application.yml (base) always supplies all three keys
 * (mirrors SyncConfigurationProperties / TwinCanonicalProperties).
 */
@ConfigurationProperties(prefix = "application.weekly-offer-fill")
data class WeeklyOfferFillProperties
    @ConstructorBinding
    constructor(
        /** Master switch for the scheduled full-catalog NauSys weekly fill. */
        val enabled: Boolean,
        /** How many months ahead to ask NauSys for weekly prices. */
        val horizonMonths: Long,
        /** Yacht ids per `getFreeYachts` batch call (the whole fleet is chunked). */
        val chunkSize: Int,
        /** Also run the full fill once on startup (one-off validation; off in steady state). */
        val runAllOnStartup: Boolean,
    )

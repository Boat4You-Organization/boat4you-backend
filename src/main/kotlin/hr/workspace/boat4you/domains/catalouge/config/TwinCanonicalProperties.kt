package hr.workspace.boat4you.domains.catalouge.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.ConstructorBinding

/**
 * Cross-source duplicate ("twin") canonicalization.
 *
 * The same physical yacht is ingested separately per partner source (NauSys,
 * MMK, …), so it lives as several `yacht` rows with different ids and slugs and
 * — crucially — different availability completeness (one source may publish the
 * full year while another only the summer season). Without consolidation a
 * visitor can land on the sparser copy and see e.g. prices "only up to
 * September" while the complete copy (full year) sits behind a different slug.
 *
 * When enabled, the detail endpoints resolve a requested yacht id to the
 * canonical copy of its twin group before serving (see
 * `YachtTwinCanonicalService`). Canonical = the copy with the highest total
 * forward broker margin (Mario's policy 23.6.2026), which — because per-week
 * margins are identical across copies of the same boat — also surfaces the most
 * complete calendar.
 */
@ConfigurationProperties(prefix = "application.twin-canonical")
data class TwinCanonicalProperties
    @ConstructorBinding
    constructor(
        // NOTE: no Kotlin default values — a data class whose ctor params all
        // have defaults makes @ConstructorBinding bind to a synthetic no-args
        // constructor and the context fails to start. Both keys are always
        // supplied by application.yml (base) so defaults aren't needed. Mirrors
        // SyncConfigurationProperties.
        /** Master switch. When false, `resolve()` is a no-op. */
        val enabled: Boolean,
        /**
         * Pilot allow-list. When non-empty, canonicalization fires ONLY for the
         * listed yacht ids (and their twins resolve to the group's canonical),
         * so the behaviour can be validated on one boat before catalog-wide
         * rollout. Empty list = canonicalize every twin group.
         */
        val pilotYachtIds: List<Long>,
    )

package hr.workspace.boat4you.domains.catalouge.services

/**
 * The search tests drive YachtQueryingService / YachtDistributionService on a plain
 * EntityManager without Spring's transaction manager, so the gate + transaction of
 * [HeavyQueryGuard] (covered by HeavyQueryGuardTests) are replaced by a direct call.
 */
object PassThroughHeavyQueries : HeavyQueryRunner {
    override fun <T> read(
        query: HeavyQuery,
        block: () -> T,
    ): T = block()
}

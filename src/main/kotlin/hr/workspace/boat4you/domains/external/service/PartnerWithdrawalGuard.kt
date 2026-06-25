package hr.workspace.boat4you.domains.external.service

/**
 * Shared safety math for "mirror a partner withdrawal" — used by both the reservation/occupancy
 * reconcile ([ExternalAvailabilityReconcileService.reconcileAbsent]) and the MMK offer sync.
 *
 * A real cancellation/withdrawal wave is small relative to total inventory; a LARGE absent
 * fraction almost always means the partner returned a TRUNCATED-but-parseable (HTTP 200) response,
 * not real removals. We refuse to mass-remove on those: skip, log, and self-heal on the next
 * complete response. The #1 rule is to NEVER wipe good rows on a partial partner reply.
 *
 * Extracted so the offer path reuses the EXACT same cap as the proven reconcile path and the two
 * can never drift.
 */
object PartnerWithdrawalGuard {
    /** Max rows that may be withdrawn in a single run: `max(10, 30% of in-scope)`. */
    fun maxWithdrawable(inScopeCount: Int): Int = maxOf(10, (inScopeCount * 0.30).toInt())

    /**
     * @param partnerReturnedNonEmpty `false` ⇒ never withdraw (no-data, not "all gone").
     * @return `true` when applying [withdrawCount] withdrawals out of [inScopeCount] in-scope rows
     *   is SAFE (i.e. within the cap and the partner actually returned data).
     */
    fun isSafeToWithdraw(
        partnerReturnedNonEmpty: Boolean,
        inScopeCount: Int,
        withdrawCount: Int,
    ): Boolean {
        if (!partnerReturnedNonEmpty) return false
        if (withdrawCount == 0) return true
        return withdrawCount <= maxWithdrawable(inScopeCount)
    }
}

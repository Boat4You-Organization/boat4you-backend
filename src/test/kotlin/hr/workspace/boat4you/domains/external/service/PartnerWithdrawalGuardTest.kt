package hr.workspace.boat4you.domains.external.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PartnerWithdrawalGuardTest {
    @Test
    fun `cap is max of 10 and 30 percent of in-scope`() {
        assertEquals(10, PartnerWithdrawalGuard.maxWithdrawable(0))
        assertEquals(10, PartnerWithdrawalGuard.maxWithdrawable(33)) // 30% = 9 (floored) -> floor < 10 -> 10
        assertEquals(15, PartnerWithdrawalGuard.maxWithdrawable(50))
        assertEquals(30, PartnerWithdrawalGuard.maxWithdrawable(100))
        assertEquals(300, PartnerWithdrawalGuard.maxWithdrawable(1000))
    }

    @Test
    fun `empty partner response never withdraws (no-data, not all-gone)`() {
        assertFalse(PartnerWithdrawalGuard.isSafeToWithdraw(partnerReturnedNonEmpty = false, inScopeCount = 100, withdrawCount = 1))
        assertFalse(PartnerWithdrawalGuard.isSafeToWithdraw(partnerReturnedNonEmpty = false, inScopeCount = 0, withdrawCount = 0))
    }

    @Test
    fun `zero withdrawals is always safe`() {
        assertTrue(PartnerWithdrawalGuard.isSafeToWithdraw(true, inScopeCount = 0, withdrawCount = 0))
        assertTrue(PartnerWithdrawalGuard.isSafeToWithdraw(true, inScopeCount = 100, withdrawCount = 0))
    }

    @Test
    fun `withdrawal within the 30 percent cap is safe, one over is not`() {
        assertTrue(PartnerWithdrawalGuard.isSafeToWithdraw(true, inScopeCount = 100, withdrawCount = 30))
        assertFalse(PartnerWithdrawalGuard.isSafeToWithdraw(true, inScopeCount = 100, withdrawCount = 31))
    }

    @Test
    fun `tiny inventory still allows withdrawing up to 10`() {
        // 5 in scope -> cap max(10, 1) = 10 -> withdrawing all 5 is fine (e.g. small fleet rotation)
        assertTrue(PartnerWithdrawalGuard.isSafeToWithdraw(true, inScopeCount = 5, withdrawCount = 5))
    }

    @Test
    fun `mass withdrawal is blocked as a likely truncated response`() {
        assertFalse(PartnerWithdrawalGuard.isSafeToWithdraw(true, inScopeCount = 1000, withdrawCount = 500))
    }
}

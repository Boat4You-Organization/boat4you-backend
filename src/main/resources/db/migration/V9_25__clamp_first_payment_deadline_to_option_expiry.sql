-- First-payment deadline must never exceed the partner option expiry
-- (Mario 1.7.2026): the first payment is what confirms the option — a pay-by
-- date AFTER the option lapses invites the customer to pay for a boat the
-- agency may already have re-let. Partner payment plans regularly say "first
-- installment within N days" while the option window is shorter (Zen
-- 100183/2026: plan said 08.07, option expired 06.07 23:59).
--
-- Going forward the clamp happens at reservation creation
-- (ReservationMutationService.clampFirstPaymentDeadlineToOptionExpiry). This
-- migration fixes rows created before the fix: LIVE options only (expiry in
-- the future), still unconfirmed (no phase paid), and only the EARLIEST unpaid
-- phase — later installments keep the partner schedule. Dry-run 2026-07-01 on
-- prod matched exactly 1 row (the Zen reservation).
UPDATE reservation_payment_phase p
SET deadline = r.option_expires_at::date,
    modified = NOW()
FROM reservation r
WHERE r.reservation_flow_id = p.reservation_flow_id
  AND p.paid_on IS NULL
  AND r.option_expires_at IS NOT NULL
  AND r.option_expires_at >= NOW()
  AND r.sys_status IN ('OPTION', 'OPTION_WAITING')
  AND NOT EXISTS (
        SELECT 1 FROM reservation_payment_phase pp
        WHERE pp.reservation_flow_id = p.reservation_flow_id
          AND pp.paid_on IS NOT NULL
      )
  AND p.deadline > r.option_expires_at::date
  AND p.deadline = (
        SELECT MIN(p2.deadline) FROM reservation_payment_phase p2
        WHERE p2.reservation_flow_id = p.reservation_flow_id
          AND p2.paid_on IS NULL
      );

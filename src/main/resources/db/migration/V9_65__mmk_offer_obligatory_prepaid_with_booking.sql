-- MMK offer-level obligatory extras that MMK bills WITH the booking (26.9.2026).
--
-- MMK adds every obligatory extra of the offer with payableInBase=false into the
-- reservation clientPrice and payment plan (verified on all 165 reservations our
-- account held for 2026-2027: clientPrice = basePrice x (1 - discount%) + those
-- items, to the cent). The MMK offer sync classified them ON_SITE /
-- ADVANCE_TO_OPERATOR, so checkout left them out of our online total and told the
-- client to pay them at the marina (1441012/2026: 350 EUR fixed part).
--
-- The sync now writes WITH_BOOKING for them (ExtraPaymentType.fromMmkOfferObligatory);
-- this brings the rows already stored in line until each offer is re-synced.
-- Scope: future offers of MMK-only yachts. Catalogue rows only — existing bookings
-- keep their own reservation_extras snapshot and are not touched. Yacht-level extras
-- unchanged. Idempotent; price <> 0 matches the classifier (0 negative rows today).
--
-- ~341k rows / ~17 s on prod, so the same UPDATE was applied by hand in batches
-- before the cusma2 restart (snapshot of the old values kept for undo, DEPLOY_NOTES);
-- here it only catches rows an old-jar node rewrote in between.
-- Same lock guard as V9_58-V9_64: a lock conflict with a running MMK sync rolls this
-- back cleanly and the API's systemd restart tries again (deploy in a quiet window).
SET LOCAL lock_timeout = '5s';

UPDATE offer_extras oe
   SET payment_type = 'WITH_BOOKING'
  FROM offer o
 WHERE o.id = oe.offer_id
   AND o.date_from >= current_date
   AND o.yacht_id IN (SELECT em.system_id FROM external_mapping em WHERE em.type = 'Yacht' AND em.external_system_id = 1)
   AND NOT EXISTS (SELECT 1 FROM external_mapping n WHERE n.type = 'Yacht' AND n.external_system_id = 2 AND n.system_id = o.yacht_id)
   AND oe.obligatory
   AND NOT oe.payable_in_base
   AND oe.price <> 0
   AND oe.payment_type IS DISTINCT FROM 'WITH_BOOKING';

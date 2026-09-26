-- NauSys offer-level obligatory ADVANCE_PAYMENT extras are paid with the booking (26.9.2026).
--
-- NauSys's advance total (offer.ext_total_price = totalPriceWithExtras, "total price to be
-- paid in advance by the Agency to the Fleet operator, including ... all extras marked as
-- advance payment") always contains the offer's obligatory ADVANCE_PAYMENT extras: on 80,691
-- of 87,392 future NauSys offers it equals ext_client_price + those items to the cent, and on
-- none does it leave them out (the rest add further taxes/percentages on top). The sync
-- stored them as ADVANCE_TO_OPERATOR (payable_in_base = false), so checkout left them out of
-- our online total. The sync now writes WITH_BOOKING (ExtraPaymentType.fromNausysOfferObligatory);
-- this aligns the stored rows until each offer is re-synced. Same rule as V9_65 for MMK.
-- Scope: future offers of NauSys-only yachts, obligatory ADVANCE_TO_OPERATOR rows with
-- payable_in_base = false (how the offer sync stores ADVANCE_PAYMENT). Catalogue rows only —
-- existing bookings keep their reservation_extras snapshot. Yacht-level and optional extras
-- unchanged. Idempotent. Applied by hand in batches before the cusma2 restart (snapshot of
-- the old values kept for undo, DEPLOY_NOTES); here it only catches rows an old-jar node
-- rewrote in between.
-- Same lock guard as V9_58-V9_65: a lock conflict with a running NauSys offer sync rolls this
-- back cleanly and the API's systemd restart tries again (deploy in a quiet window).
SET LOCAL lock_timeout = '5s';

UPDATE offer_extras oe
   SET payment_type = 'WITH_BOOKING'
  FROM offer o
 WHERE o.id = oe.offer_id
   AND o.date_from >= current_date
   AND o.yacht_id IN (SELECT em.system_id FROM external_mapping em WHERE em.type = 'Yacht' AND em.external_system_id = 2)
   AND NOT EXISTS (SELECT 1 FROM external_mapping m WHERE m.type = 'Yacht' AND m.external_system_id = 1 AND m.system_id = o.yacht_id)
   AND oe.obligatory
   AND NOT oe.payable_in_base
   AND oe.price <> 0
   AND oe.payment_type = 'ADVANCE_TO_OPERATOR';

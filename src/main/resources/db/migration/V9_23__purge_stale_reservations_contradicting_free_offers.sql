-- One-time cleanup of the customer-facing legacy damage: stale RESERVATION/SERVICE rows that
-- HIDE a yacht the partner has actually freed. Root cause (fixed in code, same deploy): the old
-- absent-reconcile matched our reservations to the partner response by external_mapping, so a row
-- whose mapping went MISSING (96k legacy rows) or was DUPLICATE-mapped to another yacht (the Vi La
-- Ut case) was never removed and kept hard-blocking the boat in search.
--
-- SAFE-BY-PROOF criterion (no partner call needed): a future RESERVATION/SERVICE row that OVERLAPS
-- one of OUR OWN status='FREE' offers on the SAME yacht is self-contradictory. The offer sync owns
-- FREE and would have flipped the offer to UNAVAILABLE if the block were real — so the FREE offer is
-- the trustworthy signal and the reservation is the stale leftover. We delete ONLY:
--   * status RESERVATION or SERVICE (OPTION is honest pre-reserved — never touched here),
--   * option_expiration IS NULL (genuine block; expired "zombie" holds are owned by
--     ExternalAvailabilityReconcileService.purgeExpiredOptions, not this migration),
--   * date_to > CURRENT_DATE (future only — never rewrite history),
--   * AND a half-open-overlapping FREE offer exists on the same yacht (the contradiction).
--
-- Self-healing: if any deleted row were genuinely still booked at the partner, the next availability
-- sync re-upserts it from the partner's complete response — so over-deletion (none expected) repairs
-- itself within hours, and the booking-time live partner check prevents any overbooking meanwhile.
--
-- The bulk of the damage (96k mapping-less + 1003 duplicate-mapped rows that have NO contradicting
-- FREE offer) is NOT touched here — it is drained safely by the new natural-key reconcile over its
-- normal sync cycles, within the 30% circuit breaker.
--
-- FK safety: external_reservations has only an outbound yacht FK (ON DELETE SET NULL); no table has
-- an inbound FK to it; external_mapping.system_id is a soft reference (cleaned in step 1 below).
-- Post-deploy: yacht_search_view refreshes on its 5-min schedule (search hard-block reads
-- external_reservations live, so the fix is effective immediately on commit). Mario 29.6.2026.

-- 1) Drop the soft-orphan reservation mappings first (set-based).
DELETE FROM external_mapping m
WHERE m.type = 'ExternalReservation'
  AND EXISTS (
    SELECT 1 FROM external_reservations r
    WHERE r.id = m.system_id
      AND r.status IN ('RESERVATION', 'SERVICE')
      AND r.option_expiration IS NULL
      AND r.date_to > CURRENT_DATE
      AND EXISTS (
        SELECT 1 FROM offer o
        WHERE o.yacht_id = r.yacht_id
          AND o.status = 'FREE'
          AND o.date_from < r.date_to AND o.date_to > r.date_from
      )
  );

-- 2) Delete the self-contradictory future hard-blocks.
DELETE FROM external_reservations r
WHERE r.status IN ('RESERVATION', 'SERVICE')
  AND r.option_expiration IS NULL
  AND r.date_to > CURRENT_DATE
  AND EXISTS (
    SELECT 1 FROM offer o
    WHERE o.yacht_id = r.yacht_id
      AND o.status = 'FREE'
      AND o.date_from < r.date_to AND o.date_to > r.date_from
  );

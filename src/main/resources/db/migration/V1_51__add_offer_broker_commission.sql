-- V1_51: offer-level broker commission, populated from the partner's
-- per-offer commission field (MMK commissionValue, Nausys price.agencyCommission).
--
-- Separate from `offer.agency_commission`, which historically holds our
-- CUSTOMER discount (ext_client_price - our client_price) and is copied
-- into reservation_flow.agency_commission at booking time. That
-- semantics is wrong for the admin Offers workspace — the broker needs
-- to see "what we keep" (clientPrice - agencyPrice), which matches what
-- /bookings displays. Mixing the two would break reservation_flow
-- migration behavior, so we add a dedicated column instead of redefining
-- the existing one.

ALTER TABLE offer
    ADD COLUMN IF NOT EXISTS broker_commission NUMERIC;

-- Grant the app role read/write on the new column. Dev splits owner/app
-- roles (same pattern as V1_50 yacht_swap_audit); safe no-op in prod
-- when the roles are unified.
GRANT SELECT, UPDATE ON offer TO boat4you_app;

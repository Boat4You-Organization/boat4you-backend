-- Agencies the partner stops serving (MMK returns 400 "Illegal access to
-- entity") get their yachts hidden from the site until access returns — stale
-- availability is a booking risk (Mario rule 29.6.2026). Separate from
-- agency.active (manual blacklist) so the availability sync keeps re-probing
-- and auto-restores. Set/cleared by MmkAvailabilityIntegrationService; enforced
-- in yacht_search_view (R__1_03) + getValidYacht + ReservationFlowMutationService.
ALTER TABLE agency ADD COLUMN IF NOT EXISTS availability_blocked boolean NOT NULL DEFAULT false;

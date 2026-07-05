-- Agency mirror (Mario 5.7.2026): the partner's company list drives our agency list —
-- new MMK/NauSys companies are auto-created, companies the partner dropped are auto-deactivated.
--
-- sync_deactivated_by records WHICH external system's mirror deactivated the agency
-- (1=MMK, 2=NauSys, NULL=not deactivated by the mirror). Only the SAME system may
-- re-activate it when the company reappears — this both protects a manual admin
-- blacklist (toggleActive resets it to NULL) and prevents cross-system ping-pong
-- where one partner's sync would daily re-activate what the other partner's
-- reconcile deactivated.
ALTER TABLE agency
    ADD COLUMN sync_deactivated_by INT;

-- One partner company = at most one agency row per system. In-loop dedup handles a
-- duplicated id inside one response; this backstops concurrent runs (scheduled job +
-- manual admin trigger) from forking duplicate agencies. Verified 0 duplicates in prod.
CREATE UNIQUE INDEX ux_agency_source_system_external
    ON agency_source (external_system_id, external_id);

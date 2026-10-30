-- V9_112 AI call center (Mario 27.10.2026, design infra/docs/ai-call-center-dizajn-2026-10-27.md §6.3): what a call needs on
-- the inquiry. Only added columns with defaults, so the previous jar keeps working on this schema (it never maps them).
--   site / locale             - which of the 7 sites and language the inquiry came from (explicit form field, not Origin)
--   contact_consent / consent_text_version / consent_at - the mandatory consent checkbox (ZEK art. 50, GDPR); no consent = never called
--   phone_e164                - the phone normalised to E.164; NULL = unreadable as an international number = never called
--   details                   - structured form fields (people, cabins, budget, region, boat type, crew, week price)
--   do_not_call               - set by the client in a call or by Mario; kept as long as the inquiry exists
--   offer_sent_at / offer_note - Mario's "Ponuda poslana" with one sentence Nika reads in the follow-up call
--   human_takeover_at         - Mario's "Nazvat ću sam": no more AI calls for this inquiry
SET lock_timeout = '5s';

ALTER TABLE inquiry
    ADD COLUMN site                 VARCHAR(40),
    ADD COLUMN locale               VARCHAR(5),
    ADD COLUMN contact_consent      BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN consent_text_version VARCHAR(20),
    ADD COLUMN consent_at           TIMESTAMPTZ,
    ADD COLUMN phone_e164           VARCHAR(20),
    ADD COLUMN details              JSONB,
    ADD COLUMN do_not_call          BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN offer_sent_at        TIMESTAMPTZ,
    ADD COLUMN offer_note           VARCHAR(300),
    ADD COLUMN human_takeover_at    TIMESTAMPTZ;

CREATE INDEX idx_inquiry_phone_e164 ON inquiry (phone_e164);

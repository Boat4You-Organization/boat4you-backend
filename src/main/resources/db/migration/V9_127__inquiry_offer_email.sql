-- V9_127 Nika reads the whole offer e-mail (Mario 14.11.2026: "ona mora znat sve napisano"). Mario's "Ponuda poslana" finds
-- the offer he sent from one of the site mailboxes (cPanel IMAP, Sent folder), cleans it and stores the text here; the
-- follow-up call passes it to Nika as {{offer_email}}. Only added nullable columns, so the previous jar keeps working.
--   offer_email_text    - the cleaned body (quotes, signature and partner names removed), at most 8000 characters
--   offer_email_sent_at - when the e-mail was sent; the text is cleared 30 days after it (CallRetention), like recordings
SET lock_timeout = '5s';

ALTER TABLE inquiry
    ADD COLUMN offer_email_text    TEXT,
    ADD COLUMN offer_email_sent_at TIMESTAMPTZ;

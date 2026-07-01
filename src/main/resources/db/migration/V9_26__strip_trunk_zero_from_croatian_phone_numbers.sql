-- Customers habitually type their phone in NATIONAL format with the trunk
-- zero (098 360 398); the web PhoneInput used to prepend the dial code
-- verbatim, storing undialable "+3850983…" instead of "+38598…". The FE fix
-- (boat4you-web PhoneInput.getE164Format) strips the trunk zero going
-- forward; this migration repairs the rows already stored. Scope is
-- deliberately ONLY Croatian numbers (+3850…) — a Croatian subscriber number
-- never starts with 0, so the rewrite is unambiguous. Other dial codes were
-- checked on prod 2026-07-02 and had zero broken rows (Italy +390 is a VALID
-- prefix there and must never be rewritten). Prod counts at migration time:
-- reservation_flow 18, inquiry 2, users 0.
UPDATE reservation_flow
SET phone = regexp_replace(phone, '^\+3850+', '+385')
WHERE phone LIKE '+3850%';

UPDATE inquiry
SET phone = regexp_replace(phone, '^\+3850+', '+385')
WHERE phone LIKE '+3850%';

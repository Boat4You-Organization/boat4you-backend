-- Mario 22.8.2026: admin /users showed no phone for ANY customer. The profile
-- field (users.phone_number) was only ever written by the my-profile form;
-- the booking form saved the number onto reservation_flow.phone and the
-- inquiry form onto inquiry.phone, and nothing copied either back to the
-- user. The booking path now fills the profile on first contact
-- (ReservationFlowMutationService); this backfills the customers we already
-- have. Booking numbers win over inquiry numbers, newest first in each.
--
-- Guards, in order of why they exist:
--   * deleted_at IS NULL — an Art. 17 erasure nulls the profile phone on
--     purpose; a NULL-only check would write the number straight back.
--   * email must match the user — a logged-in non-admin books as THEMSELVES
--     even when the form names someone else, so a broker's or a friend's
--     flow must not stamp the client's phone onto the booker.
--   * ABANDONED flows rank last — a failed double-submit is often the
--     newest row and must not beat the real booking, but when a customer's
--     only flows failed, the number they typed is still their own.
--   * only values that pass the booking form's own rule (+ and 10–15 digits
--     once spaces/dashes/brackets are stripped) — inquiry.phone is free text.
CREATE TEMP TABLE phone_src AS
SELECT user_id, phone FROM (
    SELECT rf.user_id,
           regexp_replace(rf.phone, '[\s\-()]', '', 'g') AS phone,
           row_number() OVER (
               PARTITION BY rf.user_id
               ORDER BY (rf.status = 'ABANDONED') ASC, rf.created_at DESC, rf.id DESC
           ) AS rn
    FROM reservation_flow rf
    JOIN users u ON u.id = rf.user_id
    WHERE lower(rf.email) = lower(u.email)
      AND regexp_replace(rf.phone, '[\s\-()]', '', 'g') ~ '^\+?[0-9]{10,15}$'
) t WHERE rn = 1;

UPDATE users u
SET phone_number = s.phone
FROM phone_src s
WHERE u.id = s.user_id
  AND u.deleted_at IS NULL
  AND (u.phone_number IS NULL OR btrim(u.phone_number) = '');

CREATE TEMP TABLE inquiry_src AS
SELECT email, phone FROM (
    SELECT lower(i.email) AS email,
           regexp_replace(i.phone, '[\s\-()]', '', 'g') AS phone,
           row_number() OVER (PARTITION BY lower(i.email) ORDER BY i.created_at DESC, i.id DESC) AS rn
    FROM inquiry i
    WHERE regexp_replace(i.phone, '[\s\-()]', '', 'g') ~ '^\+?[0-9]{10,15}$'
) t WHERE rn = 1;

UPDATE users u
SET phone_number = s.phone
FROM inquiry_src s
WHERE lower(u.email) = s.email
  AND u.deleted_at IS NULL
  AND (u.phone_number IS NULL OR btrim(u.phone_number) = '');

DROP TABLE phone_src;
DROP TABLE inquiry_src;

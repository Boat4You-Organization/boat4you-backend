-- V9_130 Calls 31, 32 and 52 (inquiry 65): the client's only turn was "..." (ElevenLabs' text for noise or silence), Nika
-- asked "Allô ?" and the call ended after 30 s of silence, yet it was stored as DONE (UNCLEAR, CONFIRMED, CONFIRMED). From
-- this jar on a client turn counts only with a letter or a digit, so such a call is NO_ANSWER (CallOutcomeService). This
-- relabels the three calls the same way, like V9_129 did for 58, 62, 72 and 77. It schedules no new call (Mario decides,
-- 18.11.2026). A row that no longer matches (other status or outcome, or a client turn with text) is left alone.
SET LOCAL lock_timeout = '5s';

UPDATE inquiry_call c
SET status     = 'NO_ANSWER',
    outcome    = NULL,
    updated_at = now()
WHERE c.id IN (31, 32, 52)
  AND c.status = 'DONE'
  AND c.outcome IN ('CONFIRMED', 'UNCLEAR')
  AND c.kind <> 'HUMAN_CALLBACK'
  AND NOT EXISTS (
      SELECT 1
      FROM jsonb_array_elements(CASE WHEN jsonb_typeof(c.transcript) = 'array' THEN c.transcript ELSE '[]'::jsonb END) t
      WHERE t ->> 'role' = 'user'
        AND regexp_replace(coalesce(t ->> 'message', ''), '[[:punct:][:space:]…]', '', 'g') <> ''
  );

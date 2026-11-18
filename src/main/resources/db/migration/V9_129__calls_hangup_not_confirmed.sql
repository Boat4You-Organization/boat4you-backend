-- V9_129 Calls in which the client never said a word were stored as DONE (CONFIRMED or UNCLEAR) and never dialled again.
-- Call 72 (18.11.2026): ElevenLabs' call_successful only says that Nika broke no quality rule, and every rule counts a
-- hang-up as success. From this jar on such a call ends as NO_ANSWER (CallOutcomeService.applyTranscription). This relabels
-- the four production calls found on 18.11.2026 (58, 62, 72, 77) the same way. It schedules no new call: Mario decides
-- ("Nazovi sada" in the admin, Mario 18.11.2026). A row that no longer matches (other status, outcome or a client turn in
-- the transcript) is left alone, so the statement changes nothing on any other database.
SET lock_timeout = '5s';

UPDATE inquiry_call c
SET status     = 'NO_ANSWER',
    outcome    = NULL,
    updated_at = now()
WHERE c.id IN (58, 62, 72, 77)
  AND c.status = 'DONE'
  AND c.outcome IN ('CONFIRMED', 'UNCLEAR')
  AND c.kind <> 'HUMAN_CALLBACK'
  AND NOT EXISTS (
      SELECT 1
      FROM jsonb_array_elements(CASE WHEN jsonb_typeof(c.transcript) = 'array' THEN c.transcript ELSE '[]'::jsonb END) t
      WHERE t ->> 'role' = 'user'
        AND btrim(coalesce(t ->> 'message', '')) <> ''
  );

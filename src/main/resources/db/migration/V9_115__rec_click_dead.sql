-- Contact Center P6 R3 (3.11.2026): a dead click — no link, not a form field, and neither a DOM change, a scroll nor the end of
-- the page view within 1500 ms after it (the page did not react). Set by the worker when a page view closes; NULL until a
-- recording's file has been read (older page views are back-filled from their files while the files exist).
ALTER TABLE rec_click ADD COLUMN dead BOOLEAN;
-- The back-fill finds its page views through the clicks still unknown; new clicks never enter it, so it stays small.
CREATE INDEX idx_rec_click_dead_unknown ON rec_click (page_view_id) WHERE dead IS NULL;

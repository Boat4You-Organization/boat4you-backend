-- Contact Center P6 R3 (3.11.2026): a dead click — no link, not a form field, and neither a DOM change nor the end of the page
-- view within 1500 ms after it (the page did not react). Set by the worker when a page view closes; NULL until a recording's
-- file has been read (older page views are back-filled from their files while the files exist).
ALTER TABLE rec_click ADD COLUMN dead BOOLEAN;

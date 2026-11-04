-- Contact Center P6 R4 (3.11.2026): where the mouse rests, per recorded page view (desktop only: a page view with a touch gets
-- none). One row per element and 1024-grid cell within it (ex/ey = the cell centre, 0..32767): the milliseconds the pointer
-- stayed there (each sample at most 2 s, so a parked mouse does not count for minutes) and the samples. Written by the worker
-- when the page view closes; recordings made before the F8 recorder carry no element on a move, so they get no rows.
CREATE TABLE rec_hover (
    id BIGSERIAL PRIMARY KEY,
    page_view_id BIGINT NOT NULL REFERENCES rec_page_view (id) ON DELETE CASCADE,
    hash VARCHAR(40),
    hash_beta VARCHAR(40),
    ex INTEGER NOT NULL,
    ey INTEGER NOT NULL,
    ms INTEGER NOT NULL,
    n INTEGER NOT NULL
);
CREATE INDEX idx_rec_hover_page_view ON rec_hover (page_view_id);
GRANT SELECT, INSERT, UPDATE, DELETE ON rec_hover TO boat4you_app;
GRANT USAGE, SELECT ON SEQUENCE rec_hover_id_seq TO boat4you_app;

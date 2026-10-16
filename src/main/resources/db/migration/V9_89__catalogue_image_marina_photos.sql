-- V9_89 (16.10.2026): catalogue_image - photos we hold ourselves, outside the partner-owned yacht_image.
--
-- Mario 16.10.2026: every boat page shows 2-3 photos of its departure marina in the gallery, where we have them
-- (photos DESIGN.md, section Backend). yacht_image cannot hold them: both partner syncs (MMK / NauSys syncPictures)
-- delete every row of a boat whose external_url is not in the partner's list, file included, and ImageDownloadJob reads
-- external_url of every unsynced row. Nothing in the syncs, the image purge or ImageDownloadJob reads this table.
--
-- Ids: catalogue_image_id_seq starts at 2,000,000,000 and yacht_image_id_seq is capped one below (it is at ~359,000 on
-- 16.10.2026), so GET /public/image/{id} tells the two tables apart by the number alone (YachtImageService) and every
-- image URL of the 7 web sites and Bunny keeps its shape. The CHECK keeps a hand-written id out of the yacht range.
--
-- kind: only MARINA for now (model photos are read from a same-model boat at read time, no table - YachtGalleryService).
-- location_id: the marina row the photo belongs to; a curated location_same_place pair shares its photos (the read
-- looks up both ids). url = stored path under the upload directory, like yacht_image.url. credit / source_url are shown
-- with the photo (lightbox caption); licence and created_by stay admin-only (takedown log).
--
-- No data. Idempotent, and a re-run takes no lock outside catalogue_image and its own sequence. Only the first apply
-- locks shared objects: the FK of the CREATE TABLE takes SHARE ROW EXCLUSIVE on location (the catalogue syncs write it),
-- and ALTER SEQUENCE yacht_image_id_seq takes SHARE ROW EXCLUSIVE on the sequence, which waits for every open
-- transaction that took a nextval from it - an MMK / NauSys agency sync that stored one new picture holds that until the
-- agency commits, minutes. So the file is applied by hand before the API restart and retried until it commits
-- (DEPLOY_NOTES, like V9_83); Flyway's run at boot then skips both: CREATE TABLE IF NOT EXISTS returns before the FK,
-- and the guard reads pg_catalog.pg_sequence (not the pg_sequences view, which opens the sequence). lock_timeout keeps
-- the hand run from queueing in front of the syncs: a timeout rolls the whole file back, nothing half done.
SET LOCAL lock_timeout = '5s';

CREATE SEQUENCE IF NOT EXISTS public.catalogue_image_id_seq AS bigint MINVALUE 2000000000 START WITH 2000000000;

-- guard: a partner image id can never reach the catalogue range (altered only when not capped yet: no lock on a re-run)
DO $$
BEGIN
    IF (SELECT seqmax FROM pg_catalog.pg_sequence WHERE seqrelid = 'public.yacht_image_id_seq'::regclass) <> 1999999999 THEN
        ALTER SEQUENCE public.yacht_image_id_seq MAXVALUE 1999999999;
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS public.catalogue_image (
    id          BIGINT        PRIMARY KEY DEFAULT nextval('public.catalogue_image_id_seq'),
    kind        VARCHAR(16)   NOT NULL DEFAULT 'MARINA',
    location_id BIGINT        NOT NULL REFERENCES public.location (id) ON DELETE CASCADE,
    url         VARCHAR(255)  NOT NULL,
    caption     VARCHAR(255),
    credit      VARCHAR(255),
    source_url  VARCHAR(1024),
    licence     VARCHAR(64),
    "position"  SMALLINT      NOT NULL DEFAULT 0,
    active      BOOLEAN       NOT NULL DEFAULT true,
    created     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by  VARCHAR(128),
    CONSTRAINT catalogue_image_kind_check CHECK (kind IN ('MARINA')),
    CONSTRAINT catalogue_image_id_range CHECK (id >= 2000000000)
);

ALTER SEQUENCE public.catalogue_image_id_seq OWNED BY public.catalogue_image.id;

CREATE INDEX IF NOT EXISTS idx_catalogue_image_location_active_position
    ON public.catalogue_image (location_id, active, "position");

GRANT SELECT, INSERT, UPDATE, DELETE ON public.catalogue_image TO boat4you_app;
GRANT USAGE, SELECT ON SEQUENCE public.catalogue_image_id_seq TO boat4you_app;

-- Check after deploy (read only):
--   SELECT last_value, max_value FROM pg_sequences WHERE sequencename IN ('catalogue_image_id_seq', 'yacht_image_id_seq');
--     -> catalogue 2000000000 / 9223372036854775807, yacht ~359000 / 1999999999
--   SELECT count(*) FROM catalogue_image;  -> 0 (photos come through the admin API)
--
-- Undo (by hand, only while the table is empty or its photos are given up):
--   DROP TABLE IF EXISTS catalogue_image;  -- drops catalogue_image_id_seq with it (OWNED BY)
--   ALTER SEQUENCE yacht_image_id_seq NO MAXVALUE;

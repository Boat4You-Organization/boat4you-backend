-- V9_111 (28.10.2026, Mario: "Nema mi slika marina" on CC Lagoon 55 Black Caviar 11675, Port of Split):
-- the second marina photo import of 20.10.2026 (catalogue_image ids 2000000089.., 123 rows, 48 marinas) wrote url
-- 'marina-<location_id>/<uuid>' while the files on disk are 'marina-<location_id>/<uuid>.webp' (the first import, ids
-- 2000000000-2000000088, wrote the extension). YachtImageService.resizeImage looks up the file by url, so every batch-2
-- photo answers 404 "Image not found" (code 1602) and the gallery/lightbox shows an empty grey slide with the credit.
-- Fix: append '.webp' to exactly those urls (uuid with no extension). Idempotent: a second run matches nothing.
-- PRE-APPLIED by hand on cusma4 as boat4you_owner; Flyway on the next API start then changes nothing.
-- Numbered after the contact-center reservations (P1b V9_103-V9_106, P1c V9_107-V9_110): merge into main only after
-- V9_107/V9_108 are live, or prod Flyway (out-of-order off) would refuse them.
-- Rollback: UPDATE catalogue_image SET url = left(url, -5) WHERE id >= 2000000089 AND url ~ '^marina-[0-9]+/[0-9a-f-]{36}\.webp$';
UPDATE public.catalogue_image
SET url = url || '.webp'
WHERE kind = 'MARINA'
  AND url ~ '^marina-[0-9]+/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

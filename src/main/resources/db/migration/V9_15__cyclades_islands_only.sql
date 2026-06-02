-- Cyclades (region) should list only Cyclades-ISLAND marinas. Athens-area (Attica) and
-- Lavrion are mainland departure ports, not islands — a "Cyclades" search should return
-- island offers only. Drop those location_region rows from the Cyclades region.
--
-- Resolve by name/city (region/location IDs renumber across syncs/environments). Marina names
-- carry the " | {city}" suffix (V9_13), so an Athens/Lavrion match hits either part. Idempotent.
DELETE FROM location_region lr
USING location l, region r
WHERE lr.location_id = l.id
  AND lr.region_id = r.id
  AND r.name = 'Cyclades'
  AND (
        l.name ILIKE '%athen%' OR l.name ILIKE '%lavrio%'
        OR l.city ILIKE '%athen%' OR l.city ILIKE '%lavrio%'
      );

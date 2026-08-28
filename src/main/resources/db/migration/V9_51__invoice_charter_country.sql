-- Charter departure country (Mario, 28.8.2026): so the invoice list shows at
-- a glance whether the charter left from Croatia, Greece or elsewhere.
ALTER TABLE invoice
    ADD COLUMN charter_country VARCHAR(63);

-- Backfill 1 — linked invoices from the booking's departure-location country
-- (ISO alpha-2 in reservation_view, stored here as the English name).
UPDATE invoice i
SET charter_country =
    CASE v.location_from_country
        WHEN 'HR' THEN 'Croatia'
        WHEN 'GR' THEN 'Greece'
        WHEN 'IT' THEN 'Italy'
        WHEN 'TR' THEN 'Türkiye'
        WHEN 'ES' THEN 'Spain'
        WHEN 'FR' THEN 'France'
        WHEN 'ME' THEN 'Montenegro'
        ELSE v.location_from_country
    END
FROM reservation_view v
WHERE v.reservation_flow_id = i.reservation_flow_id
  AND i.reservation_flow_id IS NOT NULL
  AND v.location_from_country IS NOT NULL
  AND i.charter_country IS NULL;

-- Backfill 2 — standalone commission invoices: the item text names the
-- departure base right after "iz " (HR) / "from " (EN); the base's leading
-- city keyword pins the country. Keywords cover every base present on the
-- 28.8.2026 import (verified against all 109 rows).
UPDATE invoice SET charter_country = 'Croatia'
WHERE charter_country IS NULL
  AND invoice_item ~ ' (iz|from) (Trogir|Kaštel|Kastel|Marina Kaštela|Marina Kastela|Split|ACI Marina Split|Dubrovnik|Rogoznica|Sukošan|Sukosan|Marina Agana)';

UPDATE invoice SET charter_country = 'Greece'
WHERE charter_country IS NULL
  AND invoice_item ~ ' (iz|from) (Athens|Alimos|Lavrio|Preveza|Lefka|Corfu|Skiathos|Kos|Santorini|Paros|Mykonos|Kefalonia|Nea Peramos)';

UPDATE invoice SET charter_country = 'Türkiye'
WHERE charter_country IS NULL AND invoice_item ~ ' (iz|from) Fethiye';

UPDATE invoice SET charter_country = 'Italy'
WHERE charter_country IS NULL AND invoice_item ~ ' (iz|from) (Marina di Cannigione|Salerno)';

UPDATE invoice SET charter_country = 'Antigua and Barbuda'
WHERE charter_country IS NULL AND invoice_item ~ ' (iz|from) Antigua';

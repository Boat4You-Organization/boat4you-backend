-- V1_53: audit table for yacht duplicates before any merge.
-- DOES NOT mutate yacht / offer / reservation rows — merge is a manual
-- follow-up after Mario reviews the audit output.
--
-- Background (23.4.2026): 447 (agency_id, name) groups have >1 row.
-- Pattern: the same physical yacht has separate records for its MMK
-- mapping and its NauSys mapping — the historical sync did not match
-- an incoming partner yacht against a yacht already stored under the
-- other partner. Frontend slug routing picks ONE row by id and can
-- therefore surface a siphoned-off extras set instead of the union.
--
-- This migration creates `yacht_dedup_audit` and a single query that
-- populates it. The audit row flags the "canonical" candidate (most
-- yacht_extras, then most yacht_images, then lower id = older/first
-- synced). The script is deliberately additive + idempotent; the user
-- re-runs the INSERT as new candidates appear.

CREATE TABLE IF NOT EXISTS yacht_dedup_audit (
    id                   BIGSERIAL PRIMARY KEY,
    agency_id            BIGINT    NOT NULL,
    name                 VARCHAR   NOT NULL,
    dup_count            INT       NOT NULL,
    canonical_yacht_id   BIGINT    NOT NULL,
    other_yacht_ids      BIGINT[]  NOT NULL,
    canonical_extras_ct  INT       NOT NULL,
    canonical_images_ct  INT       NOT NULL,
    canonical_offers_ct  INT       NOT NULL,
    other_extras_ct      INT       NOT NULL,
    other_images_ct      INT       NOT NULL,
    other_offers_ct      INT       NOT NULL,
    systems_summary      TEXT      NOT NULL,
    merged_at            TIMESTAMP WITH TIME ZONE,
    merged_by            VARCHAR,
    CONSTRAINT yacht_dedup_audit_unique UNIQUE (agency_id, name)
);

GRANT SELECT, INSERT, UPDATE ON yacht_dedup_audit TO boat4you_app;
GRANT USAGE, SELECT ON SEQUENCE yacht_dedup_audit_id_seq TO boat4you_app;

-- Populate. `ON CONFLICT (agency_id, name) DO UPDATE SET ...` means we
-- refresh counts if the same (agency, name) pair is re-audited after a
-- new sync run.
INSERT INTO yacht_dedup_audit (
    agency_id, name, dup_count,
    canonical_yacht_id, other_yacht_ids,
    canonical_extras_ct, canonical_images_ct, canonical_offers_ct,
    other_extras_ct, other_images_ct, other_offers_ct,
    systems_summary
)
SELECT
    agg.agency_id,
    agg.name,
    agg.dup_count,
    agg.canonical_yacht_id,
    agg.other_yacht_ids,
    agg.canonical_extras_ct,
    agg.canonical_images_ct,
    agg.canonical_offers_ct,
    agg.other_extras_ct,
    agg.other_images_ct,
    agg.other_offers_ct,
    agg.systems_summary
FROM (
    SELECT
        y.agency_id,
        y.name,
        COUNT(*) AS dup_count,
        (ARRAY_AGG(y.id ORDER BY
            (SELECT COUNT(*) FROM yacht_extras ye WHERE ye.yacht_id = y.id) DESC,
            (SELECT COUNT(*) FROM yacht_image yi WHERE yi.yacht_id = y.id) DESC,
            y.id ASC
        ))[1] AS canonical_yacht_id,
        (ARRAY_AGG(y.id ORDER BY
            (SELECT COUNT(*) FROM yacht_extras ye WHERE ye.yacht_id = y.id) DESC,
            (SELECT COUNT(*) FROM yacht_image yi WHERE yi.yacht_id = y.id) DESC,
            y.id ASC
        ))[2:] AS other_yacht_ids,
        MAX((SELECT COUNT(*) FROM yacht_extras ye WHERE ye.yacht_id = y.id))::INT AS canonical_extras_ct,
        MAX((SELECT COUNT(*) FROM yacht_image yi WHERE yi.yacht_id = y.id))::INT AS canonical_images_ct,
        MAX((SELECT COUNT(*) FROM offer o WHERE o.yacht_id = y.id))::INT AS canonical_offers_ct,
        SUM((SELECT COUNT(*) FROM yacht_extras ye WHERE ye.yacht_id = y.id))::INT
            - MAX((SELECT COUNT(*) FROM yacht_extras ye WHERE ye.yacht_id = y.id))::INT AS other_extras_ct,
        SUM((SELECT COUNT(*) FROM yacht_image yi WHERE yi.yacht_id = y.id))::INT
            - MAX((SELECT COUNT(*) FROM yacht_image yi WHERE yi.yacht_id = y.id))::INT AS other_images_ct,
        SUM((SELECT COUNT(*) FROM offer o WHERE o.yacht_id = y.id))::INT
            - MAX((SELECT COUNT(*) FROM offer o WHERE o.yacht_id = y.id))::INT AS other_offers_ct,
        STRING_AGG(DISTINCT es.name, ',' ORDER BY es.name) AS systems_summary
    FROM yacht y
    LEFT JOIN external_mapping em ON em.system_id = y.id AND em.type = 'Yacht'
    LEFT JOIN external_system es  ON es.id = em.external_system_id
    GROUP BY y.agency_id, y.name
    HAVING COUNT(*) > 1
) agg
ON CONFLICT (agency_id, name) DO UPDATE SET
    dup_count             = EXCLUDED.dup_count,
    canonical_yacht_id    = EXCLUDED.canonical_yacht_id,
    other_yacht_ids       = EXCLUDED.other_yacht_ids,
    canonical_extras_ct   = EXCLUDED.canonical_extras_ct,
    canonical_images_ct   = EXCLUDED.canonical_images_ct,
    canonical_offers_ct   = EXCLUDED.canonical_offers_ct,
    other_extras_ct       = EXCLUDED.other_extras_ct,
    other_images_ct       = EXCLUDED.other_images_ct,
    other_offers_ct       = EXCLUDED.other_offers_ct,
    systems_summary       = EXCLUDED.systems_summary
WHERE yacht_dedup_audit.merged_at IS NULL;

-- Equipment link fix, schema (equipment audit 8.10.2026, FIX_CONTRACT 2, 7, 9). Fast: catalogue-size changes only.
--
-- 0. _equipment_backup_20261008: the catalogue as it was, once (review 8.10.: the old R__1_05 restores ids 1-58 only).
--    Rollback: UPDATE equipment e SET name = b.name, category = b.category, match_keys = b.match_keys,
--    filter_order = b.filter_order, merged_into_id = NULL FROM _equipment_backup_20261008 b WHERE b.id = e.id;
--    (DEPLOY_NOTES has the whole recipe and its order).
-- 1. equipment.merged_into_id: an alias row points at the code that replaces it (bow-thruster-deck -> bow-thruster,
--    refrigerator -> fridge, sundeck-cushions -> sun-pads; set by V9_75 and R__1_05). The alias row stays forever so a
--    saved id still resolves (/public/yachts?amenities=<alias> is canonicalised in YachtController), its match_keys stay
--    '' (R__1_05) and the matcher skips it. Never delete it: yacht_equipment.equipment_id is ON DELETE SET NULL.
-- 2. partner_equipment_mapping: an explicit link for one partner item, ahead of the matcher. A catalogue item is
--    (system, partner item id, ''); an MMK free-text item (parentId -1) is (1, -1, EquipmentNames.normalize(name)).
--    equipment_id NULL = deliberately no link. Rows are written by R__1_05 only (single writer, upsert); read through
--    partnerEquipmentMappingCache (10 h, restart refreshes). Targets by label_code, never by numeric id.
-- Rollback: the old JAR ignores the extra column and tables (ddl-auto validate tolerates them).
-- Flyway only, or psql --single-transaction -f (SET LOCAL needs one transaction).
SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS _equipment_backup_20261008 AS
SELECT * FROM equipment;
COMMENT ON TABLE _equipment_backup_20261008 IS
    'equipment before V9_74 / V9_75 / R__1_05 v2 (equipment audit 8.10.2026); rollback source, drop after the fix has settled.';

ALTER TABLE equipment ADD COLUMN IF NOT EXISTS merged_into_id bigint NULL REFERENCES equipment (id);
COMMENT ON COLUMN equipment.merged_into_id IS
    'Alias row: the canonical equipment that replaces this code. Kept so old ids and saved filters still resolve; the matcher and the catalogue lists skip it.';

CREATE TABLE IF NOT EXISTS partner_equipment_mapping (
    id                 bigserial PRIMARY KEY,
    external_system_id integer     NOT NULL REFERENCES external_system (id),
    partner_item_id    bigint      NOT NULL,            -- external_equipment.external_id; -1 = MMK free text
    partner_name_norm  text        NOT NULL DEFAULT '', -- '' for catalogue items; EquipmentNames.normalize(name) for -1
    equipment_id       bigint      NULL REFERENCES equipment (id), -- NULL = deliberately no link
    note               text        NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT partner_equipment_mapping_uq UNIQUE (external_system_id, partner_item_id, partner_name_norm),
    CONSTRAINT partner_equipment_mapping_name_ck CHECK ((partner_item_id > 0) = (partner_name_norm = ''))
);
COMMENT ON TABLE partner_equipment_mapping IS
    'Explicit link of one partner equipment item to our equipment catalogue, ahead of the name matcher (EquipmentLinkResolver). equipment_id NULL = no link. Seeded and maintained by R__1_05.';

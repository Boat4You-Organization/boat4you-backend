-- Equipment link fix, schema (equipment audit 8.10.2026, FIX_CONTRACT 2, 7, 9). Fast: two catalogue-size changes.
--
-- 1. equipment.merged_into_id: an alias row points at the code that replaces it (bow-thruster-deck -> bow-thruster,
--    refrigerator -> fridge, sundeck-cushions -> sun-pads; set by V9_75). The alias row stays forever so a saved id
--    still resolves (/public/yachts?amenities=<alias> is canonicalised in YachtController), its match_keys stay ''
--    (R__1_05) and the matcher skips it. Never delete it: yacht_equipment.equipment_id is ON DELETE SET NULL.
-- 2. partner_equipment_mapping: an explicit link for one partner item, ahead of the matcher. A catalogue item is
--    (system, partner item id, ''); an MMK free-text item (parentId -1) is (1, -1, EquipmentNames.normalize(name)).
--    equipment_id NULL = deliberately no link. Read through partnerEquipmentMappingCache (10 h, restart refreshes).
--    Changes go in as Flyway data migrations; the targets are resolved by label_code, never by numeric id.
-- Rollback: the old JAR ignores the extra column and table (ddl-auto validate tolerates them).
SET LOCAL lock_timeout = '5s';

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
    'Explicit link of one partner equipment item to our equipment catalogue, ahead of the name matcher (EquipmentLinkResolver). equipment_id NULL = no link.';

-- Seed: 80 MMK free-text names whose verified target the keys cannot express (example: "Kitchen equipment",
-- "Banana", "Dissalator 2001/H"). Target by label_code, NONE = NULL.
DROP TABLE IF EXISTS _partner_equipment_mapping_seed;
CREATE TEMP TABLE _partner_equipment_mapping_seed (
    seed_order         int     NOT NULL,
    external_system_id integer NOT NULL,
    partner_item_id    bigint  NOT NULL,
    partner_name_norm  text    NOT NULL,
    target_label_code  varchar NOT NULL,
    note               text    NOT NULL
) ON COMMIT DROP;

INSERT INTO _partner_equipment_mapping_seed (seed_order, external_system_id, partner_item_id, partner_name_norm, target_label_code, note)
VALUES
    (1, 1, -1, 'battened mainsail with lazyjack system', 'lazy-jacks', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (2, 1, -1, 'digital speed and deep control', 'logge-speed-wind', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (3, 1, -1, 'banana', 'water-toys', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (4, 1, -1, 'pots pans', 'kitchen-utensils', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (5, 1, -1, 'sunbeds', 'sun-pads', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (6, 1, -1, 'tubes', 'water-toys', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (7, 1, -1, 'external table', 'cockpit-table', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (8, 1, -1, 'large dinette with extra double bed', 'lowerable-salon-table', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (9, 1, -1, 'linen full set', 'pillows-and-blankets', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (10, 1, -1, 'nespresso', 'coffee-machine', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (11, 1, -1, 'interior and cockpit speakers', 'outside-speakers', 'equipment audit 8.10.2026, verified; matcher alone: inside-speakers'),
    (12, 1, -1, 'linen', 'pillows-and-blankets', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (13, 1, -1, 'linens set', 'pillows-and-blankets', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (14, 1, -1, 'mp3', 'audio-system', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (15, 1, -1, 'speedometer echo sounder', 'logge-speed-wind', 'equipment audit 8.10.2026, verified; matcher alone: depth-sounder'),
    (16, 1, -1, '230 volt socket', 'shore-connection-220v', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (17, 1, -1, 'bathing platform with shower', 'outside-shower', 'equipment audit 8.10.2026, verified; matcher alone: bathing-platform'),
    (18, 1, -1, 'bluetooth player', 'audio-system', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (19, 1, -1, 'bow solarium', 'sun-pads', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (20, 1, -1, 'dinette convertible', 'lowerable-salon-table', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (21, 1, -1, 'dissalator 2001 h', 'water-maker', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (22, 1, -1, 'horn', 'fog-horn', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (23, 1, -1, 'hydraulic platform with remote', 'bathing-platform', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (24, 1, -1, 'radio on the flybridge', 'audio-system', 'equipment audit 8.10.2026, verified; matcher alone: flybridge'),
    (25, 1, -1, 'sea bobs', 'water-toys', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (26, 1, -1, 'speakers in out', 'audio-system', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (27, 1, -1, 'sun loungers', 'sun-pads', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (28, 1, -1, 'sunbed', 'sun-pads', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (29, 1, -1, 'zatera di salvataggio', 'liferaft', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (30, 1, -1, '2b g triton2 instuments display', 'logge-speed-wind', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (31, 1, -1, 'aft shower', 'outside-shower', 'equipment audit 8.10.2026, verified; matcher alone: shower'),
    (32, 1, -1, 'anchor winch remote control', 'electric-anchor-windlass', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (33, 1, -1, 'aux for ipod iphone bluetooth usb', 'audio-system', 'equipment audit 8.10.2026, verified; matcher alone: usb-sockets'),
    (34, 1, -1, 'banana for 3 pax', 'water-toys', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (35, 1, -1, 'bed sheets', 'pillows-and-blankets', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (36, 1, -1, 'big kitchen fork', 'kitchen-utensils', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (37, 1, -1, 'bluetooth music', 'audio-system', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (38, 1, -1, 'bluetooth sound box', 'audio-system', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (39, 1, -1, 'cockpit foldtable', 'cockpit-table', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (40, 1, -1, 'convertible dinette', 'lowerable-salon-table', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (41, 1, -1, 'corkscrew', 'kitchen-utensils', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (42, 1, -1, 'cushions in fly bridge', 'sun-pads', 'equipment audit 8.10.2026, verified; matcher alone: flybridge'),
    (43, 1, -1, 'depth speed wind repeaters', 'logge-speed-wind', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (44, 1, -1, 'dishwashing machine', 'dishwasher', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (45, 1, -1, 'el mooring winches in cockpit', 'electric-winches', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (46, 1, -1, 'el winch for all monoeuvres', 'electric-winches', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (47, 1, -1, 'el winch for main', 'electric-winches', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (48, 1, -1, 'el windlass', 'electric-anchor-windlass', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (49, 1, -1, 'espresso machine', 'coffee-machine', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (50, 1, -1, 'floating mats', 'water-toys', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (51, 1, -1, 'full teak', 'teak-deck', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (52, 1, -1, 'induction hot plate', 'cooker', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (53, 1, -1, 'inductions hub', 'cooker', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (54, 1, -1, 'inflatable slide', 'water-toys', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (55, 1, -1, 'jenerator', 'generator', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (56, 1, -1, 'kitchen with cooking facilities', 'cooker', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (57, 1, -1, 'knives', 'kitchen-utensils', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (58, 1, -1, 'life raft and life jackets', 'liferaft', 'equipment audit 8.10.2026, verified; matcher alone: life-jackets'),
    (59, 1, -1, 'light buoy', 'life-buoy', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (60, 1, -1, 'log speedometer depth sounder', 'logge-speed-wind', 'equipment audit 8.10.2026, verified; matcher alone: depth-sounder'),
    (61, 1, -1, 'medical equipment', 'first-aid-kit', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (62, 1, -1, 'mini bar', 'fridge', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (63, 1, -1, 'music player', 'audio-system', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (64, 1, -1, 'navigation kit', 'navigation-set', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (65, 1, -1, 'norwegian cruising guide', 'navigation-set', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (66, 1, -1, 'outside table', 'cockpit-table', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (67, 1, -1, 'raymarine p70s autopilotg', 'autopilot', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (68, 1, -1, 'raymarine plotters in saloon and flybridge', 'outside-GPS-plotter', 'equipment audit 8.10.2026, verified; matcher alone: salon-GPS-plotter'),
    (69, 1, -1, 'saloon lowering table', 'lowerable-salon-table', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (70, 1, -1, 'shore charger', 'battery-charger', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (71, 1, -1, 'speakers 2 interior 2 exterior', 'outside-speakers', 'equipment audit 8.10.2026, verified; matcher alone: inside-speakers'),
    (72, 1, -1, 'speakers in salon', 'inside-speakers', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (73, 1, -1, 'sunlounging area cushions', 'sun-pads', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (74, 1, -1, 'swimming platform with shower', 'outside-shower', 'equipment audit 8.10.2026, verified; matcher alone: bathing-platform'),
    (75, 1, -1, 'tube', 'water-toys', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (76, 1, -1, 'usb player', 'audio-system', 'equipment audit 8.10.2026, verified; matcher alone: usb-sockets'),
    (77, 1, -1, 'vebasto', 'heating', 'equipment audit 8.10.2026, verified; matcher alone: NONE'),
    (78, 1, -1, 'vhf epirb', 'epirb', 'equipment audit 8.10.2026, verified; matcher alone: vhf-radio'),
    (79, 1, -1, 'wind and depth sounders', 'logge-speed-wind', 'equipment audit 8.10.2026, verified; matcher alone: depth-sounder'),
    (80, 1, -1, 'wind speed', 'logge-speed-wind', 'equipment audit 8.10.2026, verified; matcher alone: NONE');

DO $$
DECLARE
    missing text;
BEGIN
    -- A fresh database runs every versioned migration before R__1_05 creates the catalogue: nothing to point at yet.
    IF NOT EXISTS (SELECT 1 FROM equipment WHERE label_code = 'air-conditioning') THEN
        RAISE NOTICE 'partner_equipment_mapping seed skipped: equipment catalogue not seeded yet (fresh database)';
        RETURN;
    END IF;

    SELECT string_agg(DISTINCT s.target_label_code, ', ')
    INTO missing
    FROM _partner_equipment_mapping_seed s
    WHERE s.target_label_code <> 'NONE'
      AND NOT EXISTS (SELECT 1 FROM equipment e WHERE e.label_code = s.target_label_code);
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'partner_equipment_mapping seed: unknown label_code %', missing;
    END IF;

    INSERT INTO partner_equipment_mapping (external_system_id, partner_item_id, partner_name_norm, equipment_id, note)
    SELECT s.external_system_id, s.partner_item_id, s.partner_name_norm, e.id, s.note
    FROM _partner_equipment_mapping_seed s
    LEFT JOIN equipment e ON e.label_code = s.target_label_code
    ORDER BY s.seed_order
    ON CONFLICT (external_system_id, partner_item_id, partner_name_norm) DO NOTHING;
END $$;

-- Expand the Equipment catalog from 3 to 9 categories so the yacht detail
-- page can render the same level of detail as the partner-source charter
-- sites (Boataround / Sailo). Original 3 buckets — SALOON_AND_CABINS,
-- NAVIGATION_AND_SAFETY, ENTERTAINMENT — were too coarse: a partner row
-- like "Cockpit cushions" or "Refrigerator" landed under a single Saloon
-- header alongside totally unrelated items.
--
-- Layout target (mirrors the reference screenshot):
--   3=Comfort, 4=Deck, 2=Entertainment, 5=Galley, 6=Interior,
--   7=Navigation, 8=Safety, 9=Sails, 10=Yacht electrics
--
-- The two legacy ordinals (0=SALOON_AND_CABINS, 1=NAVIGATION_AND_SAFETY)
-- stay defined in CategoryEnum.kt so the column NOT NULL never trips on a
-- pre-migration row, but no Equipment row should remain on them after this
-- migration runs.

-- ===== Recategorise existing 58 Equipment rows =====

-- Galley (cooking + food storage)
UPDATE equipment SET category = 5 WHERE label_code IN (
    'coffee-machine', 'cooker', 'dishwasher', 'freezer', 'BBQ',
    'ice-maker', 'kitchen-utensils', 'microwave', 'oven', 'fridge',
    'sink', 'water-maker'
);

-- Interior (saloon, cabins, plumbing fixtures)
UPDATE equipment SET category = 6 WHERE label_code IN (
    'shower', 'pillows-and-blankets', 'towels', 'washing-machine',
    'waste-tank'
);

-- Yacht electrics (climate, power, charging)
UPDATE equipment SET category = 10 WHERE label_code IN (
    'air-conditioning', 'heating', 'generator', 'inverter',
    'solar-panels', 'wifi'
);

-- Deck (anchoring + topside)
UPDATE equipment SET category = 4 WHERE label_code IN (
    'outside-shower', 'bimini', 'dinghy', 'flybridge', 'teak-deck'
);

-- Navigation (helm + plotters + radar)
UPDATE equipment SET category = 7 WHERE label_code IN (
    'autopilot', 'bow-thruster', 'salon-GPS-plotter',
    'outside-GPS-plotter', 'radar'
);

-- Sails (rigging + winches + extra sails)
UPDATE equipment SET category = 9 WHERE label_code IN (
    'electric-winches', 'gennaker', 'spinnaker'
);

-- Safety (life-saving + emergency gear)
UPDATE equipment SET category = 8 WHERE label_code IN ('safety-net');

-- Comfort (cushions + relaxation)
UPDATE equipment SET category = 3 WHERE label_code IN (
    'jacuzzi', 'sun-pads'
);

-- Entertainment stays at category=2 (already there): DVD-player,
-- game-console, inside-speakers, karaoke, flat-screen-TV, outside-speakers,
-- audio-system, snorkel-sets, stand-up-paddle, surf, wakeboard, water-skis,
-- water-toys, windsurf, kayak, jet-ski, fishing-set, bicycle, bathing-platform.

-- ===== Add high-frequency partner equipment rows =====
-- BIGSERIAL sequences fall behind max(id) when rows have been backfilled
-- via earlier scripts that supplied explicit ids; advance the sequence
-- before INSERT so we don't collide on equipment_pk.
SELECT setval('equipment_id_seq', (SELECT COALESCE(MAX(id), 1) FROM equipment));

-- These correspond to items the partner APIs ship constantly but which had
-- no Equipment record before — sync used the partner name verbatim instead
-- of a labelCode, so they couldn't be filtered/translated. matchKeys uses
-- the same DSL as existing rows: `token-match:` for normalised token match
-- (Jaro-Winkler ≥ 0.9 against the partner name's tokens), `not:` for
-- negative carve-outs, plain text for fuzzy similarity.

INSERT INTO equipment (name, label_code, category, match_keys, filter_order) VALUES
    -- Comfort
    ('Cockpit cushions', 'cockpit-cushions', 3, 'token-match:cockpit cushions', NULL),
    ('Sundeck cushions', 'sundeck-cushions', 3, 'token-match:sundeck cushions, token-match:sun deck cushions, token-match:sun pads', NULL),
    -- Deck
    ('Anchor', 'main-anchor', 4, 'token-match:anchor, not:anchor line, not:anchor swivel, not:anchor light, not:anchor chain, not:storm anchor, not:dinghy anchor, not:spare anchor, not:reserve anchor, not:winch, not:authorization, not:anchorage', NULL),
    ('Anchor line', 'anchor-line', 4, 'token-match:anchor line', NULL),
    ('Anchor swivel', 'anchor-swivel', 4, 'token-match:anchor swivel', NULL),
    ('Spare anchor', 'spare-anchor', 4, 'token-match:spare anchor, token-match:reserve anchor, token-match:auxiliary anchor', NULL),
    ('Electric anchor windlass', 'electric-anchor-windlass', 4, 'token-match:electric anchor windlass, token-match:anchor windlass', NULL),
    ('Bow thruster', 'bow-thruster-deck', 4, 'token-match:bow thruster', NULL),
    ('Boat hook', 'boat-hook', 4, 'token-match:boat hook', NULL),
    ('Bosun chair', 'bosun-chair', 4, 'token-match:bosun chair, token-match:safe seat, token-match:boatswain chair', NULL),
    ('Cockpit table', 'cockpit-table', 4, 'token-match:cockpit table', NULL),
    ('Davit', 'davit', 4, 'token-match:davit', NULL),
    ('Outboard engine', 'outboard-engine', 4, 'token-match:outboard engine, token-match:outboard motor', NULL),
    ('Fenders', 'fenders', 4, 'token-match:fenders, token-match:fender', NULL),
    ('Gangway', 'gangway', 4, 'token-match:gangway, token-match:passerelle', NULL),
    ('Hawser', 'hawser', 4, 'token-match:hawser', NULL),
    ('Mooring ropes', 'mooring-ropes', 4, 'token-match:mooring ropes, token-match:mooring rope', NULL),
    ('Sprayhood', 'sprayhood', 4, 'token-match:sprayhood, token-match:spray hood', NULL),
    ('Spring line', 'spring-line', 4, 'token-match:spring line', NULL),
    ('Water hose', 'water-hose', 4, 'token-match:water hose', NULL),
    -- Galley
    ('Gas bottles', 'gas-bottles', 5, 'token-match:gas bottles, token-match:gas bottle', NULL),
    ('Hot water', 'hot-water', 5, 'token-match:hot water', NULL),
    -- Interior
    ('Barometer', 'barometer', 6, 'token-match:barometer', NULL),
    ('Clock', 'clock', 6, 'token-match:clock', NULL),
    ('Electric fans', 'electric-fans', 6, 'token-match:electric fans, token-match:electric fans in cabins', NULL),
    ('Electric toilet', 'electric-toilet', 6, 'token-match:electric toilet', NULL),
    ('Lowerable salon table', 'lowerable-salon-table', 6, 'token-match:lowerable salon table', NULL),
    -- Navigation
    ('AIS', 'ais', 7, 'full-match:AIS', NULL),
    ('Binoculars', 'binoculars', 7, 'token-match:binoculars', NULL),
    ('Compass', 'compass', 7, 'token-match:compass', NULL),
    ('Logge / Speed / Wind instrument', 'logge-speed-wind', 7, 'token-match:logge, token-match:speed instrument, token-match:wind instrument', NULL),
    ('Navigation set', 'navigation-set', 7, 'token-match:navigation set', NULL),
    ('Refrigerator', 'refrigerator', 5, 'token-match:refrigerator', NULL),
    -- Safety
    ('Distress signals', 'distress-signals', 8, 'token-match:distress signals, token-match:distress signal', NULL),
    ('EPIRB', 'epirb', 8, 'token-match:EPIRB, token-match:distress radio beacon', NULL),
    ('Fire extinguisher', 'fire-extinguisher', 8, 'token-match:fire extinguisher', NULL),
    ('First aid kit', 'first-aid-kit', 8, 'token-match:first aid kit', NULL),
    ('Flashlight', 'flashlight', 8, 'token-match:flashlight, token-match:torch', NULL),
    ('Fog horn', 'fog-horn', 8, 'token-match:fog horn, token-match:foghorn', NULL),
    ('Life belts', 'life-belts', 8, 'token-match:life belts, token-match:safety harness', NULL),
    ('Life buoy', 'life-buoy', 8, 'token-match:life buoy, token-match:lifebuoy, token-match:flashing light', NULL),
    ('Life jackets', 'life-jackets', 8, 'token-match:life jackets, token-match:life jacket, token-match:lifejacket', NULL),
    ('Liferaft', 'liferaft', 8, 'token-match:liferaft, token-match:life raft', NULL),
    ('VHF radio', 'vhf-radio', 8, 'token-match:VHF radio, token-match:VHF', NULL),
    -- Sails
    ('Lazy bag', 'lazy-bag', 9, 'token-match:lazy bag', NULL),
    ('Lazy jacks', 'lazy-jacks', 9, 'token-match:lazy jacks', NULL),
    -- Yacht electrics
    ('Battery charger', 'battery-charger', 10, 'token-match:battery charger', NULL),
    ('Shore connection 220 V', 'shore-connection-220v', 10, 'token-match:shore connection, token-match:shore power, token-match:220V socket, token-match:220V', NULL),
    ('USB sockets', 'usb-sockets', 10, 'token-match:USB sockets, token-match:USB socket', NULL);

-- Equipment id 15 (shower) was matching "Shore connection 220 V" because
-- "shore" / "shower" cross the Jaro-Winkler 0.9 threshold. The dedicated
-- shore-connection-220v row above absorbs those, but the shower entry
-- still needs an explicit negative carve-out so we don't fall back to
-- shower for older partner names that contain "shore".
UPDATE equipment
SET match_keys = 'token-match:shower, not:no inside shower, not:shore connection, not:shore power'
WHERE label_code = 'shower';

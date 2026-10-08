-- SINGLE SOURCE of the equipment catalogue: name, category, filter_order and match_keys of every row.
-- Keyed by label_code (numeric ids differ between environments: prod and b4y-rehearsal have 59 = sundeck-cushions,
-- the local :5434 copy has 59 = cockpit-cushions). Never write these four columns in a V__ migration: two writers
-- (this file + V1_74 & co.) overwrote each other's keys three times (27.5., 31.5., 24.6.2026). Enforced by
-- EquipmentSeedConsistencyTest. Rows are never deleted here; merged_into_id (aliases) is owned by V9_75.
--
-- match_keys DSL, read by EquipmentMatcher (equipment audit 8.10.2026, FIX_CONTRACT 5-6):
--   token-match:<phrase>   every token of the phrase is somewhere in the partner name (any order); a token counts when
--                          equal, a plural (s / es) or a typo (Levenshtein 1, same first letter, both >= 6 chars).
--                          A compound prefix never counts: lifebuoy is not life, watermaker is not water.
--   full-match:<phrase>    the normalised name equals the normalised phrase
--   case:<TOKEN>           exact case-sensitive token of the raw name (case:SUP)
--   not:<phrase>           veto: substring of the name (any case) or its tokens consecutive in the name (no typos)
-- The best key wins over all rows (exact > more key tokens > earlier in the name > closer > longer), ties go to the
-- lower id. Names that start with no / without / not never link. Keys never hold a comma or an apostrophe.
-- An alias row keeps '' (never NULL) and is skipped by the matcher.
CREATE UNIQUE INDEX IF NOT EXISTS equipment_label_code_uq ON equipment (label_code);

DROP TABLE IF EXISTS _equipment_seed;
CREATE TEMP TABLE _equipment_seed (
    seed_order   int          NOT NULL,
    label_code   varchar(100) NOT NULL,
    name         varchar(100) NOT NULL,
    category     varchar(31)  NOT NULL,
    filter_order smallint,
    match_keys   varchar      NOT NULL
) ON COMMIT DROP;

INSERT INTO _equipment_seed (seed_order, label_code, name, category, filter_order, match_keys)
VALUES
    (1, 'air-conditioning', 'Air conditioning', 'YACHT_ELECTRICS', 200, 'token-match:air conditioning, token-match:air condition, token-match:air conditioner, token-match:a/c, token-match:aircon, full-match:ac, token-match:aircondition, token-match:airconditioning, token-match:ac cabin, token-match:ac saloon, token-match:ac salon'),
    (2, 'coffee-machine', 'Coffee machine', 'GALLEY', 201, 'token-match:coffee, token-match:coffee machine'),
    (3, 'cooker', 'Cooker', 'GALLEY', 203, 'token-match:cooker, token-match:stove, token-match:cook stove, token-match:hob, token-match:cooktop, token-match:burner, token-match:burners, token-match:gas cooker, not:cooler'),
    (4, 'dishwasher', 'Dishwasher', 'GALLEY', NULL, 'token-match:dishwasher, token-match:dish washer, not:dishwashing, not:liquid, not:detergent'),
    (5, 'freezer', 'Freezer', 'GALLEY', NULL, 'token-match:freezer'),
    (6, 'BBQ', 'BBQ', 'GALLEY', NULL, 'token-match:bbq, token-match:barbecue, token-match:barbeque, token-match:grill, token-match:plancha'),
    (7, 'heating', 'Heating', 'YACHT_ELECTRICS', NULL, 'token-match:heating, token-match:heater, token-match:webasto, token-match:eberspacher, not:water heater'),
    (8, 'ice-maker', 'Ice maker', 'GALLEY', NULL, 'token-match:ice maker, token-match:icemaker'),
    (9, 'kitchen-utensils', 'Kitchen utensils', 'GALLEY', 204, 'token-match:kitchen utensils, token-match:kitchen towel, token-match:kitchen equipment, token-match:galley equipment, token-match:cutlery, token-match:crockery, token-match:equipped kitchen, token-match:equipped galley, token-match:cooking set, token-match:kitchen utilities, token-match:dishes'),
    (10, 'microwave', 'Microwave', 'GALLEY', NULL, 'token-match:microwave'),
    (11, 'outside-shower', 'Outside shower', 'DECK', 202, 'token-match:outside shower, token-match:outdoor shower, token-match:deck shower, token-match:cockpit shower, token-match:stern shower, token-match:bow shower, token-match:transom shower, token-match:external shower, token-match:exterior shower'),
    (12, 'oven', 'Oven', 'GALLEY', 205, 'token-match:oven'),
    (13, 'pillows-and-blankets', 'Pillows and blankets', 'INTERIOR', NULL, 'token-match:pillows and blankets, token-match:pillows, token-match:pillow, token-match:blankets, token-match:blanket, token-match:bed linen, token-match:bedding, token-match:duvet, not:fire'),
    (14, 'fridge', 'Fridge', 'GALLEY', 206, 'token-match:fridge, token-match:refrigerator, token-match:wine cooler, token-match:compressor cooler, token-match:refrigeration'),
    (15, 'shower', 'Shower', 'INTERIOR', NULL, 'token-match:shower, not:no inside shower, not:shore, not:outside shower, not:outdoor shower, not:deck shower, not:cockpit shower, not:stern shower, not:bow shower, not:transom shower, not:towel'),
    (16, 'sink', 'Sink', 'GALLEY', NULL, 'token-match:sink'),
    (17, 'towels', 'Towels', 'INTERIOR', 207, 'token-match:towels, token-match:towel, not:towable, not:kitchen towel'),
    (18, 'washing-machine', 'Washing machine', 'INTERIOR', NULL, 'token-match:washing machine, token-match:washer, token-match:laundry machine'),
    (19, 'waste-tank', 'Waste tank', 'INTERIOR', 208, 'token-match:waste tank, token-match:holding tank, token-match:black water tank, token-match:blackwater tank, token-match:grey water tank, token-match:gray water tank, token-match:septic tank, not:water maker, not:watermaker, not:desalin'),
    (20, 'water-maker', 'Water maker', 'GALLEY', 210, 'token-match:water maker, token-match:watermaker, token-match:desalinator, token-match:desalination'),
    (21, 'autopilot', 'Autopilot', 'NAVIGATION', 100, 'token-match:autopilot, token-match:auto pilot, not:no autopilot'),
    (22, 'bimini', 'Bimini', 'DECK', 103, 'token-match:bimini'),
    (23, 'bow-thruster', 'Bow thruster', 'NAVIGATION', NULL, 'token-match:bow thruster, token-match:bowthruster, token-match:jet thruster'),
    (24, 'salon-GPS-plotter', 'Salon GPS plotter', 'NAVIGATION', NULL, 'token-match:salon gps plotter, token-match:gps plotter cabina, token-match:chart plotter, token-match:chartplotter, token-match:gps plotter, token-match:plotter'),
    (25, 'outside-GPS-plotter', 'Outside GPS plotter', 'NAVIGATION', 101, 'token-match:outside gps plotter, token-match:outside plotter, token-match:plotter cockpit, token-match:cockpit plotter, token-match:chart plotter cockpit, token-match:gps chart plotter cockpit, token-match:chartplotter cockpit'),
    (26, 'dinghy', 'Dinghy', 'DECK', 104, 'token-match:dinghy, token-match:dinghy outboard engine, token-match:tender, token-match:rib, token-match:zodiac, token-match:highfield, not:pump, not:repair, not:garage, not:lifting, not:lift, not:davit, not:anchor, not:cover, not:system, not:platform, not:ladder'),
    (27, 'electric-winches', 'Electric winches', 'SAILS', 105, 'token-match:electric winches, token-match:electric winch, token-match:electrical winch, not:windlass, not:anchor'),
    (28, 'flybridge', 'Flybridge', 'DECK', NULL, 'token-match:flybridge, token-match:fly bridge, not:lights'),
    (29, 'generator', 'Generator', 'YACHT_ELECTRICS', 102, 'token-match:generator, token-match:genset, not:wind generator'),
    (30, 'gennaker', 'Gennaker', 'SAILS', NULL, 'token-match:gennaker'),
    (31, 'inverter', 'Inverter', 'YACHT_ELECTRICS', NULL, 'token-match:inverter'),
    (32, 'radar', 'Radar', 'NAVIGATION', NULL, 'token-match:radar, not:reflector'),
    (33, 'safety-net', 'Safety net', 'SAFETY', NULL, 'token-match:railing net, token-match:safety net, not:safety equipment'),
    (34, 'solar-panels', 'Solar panels', 'YACHT_ELECTRICS', NULL, 'token-match:solar panels, token-match:solar panel, token-match:photovoltaic'),
    (35, 'spinnaker', 'Spinnaker', 'SAILS', NULL, 'token-match:spinnaker'),
    (36, 'teak-deck', 'Teak deck', 'DECK', NULL, 'token-match:teak deck, token-match:teak cockpit, token-match:teak flybridge, not:seat, not:interior, not:table'),
    (37, 'bathing-platform', 'Bathing platform', 'ENTERTAINMENT', NULL, 'token-match:bathing platform, token-match:swimming platform, token-match:swim platform, token-match:bath platform'),
    (38, 'bicycle', 'Bicycle', 'ENTERTAINMENT', NULL, 'token-match:bicycle'),
    (39, 'DVD-player', 'DVD Player', 'ENTERTAINMENT', NULL, 'token-match:dvd player, token-match:dvd, not:cd player, not:cd mp3'),
    (40, 'fishing-set', 'Fishing set', 'ENTERTAINMENT', NULL, 'token-match:fishing set, token-match:fishing equipment, token-match:fishing gear, token-match:trolling equipment'),
    (41, 'game-console', 'Game console', 'ENTERTAINMENT', NULL, 'token-match:game console, token-match:playstation, token-match:xbox'),
    (42, 'inside-speakers', 'Inside speakers', 'ENTERTAINMENT', NULL, 'token-match:inside speakers, token-match:indoor speakers, token-match:interior speakers, token-match:internal speakers'),
    (43, 'jacuzzi', 'Jacuzzi', 'COMFORT', NULL, 'token-match:jacuzzi'),
    (44, 'jet-ski', 'Jet ski', 'ENTERTAINMENT', NULL, 'token-match:jet ski, token-match:jetski, token-match:sea doo, not:dock'),
    (45, 'karaoke', 'Karaoke', 'ENTERTAINMENT', NULL, 'token-match:karaoke'),
    (46, 'kayak', 'Kayak', 'ENTERTAINMENT', NULL, 'token-match:kayak, token-match:canoe, not:boom'),
    (47, 'flat-screen-TV', 'Flat screen TV', 'ENTERTAINMENT', NULL, 'token-match:flat screen tv, token-match:tv, token-match:television, not:antenna'),
    (48, 'outside-speakers', 'Outside speakers', 'ENTERTAINMENT', 301, 'token-match:outside speakers, token-match:outdoor speakers, token-match:cockpit speakers, token-match:exterior speakers, token-match:deck speakers'),
    (49, 'audio-system', 'Audio system', 'ENTERTAINMENT', 303, 'token-match:audio system, token-match:stereo, token-match:sound system, token-match:radio cd, token-match:cd player, token-match:radio mp3, token-match:radio usb, token-match:hi fi, token-match:hifi, token-match:fusion, full-match:radio, token-match:audio, token-match:cd mp3, token-match:music system, token-match:bluetooth speaker, token-match:bluetooth speakers, token-match:bluetooth radio'),
    (50, 'snorkel-sets', 'Snorkel sets', 'ENTERTAINMENT', 302, 'token-match:snorkel sets, token-match:snorkel set, token-match:snorkeling equipment, token-match:snorkelling equipment, token-match:snorkeling, token-match:snorkelling, token-match:snorkel, token-match:mask and snorkel, token-match:fins'),
    (51, 'stand-up-paddle', 'Stand Up Paddle', 'ENTERTAINMENT', NULL, 'token-match:stand up paddle, token-match:paddle board, token-match:paddleboard, full-match:sup, case:SUP, token-match:paddle surf'),
    (52, 'sun-pads', 'Sun pads', 'COMFORT', NULL, 'token-match:sun pads, token-match:sun pad, token-match:sunpads, token-match:sundeck cushions, token-match:sun deck cushions, token-match:sun mattresses, token-match:sunbathing mattresses, token-match:sunbed cushions, token-match:sundeck, token-match:sun deck, token-match:bow cushions, token-match:deck cushions, not:chairs'),
    (53, 'surf', 'Surf', 'ENTERTAINMENT', NULL, 'token-match:surf, token-match:surf board, token-match:surfboard'),
    (54, 'wakeboard', 'Wakeboard', 'ENTERTAINMENT', NULL, 'token-match:wakeboard, token-match:wake board'),
    (55, 'water-skis', 'Water skis', 'ENTERTAINMENT', NULL, 'token-match:water skis, token-match:water ski, token-match:waterski, token-match:waterskis, not:slide'),
    (56, 'water-toys', 'Water toys', 'ENTERTAINMENT', 300, 'token-match:water toys, token-match:watertoys, token-match:towables, token-match:towable, token-match:sea scooter, token-match:seascooter, token-match:seabob, token-match:water slide, token-match:donut, token-match:ringo, token-match:tube ride, token-match:underwater scooter, token-match:flyboard, not:water hose'),
    (57, 'windsurf', 'Windsurf', 'ENTERTAINMENT', NULL, 'token-match:windsurf, token-match:windsurfing'),
    (58, 'wifi', 'WiFi', 'COMFORT', 209, 'token-match:wifi, token-match:wi fi, token-match:internet, token-match:wlan'),
    (59, 'sundeck-cushions', 'Sundeck cushions', 'COMFORT', NULL, ''),  -- alias of sun-pads, never matched (merged_into_id, V9_75)
    (60, 'main-anchor', 'Anchor', 'DECK', NULL, 'token-match:anchor, token-match:main anchor, token-match:bow anchor, token-match:anchor with chain, not:anchor line, not:anchor swivel, not:anchor light, not:anchor chain, not:storm anchor, not:dinghy anchor, not:spare anchor, not:reserve anchor, not:second anchor, not:sea anchor, not:stern anchor, not:winch, not:windlass, not:authorization, not:anchorage, not:counter, not:remote, not:release, not:stabilizer, not:ball, not:drogue'),
    (61, 'anchor-line', 'Anchor line', 'DECK', NULL, 'token-match:anchor line, token-match:anchor chain, token-match:chain, not:counter, not:claw'),
    (62, 'anchor-swivel', 'Anchor swivel', 'DECK', NULL, 'token-match:anchor swivel'),
    (63, 'spare-anchor', 'Spare anchor', 'DECK', NULL, 'token-match:spare anchor, token-match:reserve anchor, token-match:auxiliary anchor, token-match:second anchor, token-match:stern anchor'),
    (64, 'electric-anchor-windlass', 'Electric anchor windlass', 'DECK', NULL, 'token-match:electric anchor windlass, token-match:anchor windlass, token-match:electric windlass, token-match:electric anchor, token-match:anchorwinch, token-match:electrical windlass, not:mainsail, not:main sail, not:handle'),
    (65, 'bow-thruster-deck', 'Bow thruster', 'DECK', NULL, ''),  -- alias of bow-thruster, never matched (merged_into_id, V9_75)
    (66, 'boat-hook', 'Boat hook', 'DECK', NULL, 'token-match:boat hook, token-match:boathook'),
    (67, 'bosun-chair', 'Bosun chair', 'DECK', NULL, 'token-match:bosun chair, token-match:safe seat, token-match:boatswain chair'),
    (68, 'cockpit-table', 'Cockpit table', 'DECK', NULL, 'token-match:cockpit table'),
    (69, 'davit', 'Davit', 'DECK', NULL, 'token-match:davit, token-match:davits'),
    (70, 'outboard-engine', 'Outboard engine', 'DECK', NULL, 'token-match:outboard engine, token-match:outboard motor, token-match:outboard, not:lift, not:shower'),
    (71, 'fenders', 'Fenders', 'DECK', NULL, 'token-match:fenders, token-match:fender, token-match:fenderstep, not:finder'),
    (72, 'gangway', 'Gangway', 'DECK', NULL, 'token-match:gangway, token-match:passerelle, token-match:passarelle'),
    (73, 'hawser', 'Hawser', 'DECK', NULL, 'token-match:hawser'),
    (74, 'mooring-ropes', 'Mooring ropes', 'DECK', NULL, 'token-match:mooring ropes, token-match:mooring rope, token-match:mooring lines'),
    (75, 'sprayhood', 'Sprayhood', 'DECK', NULL, 'token-match:sprayhood, token-match:spray hood'),
    (76, 'spring-line', 'Spring line', 'DECK', NULL, 'token-match:spring line'),
    (77, 'water-hose', 'Water hose', 'DECK', NULL, 'token-match:water hose'),
    (78, 'gas-bottles', 'Gas bottles', 'GALLEY', NULL, 'token-match:gas bottles, token-match:gas bottle, token-match:gas cylinder, token-match:cooking gas'),
    (79, 'hot-water', 'Hot water', 'GALLEY', NULL, 'token-match:hot water, token-match:water heater, token-match:warm water, token-match:boiler'),
    (80, 'barometer', 'Barometer', 'INTERIOR', NULL, 'token-match:barometer, token-match:barometric'),
    (81, 'clock', 'Clock', 'INTERIOR', NULL, 'token-match:clock'),
    (82, 'electric-fans', 'Electric fans', 'INTERIOR', NULL, 'token-match:electric fans, token-match:electric fans in cabins, token-match:electric fan, token-match:fans, token-match:fan, token-match:ventilator'),
    (83, 'electric-toilet', 'Electric toilet', 'INTERIOR', 211, 'token-match:electric toilet, token-match:electric wc, token-match:electrical toilet'),
    (84, 'lowerable-salon-table', 'Lowerable salon table', 'INTERIOR', NULL, 'token-match:lowerable salon table, token-match:convertible table, token-match:transformable table, token-match:convertible saloon'),
    (85, 'ais', 'AIS', 'NAVIGATION', NULL, 'full-match:AIS, token-match:ais'),
    (86, 'binoculars', 'Binoculars', 'NAVIGATION', NULL, 'token-match:binoculars'),
    (87, 'compass', 'Compass', 'NAVIGATION', NULL, 'token-match:compass'),
    (88, 'logge-speed-wind', 'Logge / Speed / Wind instrument', 'NAVIGATION', NULL, 'token-match:logge, token-match:log, token-match:speed log, token-match:log speed, token-match:speed instrument, token-match:wind instrument, token-match:anemometer, token-match:windmeter, token-match:wind meter, token-match:speedometer, token-match:tridata, token-match:wind indicator, token-match:sumlog, token-match:speed indicator, token-match:wind station, not:transit log, not:logbook, not:log book'),
    (89, 'navigation-set', 'Navigation set', 'NAVIGATION', NULL, 'token-match:navigation set, token-match:navigational set, token-match:nautical charts, token-match:charts, token-match:pilot book, token-match:pilot books, token-match:maps, token-match:pilot, token-match:nautical guide, token-match:nautical guides, token-match:harbour guide, token-match:harbour guides, token-match:divider, token-match:parallel ruler, not:electronic'),
    (90, 'refrigerator', 'Refrigerator', 'GALLEY', NULL, ''),  -- alias of fridge, never matched (merged_into_id, V9_75)
    (91, 'distress-signals', 'Distress signals', 'SAFETY', NULL, 'token-match:distress signals, token-match:distress signal, token-match:signal rockets, token-match:flares, token-match:distress flare, token-match:smoke signal'),
    (92, 'epirb', 'EPIRB', 'SAFETY', NULL, 'token-match:EPIRB, token-match:distress radio beacon'),
    (93, 'fire-extinguisher', 'Fire extinguisher', 'SAFETY', NULL, 'token-match:fire extinguisher, token-match:fire extinguishers'),
    (94, 'first-aid-kit', 'First aid kit', 'SAFETY', NULL, 'token-match:first aid kit, token-match:first aid'),
    (95, 'flashlight', 'Flashlight', 'SAFETY', NULL, 'token-match:flashlight, token-match:torch, not:signal, not:rocket, not:flare, not:buoy'),
    (96, 'fog-horn', 'Fog horn', 'SAFETY', NULL, 'token-match:fog horn, token-match:foghorn'),
    (97, 'life-belts', 'Life belts', 'SAFETY', NULL, 'token-match:life belts, token-match:safety harness, token-match:safety belts, token-match:safety belt'),
    (98, 'life-buoy', 'Life buoy', 'SAFETY', NULL, 'token-match:life buoy, token-match:lifebuoy, token-match:life ring, token-match:horseshoe buoy, token-match:horse shoe buoy, token-match:flashing light, token-match:life attol, token-match:life atoll, not:jacket, not:vest, not:raft, not:belt, not:harness'),
    (99, 'life-jackets', 'Life jackets', 'SAFETY', NULL, 'token-match:life jackets, token-match:life jacket, token-match:lifejacket, token-match:lifejackets, token-match:life vest, token-match:life vests, token-match:lifevest, token-match:lifevests, token-match:safety jackets, token-match:safety jacket'),
    (100, 'liferaft', 'Liferaft', 'SAFETY', NULL, 'token-match:liferaft, token-match:life raft, not:jacket, not:vest, not:buoy, not:belt'),
    (101, 'vhf-radio', 'VHF radio', 'SAFETY', NULL, 'token-match:VHF radio, token-match:VHF'),
    (102, 'lazy-bag', 'Lazy bag', 'SAILS', NULL, 'token-match:lazy bag'),
    (103, 'lazy-jacks', 'Lazy jacks', 'SAILS', NULL, 'token-match:lazy jacks'),
    (104, 'battery-charger', 'Battery charger', 'YACHT_ELECTRICS', NULL, 'token-match:battery charger'),
    (105, 'shore-connection-220v', 'Shore connection 220 V', 'YACHT_ELECTRICS', NULL, 'token-match:shore connection, token-match:shore power, token-match:shorepower, token-match:220V socket, token-match:220V, token-match:220 v, token-match:220 volt'),
    (106, 'usb-sockets', 'USB sockets', 'YACHT_ELECTRICS', NULL, 'token-match:USB sockets, token-match:USB socket, token-match:usb'),
    (107, 'cockpit-cushions', 'Cockpit cushions', 'COMFORT', NULL, 'token-match:cockpit cushions, token-match:cockpit cushion'),  -- added 8.10.2026
    (108, 'depth-sounder', 'Depth sounder', 'NAVIGATION', NULL, 'token-match:depth sounder, token-match:depthsounder, token-match:echo sounder, token-match:echosounder, token-match:sounder, token-match:depth gauge, token-match:depth meter');  -- added 8.10.2026

-- New rows the way V1_73 added them: sequence first (rows were seeded with explicit ids), then one nextval per
-- missing label in seed order (prod: cockpit-cushions = 107, depth-sounder = 108; an empty table starts at 1). No
-- INSERT ... ON CONFLICT: it burns a nextval for every VALUES row.
SELECT setval('equipment_id_seq', COALESCE((SELECT MAX(id) FROM equipment), 0) + 1, false);

INSERT INTO equipment (name, label_code, category, match_keys, filter_order)
SELECT s.name, s.label_code, s.category, s.match_keys, s.filter_order
FROM _equipment_seed s
WHERE NOT EXISTS (SELECT 1 FROM equipment e WHERE e.label_code = s.label_code)
ORDER BY s.seed_order;

UPDATE equipment e
SET name         = s.name,
    category     = s.category,
    match_keys   = s.match_keys,
    filter_order = s.filter_order
FROM _equipment_seed s
WHERE e.label_code = s.label_code
  AND (e.name, e.category, e.match_keys, e.filter_order)
      IS DISTINCT FROM (s.name, s.category, s.match_keys, s.filter_order);

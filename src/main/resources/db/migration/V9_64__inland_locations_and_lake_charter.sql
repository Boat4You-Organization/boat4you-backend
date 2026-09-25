-- 25.9.2026: sea charter only, lakes included (Mario). V9_63 took the river/canal operators off; lake charter was
--   still live: Masurian-lakes agencies Ahoj Czarter 1335, New Port (Nowy Sztynort) 698, Okej-Czarter 1326 (PL) and
--   Waterfront Jachtcharter 1464 (Veerse Meer, NL), plus boats of SEA agencies kept at a lake base: Starsails
--   Yachtcharter at Lemmer (IJsselmeer, 24 boats - its Mallorca fleet stays), Boat Experiences + Goolets at
--   Aalsmeer/Leimuiden, Aquariva at Yachthafen Rapperswil (Lake Zurich), Sail&More at Marina di Navene (Lake Garda).
-- This records the fix applied by hand in production at ~16:05 UTC (4 agencies active=false + 28 yachts
-- sys_active=false) and makes the yacht sync keep it: every normal yacht update sets sys_active=true, so the base
-- itself is now flagged.
-- 1) location.inland: a river / canal / lake base. The MMK and NauSys yacht sync treat a yacht whose base is inland
--    like an inland builder (InlandVesselRules): never imported, one imported earlier switched off; the weekly
--    inventory does the same. Location ids differ between databases (e.g. local vs production), so every id is
--    guarded by its production name too - a mismatch flags nothing.
ALTER TABLE public.location ADD COLUMN IF NOT EXISTS inland boolean NOT NULL DEFAULT false;

UPDATE public.location l
   SET inland = true
  FROM (VALUES
        -- CA
        (909, 'Horseshoe Bay Marina'), (839, 'Smiths Falls'),
        -- CH
        (1319, 'Port du Landeron'), (1058, 'Yachthafen Rapperswil'),
        -- DE
        (663, 'Bernburg'), (716, 'Fleesensee'), (621, 'Jabel'), (1617, 'Lübz'), (1191, 'Marina Fürstenberg'),
        (829, 'Marina Wolfsbruch'),
        -- FR
        (1408, 'Argens-Minervois'), (1111, 'Bellegarde'), (681, 'Branges'), (1604, 'Buzet-sur-Baïse'), (1389, 'Cahors'),
        (1148, 'Castelnaudary'), (1701, 'Corbigny'), (1431, 'Decize'), (1851, 'Dole'), (1014, 'Dompierre-sur-Besbre'),
        (1511, 'Douelle'), (1900, 'Glénac'), (1037, 'Grez-Neuville'), (746, 'Hesse'), (1612, 'Homps'), (1469, 'Jarnac'),
        (952, 'Joigny'), (1627, 'Lattes'), (1713, 'Le Mas d´Agenais'), (1487, 'Le Somail'), (796, 'Lutzelbourg'),
        (741, 'Marina of Port-sur-Saône'), (1790, 'Messac'), (1365, 'Migennes'), (695, 'Negra'),
        (1070, 'Port Cassafières'), (718, 'Port Lauragais'), (1462, 'Port de Bram'), (1026, 'Port de Plagny'),
        (2038, 'Port de Plaisance de Mittersheim'), (2039, 'Port de Savoyeux'), (982, 'Port de Trèbes'),
        (1350, 'Sablé-sur-Sarthe'), (1060, 'Saint Gilles'), (787, 'Saint-Jean-de-Losne'), (789, 'Saint-Leger-sur-Dheune'),
        (1915, 'Saint-Martin-sur-Oust'), (1089, 'Saintes'), (871, 'Saverne'), (714, 'Scey-sur-Saone'), (1313, 'Sireuil'),
        (1116, 'Sucé-sur-Erdre'), (800, 'Tannay'),
        -- GB
        (720, 'Bellanaleck'), (1760, 'Benson'), (1769, 'Chertsey'), (1733, 'Laggan'),
        -- HU
        (702, 'Kisköre'), (1257, 'Tokaj'),
        -- IE
        (1300, 'Connaught Harbour'), (1008, 'The Marina'), (1636, 'The Marina Banagher'),
        -- IT
        (868, 'Casale Sul Sile'), (1309, 'Marina di Navene'),
        -- NL
        (1095, 'Aalsmeer/Leimuiden'), (1658, 'Akkrum'), (1428, 'Hindeloopen'), (986, 'Jachthaven Drachten de Drait'),
        (1715, 'Kerkdriel'), (1673, 'Kortgene'), (1723, 'Lemmer'), (1717, 'Loosdrecht'), (1775, 'Noord IJsseldijk'),
        (1363, 'Vinkeveen'), (1091, 'Woudsend'),
        -- PL
        (1228, 'AZS Wilkasy'), (900, 'PTTK Wilkasy'), (931, 'Port Sztynort'), (1298, 'Port ZHP'),
        -- PT
        (1762, 'Amieira Marina')
       ) AS v (id, name)
 WHERE l.id = v.id
   AND lower(normalize(trim(l.name), NFC)) = lower(normalize(v.name, NFC))
   AND NOT l.inland;

-- 2) The four lake agencies: manual OFF (sync_deactivated_by NULL - the mirrors re-activate only what they switched
--    off themselves). Guarded by id and name; idempotent (production already matches nothing).
UPDATE public.agency
   SET active = false,
       sync_deactivated_by = NULL
 WHERE (id, lower(trim(name))) IN ((1335, 'ahoj czarter'),
                                   (698, 'new port (nowy sztynort)'),
                                   (1326, 'okej-czarter'),
                                   (1464, 'waterfront jachtcharter'))
   AND (active OR sync_deactivated_by IS NOT NULL);

-- 3) Yachts at an inland base go off the sites (no delete; the sync keeps them off from now on). Idempotent.
UPDATE public.yacht y
   SET sys_active = false
  FROM public.location l
 WHERE l.id = y.location_id
   AND l.inland
   AND y.sys_active;

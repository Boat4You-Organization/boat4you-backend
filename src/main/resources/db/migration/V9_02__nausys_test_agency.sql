-- insert new test agency data
INSERT INTO agency (id, name, email, phone, active)
VALUES (9000, 'Nausys Test Agency', 'that@workspace.hr', '+38512345678', true);
INSERT INTO agency_source (agency_id, external_system_id, "primary", external_id)
VALUES (9000, 2, true, 102701);
INSERT INTO external_mapping  (system_id, external_id, external_system_id, "type")
VALUES (9000, 102701, 2, 'Agency');


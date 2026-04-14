-- insert new test agency data
INSERT INTO agency (id, name, email, phone, active)
VALUES (9001, 'MMK Test Agency', 'that@workspace.hr', '+38512345678', true);
INSERT INTO agency_source (agency_id, external_system_id, "primary", external_id)
VALUES (9001, 1, true, 225);
INSERT INTO external_mapping  (system_id, external_id, external_system_id, "type")
VALUES (9001, 225, 1, 'Agency');


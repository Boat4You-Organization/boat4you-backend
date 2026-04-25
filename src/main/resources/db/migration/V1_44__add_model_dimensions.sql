-- Nausys returns length (loa) and beam at the model level (RestYachtModel),
-- not per yacht (RestYacht). Store them on the model so each yacht can pick
-- them up via its model reference; backfill happens on the next catalogue sync.
ALTER TABLE model ADD COLUMN length NUMERIC(10, 2);
ALTER TABLE model ADD COLUMN beam NUMERIC(10, 2);

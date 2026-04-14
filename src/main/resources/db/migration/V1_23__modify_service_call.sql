ALTER TABLE service_call ADD COLUMN success boolean;
ALTER TABLE service_call ALTER COLUMN response_status TYPE varchar(30);
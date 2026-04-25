-- Add optional street/city address to user profile. Some charter agencies
-- (Nausys / MMK partners) require client postal address on the reservation.
ALTER TABLE users ADD COLUMN address VARCHAR(255);

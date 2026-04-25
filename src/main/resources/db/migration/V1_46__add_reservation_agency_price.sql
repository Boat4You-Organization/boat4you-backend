-- Split out the "what we owe the charter agency" figure as its own column so
-- admin reporting can compare it against the client's total price and the
-- commission we keep. Until now:
--   * Nausys response `agencyPrice` was never persisted
--   * MMK response `finalPrice` ("amount charter operator receives") was
--     misread as `totalPrice` on the reservation
-- The new column gives both integrations a clean slot to write to, and the
-- reservation_view is extended in the matching R__1_02 to surface it to admin.

ALTER TABLE reservation ADD COLUMN agency_price NUMERIC(12, 2);

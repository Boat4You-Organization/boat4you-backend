-- Admin "fictitious" replacement reservations (see
-- ReservationFlowMutationService.createFictitiousReservation) have no offer —
-- the agency moved the customer onto a different yacht directly in
-- Nausys/MMK and we only record the swap on our side. Drop the
-- NOT NULL constraint on `reservation_flow.offer_id` so that flow can
-- persist. Customer + guest flows always set this field; the relaxation
-- is specifically for the admin replacement path.
ALTER TABLE reservation_flow ALTER COLUMN offer_id DROP NOT NULL;

-- Admin "fictitious" replacement reservations never call the partner API
-- (see ReservationFlowMutationService.createFictitiousReservation), so the
-- three partner-derived fields must be nullable. Customer + guest flows
-- always populate these from the MMK/Nausys createOption response.
ALTER TABLE reservation ALTER COLUMN external_id DROP NOT NULL;
ALTER TABLE reservation ALTER COLUMN external_reservation_code DROP NOT NULL;
ALTER TABLE reservation ALTER COLUMN external_created_at DROP NOT NULL;

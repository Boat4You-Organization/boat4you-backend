ALTER TABLE public.offer
    ADD COLUMN agency_commission decimal DEFAULT 0;

UPDATE public.offer SET agency_commission = 0;

ALTER TABLE public.offer
    ALTER COLUMN agency_commission SET NOT NULL;

-- reservation_flow
ALTER TABLE public.reservation_flow
    ADD COLUMN agency_commission decimal DEFAULT 0;

UPDATE public.reservation_flow SET agency_commission = 0;

ALTER TABLE public.reservation_flow
    ALTER COLUMN agency_commission SET NOT NULL;
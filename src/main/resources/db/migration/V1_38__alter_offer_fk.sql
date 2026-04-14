ALTER TABLE public.offer_extras
    DROP CONSTRAINT offer_fk;

ALTER TABLE public.offer_extras ADD CONSTRAINT offer_fk FOREIGN KEY (offer_id)
    REFERENCES public.offer (id) MATCH FULL
    ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE public.offer_payment_plan
    DROP CONSTRAINT offer_fk;

ALTER TABLE public.offer_payment_plan ADD CONSTRAINT offer_fk FOREIGN KEY (offer_id)
    REFERENCES public.offer (id) MATCH FULL
    ON DELETE CASCADE ON UPDATE CASCADE;
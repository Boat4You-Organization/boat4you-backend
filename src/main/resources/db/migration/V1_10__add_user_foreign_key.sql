ALTER TABLE public.reservation_flow ADD CONSTRAINT user_fk FOREIGN KEY (user_id)
    REFERENCES public.users (id) MATCH FULL
    ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE public.reservation_flow ADD CONSTRAINT created_by_user_fk FOREIGN KEY (created_by)
    REFERENCES public.users (id) MATCH FULL
    ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE public.custom_offer ADD CONSTRAINT user_fk FOREIGN KEY (user_id)
    REFERENCES public.users (id) MATCH FULL
    ON DELETE RESTRICT ON UPDATE CASCADE;
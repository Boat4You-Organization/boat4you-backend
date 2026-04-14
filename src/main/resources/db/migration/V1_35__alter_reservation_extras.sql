ALTER TABLE boat4you_db.public.reservation_extras DROP COLUMN yacht_extras_id;
ALTER TABLE boat4you_db.public.reservation_extras ADD COLUMN extras_id BIGINT;
ALTER TABLE boat4you_db.public.reservation_extras ADD COLUMN name VARCHAR;
ALTER TABLE boat4you_db.public.reservation_extras ADD COLUMN obligatory BOOLEAN;
ALTER TABLE boat4you_db.public.reservation_extras ADD COLUMN payable_at_base BOOLEAN;
ALTER TABLE boat4you_db.public.reservation_extras ADD COLUMN unit_price NUMERIC;
ALTER TABLE boat4you_db.public.reservation_extras ADD COLUMN unit SMALLINT;
ALTER TABLE boat4you_db.public.reservation_extras ADD COLUMN source_id BIGINT;
ALTER TABLE boat4you_db.public.reservation_extras ADD COLUMN external_id BIGINT;


ALTER TABLE public.reservation_extras
    ADD CONSTRAINT extras_fk FOREIGN KEY (extras_id)
        REFERENCES public.extras (id) MATCH FULL
        ON DELETE RESTRICT ON UPDATE CASCADE;

COMMENT ON COLUMN public.reservation_extras.source_id IS E'This is offer extras id or yacht extras id. For troubleshooting';
-- fixes
COMMENT ON TABLE public.external_reservations IS E'Currently only for nausys reservations';
COMMENT ON TABLE public.external_seasons IS E'Currently only for nausys seasons';

-- bases for nausys yacht_extras sync
CREATE TABLE external_bases
(
    id                 bigserial NOT NULL,
    external_id        bigint,
    external_system_id bigint,
    agency_id          bigint,
    ext_agency_id      bigint,
    location_id        bigint,
    ext_location_id    bigint,
    checkin_time       varchar(20),
    checkout_time      varchar(20),
    CONSTRAINT bases_pk PRIMARY KEY (id)
);

COMMENT ON TABLE public.external_bases IS E'Currently only for nausys bases';
COMMENT ON COLUMN public.external_bases.external_system_id IS E'This will always be nausys';

GRANT SELECT, UPDATE, INSERT, DELETE ON public.external_bases TO boat4you_app;

-- Optional: Grant permissions on sequences in the schema
GRANT USAGE, SELECT ON public.external_bases_id_seq TO boat4you_app;

ALTER TABLE public.external_bases
    ADD CONSTRAINT agency_fk FOREIGN KEY (agency_id)
        REFERENCES public.agency (id) MATCH FULL
        ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE public.external_bases
    ADD CONSTRAINT location_fk FOREIGN KEY (location_id)
        REFERENCES public.location (id) MATCH FULL
        ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE public.yacht_extras
    ADD COLUMN valid_for_bases BIGINT[];

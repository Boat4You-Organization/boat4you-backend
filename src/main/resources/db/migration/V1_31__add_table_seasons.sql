CREATE TABLE external_seasons
(
    id bigserial NOT NULL,
    valid_from date,
    valid_to date,
    external_id bigint,
    name varchar(255),
    default_season bool,
    CONSTRAINT seasons_pk PRIMARY KEY (id)
);

COMMENT ON TABLE public.external_reservations IS E'Currently only for nausys seasons';

GRANT SELECT, UPDATE, INSERT, DELETE ON public.external_seasons TO boat4you_app;

-- Optional: Grant permissions on sequences in the schema
GRANT USAGE, SELECT ON public.external_seasons_id_seq TO boat4you_app;

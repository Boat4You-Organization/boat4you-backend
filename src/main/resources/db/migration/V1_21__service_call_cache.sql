CREATE TABLE public.service_call_cache (
                                           id bigserial NOT NULL,
                                           method smallint NOT NULL,
                                           hash_code bigint NOT NULL,
                                           created_at timestamp NOT NULL,
                                           CONSTRAINT service_call_cache_pk PRIMARY KEY (id)
);
-- ddl-end --
ALTER TABLE public.service_call_cache OWNER TO boat4you_owner;
-- ddl-end --

-- object: idx_serice_call_method_hash_code | type: INDEX --
-- DROP INDEX IF EXISTS public.idx_serice_call_method_hash_code CASCADE;
CREATE INDEX idx_serice_call_method_hash_code ON public.service_call_cache
    USING btree
    (
     method,
     hash_code
        );
-- ddl-end --


-- TODO FIX PERMISSIONS

-- Grant permissions on all existing tables in the schema
GRANT SELECT, UPDATE, INSERT, DELETE ON public.service_call_cache TO boat4you_app;

-- Optional: Grant permissions on sequences in the schema
GRANT USAGE, SELECT ON public.service_call_cache_id_seq TO boat4you_app;
CREATE TABLE IF NOT EXISTS settings
(
    id BIGSERIAL NOT NULL
    CONSTRAINT pk_settings_id PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    value VARCHAR(255),

    creator_id BIGINT,
    modifier_id BIGINT,
    created TIMESTAMP NOT NULL,
    modified TIMESTAMP,
    entity_status VARCHAR(31) NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uidx_settings_name ON settings(name);

-- TODO FIX PERMISSIONS

-- Grant permissions on all existing tables in the schema
GRANT SELECT, UPDATE, INSERT, DELETE ON public.settings TO boat4you_app;

-- Optional: Grant permissions on sequences in the schema
GRANT USAGE, SELECT ON public.settings_id_seq TO boat4you_app;

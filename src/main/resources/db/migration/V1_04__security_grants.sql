-- Grant permissions on all existing tables in the schema
GRANT SELECT, UPDATE, INSERT, DELETE ON ALL TABLES IN SCHEMA public TO boat4you_app;

-- Grant permissions on schema usage
GRANT USAGE ON SCHEMA public TO boat4you_app;

-- Optional: Grant permissions on sequences in the schema
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO boat4you_app;

-- Optional: Set default permissions for future tables
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, UPDATE, INSERT, DELETE ON TABLES TO boat4you_app;
DO $$
BEGIN

  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'boat4you_owner') THEN
CREATE ROLE boat4you_owner LOGIN PASSWORD 'testpass';
END IF;

  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'boat4you_app') THEN
CREATE ROLE boat4you_app LOGIN PASSWORD 'testpass';
END IF;
END$$;

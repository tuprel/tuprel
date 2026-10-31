-- Run as a role administrator in tuprel_devtest (PostgreSQL 17).
-- No login/password, database-wide grants or application objects are created.
-- Roles are cluster-scoped; this grants no object privileges in other databases.
DO $$
BEGIN
    IF current_database() <> 'tuprel_devtest' THEN
        RAISE EXCEPTION 'Use tuprel_devtest';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'tuprel_status_reader') THEN
        CREATE ROLE tuprel_status_reader NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'tuprel_status_reader'
               AND (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolbypassrls)) THEN
        RAISE EXCEPTION 'Existing tuprel_status_reader has unexpected privileges; inspect manually';
    END IF;
END $$;
GRANT tuprel_status_reader TO tuprel_test_user WITH INHERIT FALSE;
GRANT tuprel_status_reader TO tuprel_test_user WITH SET TRUE;

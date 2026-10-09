-- Clears data from every table in the public schema EXCEPT the ones listed in keep_tables.
-- Table structures are kept; ID sequences of cleared tables restart from 1.
-- DEV ONLY. Never run against UAT / PROD.
--
-- Kept on purpose:
--   employee, employee_address  -> requested to be retained
--   users                       -> employee.user_id references users; also needed to log in
--   flyway_schema_history       -> otherwise Flyway re-runs every migration on startup
--
-- No CASCADE: if a cleared table were still referenced by a kept table, TRUNCATE fails
-- instead of silently wiping the kept table.

DO
$$
DECLARE
    keep_tables   TEXT[] := ARRAY ['employee', 'employee_address', 'users', 'flyway_schema_history'];
    tables_to_clear TEXT;
BEGIN
    SELECT string_agg(format('%I.%I', schemaname, tablename), ', ')
    INTO tables_to_clear
    FROM pg_tables
    WHERE schemaname = 'public'
      AND tablename <> ALL (keep_tables);

    IF tables_to_clear IS NULL THEN
        RAISE NOTICE 'Nothing to clear';
        RETURN;
    END IF;

    RAISE NOTICE 'Clearing: %', tables_to_clear;
    EXECUTE 'TRUNCATE TABLE ' || tables_to_clear || ' RESTART IDENTITY';
END
$$;

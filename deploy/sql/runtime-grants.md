# Runtime DB grants

The `migrate` service connects as the Postgres superuser and applies Liquibase.

The long-lived `backend` connects as `POSTGRES_RUNTIME_USER` (default `repogrowth`) and must **not** have DDL.

## Fresh volume

`deploy/postgres/init-runtime-role.sh` runs once on empty data and creates the runtime role plus default privileges.

After the first migrate, grant existing objects:

```sql
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO repogrowth;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO repogrowth;
```

## Existing volume (upgrade)

```sql
CREATE ROLE repogrowth LOGIN PASSWORD '…';
GRANT CONNECT ON DATABASE repogrowth TO repogrowth;
GRANT USAGE ON SCHEMA public TO repogrowth;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO repogrowth;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO repogrowth;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO repogrowth;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO repogrowth;
```

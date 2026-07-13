#!/bin/sh
set -eu

psql --username "${POSTGRES_USER}" --dbname postgres \
  --set=reader_password="${QW_POSTGRES_READER_PASSWORD}" <<'SQL'
SELECT format('CREATE ROLE queryweaver_reader LOGIN PASSWORD %L', :'reader_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'queryweaver_reader')\gexec
ALTER ROLE queryweaver_reader NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
GRANT CONNECT ON DATABASE china_population_db TO queryweaver_reader;
SQL

psql --username "${POSTGRES_USER}" --dbname china_population_db <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO queryweaver_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO queryweaver_reader;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO queryweaver_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO queryweaver_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO queryweaver_reader;
SQL

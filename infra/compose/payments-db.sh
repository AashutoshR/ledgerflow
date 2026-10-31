#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres --set=payments_password="$PAYMENTS_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE payments LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE', :'payments_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='payments') \gexec
SELECT 'CREATE DATABASE payments OWNER payments'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname='payments') \gexec
REVOKE ALL ON DATABASE payments FROM PUBLIC;
SQL

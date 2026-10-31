#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres --set=workspace_password="$WORKSPACE_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE workspace LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE', :'workspace_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='workspace') \gexec
SELECT 'CREATE DATABASE workspace OWNER workspace'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname='workspace') \gexec
REVOKE ALL ON DATABASE workspace FROM PUBLIC;
SQL

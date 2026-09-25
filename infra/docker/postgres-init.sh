#!/bin/sh
set -eu
: "${APP_DB_PASSWORD:?APP_DB_PASSWORD must be configured}"
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=ON_ERROR_STOP=1 --set=app_password="$APP_DB_PASSWORD" <<'SQL'
CREATE ROLE ledgerflow_runtime NOLOGIN;
CREATE ROLE ledgerflow_ledger_owner NOLOGIN;
CREATE ROLE ledgerflow_app LOGIN PASSWORD :'app_password';
GRANT ledgerflow_runtime TO ledgerflow_app;
SQL

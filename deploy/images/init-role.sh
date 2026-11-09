#!/bin/sh
set -eu
export LIFEAPP_ROLE_PASSWORD="$(cat /run/secrets/db_password)"
psql --no-psqlrc --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 <<'SQL'
\getenv app_password LIFEAPP_ROLE_PASSWORD
CREATE ROLE lifeapp LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'app_password';
ALTER DATABASE lifeapp OWNER TO lifeapp;
SQL
unset LIFEAPP_ROLE_PASSWORD

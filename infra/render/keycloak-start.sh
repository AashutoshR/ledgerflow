#!/bin/bash
set -eu
export KC_DB_URL="jdbc:postgresql://${PGHOST}:${PGPORT:-5432}/${PGDATABASE}"
export KC_DB_USERNAME="$PGUSER"
export KC_DB_PASSWORD="$PGPASSWORD"
export KC_DB_SCHEMA=keycloak
export KC_HOSTNAME="https://${RENDER_EXTERNAL_HOSTNAME:-$AUTH_HOST}"
export KC_HTTP_PORT="${PORT:-10000}"
java -Xmx32m -cp '/opt/keycloak/bootstrap:/opt/keycloak/lib/lib/main/*' Bootstrap
exec /opt/keycloak/bin/kc.sh start --optimized --import-realm --http-enabled=true --proxy-headers=xforwarded --cache=local

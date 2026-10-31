#!/bin/bash
set -eu
umask 077
export KC_DB_URL="jdbc:postgresql://${PGHOST}:${PGPORT:-5432}/${PGDATABASE}"
export KC_DB_USERNAME="$PGUSER"
export KC_DB_PASSWORD="$PGPASSWORD"
export KC_DB_SCHEMA=keycloak
export KC_HOSTNAME="https://${RENDER_EXTERNAL_HOSTNAME:-$AUTH_HOST}"
export KC_HTTP_PORT="${PORT:-10000}"
java -Xmx32m -cp '/opt/keycloak/bootstrap:/opt/keycloak/lib/lib/main/*' Bootstrap
/opt/keycloak/bin/kc.sh start --optimized --import-realm --http-enabled=true --proxy-headers=xforwarded --cache=local &
server_pid=$!
trap 'kill "$server_pid" 2>/dev/null || true' EXIT
trap 'kill "$server_pid" 2>/dev/null || true' TERM INT

# Existing realms skip imports. Idempotently repair the dedicated browser subject
# mapper using the supported Admin API, preserving all accounts and records.
export JAVA_OPTS='-Xms8m -Xmx32m -XX:MaxMetaspaceSize=64m -XX:ReservedCodeCacheSize=16m'
admin_config=/tmp/ledgerflow-kcadm.config
authenticated=false
for ((attempt=0; attempt<90; attempt++)); do
  if /opt/keycloak/bin/kcadm.sh config credentials --config "$admin_config" \
      --server "http://127.0.0.1:${KC_HTTP_PORT}" --realm master \
      --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null 2>&1; then
    authenticated=true
    break
  fi
  kill -0 "$server_pid" 2>/dev/null || exit 1
  sleep 2
done
if [[ "$authenticated" != true ]]; then
  echo 'Unable to configure browser identity mapping.' >&2
  exit 1
fi
client_id=
while IFS= read -r line; do
  [[ "$line" =~ ^[a-f0-9-]{36}$ ]] && client_id="$line"
done < <(/opt/keycloak/bin/kcadm.sh get clients --config "$admin_config" -r ledgerflow \
    -q clientId=ledgerflow-web --fields id --format csv --noquotes)
[[ -n "$client_id" ]] || exit 1
mappers=$(/opt/keycloak/bin/kcadm.sh get "clients/$client_id/protocol-mappers/models" \
    --config "$admin_config" -r ledgerflow --fields name --format csv --noquotes)
if [[ "$mappers" != *ledgerflow-subject* ]]; then
  /opt/keycloak/bin/kcadm.sh create "clients/$client_id/protocol-mappers/models" \
      --config "$admin_config" -r ledgerflow -f /opt/keycloak/subject-mapper.json >/dev/null
fi
echo 'Browser identity mapping is ready.'
rm -f -- "$admin_config"
wait "$server_pid"

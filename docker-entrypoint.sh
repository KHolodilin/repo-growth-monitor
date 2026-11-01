#!/bin/sh
set -eu

# Load Docker/Compose file secrets into env when the plain var is empty.
load_secret() {
  var_name="$1"
  file_var="${var_name}_FILE"
  eval "current=\${${var_name}:-}"
  eval "file_path=\${${file_var}:-}"
  if [ -z "${current}" ] && [ -n "${file_path}" ] && [ -f "${file_path}" ]; then
    value=$(cat "${file_path}")
    export "${var_name}=${value}"
  fi
}

load_secret GITHUB_TOKEN
load_secret POSTGRES_PASSWORD
load_secret SPRING_DATASOURCE_PASSWORD
load_secret AUTH_OIDC_CLIENT_SECRET
load_secret MANAGEMENT_PASSWORD
load_secret LIQUIBASE_PASSWORD

# Prefer dedicated datasource password; fall back to POSTGRES_PASSWORD for simple packs.
if [ -z "${SPRING_DATASOURCE_PASSWORD:-}" ] && [ -n "${POSTGRES_PASSWORD:-}" ]; then
  export SPRING_DATASOURCE_PASSWORD="${POSTGRES_PASSWORD}"
fi
if [ -z "${LIQUIBASE_PASSWORD:-}" ] && [ -n "${POSTGRES_PASSWORD:-}" ]; then
  export LIQUIBASE_PASSWORD="${POSTGRES_PASSWORD}"
fi

exec java ${JAVA_OPTS:-} -jar /app/app.jar "$@"

#!/bin/sh
# Bancos e usuários SEPARADOS da API e do Keycloak (mínimo privilégio: cada um só no seu banco).
set -e
psql -v ON_ERROR_STOP=1 -U postgres <<SQL
CREATE ROLE engenheiro LOGIN PASSWORD '$ENGENHEIRO_DB_PASSWORD';
CREATE DATABASE engenheiro OWNER engenheiro;
REVOKE ALL ON DATABASE engenheiro FROM PUBLIC;
CREATE ROLE keycloak LOGIN PASSWORD '$KC_DB_PASSWORD';
CREATE DATABASE keycloak OWNER keycloak;
REVOKE ALL ON DATABASE keycloak FROM PUBLIC;
SQL

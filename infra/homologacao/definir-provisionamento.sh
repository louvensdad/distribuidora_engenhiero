#!/usr/bin/env bash
# Provisionamento de contas de funcionários na HOMOLOGAÇÃO (ADR-027, F17): cria (se faltar) o client de serviço
# engenheiro-provisioner no realm engenheiro, só com manage-users/view-users/query-users, e grava o segredo no .env da
# homologação (fora do repositório, 600). A API usa o IdP pela rede interna (http://keycloak:8080), nunca pela borda.
# Produção: mesmo roteiro no IdP de produção, segredo no cofre (D09). Depois: infra/operacao/implantar.sh <commit>.
set -euo pipefail
cd "$(dirname "$0")/../.."
export HOMOLOG_DIR=${ENGENHEIRO_HOMOLOG_DIR:-$HOME/.config/engenheiro/homologacao}
set -a; . "$HOMOLOG_DIR/.env"; set +a
DC="docker compose -p engenheiro-homolog -f infra/homologacao/docker-compose.yml --env-file $HOMOLOG_DIR/.env"
SECRET=$($DC exec -T -e ADM="$KC_ADMIN_PASSWORD" keycloak sh -c '
set -e
K=/opt/keycloak/bin/kcadm.sh
$K config credentials --server http://localhost:8080 --realm master --user admin-homolog --password "$ADM" >/dev/null 2>&1
ID=$($K get clients -r engenheiro -q clientId=engenheiro-provisioner --fields id --format csv --noquotes)
if [ -z "$ID" ]; then
  $K create clients -r engenheiro -s clientId=engenheiro-provisioner -s publicClient=false -s standardFlowEnabled=false \
     -s directAccessGrantsEnabled=false -s serviceAccountsEnabled=true >/dev/null
  ID=$($K get clients -r engenheiro -q clientId=engenheiro-provisioner --fields id --format csv --noquotes)
fi
$K add-roles -r engenheiro --uusername service-account-engenheiro-provisioner --cclientid realm-management \
   --rolename manage-users --rolename view-users --rolename query-users >/dev/null
$K update users/profile -r engenheiro -s "unmanagedAttributePolicy=ADMIN_EDIT" >/dev/null 2>&1 || true
$K get "clients/$ID/client-secret" -r engenheiro --fields value --format csv --noquotes')
[[ -n "$SECRET" ]] || { echo "segredo do provisionamento não obtido"; exit 1; }
sed -i '/^ENGENHEIRO_PROVISIONER_CLIENT_\(ID\|SECRET\)=/d' "$HOMOLOG_DIR/.env"
printf 'ENGENHEIRO_PROVISIONER_CLIENT_ID=engenheiro-provisioner\nENGENHEIRO_PROVISIONER_CLIENT_SECRET=%s\n' "$SECRET" >> "$HOMOLOG_DIR/.env"
chmod 600 "$HOMOLOG_DIR/.env"
echo "provisionamento configurado (client engenheiro-provisioner; segredo só no .env da homologação)"

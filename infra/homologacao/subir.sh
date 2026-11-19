#!/usr/bin/env bash
# Sobe a HOMOLOGAÇÃO local (F16): constrói jar, site e as duas imagens etiquetadas pelo commit, sobe a pilha com TLS
# e define as senhas de homologação no Keycloak. Uso: infra/homologacao/subir.sh [--sem-build|--parar|--apagar]
# --apagar remove também os volumes (banco e documentos de homologação).
set -euo pipefail
cd "$(dirname "$0")/../.."
RAIZ=$PWD
export HOMOLOG_DIR=${ENGENHEIRO_HOMOLOG_DIR:-$HOME/.config/engenheiro/homologacao}
[[ -f "$HOMOLOG_DIR/.env" ]] || infra/homologacao/preparar.sh
set -a; . "$HOMOLOG_DIR/.env"; set +a
COMMIT=$(git rev-parse --short=12 HEAD)
git diff --quiet HEAD -- services apps/web-admin || COMMIT="$COMMIT-sujo"
export ENGENHEIRO_COMMIT=$COMMIT
# Patrão fictício do realm (id fixo) vira o dono do banco vazio pelo bootstrap (D06).
export ENGENHEIRO_BOOTSTRAP_OWNER_SUBJECT=${ENGENHEIRO_BOOTSTRAP_OWNER_SUBJECT:-00000000-0000-4000-8000-000000000a01}
DC="docker compose -f infra/homologacao/docker-compose.yml --env-file $HOMOLOG_DIR/.env"

case "${1:-}" in
  --parar) $DC stop; exit 0 ;;
  --apagar) $DC down -v; exit 0 ;;
esac

if [[ "${1:-}" != "--sem-build" ]]; then
  echo "1/4 build (commit $COMMIT)"
  (cd services/api && ./mvnw -q -DskipTests package)
  (cd apps/web-admin && npm run -s build >/dev/null)
  docker build -q -t "engenheiro-api:$COMMIT" --build-arg COMMIT="$COMMIT" services/api >/dev/null
  docker build -q -t "engenheiro-site:$COMMIT" --build-arg COMMIT="$COMMIT" apps/web-admin >/dev/null
fi

echo "2/4 pilha (postgres, keycloak, api, site, borda TLS)"
$DC up -d >/dev/null
$DC exec -T borda nginx -s reload >/dev/null 2>&1 || true   # borda.conf é montado: aplica mudanças
espera() { for _ in $(seq 1 "$3"); do eval "$2" >/dev/null 2>&1 && { echo "  ✓ $1"; return 0; }; sleep 2; done; echo "  ✗ $1"; return 1; }
CA="$HOMOLOG_DIR/tls/ca.crt"
URL_LOGIN="https://login.$HOMOLOG_DOMINIO:$HOMOLOG_PORTA"
URL_SITE="https://gestao.$HOMOLOG_DOMINIO:$HOMOLOG_PORTA"
espera "Keycloak por HTTPS" "curl -sf --cacert $CA $URL_LOGIN/realms/engenheiro/.well-known/openid-configuration" 90
espera "API pronta (readiness)" "$DC exec -T api wget -qO- http://127.0.0.1:8080/actuator/health/readiness | grep -q UP" 90
espera "site por HTTPS" "curl -sf --cacert $CA $URL_SITE/ | grep -q '<div id=\"root\"'" 30

echo "3/4 senhas de homologação (fictícias, do .env; nada impresso)"
$DC exec -T -e SENHA="$ENGENHEIRO_SENHA_HOMOLOG" -e ADM="$KC_ADMIN_PASSWORD" keycloak sh -c '
set -e
K=/opt/keycloak/bin/kcadm.sh
$K config credentials --server http://localhost:8080 --realm master --user admin-homolog --password "$ADM" >/dev/null 2>&1
for u in patrao balcao recebimento coordenador motorista.a motorista.b contador; do
  $K set-password -r engenheiro --username "$u" --new-password "$SENHA"
done'

echo "4/4 pronto: $URL_SITE (commit $COMMIT) — CA de homologação: $CA"

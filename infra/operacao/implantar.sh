#!/usr/bin/env bash
# Implantação com rollback (F16, OPS-04) na homologação (mesmo roteiro da produção — runbook):
#   1) backup completo antes da migração  2) API e site na versão nova  3) prontidão + fumaça
#   4) se falhar: volta API e site à versão anterior e confere de novo (schema só expande — a anterior roda sobre ele).
# uso: infra/operacao/implantar.sh <commit>     (imagens já construídas por construir.sh)
set -euo pipefail
cd "$(dirname "$0")/../.."
NOVO=$(git rev-parse --short=12 "${1:?commit}")
export HOMOLOG_DIR=${ENGENHEIRO_HOMOLOG_DIR:-$HOME/.config/engenheiro/homologacao}
set -a; . "$HOMOLOG_DIR/.env"; set +a
export ENGENHEIRO_BOOTSTRAP_OWNER_SUBJECT=
DC="docker compose -p engenheiro-homolog -f infra/homologacao/docker-compose.yml --env-file $HOMOLOG_DIR/.env"
ANTERIOR=$(docker inspect -f '{{.Config.Image}}' engenheiro-homolog-api-1 | cut -d: -f2)
docker image inspect "engenheiro-api:$NOVO" "engenheiro-site:$NOVO" >/dev/null || { echo "construa antes: construir.sh $NOVO"; exit 2; }
T0=$(date +%s)
pronta() {
  for _ in $(seq 1 60); do
    st=$(docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' engenheiro-homolog-api-1 2>/dev/null || true)
    [[ "$st" == "running healthy" ]] && return 0
    sleep 2
  done; return 1
}
troca() { ENGENHEIRO_COMMIT=$1 $DC up -d --no-deps api site >/dev/null 2>&1; }
echo "implantação $ANTERIOR → $NOVO"
infra/operacao/backup.sh "${BACKUP_DESTINO:-$HOME/.local/state/engenheiro/backups}" | sed 's/^/  pré-migração: /'
troca "$NOVO"
if pronta && python3 -I infra/homologacao/fumaca.py > /tmp/fumaca-$$.txt 2>&1; then
  echo "  ✓ $NOVO no ar: prontidão e fumaça PASS ($(( $(date +%s) - T0 )) s); schema $($DC exec -T postgres psql -U postgres -d engenheiro -Atc "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")"
  rm -f /tmp/fumaca-$$.txt; exit 0
fi
echo "  ✗ $NOVO falhou ($(grep -c '✗' /tmp/fumaca-$$.txt 2>/dev/null || echo prontidão) itens) — ROLLBACK para $ANTERIOR"
docker logs --tail 3 engenheiro-homolog-api-1 2>&1 | grep -o '"message":"[^"]\{0,240\}' | sed 's/^/    /' || true
troca "$ANTERIOR"
if pronta && python3 -I infra/homologacao/fumaca.py >/dev/null 2>&1; then
  echo "  ✓ rollback concluído: $ANTERIOR no ar, fumaça PASS ($(( $(date +%s) - T0 )) s)"
else
  echo "  ✗ rollback sem sucesso: restaurar o backup pré-migração (runbook)"; exit 4
fi
rm -f /tmp/fumaca-$$.txt; exit 3

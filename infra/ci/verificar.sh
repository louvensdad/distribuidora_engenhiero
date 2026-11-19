#!/usr/bin/env bash
# Portões de qualidade e segurança (F16) — o mesmo roteiro local e no CI (.github/workflows/ci.yml).
# Reprova se QUALQUER um falhar: especificação íntegra, segredos, vulnerabilidades altas/críticas (dependências e
# imagens), testes da API, site e apps, builds e imagens do commit. uso: infra/ci/verificar.sh [--sem-imagens]
set -uo pipefail
cd "$(dirname "$0")/../.."
FLUTTER=${FLUTTER:-$HOME/development/flutter/bin/flutter}
TRIVY_IMG="aquasec/trivy:0.76.1@sha256:790fd66d1697395d8693d1acaa1ea702406a20f109b6fc7bac8db623eca6c06c"
TRIVY_CACHE=${TRIVY_CACHE:-$HOME/.cache/engenheiro-trivy}
mkdir -p "$TRIVY_CACHE"
trivy() { docker run --rm -v "$TRIVY_CACHE":/root/.cache/trivy -v "$HOME/.m2":/root/.m2:ro -v "$PWD":/repo:ro \
          -v /var/run/docker.sock:/var/run/docker.sock "$TRIVY_IMG" "$@"; }
falhas=0
etapa() { # etapa <nome> <comando...>
  local nome=$1; shift; local t=$(date +%s)
  if out=$("$@" 2>&1); then echo "  ✓ $nome ($(( $(date +%s) - t )) s)"
  else echo "  ✗ $nome ($(( $(date +%s) - t )) s)"; echo "$out" | tail -15 | sed 's/^/      /'; falhas=$((falhas + 1)); fi
}
SKIP="--skip-dirs /repo/apps/web-admin/node_modules --skip-dirs /repo/build --skip-dirs /repo/services/api/target --skip-dirs /repo/.dart_tool"
echo "Verificação do commit $(git rev-parse --short=12 HEAD)$(git diff --quiet HEAD || echo ' (com alterações locais)')"
etapa "pacote da especificação íntegro" python3 -I docs/especificacao/ferramentas/validar_pacote.py
etapa "nenhum segredo no repositório (Trivy)" trivy fs --quiet --scanners secret --exit-code 1 $SKIP /repo
etapa "dependências sem vulnerabilidade alta/crítica (Maven, npm, pub)" trivy fs --quiet --offline-scan --scanners vuln \
  --severity HIGH,CRITICAL --exit-code 1 $SKIP /repo
etapa "npm audit (produção)" sh -c 'cd apps/web-admin && npx -y npm@11.21.1 audit --omit=dev --audit-level=high'
etapa "API: testes (unitários + integração com PostgreSQL real)" sh -c 'cd services/api && ./mvnw -q test'
etapa "site: tipos" sh -c 'cd apps/web-admin && npm run -s typecheck'
etapa "site: testes" sh -c 'cd apps/web-admin && npx vitest run'
etapa "site: build de produção" sh -c 'cd apps/web-admin && npm run -s build'
etapa "apps: análise estática" "$FLUTTER" analyze apps/mobile-owner apps/mobile-counter apps/mobile-delivery apps/mobile-accountant packages/mobile-core packages/mobile-api
for d in packages/mobile-core packages/mobile-api apps/mobile-owner apps/mobile-counter apps/mobile-delivery apps/mobile-accountant; do
  [[ -d "$d/test" ]] && etapa "apps: testes $d" sh -c "cd $d && $FLUTTER test --no-pub"
done
if [[ "${1:-}" != "--sem-imagens" ]]; then
  C=$(git rev-parse --short=12 HEAD)
  etapa "imagens do commit (checkout limpo)" infra/operacao/construir.sh "$C"
  for img in "engenheiro-api:$C" "engenheiro-site:$C"; do
    etapa "imagem $img sem vulnerabilidade alta/crítica nem segredo" trivy image --quiet --offline-scan \
      --java-db-repository ghcr.io/aquasecurity/trivy-java-db:1 --scanners vuln,secret --severity HIGH,CRITICAL --exit-code 1 "$img"
  done
fi
echo "RESULTADO: $([[ $falhas == 0 ]] && echo PASS || echo "FAIL ($falhas)")"
exit $(( falhas > 0 ))

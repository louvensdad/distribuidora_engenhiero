#!/usr/bin/env bash
# Constrói os artefatos de UM commit a partir de um checkout limpo (git worktree): jar da API, build do site e as duas
# imagens etiquetadas pelo commit, com manifesto (sha256 do jar, digests/ids das imagens). Reprodutível e sem
# alterações locais misturadas. uso: infra/operacao/construir.sh [commit]   (padrão HEAD)
set -euo pipefail
cd "$(dirname "$0")/../.."
C=$(git rev-parse --short=12 "${1:-HEAD}")
W=$(mktemp -d)/engenheiro-$C
ART=${ENGENHEIRO_ARTEFATOS:-$HOME/.local/state/engenheiro/artefatos}
mkdir -p "$ART"
git worktree add --detach -q "$W" "$C"
trap 'git worktree remove --force "$W" >/dev/null 2>&1 || true' EXIT
(cd "$W/services/api" && ./mvnw -q -DskipTests package)
(cd "$W/apps/web-admin" && npm ci --no-audit --no-fund --loglevel=error >/dev/null && npm run -s build >/dev/null)
VER=$(cd "$W/services/api" && ./mvnw -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null)
docker build -q -t "engenheiro-api:$C" --build-arg COMMIT="$C" --build-arg VERSAO="$VER" "$W/services/api" >/dev/null
docker build -q -t "engenheiro-site:$C" --build-arg COMMIT="$C" "$W/apps/web-admin" >/dev/null
JAR_SHA=$(sha256sum "$W/services/api/target/engenheiro-api-$VER.jar" | cut -d' ' -f1)
python3 -I - "$ART/$C.json" "$C" "$(git log -1 --format=%cI "$C")" "$VER" "$JAR_SHA" \
  "$(docker image inspect -f '{{.Id}}' "engenheiro-api:$C")" "$(docker image inspect -f '{{.Id}}' "engenheiro-site:$C")" <<'PY'
import json, sys
out, c, data, ver, jar, api, site = sys.argv[1:]
json.dump({"commit": c, "data_commit": data, "versao": ver, "api_jar_sha256": jar,
           "imagens": {f"engenheiro-api:{c}": api, f"engenheiro-site:{c}": site}}, open(out, "w"), indent=2)
PY
echo "artefatos $C: $ART/$C.json"

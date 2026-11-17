#!/usr/bin/env bash
# Sobe (ou para) a pilha local inteira: Postgres + Keycloak (docker), API :8081, site (Vite) :4200 e, opcionalmente,
# o emulador Android com adb reverse. Não redefine senhas nem TOTP; nenhum segredo é gravado em arquivo.
#
# uso (na raiz do repo):
#   infra/local/subir-tudo.sh            # banco, Keycloak, API e site
#   infra/local/subir-tudo.sh --emulador # idem + emulador engenheiro_api36 (sem janela) e adb reverse 8180/8081
#   infra/local/subir-tudo.sh --parar    # para API, site e emulador (os containers continuam; docker compose stop para eles)
#
# Requisitos fora do repo: ~/.config/engenheiro/kc-admin (senha do admin do Keycloak local).
# Logs: ~/.local/state/engenheiro/{api,vite,emulador}.log
set -euo pipefail
cd "$(dirname "$0")/../.."
RAIZ=$PWD
LOGS=~/.local/state/engenheiro
mkdir -p "$LOGS"
ADB=~/Android/Sdk/platform-tools/adb
EMU=~/Android/Sdk/emulator/emulator

pid_na_porta() { ss -ltnpH "sport = :$1" 2>/dev/null | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2; }
espera() { # espera <descrição> <comando> <segundos>
  for _ in $(seq 1 "$3"); do eval "$2" >/dev/null 2>&1 && { echo "  ✓ $1"; return 0; }; sleep 1; done
  echo "  ✗ $1 não respondeu em $3 s (veja $LOGS)"; return 1
}

if [[ "${1:-}" == "--parar" ]]; then
  for p in 8081 4200; do pid=$(pid_na_porta $p); [[ -n "$pid" ]] && kill "$pid" && echo "parado :$p (pid $pid)"; done
  "$ADB" devices 2>/dev/null </dev/null | grep -q emulator- && "$ADB" emu kill >/dev/null 2>&1 </dev/null && echo "emulador desligado"
  exit 0
fi

echo "1/4 Postgres e Keycloak (docker compose)"
KC_ADMIN_PASSWORD="$(cat ~/.config/engenheiro/kc-admin)" docker compose -f infra/local/docker-compose.yml up -d >/dev/null
espera "Keycloak :8180" "curl -sf http://localhost:8180/realms/engenheiro/.well-known/openid-configuration" 120

echo "2/4 API :8081 (perfil local)"
if [[ -n "$(pid_na_porta 8081)" ]]; then
  echo "  já rodando (pid $(pid_na_porta 8081)); para recompilar: --parar e rodar de novo"
else
  # Segredo do client web lido do Keycloak local e passado só no ambiente do processo.
  SEGREDO=$(docker exec engenheiro-local-keycloak-1 sh -c '
    K=/opt/keycloak/bin/kcadm.sh
    $K config credentials --server http://localhost:8180 --realm master \
      --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null 2>&1
    ID=$($K get clients -r engenheiro -q clientId=engenheiro-web --fields id --format csv --noquotes)
    $K get clients/$ID/client-secret -r engenheiro --fields value --format csv --noquotes')
  # Criação de contas de funcionários (ADR-027): só se o client de serviço existir (definir-provisionamento-local.sh).
  SEGREDO_PROV=$(docker exec engenheiro-local-keycloak-1 sh -c '
    K=/opt/keycloak/bin/kcadm.sh
    ID=$($K get clients -r engenheiro -q clientId=engenheiro-provisioner --fields id --format csv --noquotes 2>/dev/null)
    [ -n "$ID" ] && $K get clients/$ID/client-secret -r engenheiro --fields value --format csv --noquotes || true')
  PROV_ID=""; [[ -n "$SEGREDO_PROV" ]] && PROV_ID=engenheiro-provisioner
  (cd services/api && ./mvnw -q -o clean package -DskipTests 2>&1 | tail -5 || ./mvnw -q clean package -DskipTests)
  # exec + todas as saídas redirecionadas: nada fica preso ao terminal de quem chamou.
  (cd services/api && PORT=8081 SPRING_PROFILES_ACTIVE=local ENGENHEIRO_WEB_CLIENT_SECRET="$SEGREDO" \
    ENGENHEIRO_PROVISIONER_CLIENT_ID="$PROV_ID" ENGENHEIRO_PROVISIONER_CLIENT_SECRET="$SEGREDO_PROV" \
    exec nohup java -jar target/engenheiro-api-0.1.0-SNAPSHOT.jar) >"$LOGS/api.log" 2>&1 </dev/null &
  disown
  unset SEGREDO SEGREDO_PROV
  espera "API pronta" "curl -sf http://localhost:8081/actuator/health/readiness" 120
  grep -oE 'now at version v[0-9]+|Schema "public" is up to date[^"]*' "$LOGS/api.log" | tail -1 | sed 's/^/  migrations: /' || true
fi

echo "3/4 Site (Vite) :4200"
if [[ -n "$(pid_na_porta 4200)" ]]; then
  echo "  já rodando"
else
  (cd apps/web-admin && exec nohup node node_modules/.bin/vite --port 4200 --strictPort) >"$LOGS/vite.log" 2>&1 </dev/null &
  disown
  espera "site em http://localhost:4200" "curl -sf http://localhost:4200/" 60
fi

echo "4/4 Emulador Android"
"$ADB" start-server >/dev/null 2>&1 </dev/null || true
if [[ "${1:-}" == "--emulador" ]]; then
  if ! "$ADB" devices 2>/dev/null | grep -q "emulator-.*device$"; then
    nohup "$EMU" -avd engenheiro_api36 -no-window -no-audio -no-snapshot-save -gpu swiftshader_indirect -memory 3072 \
      >"$LOGS/emulador.log" 2>&1 </dev/null &
    disown
    espera "emulador ligado" "[ \"\$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')\" = 1 ]" 240
  else
    echo "  ✓ já ligado"
  fi
  "$ADB" reverse tcp:8180 tcp:8180 >/dev/null && "$ADB" reverse tcp:8081 tcp:8081 >/dev/null && echo "  ✓ adb reverse 8180/8081"
else
  echo "  (pulado; use --emulador para os apps)"
fi

cat <<EOF

Pronto. Site: http://localhost:4200 · API: http://localhost:8081 · Keycloak: http://localhost:8180
Testes: (cd services/api && ./mvnw verify) · (cd apps/web-admin && npx -y npm@11.21.1 test)
        (cd apps/web-admin && ENGENHEIRO_SENHA_LOCAL="\$(cat ~/.config/engenheiro/senha-local)" npx playwright test)
Se o container do Keycloak tiver sido recriado (senhas perdidas):
        ENGENHEIRO_SENHA_LOCAL="\$(cat ~/.config/engenheiro/senha-local)" infra/local/definir-senhas-locais.sh
Parar: infra/local/subir-tudo.sh --parar
EOF

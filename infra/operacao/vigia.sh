#!/usr/bin/env bash
# Vigia de serviços (F16, OPS-03): roda a cada 5 min pelo timer engenheiro-vigia; sai ≠ 0 se algo falhar e o systemd
# dispara OnFailure → engenheiro-alerta@ (mesmo canal do backup). Confere, como o usuário veria:
#   site e IdP pela borda TLS (certificado validado) · cada instância da API saudável (readiness inclui o banco)
#   · último backup OK e com menos de VIGIA_BACKUP_MAX_H horas (RPO 24 h + folga) · disco do servidor.
# Integrações externas (fiscal, IA) não derrubam o vigia: aparecem como pendência/NÃO CONFIGURADA nas telas e no log.
# Variáveis (padrão = homologação local): VIGIA_DOMINIO, VIGIA_PORTA, VIGIA_CA (vazio = CAs do sistema),
#   VIGIA_PROJETO (prefixo dos contêineres), BACKUP_DESTINO, VIGIA_BACKUP_MAX_H (26), VIGIA_DISCO_MIN_PCT (10).
# Nunca imprime segredo, token ou dado pessoal: só nome do serviço, status HTTP e idade.
set -uo pipefail
H=${ENGENHEIRO_HOMOLOG_DIR:-$HOME/.config/engenheiro/homologacao}
if [[ -z "${VIGIA_DOMINIO:-}" && -f "$H/.env" ]]; then
  VIGIA_DOMINIO=$(sed -n 's/^HOMOLOG_DOMINIO=//p' "$H/.env"); VIGIA_PORTA=$(sed -n 's/^HOMOLOG_PORTA=//p' "$H/.env")
  VIGIA_CA=${VIGIA_CA-$H/tls/ca.crt}
fi
DOM=${VIGIA_DOMINIO:?defina VIGIA_DOMINIO}; PORTA=${VIGIA_PORTA:-443}; CA=${VIGIA_CA:-}
PROJ=${VIGIA_PROJETO:-engenheiro-homolog}
DEST=${BACKUP_DESTINO:-$HOME/.local/state/engenheiro/backups}
MAXH=${VIGIA_BACKUP_MAX_H:-26}; DISCO=${VIGIA_DISCO_MIN_PCT:-10}
falhas=()
ok() { echo "  ✓ $*"; }
falha() { echo "  ✗ $*"; falhas+=("$1"); }
http() {  # status HTTP sem seguir redirecionamento; 000 = sem resposta/TLS inválido
  curl -s -o /dev/null -w '%{http_code}' --max-time 10 ${CA:+--cacert "$CA"} "$1" || true; }

s=$(http "https://gestao.$DOM:$PORTA/")
[[ "$s" =~ ^(200|302)$ ]] && ok "site pela borda: HTTP $s" || falha "site" "pela borda: HTTP $s"
s=$(http "https://login.$DOM:$PORTA/realms/engenheiro/.well-known/openid-configuration")
[[ "$s" == 200 ]] && ok "IdP (descoberta OIDC): HTTP $s" || falha "IdP" "descoberta OIDC: HTTP $s"

apis=$(docker ps -a --filter "name=^${PROJ}-api-" --format '{{.Names}}' 2>/dev/null | sort)
[[ -n "$apis" ]] || falha "API" "nenhuma instância encontrada ($PROJ-api-*)"
for c in $apis; do
  st=$(docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' "$c" 2>/dev/null)
  [[ "$st" == "running healthy" ]] && ok "$c: $st" || falha "$c" "$st"
done

if [[ -f "$DEST/ULTIMO_BACKUP.json" ]]; then
  read -r res quando < <(python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); print(d["resultado"], d["quando_utc"])' "$DEST/ULTIMO_BACKUP.json")
  idade=$(( ( $(date -u +%s) - $(date -u -d "$quando" +%s) ) / 3600 ))
  [[ "$res" == OK && $idade -lt $MAXH ]] && ok "último backup: $res há ${idade} h" || falha "backup" "último: $res há ${idade} h (máx. $MAXH h)"
else
  falha "backup" "nenhum backup agendado registrado em $DEST"
fi

livre=$(df --output=pcent "$DEST" 2>/dev/null | tail -1 | tr -dc 0-9); livre=$((100 - ${livre:-100}))
[[ $livre -ge $DISCO ]] && ok "disco: ${livre}% livre" || falha "disco" "${livre}% livre (mín. $DISCO%)"

if ((${#falhas[@]})); then echo "VIGIA: FALHA — ${falhas[*]}"; exit 1; fi
echo "VIGIA: OK"

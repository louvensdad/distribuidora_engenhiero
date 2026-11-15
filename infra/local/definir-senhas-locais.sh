#!/usr/bin/env bash
# Define senhas dos usuários FICTÍCIOS do realm local. Nenhuma senha fica no repositório.
# Uso: KC_ADMIN_PASSWORD=... ENGENHEIRO_SENHA_LOCAL=... ./definir-senhas-locais.sh
set -euo pipefail
: "${KC_ADMIN_PASSWORD:?defina KC_ADMIN_PASSWORD}"
: "${ENGENHEIRO_SENHA_LOCAL:?defina ENGENHEIRO_SENHA_LOCAL (mín. 12 caracteres)}"
KC="docker compose -f $(dirname "$0")/docker-compose.yml exec -T keycloak /opt/keycloak/bin/kcadm.sh"
$KC config credentials --server http://localhost:8180 --realm master --user "${KC_ADMIN_USER:-admin-local}" --password "$KC_ADMIN_PASSWORD"
for u in patrao balcao recebimento coordenador motorista.a motorista.b contador; do
  $KC set-password -r engenheiro --username "$u" --new-password "$ENGENHEIRO_SENHA_LOCAL"
done
echo "Senhas locais definidas. O patrão deverá configurar TOTP no primeiro acesso."

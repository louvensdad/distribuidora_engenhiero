#!/usr/bin/env bash
# Define senhas dos usuários FICTÍCIOS do realm local e reinicia o TOTP do patrão.
# Nenhuma senha fica no repositório. Uso: ENGENHEIRO_SENHA_LOCAL=... ./definir-senhas-locais.sh
set -euo pipefail
: "${ENGENHEIRO_SENHA_LOCAL:?defina ENGENHEIRO_SENHA_LOCAL (mín. 12 caracteres)}"
C=engenheiro-local-keycloak-1
docker exec -e SENHA="$ENGENHEIRO_SENHA_LOCAL" "$C" sh -c '
set -e
K=/opt/keycloak/bin/kcadm.sh
$K config credentials --server http://localhost:8180 --realm master \
   --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null 2>&1
for u in patrao balcao recebimento coordenador motorista.a motorista.b contador; do
  $K set-password -r engenheiro --username "$u" --new-password "$SENHA"
done
# Patrão: remove OTP antigo e exige novo cadastro de TOTP no próximo login.
UID_P=00000000-0000-4000-8000-000000000a01
for cred in $($K get users/$UID_P/credentials -r engenheiro --fields id,type --format csv --noquotes | grep ",otp$" | cut -d, -f1); do
  $K delete users/$UID_P/credentials/$cred -r engenheiro
done
$K update users/$UID_P -r engenheiro -s "requiredActions=[\"CONFIGURE_TOTP\"]"
'
echo "Senhas locais definidas; o patrão cadastrará TOTP no próximo login."

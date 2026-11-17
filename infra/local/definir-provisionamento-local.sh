#!/usr/bin/env bash
# Cria (se faltar) o client de serviço engenheiro-provisioner no Keycloak LOCAL, com papéis manage-users/view-users/
# query-users, para "dar acesso aos apps" no cadastro de funcionários (ADR-027). O segredo NÃO fica no repositório:
# o subir-tudo.sh o lê do Keycloak e passa só no ambiente do processo da API.
set -euo pipefail
docker exec engenheiro-local-keycloak-1 sh -c '
set -e
K=/opt/keycloak/bin/kcadm.sh
$K config credentials --server http://localhost:8180 --realm master \
   --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null 2>&1
ID=$($K get clients -r engenheiro -q clientId=engenheiro-provisioner --fields id --format csv --noquotes)
if [ -z "$ID" ]; then
  $K create clients -r engenheiro -s clientId=engenheiro-provisioner -s publicClient=false -s standardFlowEnabled=false \
     -s directAccessGrantsEnabled=false -s serviceAccountsEnabled=true >/dev/null
  echo "client engenheiro-provisioner criado"
fi
$K add-roles -r engenheiro --uusername service-account-engenheiro-provisioner --cclientid realm-management \
   --rolename manage-users --rolename view-users --rolename query-users
# Atributo do funcionário (para retomar com segurança uma criação cuja resposta se perdeu).
$K update users/profile -r engenheiro -s "unmanagedAttributePolicy=ADMIN_EDIT" >/dev/null 2>&1 || true
echo "papéis de gestão de usuários atribuídos à conta de serviço"
'

# E-mail opcional no perfil de usuário do realm local (funcionário de balcão pode não ter e-mail).
TMP=$(mktemp -d)
docker exec engenheiro-local-keycloak-1 sh -c '/opt/keycloak/bin/kcadm.sh get users/profile -r engenheiro > /tmp/up.json'
docker cp engenheiro-local-keycloak-1:/tmp/up.json "$TMP/up.json" >/dev/null
python3 -I -c "
import json,sys; p=sys.argv[1]; d=json.load(open(p))
[a.pop('required',None) for a in d['attributes'] if a['name']=='email']
json.dump(d,open(p,'w'))" "$TMP/up.json"
docker cp "$TMP/up.json" engenheiro-local-keycloak-1:/tmp/up2.json >/dev/null
docker exec engenheiro-local-keycloak-1 sh -c '/opt/keycloak/bin/kcadm.sh update users/profile -r engenheiro -f /tmp/up2.json'
rm -rf "$TMP"
echo "e-mail opcional no perfil local"

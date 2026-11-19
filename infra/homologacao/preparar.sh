#!/usr/bin/env bash
# Prepara a HOMOLOGAÇÃO local (F16, ADR-030): gera FORA do repositório (~/.config/engenheiro/homologacao) os segredos,
# a CA e o certificado TLS de homologação, o truststore da API e o realm de homologação (URLs HTTPS, segredo próprio do
# site). Idempotente: não sobrescreve o que já existe (use --refazer para gerar tudo de novo). Nada é impresso.
set -euo pipefail
cd "$(dirname "$0")/../.."
D=${ENGENHEIRO_HOMOLOG_DIR:-$HOME/.config/engenheiro/homologacao}
DOM=${ENGENHEIRO_HOMOLOG_DOMINIO:-homolog.localtest.me}
PORTA=${ENGENHEIRO_HOMOLOG_PORTA:-8443}
[[ "${1:-}" == "--refazer" ]] && rm -rf "$D"
mkdir -p "$D/tls" && chmod 700 "$D"
gera() { openssl rand -base64 48 | tr -d '/+=\n' | cut -c1-40; }

if [[ ! -f "$D/.env" ]]; then
  umask 077
  cat > "$D/.env" <<EOT
# Gerado por infra/homologacao/preparar.sh — segredos SÓ de homologação (nunca reutilizar em produção).
HOMOLOG_DOMINIO=$DOM
HOMOLOG_PORTA=$PORTA
POSTGRES_PASSWORD=$(gera)
ENGENHEIRO_DB_PASSWORD=$(gera)
KC_DB_PASSWORD=$(gera)
KC_ADMIN_PASSWORD=$(gera)
ENGENHEIRO_WEB_CLIENT_SECRET=$(gera)
ENGENHEIRO_LOG_PEPPER=$(gera)$(gera)
ENGENHEIRO_SENHA_HOMOLOG=$(gera)
BACKUP_PASSPHRASE=$(gera)$(gera)
EOT
fi

if [[ ! -f "$D/tls/ca.crt" ]]; then
  umask 077
  # Extensões completas (clientes estritos — Python 3.14, Android, navegadores — recusam CA sem keyUsage).
  openssl req -x509 -newkey rsa:3072 -sha256 -days 365 -nodes -subj "/CN=Engenheiro Homologacao CA" \
    -addext "basicConstraints=critical,CA:TRUE,pathlen:0" -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -addext "subjectKeyIdentifier=hash" -keyout "$D/tls/ca.key" -out "$D/tls/ca.crt" 2>/dev/null
  openssl req -newkey rsa:2048 -nodes -subj "/CN=gestao.$DOM" -keyout "$D/tls/borda.key" -out "$D/tls/borda.csr" 2>/dev/null
  printf 'subjectAltName=DNS:gestao.%s,DNS:login.%s\nextendedKeyUsage=serverAuth\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\n' "$DOM" "$DOM" > "$D/tls/ext.cnf"
  openssl x509 -req -sha256 -days 180 -in "$D/tls/borda.csr" -CA "$D/tls/ca.crt" -CAkey "$D/tls/ca.key" -CAcreateserial \
    -extfile "$D/tls/ext.cnf" -out "$D/tls/borda.crt" 2>/dev/null
  chmod 644 "$D/tls/borda.crt" "$D/tls/ca.crt"; chmod 644 "$D/tls/borda.key"  # lida pelo nginx sem privilégio (uid 101)
  rm -f "$D/tls/borda.csr" "$D/tls/ext.cnf"
  keytool -importcert -noprompt -alias homolog-ca -file "$D/tls/ca.crt" -keystore "$D/tls/truststore.p12" \
    -storetype PKCS12 -storepass homolog-truststore >/dev/null 2>&1
  chmod 644 "$D/tls/truststore.p12"
fi

# Realm de homologação a partir do realm local: só os clients/usuários fictícios, URLs HTTPS do domínio de homologação.
set -a; . "$D/.env"; set +a
python3 -I - "$D/engenheiro-realm.json" <<PY
import json, os, sys
r = json.load(open("infra/local/keycloak/engenheiro-realm.json"))
dom, porta = os.environ["HOMOLOG_DOMINIO"], os.environ["HOMOLOG_PORTA"]
site = f"https://gestao.{dom}:{porta}"
r["sslRequired"] = "external"
for c in r["clients"]:
    if c["clientId"] == "engenheiro-web":
        c["redirectUris"] = [f"{site}/login/oauth2/code/engenheiro"]
        c["secret"] = os.environ["ENGENHEIRO_WEB_CLIENT_SECRET"]
        c.setdefault("attributes", {})["post.logout.redirect.uris"] = f"{site}/"
        c["webOrigins"] = []
for u in r["users"]:
    u.pop("credentials", None)
json.dump(r, open(sys.argv[1], "w"), ensure_ascii=False, indent=2)
PY
chmod 600 "$D/engenheiro-realm.json"; chmod 644 "$D/engenheiro-realm.json"  # montado só-leitura no Keycloak
echo "Homologação preparada em $D (segredos, CA/TLS, truststore e realm). Nenhum valor foi impresso."

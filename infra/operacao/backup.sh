#!/usr/bin/env bash
# Backup COMPLETO (F16, OPS-02): banco do sistema, banco do IdP (contas e TOTP) e documentos/anexos, com manifesto
# (commit em execução, versão do schema, contagens, hash de cada objeto), tudo cifrado (AES-256, gpg simétrico) e com
# SHA256SUMS dos arquivos cifrados. A senha vem de um arquivo/cofre SEPARADO do destino do backup.
# uso: infra/operacao/backup.sh <destino> [projeto-compose]   (padrão: engenheiro-homolog)
#      BACKUP_PASSPHRASE_FILE=<arquivo com a senha>   (padrão: senha do .env de homologação)
set -euo pipefail
DEST=${1:?destino do backup}
P=${2:-engenheiro-homolog}
HOMOLOG_DIR=${ENGENHEIRO_HOMOLOG_DIR:-$HOME/.config/engenheiro/homologacao}
if [[ -z "${BACKUP_PASSPHRASE_FILE:-}" ]]; then
  BACKUP_PASSPHRASE_FILE=$(mktemp); trap 'rm -f "$BACKUP_PASSPHRASE_FILE"' EXIT
  grep '^BACKUP_PASSPHRASE=' "$HOMOLOG_DIR/.env" | cut -d= -f2- > "$BACKUP_PASSPHRASE_FILE"
fi
[[ -s "$BACKUP_PASSPHRASE_FILE" ]] || { echo "senha do backup ausente"; exit 2; }
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
OUT="$DEST/engenheiro-$STAMP"
mkdir -p "$OUT" && chmod 700 "$OUT"
umask 077
T=$(date +%s.%N)
dc() { docker compose -p "$P" "$@"; }
VOL=$(docker volume ls -q --filter "label=com.docker.compose.project=$P" | grep '_objetos$')
PG_IMG=postgres:18.6-alpine
cifra() { gpg --batch --yes --quiet --pinentry-mode loopback --passphrase-file "$BACKUP_PASSPHRASE_FILE" \
            --symmetric --cipher-algo AES256 --compress-algo none -o "$1.gpg" "$1" </dev/null && rm -f "$1"; }

# Consistência: o dump é uma transação (snapshot) por banco; os objetos são imutáveis por chave (put único), então
# copiá-los DEPOIS do dump garante que todo documento citado no banco está no pacote.
dc exec -T postgres pg_dump -U postgres -Fc -d engenheiro > "$OUT/engenheiro.dump"
dc exec -T postgres pg_dump -U postgres -Fc -d keycloak > "$OUT/keycloak.dump"
docker run --rm -v "$VOL":/dados:ro "$PG_IMG" tar -C /dados -cf - . > "$OUT/objetos.tar"

q() { dc exec -T postgres psql -U postgres -d engenheiro -Atc "$1"; }
COMMIT=$(dc exec -T api wget -qO- http://127.0.0.1:8080/actuator/info 2>/dev/null \
  | python3 -I -c 'import json,sys;print(json.load(sys.stdin)["app"]["commit"])' 2>/dev/null || echo desconhecido)
python3 -I - "$OUT/manifesto.json" "$STAMP" "$COMMIT" \
  "$(q "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")" \
  "$(q "SELECT string_agg(t || '=' || n, ',') FROM (SELECT 'organizations' t, count(*) n FROM organizations UNION ALL SELECT 'app_users', count(*) FROM app_users UNION ALL SELECT 'products', count(*) FROM products UNION ALL SELECT 'customers', count(*) FROM customers UNION ALL SELECT 'expenses', count(*) FROM expenses UNION ALL SELECT 'documents', count(*) FROM documents UNION ALL SELECT 'audit_events', count(*) FROM audit_events UNION ALL SELECT 'journal_entries', count(*) FROM journal_entries) x")" \
  "$(q "SELECT string_agg(object_key || '=' || sha256, ',' ORDER BY object_key) FROM documents")" \
  "$(docker run --rm -v "$VOL":/dados:ro "$PG_IMG" sh -c 'cd /dados && find . -type f | wc -l')" <<'PY'
import json, sys
out, stamp, commit, schema, counts, objs, nfiles = sys.argv[1:]
json.dump({"criado_em_utc": stamp, "commit_em_execucao": commit, "schema_flyway": schema,
           "contagens": dict(kv.split("=") for kv in counts.split(",")),
           "documentos": dict(kv.split("=") for kv in objs.split(",")) if objs else {},
           "arquivos_no_armazenamento": int(nfiles)}, open(out, "w"), indent=2)
PY
for f in engenheiro.dump keycloak.dump objetos.tar manifesto.json; do cifra "$OUT/$f"; done
(cd "$OUT" && sha256sum ./*.gpg > SHA256SUMS)
echo "backup $OUT ($(du -sh "$OUT" | cut -f1), $(python3 -I -c "print(round($(date +%s.%N)-$T,1))") s) — cifrado; senha fora do destino"

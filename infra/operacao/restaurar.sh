#!/usr/bin/env bash
# Restauração COMPLETA (F16, OPS-02) num ambiente VAZIO: confere SHA256SUMS, decifra em área temporária privada,
# restaura banco do sistema, banco do IdP e documentos, sobe a pilha e verifica contra o manifesto (schema, contagens,
# cada objeto com o hash gravado no banco restaurado). Mede o tempo. Não redefine senhas (as contas voltam do backup).
# uso: infra/operacao/restaurar.sh <diretório-do-backup>    (BACKUP_PASSPHRASE_FILE como no backup)
set -euo pipefail
B=$(cd "${1:?diretório do backup}" && pwd)
cd "$(dirname "$0")/../.."
export HOMOLOG_DIR=${ENGENHEIRO_HOMOLOG_DIR:-$HOME/.config/engenheiro/homologacao}
set -a; . "$HOMOLOG_DIR/.env"; set +a
P=engenheiro-homolog
T0=$(date +%s.%N)
DC="docker compose -p $P -f infra/homologacao/docker-compose.yml --env-file $HOMOLOG_DIR/.env"
TMP=$(mktemp -d); chmod 700 "$TMP"
PASS=${BACKUP_PASSPHRASE_FILE:-}
if [[ -z "$PASS" ]]; then PASS="$TMP/senha"; grep '^BACKUP_PASSPHRASE=' "$HOMOLOG_DIR/.env" | cut -d= -f2- > "$PASS"; fi
trap 'rm -rf "$TMP"' EXIT
ok() { echo "  ✓ $*"; }

echo "1/5 integridade e decifragem"
(cd "$B" && sha256sum -c --quiet SHA256SUMS) && ok "SHA256SUMS confere"
for f in engenheiro.dump keycloak.dump objetos.tar manifesto.json; do
  gpg --batch --quiet --pinentry-mode loopback --passphrase-file "$PASS" -o "$TMP/$f" -d "$B/$f.gpg" </dev/null
done
ok "decifrado em área temporária privada (apagada ao fim)"
COMMIT=$(python3 -I -c "import json;print(json.load(open('$TMP/manifesto.json'))['commit_em_execucao'])")
export ENGENHEIRO_COMMIT=${ENGENHEIRO_COMMIT:-$COMMIT}
export ENGENHEIRO_BOOTSTRAP_OWNER_SUBJECT=   # restauração nunca cria dono novo

echo "2/5 banco vazio e restauração dos dois bancos"
$DC up -d postgres >/dev/null
for _ in $(seq 1 60); do $DC exec -T postgres pg_isready -U postgres -d keycloak >/dev/null 2>&1 && break; sleep 1; done
for db in engenheiro keycloak; do
  n=$($DC exec -T postgres psql -U postgres -d "$db" -Atc "SELECT count(*) FROM pg_tables WHERE schemaname='public'")
  [[ "$n" == "0" ]] || { echo "  ✗ banco $db não está vazio ($n tabelas): restauração recusada"; exit 3; }
  $DC exec -T postgres pg_restore -U postgres -d "$db" --exit-on-error --single-transaction < "$TMP/$db.dump"
done
ok "engenheiro e keycloak restaurados (transação única cada)"

echo "3/5 documentos"
$DC create api >/dev/null 2>&1
VOL=$(docker volume ls -q --filter "label=com.docker.compose.project=$P" | grep '_objetos$')
docker run --rm -i -v "$VOL":/dados postgres:18.6-alpine sh -c 'test -z "$(ls -A /dados)" && tar -C /dados -xf - && chown -R 10001:10001 /dados' < "$TMP/objetos.tar"
ok "objetos restaurados no armazenamento vazio"

echo "4/5 pilha"
$DC up -d >/dev/null
for _ in $(seq 1 90); do $DC exec -T api wget -qO- http://127.0.0.1:8080/actuator/health/readiness 2>/dev/null | grep -q UP && break; sleep 2; done
$DC exec -T borda nginx -s reload >/dev/null 2>&1 || true
for _ in $(seq 1 60); do curl -sf --cacert "$HOMOLOG_DIR/tls/ca.crt" "https://login.$HOMOLOG_DOMINIO:$HOMOLOG_PORTA/realms/engenheiro" >/dev/null && break; sleep 2; done
ok "API pronta e IdP respondendo"

echo "5/5 verificação contra o manifesto"
q() { $DC exec -T postgres psql -U postgres -d engenheiro -Atc "$1"; }
python3 -I - "$TMP/manifesto.json" \
  "$(q "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")" \
  "$(q "SELECT string_agg(t || '=' || n, ',') FROM (SELECT 'organizations' t, count(*) n FROM organizations UNION ALL SELECT 'app_users', count(*) FROM app_users UNION ALL SELECT 'products', count(*) FROM products UNION ALL SELECT 'customers', count(*) FROM customers UNION ALL SELECT 'expenses', count(*) FROM expenses UNION ALL SELECT 'documents', count(*) FROM documents UNION ALL SELECT 'audit_events', count(*) FROM audit_events UNION ALL SELECT 'journal_entries', count(*) FROM journal_entries) x")" \
  "$(q "SELECT string_agg(object_key || '=' || sha256, ',' ORDER BY object_key) FROM documents")" \
  "$($DC exec -T api sh -c 'cd /dados/objetos && find . -type f -exec sha256sum {} + | sed "s| \./| |"')" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))
schema, counts, docs, files = sys.argv[2:]
counts = dict(kv.split("=") for kv in counts.split(","))
docs = dict(kv.split("=") for kv in docs.split(",")) if docs else {}
disk = {l.split()[1]: l.split()[0] for l in files.splitlines() if l.strip()}
erros = []
if schema != m["schema_flyway"]: erros.append(f"schema {schema} != {m['schema_flyway']}")
for k, v in m["contagens"].items():
    if counts.get(k) != v: erros.append(f"{k}: {counts.get(k)} != {v}")
if docs != m["documentos"]: erros.append("documentos do banco diferem do manifesto")
for key, h in docs.items():
    if disk.get(key) != h: erros.append(f"objeto {key}: hash no disco não confere com o banco")
print(f"  {'✓' if not erros else '✗'} schema V{schema}; contagens {counts}")
print(f"  {'✓' if not erros else '✗'} {len(docs)} documentos do banco presentes no armazenamento com hash idêntico "
      f"({len(disk)} arquivos restaurados)")
for e in erros: print("  ✗", e)
sys.exit(1 if erros else 0)
PY
echo "RTO medido (restauração até verificação): $(python3 -I -c "print(round($(date +%s.%N)-$T0,1))") s"

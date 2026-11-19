#!/usr/bin/env bash
# Quatro APKs de release (F16) do commit atual, assinados com a chave do AMBIENTE e verificados: assinatura (apksigner,
# certificado esperado), pacote/versão, não depurável, rede só HTTPS, checksum e manifesto ligado ao commit.
# uso: infra/operacao/construir_apps.sh homologacao   (produção: mesma coisa com a chave do titular e URLs de produção,
#      sem ENGENHEIRO_CA_HOMOLOG — BLOQUEADO até D07)
set -euo pipefail
cd "$(dirname "$0")/../.."
AMB=${1:?ambiente (homologacao)}
[[ "$AMB" == "homologacao" ]] || { echo "só homologação nesta máquina; produção exige a chave do titular (D07)"; exit 2; }
H=${ENGENHEIRO_HOMOLOG_DIR:-$HOME/.config/engenheiro/homologacao}
set -a; . "$H/.env"; set +a
FLUTTER=${FLUTTER:-$HOME/development/flutter/bin/flutter}
BT=$(ls -d "$HOME/Android/Sdk/build-tools/"* | sort -V | tail -1)
git diff --quiet HEAD -- apps packages || { echo "há alterações locais em apps/packages: construa de um commit limpo"; exit 3; }
C=$(git rev-parse --short=12 HEAD)
N=$(git rev-list --count HEAD)          # versionCode crescente
OUT=${ENGENHEIRO_ARTEFATOS:-$HOME/.local/state/engenheiro/artefatos}/apps-$AMB-$C
mkdir -p "$OUT"
export ENGENHEIRO_KEYSTORE="$H/android/homologacao.jks" ENGENHEIRO_KEY_ALIAS=homologacao \
       ENGENHEIRO_KEYSTORE_PASSWORD="$ANDROID_KEYSTORE_PASSWORD" ENGENHEIRO_KEY_PASSWORD="$ANDROID_KEYSTORE_PASSWORD" \
       ENGENHEIRO_CA_HOMOLOG="$H/tls/ca.crt"
API="https://gestao.$HOMOLOG_DOMINIO:$HOMOLOG_PORTA"
ISS="https://login.$HOMOLOG_DOMINIO:$HOMOLOG_PORTA/realms/engenheiro"
CERT_ESPERADO=$(keytool -list -keystore "$ENGENHEIRO_KEYSTORE" -storepass "$ANDROID_KEYSTORE_PASSWORD" -alias homologacao 2>/dev/null \
  | grep -o 'SHA-256[^:]*: .*' | sed 's/.*: //; s/://g' | tr 'A-F' 'a-f')
echo "[" > "$OUT/manifesto.json"; primeiro=1
for app in mobile-owner mobile-counter mobile-delivery mobile-accountant; do
  (cd "apps/$app" && "$FLUTTER" build apk --release --no-pub --build-number="$N" \
     --dart-define=ENGENHEIRO_API="$API" --dart-define=ENGENHEIRO_ISSUER="$ISS" >/dev/null
   (cd android && ./gradlew --stop >/dev/null 2>&1 || true))   # libera a RAM entre um app e outro
  src="apps/$app/build/app/outputs/flutter-apk/app-release.apk"
  pkg=$("$BT/aapt2" dump badging "$src" | sed -n "s/^package: name='\([^']*\)' versionCode='\([^']*\)' versionName='\([^']*\)'.*/\1 \2 \3/p")
  set -- $pkg
  dest="$OUT/$1-$3+$2-$AMB.apk"; cp "$src" "$dest"
  cert=$("$BT/apksigner" verify --print-certs "$dest" | grep -m1 'SHA-256 digest' | sed 's/.*: //')
  "$BT/apksigner" verify "$dest" >/dev/null
  dbg=$("$BT/aapt2" dump xmltree --file AndroidManifest.xml "$dest" | grep -c 'debuggable.*=true' || true)
  rede=$("$BT/aapt2" dump xmltree --file AndroidManifest.xml "$dest" | grep -c 'networkSecurityConfig' || true)
  sha=$(sha256sum "$dest" | cut -d' ' -f1)
  [[ "$cert" == "$CERT_ESPERADO" && "$dbg" == "0" && "$rede" -ge 1 ]] || { echo "✗ $1: verificação falhou (cert/debuggable/rede)"; exit 4; }
  echo "  ✓ $1 $3 (build $2): assinatura de homologação conferida, não depurável, rede só HTTPS — $(du -h "$dest" | cut -f1) sha256 ${sha:0:16}…"
  [[ $primeiro == 1 ]] || echo "," >> "$OUT/manifesto.json"; primeiro=0
  printf '  {"app":"%s","versao":"%s","build":%s,"ambiente":"%s","commit":"%s","arquivo":"%s","sha256":"%s","certificado_sha256":"%s"}' \
    "$1" "$3" "$2" "$AMB" "$C" "$(basename "$dest")" "$sha" "$cert" >> "$OUT/manifesto.json"
done
echo "]" >> "$OUT/manifesto.json"
(cd "$OUT" && sha256sum ./*.apk > SHA256SUMS)
echo "APKs em $OUT (manifesto.json, SHA256SUMS)"

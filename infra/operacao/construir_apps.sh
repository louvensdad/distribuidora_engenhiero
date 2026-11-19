#!/usr/bin/env bash
# Quatro APKs de release (F16) do commit atual, assinados com a chave do AMBIENTE e verificados: assinatura (apksigner,
# certificado esperado), pacote/versão, não depurável, rede só HTTPS, checksum e manifesto ligado ao commit.
# uso: infra/operacao/construir_apps.sh homologacao
#      ENGENHEIRO_API=https://… ENGENHEIRO_ISSUER=https://…/realms/engenheiro infra/operacao/construir_apps.sh producao
#      produção (ADR-032, sem loja): chave da empresa (criar_chave_android_producao.sh), URLs HTTPS de produção e
#      NENHUMA CA de teste embutida (conferido no APK).
set -euo pipefail
cd "$(dirname "$0")/../.."
AMB=${1:?ambiente (homologacao|producao)}
FLUTTER=${FLUTTER:-$HOME/development/flutter/bin/flutter}
BT=$(ls -d "$HOME/Android/Sdk/build-tools/"* | sort -V | tail -1)
git diff --quiet HEAD -- apps packages || { echo "há alterações locais em apps/packages: construa de um commit limpo"; exit 3; }
C=$(git rev-parse --short=12 HEAD)
N=$(git rev-list --count HEAD)          # versionCode crescente
case "$AMB" in
  homologacao)
    H=${ENGENHEIRO_HOMOLOG_DIR:-$HOME/.config/engenheiro/homologacao}
    set -a; . "$H/.env"; set +a
    ALIAS=homologacao; JKS="$H/android/homologacao.jks"
    export ENGENHEIRO_CA_HOMOLOG="$H/tls/ca.crt"
    # CA privada de homologação: no Android (AppAuth, via release.gradle) e no Dart (cliente da API, http_ambiente.dart).
    CA_B64=$(base64 -w0 "$ENGENHEIRO_CA_HOMOLOG")
    API="https://gestao.$HOMOLOG_DOMINIO:$HOMOLOG_PORTA"
    ISS="https://login.$HOMOLOG_DOMINIO:$HOMOLOG_PORTA/realms/engenheiro" ;;
  producao)
    P=${ENGENHEIRO_PRODUCAO_DIR:-$HOME/.config/engenheiro/producao}
    [[ -f "$P/android/producao.jks" ]] || { echo "sem chave de produção: infra/operacao/criar_chave_android_producao.sh"; exit 2; }
    ANDROID_KEYSTORE_PASSWORD=$(sed -n 's/^ANDROID_KEYSTORE_PASSWORD=//p' "$P/.env")
    ALIAS=producao; JKS="$P/android/producao.jks"
    API=${ENGENHEIRO_API:?URL HTTPS da API de produção (D09)}; ISS=${ENGENHEIRO_ISSUER:?emissor OIDC de produção (D09)}
    [[ "$API" == https://* && "$ISS" == https://* ]] || { echo "produção só com HTTPS"; exit 2; }
    unset ENGENHEIRO_CA_HOMOLOG; CA_B64="" ;;
  *) echo "ambiente desconhecido: $AMB"; exit 2 ;;
esac
OUT=${ENGENHEIRO_ARTEFATOS:-$HOME/.local/state/engenheiro/artefatos}/apps-$AMB-$C
mkdir -p "$OUT"
export ENGENHEIRO_KEYSTORE="$JKS" ENGENHEIRO_KEY_ALIAS="$ALIAS" \
       ENGENHEIRO_KEYSTORE_PASSWORD="$ANDROID_KEYSTORE_PASSWORD" ENGENHEIRO_KEY_PASSWORD="$ANDROID_KEYSTORE_PASSWORD"
CERT_ESPERADO=$(keytool -list -keystore "$ENGENHEIRO_KEYSTORE" -storepass "$ANDROID_KEYSTORE_PASSWORD" -alias "$ALIAS" 2>/dev/null \
  | grep -o 'SHA-256[^:]*: .*' | sed 's/.*: //; s/://g' | tr 'A-F' 'a-f')
echo "[" > "$OUT/manifesto.json"; primeiro=1
for app in mobile-owner mobile-counter mobile-delivery mobile-accountant; do
  (cd "apps/$app" && "$FLUTTER" build apk --release --no-pub --build-number="$N" \
     --dart-define=ENGENHEIRO_API="$API" --dart-define=ENGENHEIRO_ISSUER="$ISS" \
     --dart-define=ENGENHEIRO_CA_HOMOLOG_B64="$CA_B64" >/dev/null
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
  # CA de teste: no recurso do Android e no código Dart (base64 de "-----BEGIN CERTIFICATE-----").
  # O release renomeia o recurso (ex.: res/GL.crt): conta qualquer certificado empacotado em res/.
  ca=$(( $(unzip -l "$dest" | grep -cE ' res/.*\.(crt|pem|cer)$' || true) + $(unzip -p "$dest" 'lib/*/libapp.so' | grep -ac 'LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0t' || true) ))
  if [[ "$AMB" == producao ]]; then
    [[ "$ca" == 0 ]] || { echo "✗ $1: APK de produção com CA de teste embutida"; exit 4; }
  else
    [[ "$ca" -ge 1 ]] || { echo "✗ $1: APK de homologação sem a CA de teste (não falaria com a homologação)"; exit 4; }
  fi
  echo "  ✓ $1 $3 (build $2): assinatura de $ALIAS conferida, não depurável, rede só HTTPS, CA de teste $([[ $AMB == producao ]] && echo ausente || echo só neste artefato) — $(du -h "$dest" | cut -f1) sha256 ${sha:0:16}…"
  [[ $primeiro == 1 ]] || echo "," >> "$OUT/manifesto.json"; primeiro=0
  printf '  {"app":"%s","versao":"%s","build":%s,"ambiente":"%s","commit":"%s","arquivo":"%s","sha256":"%s","certificado_sha256":"%s"}' \
    "$1" "$3" "$2" "$AMB" "$C" "$(basename "$dest")" "$sha" "$cert" >> "$OUT/manifesto.json"
done
echo "]" >> "$OUT/manifesto.json"
(cd "$OUT" && sha256sum ./*.apk > SHA256SUMS)
echo "APKs em $OUT (manifesto.json, SHA256SUMS)"

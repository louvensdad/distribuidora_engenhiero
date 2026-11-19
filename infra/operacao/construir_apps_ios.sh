#!/usr/bin/env bash
# Quatro apps iOS (iPhone + iPad) do commit atual — SÓ em macOS com Xcode e a conta Apple do TITULAR (D07).
# NÃO EXECUTADO até haver um Mac: a configuração é conferida em Linux por apps/ios-comum/verificar_ios.py (pipeline).
# uso (no Mac): ENGENHEIRO_APPLE_TEAM_ID=XXXXXXXXXX infra/operacao/construir_apps_ios.sh homologacao|producao
#   homologacao: URLs de homologação + CA de teste embutida no cliente da API (o aparelho instala e confia na CA:
#                Ajustes → Geral → Sobre → Ajustes de Confiança de Certificados); distribuição ad-hoc/TestFlight.
#   producao:    URLs de produção (ENGENHEIRO_API / ENGENHEIRO_ISSUER obrigatórias), sem CA de teste; App Store.
set -euo pipefail
cd "$(dirname "$0")/../.."
[[ "$(uname -s)" == "Darwin" ]] || { echo "build iOS exige macOS + Xcode (aqui: $(uname -s)). Configuração: apps/ios-comum/verificar_ios.py"; exit 2; }
AMB=${1:?ambiente (homologacao|producao)}
: "${ENGENHEIRO_APPLE_TEAM_ID:?equipe Apple do titular (D07)}"
git diff --quiet HEAD -- apps packages || { echo "há alterações locais em apps/packages: construa de um commit limpo"; exit 3; }
python3 -I apps/ios-comum/verificar_ios.py >/dev/null || { echo "configuração iOS não confere: rode apps/ios-comum/verificar_ios.py"; exit 4; }
FLUTTER=${FLUTTER:-flutter}
C=$(git rev-parse --short=12 HEAD); N=$(git rev-list --count HEAD)
DEFS=()
if [[ "$AMB" == "homologacao" ]]; then
  H=${ENGENHEIRO_HOMOLOG_DIR:-$HOME/.config/engenheiro/homologacao}; set -a; . "$H/.env"; set +a
  DEFS+=(--dart-define=ENGENHEIRO_API="https://gestao.$HOMOLOG_DOMINIO:$HOMOLOG_PORTA"
         --dart-define=ENGENHEIRO_ISSUER="https://login.$HOMOLOG_DOMINIO:$HOMOLOG_PORTA/realms/engenheiro"
         --dart-define=ENGENHEIRO_CA_HOMOLOG_B64="$(base64 < "$H/tls/ca.crt" | tr -d '\n')")
  METODO=ad-hoc
elif [[ "$AMB" == "producao" ]]; then
  : "${ENGENHEIRO_API:?URL HTTPS da API de produção}" "${ENGENHEIRO_ISSUER:?emissor OIDC de produção}"
  DEFS+=(--dart-define=ENGENHEIRO_API="$ENGENHEIRO_API" --dart-define=ENGENHEIRO_ISSUER="$ENGENHEIRO_ISSUER")
  METODO=app-store
else
  echo "ambiente desconhecido: $AMB"; exit 2
fi
OUT=${ENGENHEIRO_ARTEFATOS:-$HOME/.local/state/engenheiro/artefatos}/apps-ios-$AMB-$C
mkdir -p "$OUT"
EXPORT="$OUT/ExportOptions.plist"
cat > "$EXPORT" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>$METODO</string>
  <key>teamID</key><string>$ENGENHEIRO_APPLE_TEAM_ID</string>
  <key>signingStyle</key><string>automatic</string>
</dict></plist>
PLIST
for app in mobile-owner mobile-counter mobile-delivery mobile-accountant; do
  (cd "apps/$app" && "$FLUTTER" build ipa --release --no-pub --build-number="$N" --export-options-plist="$EXPORT" "${DEFS[@]}")
  ipa=$(ls "apps/$app/build/ios/ipa/"*.ipa | head -1)
  dest="$OUT/$(basename "$ipa" .ipa)-$AMB+$N.ipa"; cp "$ipa" "$dest"
  tmp=$(mktemp -d); unzip -q "$dest" -d "$tmp"; appdir=$(ls -d "$tmp"/Payload/*.app)
  codesign --verify --deep --strict "$appdir"
  bid=$(/usr/libexec/PlistBuddy -c "Print :CFBundleIdentifier" "$appdir/Info.plist")
  echo "  ✓ $bid (build $N): assinatura válida — sha256 $(shasum -a 256 "$dest" | cut -c1-16)…"; rm -rf "$tmp"
done
(cd "$OUT" && shasum -a 256 ./*.ipa > SHA256SUMS)
echo "IPAs em $OUT (SHA256SUMS)"

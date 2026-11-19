#!/usr/bin/env bash
# Chave de assinatura Android de PRODUÇÃO da empresa (ADR-032: distribuição direta por APK, sem loja).
# Gera UMA vez, fora do repositório (~/.config/engenheiro/producao/android, 700). NUNCA sobrescreve: perder ou trocar
# esta chave impede atualizar os apps já instalados (o Android só aceita atualização assinada pela mesma chave).
# Depois de gerar: guardar cópia do .jks e da senha em DOIS lugares seguros fora deste computador (ex.: pendrive
# cifrado no cofre + gerenciador de senhas do titular).
set -euo pipefail
P=${ENGENHEIRO_PRODUCAO_DIR:-$HOME/.config/engenheiro/producao}
JKS="$P/android/producao.jks"
if [[ -f "$JKS" ]]; then echo "a chave de produção já existe em $JKS — não foi alterada"; exit 0; fi
mkdir -p "$P/android"; chmod 700 "$P" "$P/android"
touch "$P/.env"; chmod 600 "$P/.env"
if ! grep -q '^ANDROID_KEYSTORE_PASSWORD=' "$P/.env"; then
  printf 'ANDROID_KEYSTORE_PASSWORD=%s\n' "$(openssl rand -base64 48 | tr -d '/+=\n' | cut -c1-32)" >> "$P/.env"
fi
PW=$(sed -n 's/^ANDROID_KEYSTORE_PASSWORD=//p' "$P/.env")
keytool -genkeypair -noprompt -keystore "$JKS" -storetype PKCS12 -storepass "$PW" -keypass "$PW" -alias producao \
  -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=Distribuidora de Cana Engenheiro Ltda, O=Engenheiro, C=BR" >/dev/null
chmod 600 "$JKS"
echo "chave de produção criada: $JKS (senha em $P/.env)"
keytool -list -keystore "$JKS" -storepass "$PW" -alias producao 2>/dev/null | grep -o 'SHA-256.*'
echo "FAÇA AGORA a cópia de segurança da chave e da senha (ver docs/runbooks/implantacao-e-rollback.md)."

#!/usr/bin/env bash
# Backup agendado (F16): backup completo + retenção + cópia externa + estado para monitoramento/alerta.
# RPO de projeto: 24 h (um backup diário; aprovar com o dono). Variáveis:
#   BACKUP_DESTINO (padrão ~/.local/state/engenheiro/backups), BACKUP_RETENCAO (padrão 14 cópias),
#   BACKUP_COPIA_REMOTA — comando que recebe o diretório do backup como $1 (ex.: rclone/aws s3 sync para um bucket
#   em OUTRO local, com versionamento). Sem ele: cópia externa NÃO CONFIGURADA (registrado; nunca "sucesso").
# Sai ≠ 0 em qualquer falha (o timer dispara OnFailure → alerta). Estado em $BACKUP_DESTINO/ULTIMO_BACKUP.json.
set -uo pipefail
cd "$(dirname "$0")/../.."
DEST=${BACKUP_DESTINO:-$HOME/.local/state/engenheiro/backups}
RET=${BACKUP_RETENCAO:-14}
mkdir -p "$DEST"
estado() { printf '{"quando_utc":"%s","resultado":"%s","detalhe":"%s","copia_externa":"%s"}\n' \
  "$(date -u +%FT%TZ)" "$1" "$2" "$3" > "$DEST/ULTIMO_BACKUP.json"; }
if ! saida=$(infra/operacao/backup.sh "$DEST" 2>&1); then
  estado FALHOU "backup.sh falhou" "-"; echo "$saida" | tail -3; exit 1
fi
NOVO=$(ls -d "$DEST"/engenheiro-* | sort | tail -1)
ls -d "$DEST"/engenheiro-* | sort | head -n -"$RET" | xargs -r rm -rf   # retenção: mantém as $RET mais recentes
COPIA="NAO_CONFIGURADA"
if [[ -n "${BACKUP_COPIA_REMOTA:-}" ]]; then
  if $BACKUP_COPIA_REMOTA "$NOVO"; then COPIA="OK"; else estado FALHOU "cópia externa falhou" FALHOU; exit 1; fi
fi
estado OK "$(basename "$NOVO")" "$COPIA"
echo "$saida"; echo "retenção: $(ls -d "$DEST"/engenheiro-* | wc -l) cópias (máx. $RET) · cópia externa: $COPIA"

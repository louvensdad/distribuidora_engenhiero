# Runbook — backup e restauração

Metas de projeto (a aprovar pelo dono): **RPO 24 h, RTO 4 h** no piloto. Medido em homologação (19/11/2026):
backup completo em ~2 s e restauração completa em ambiente vazio em **40,9 s** com o volume de dados de teste —
`docs/evidence/F16-backup-restauracao.txt`. Medir de novo com o volume real antes de abrir produção.

## O que entra no backup (tudo; sem uma das partes não é restauração completa)

| Parte | Por quê |
|---|---|
| Banco do sistema (`pg_dump -Fc`) | pedidos, estoque, financeiro, contabilidade, auditoria, sessões |
| Banco do IdP (Keycloak) | contas, senhas, TOTP — sem ele ninguém entra depois de restaurar |
| Documentos e anexos (volume de objetos) | arquivos citados pelo banco (o banco guarda só chave e sha256) |
| Manifesto | commit em execução, versão do esquema, contagens, sha256 de cada documento |

Tudo cifrado com AES-256 (gpg simétrico) e com `SHA256SUMS`. A **senha do backup fica no cofre**, nunca no mesmo
disco/bucket do backup.

## Agendado

`infra/operacao/backup-agendado.sh` via `systemd/engenheiro-backup.timer` (diário 03:30, America/Sao_Paulo):
retenção `BACKUP_RETENCAO` (padrão 14), cópia para outro local `BACKUP_COPIA_REMOTA` (bucket com versionamento em
outra região/conta — **NÃO CONFIGURADA** até a infraestrutura ser contratada, D09), estado em `ULTIMO_BACKUP.json`.
Falha ⇒ código ≠ 0 ⇒ `OnFailure=engenheiro-alerta@` avisa o responsável (comando de envio em
`/etc/engenheiro/alerta.env`; sem ele o alerta fica só no journal).

## Restaurar (ambiente vazio)

```
infra/operacao/restaurar.sh <diretório-do-backup>
```

1. Confere `SHA256SUMS` (backup adulterado é recusado) e decifra numa área temporária privada (apagada ao fim).
2. Recusa destino que não esteja vazio (não sobrescreve dados por engano).
3. Restaura os dois bancos (transação única cada) e os documentos.
4. Sobe a pilha e verifica contra o manifesto: versão do esquema, contagens e **cada documento com o mesmo sha256
   gravado no banco restaurado**.
5. Rodar a fumaça (`infra/homologacao/fumaca.py` ou equivalente de produção): o acesso volta com a mesma senha e TOTP.

## Teste periódico

Mensal: restaurar o backup mais recente num ambiente separado e registrar tempo e resultado em `docs/evidence/`.
Também depois de mudança de versão do PostgreSQL ou do Keycloak.

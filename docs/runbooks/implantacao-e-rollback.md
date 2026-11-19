# Runbook — implantação, migração e rollback

Vale para homologação e produção (mesmo roteiro; muda o destino, o IdP, o domínio e os segredos). Ensaiado em
homologação em 19/11/2026: `docs/evidence/F16-migracao-rollback.txt`.

## Antes de implantar

1. O commit passou em `infra/ci/verificar.sh` (ou no CI): especificação íntegra, sem segredo, sem vulnerabilidade
   alta/crítica em dependências e imagens, testes da API/site/apps e build.
2. Artefatos do commit construídos a partir de checkout limpo: `infra/operacao/construir.sh <commit>` →
   `~/.local/state/engenheiro/artefatos/<commit>.json` (sha256 do jar, ids das imagens). Em produção, as imagens vão
   para o registro privado com a mesma etiqueta (o commit) e o digest é conferido contra o manifesto.
3. Migrações novas só **expandem** o esquema (tabela/coluna nova, nunca apagar/renomear na mesma versão). Remoção vira
   uma segunda versão, depois que nenhuma versão no ar usa o que vai sair. Isso garante que a versão anterior roda
   sobre o esquema novo (rollback só da aplicação).
4. Janela combinada com o dono; avisar a equipe (Retirada, Entregas, Contabilidade) de 5 minutos de instabilidade.

## Implantar

```
infra/operacao/implantar.sh <commit>
```

O script: (1) faz backup completo cifrado **antes** da migração; (2) troca API e site para o commit; (3) espera a
prontidão (inclui o banco) e roda a fumaça pela borda TLS; (4) se falhar, **volta sozinho** à última versão que passou
(guardada em `versao-no-ar`) e confere de novo. Códigos de saída: 0 no ar; 3 revertido com sucesso; 4 rollback sem
sucesso (ir para "Restaurar o backup pré-migração").

A guarda de produção roda antes do Flyway: parâmetro faltando ou vestígio de ambiente local = nenhuma migração aplicada
e a API não sobe (mensagem lista só os NOMES dos parâmetros).

## Rollback manual da aplicação

```
infra/operacao/implantar.sh <commit-anterior>
```

A versão anterior ignora migrações futuras (o Flyway as trata como "futuras") e continua funcionando.

## Restaurar o backup pré-migração (último recurso)

Só quando a migração em si corrompeu dados e o rollback da aplicação não basta. Perde o que foi gravado depois do
backup — avisar o dono antes. Ver `backup-e-restauracao.md` (restauração em ambiente vazio).

## Primeiro acesso de um banco vazio (implantação inicial)

1. Criar a conta do dono no IdP de produção (com TOTP obrigatório) e anotar o id (subject).
2. Subir a API uma vez com `ENGENHEIRO_BOOTSTRAP_OWNER_SUBJECT=<id>` (e `ENGENHEIRO_BOOTSTRAP_OWNER_NAME`): cria
   empresa, matriz e o dono administrador (auditado). Com empresa já existente, não faz nada.
3. Retirar a variável. O dono cria unidades, cargos, funcionários e acessos pelo sistema.

## Parâmetros obrigatórios em produção (cofre de segredos → variáveis de ambiente)

`ENGENHEIRO_DB_URL`, `ENGENHEIRO_DB_USER`, `ENGENHEIRO_DB_PASSWORD`, `ENGENHEIRO_OIDC_ISSUER`, `ENGENHEIRO_OIDC_JWKS`
(HTTPS, não local), `ENGENHEIRO_WEB_APP_URL` (HTTPS), `ENGENHEIRO_WEB_CLIENT_SECRET`, `ENGENHEIRO_STORAGE_DIR`
(volume persistente com backup), `ENGENHEIRO_LOG_PEPPER` (≥ 32 caracteres). Opcionais com estado NÃO CONFIGURADO se
ausentes: `ENGENHEIRO_PROVISIONER_CLIENT_ID/SECRET` (contas de funcionários), `ANTHROPIC_API_KEY` +
`ENGENHEIRO_IA_ORCAMENTO_USD` (IA), provedores fiscal/pagamento (D02–D04). Rotação: trocar no cofre e reimplantar
(`implantar.sh` com o mesmo commit); o segredo antigo é revogado depois que a nova versão passa na fumaça.

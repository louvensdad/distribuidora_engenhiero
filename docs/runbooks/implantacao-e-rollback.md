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

## Apps Android (releases)

- `infra/operacao/construir_apps.sh homologacao` gera os 4 APKs de um commit limpo, assinados com a chave de
  homologação, e confere certificado, pacote/versão, não depurável e rede só HTTPS; grava `manifesto.json` (commit,
  sha256, certificado) e `SHA256SUMS` em `~/.local/state/engenheiro/artefatos/apps-homologacao-<commit>/`.
- Release sem `ENGENHEIRO_KEYSTORE` falha de propósito (nunca assina com a chave de depuração). Produção: chave de
  envio do titular via Play App Signing (D07), URLs de produção e **sem** `ENGENHEIRO_CA_HOMOLOG`.
- Homologação usa CA privada. Ela vai embutida no APK (AppAuth e cliente da API), mas o login abre no navegador do
  sistema, que não enxerga a CA do app: **instale `tls/ca.crt` como CA de usuário no aparelho de teste**
  (Configurações → Segurança → Criptografia e credenciais → Instalar certificado → Certificado de CA). Sem isso o
  navegador mostra "conexão não particular" e o login não começa (comportamento correto). Em produção o certificado
  é de CA pública e nada disso é necessário.
- Um aparelho com a versão de depuração instalada recusa o release (assinaturas diferentes): desinstalar antes.

## Contas de funcionários e administração do IdP (F17)

- A administração do IdP **não** passa pela internet: a borda responde 404 para `/admin/` e para o realm `master`.
  Operador administra por dentro do servidor (`docker compose exec keycloak /opt/keycloak/bin/kcadm.sh …`).
- O dono cria as contas pelo próprio sistema (Equipe → Funcionários → Acesso aos apps). Para isso:
  `infra/homologacao/definir-provisionamento.sh` (mesmo roteiro em produção, com o segredo indo ao cofre) cria o client
  de serviço `engenheiro-provisioner` (só gestão de usuários do realm `engenheiro`), deixa o e-mail opcional no perfil
  do realm (balconista/motorista sem e-mail não ficam presos no primeiro acesso) e grava o segredo. A API fala com o
  IdP por `ENGENHEIRO_PROVISIONER_INTERNAL_URL=http://keycloak:8080/realms/engenheiro`; reimplantar depois.
- Sem o segredo, "dar acesso" responde NÃO CONFIGURADO e o cadastro do funcionário continua valendo. Falha do IdP
  aparece no log como `criação de conta no provedor de identidade falhou: provedor respondeu <código> (<erro>)`.
- Ensaio completo (dono cadastra a equipe, cada um entra pelo próprio app e opera, conciliação):
  `python3 -I infra/homologacao/ensaio_piloto.py` — senhas novas ficam em
  `~/.config/engenheiro/homologacao/ensaio/<execução>.json` (600).

## APKs de produção e instalação direta (ADR-032)

1. **Uma vez:** `infra/operacao/criar_chave_android_producao.sh`. Copie `~/.config/engenheiro/producao/android/producao.jks`
   e a senha (`~/.config/engenheiro/producao/.env`) para **dois** lugares seguros fora deste computador (ex.: pendrive
   cifrado guardado com o titular + gerenciador de senhas). Sem essa chave os apps instalados não podem mais ser
   atualizados.
2. **Cada versão:** `ENGENHEIRO_API=https://<api> ENGENHEIRO_ISSUER=https://<login>/realms/engenheiro
   infra/operacao/construir_apps.sh producao` — gera os 4 APKs, `manifesto.json` e `SHA256SUMS`.
3. **Instalar:** copiar o APK do app da função para o aparelho (cabo, pasta compartilhada ou link interno), abrir e
   permitir "instalar apps desta fonte" na primeira vez. Conferir o SHA-256 do arquivo com o `SHA256SUMS`.
4. **Atualizar:** instalar o APK novo por cima; a sessão e os dados permitidos ficam. Nunca desinstalar antes (perde a
   sessão) e nunca instalar APK de homologação num aparelho de produção.

## IA (ADR-032)

No `.env`/cofre do servidor: `ENGENHEIRO_IA_PROVEDOR=deepseek`, `DEEPSEEK_API_KEY`, `ENGENHEIRO_IA_ORCAMENTO_USD`
(mensal; 0 = recusa), `ENGENHEIRO_IA_RESERVA_USD=0.05` e os preços `ENGENHEIRO_IA_PRECO_ENTRADA=0.30`,
`ENGENHEIRO_IA_PRECO_SAIDA=1.20`, `ENGENHEIRO_IA_PRECO_CACHE=0.006` (US$/milhão; revisar se a DeepSeek mudar).
Reimplantar. Falha do provedor aparece no log como `provedor de IA indisponível: …`. A API precisa confiar nas CAs
públicas (o truststore de homologação é refeito por `preparar.sh`).

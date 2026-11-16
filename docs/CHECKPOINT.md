# Checkpoint

Data/hora e fuso: 16/11/2026 11:30 America/Sao_Paulo
Agente e repositório: Claude Code (Opus 5.5) — `/home/louvens/Ivan/distribuidora-engenheiro` (movido de `~/distribuidora-engenheiro`)
Branch e commit atual: `main` — ver `git log -1`
Fase: F03 concluída (limites resolvidos em 16/11/2026); próxima F04
Gate: F00 **PASS** · F01 **PASS** (com limites) · F02 **PASS** (Android; iOS sem macOS) · F03 **PASS** (pendências externas abaixo) · F04 **NOT_STARTED**

## Entregue e comprovado

**F00** — inventário, decisões D01–D15, `toolchain.lock.json`, ADRs, mapa de requisitos.

**F01** — `services/api` (Spring Boot 4.1.2 / Java 21 / Spring Security 7.1.2):
- Apps: bearer JWT validado (assinatura, emissor, audiência, validade) + vínculo local ativo + app liberado, recarregado a cada chamada.
- Site: BFF com login OIDC no servidor (Authorization Code + PKCE S256), tokens e refresh só na sessão do servidor,
  cookie `ENGENHEIRO_SESSION` HttpOnly/SameSite=Lax (Secure fora do perfil local), CSRF (`X-XSRF-TOKEN`) em mutações por cookie,
  troca de ID de sessão no login, renovação automática do access token, logout com URL de logout do IdP.
  Sem `ENGENHEIRO_WEB_CLIENT_SECRET` o login web fica `NAO_CONFIGURADO` e a API bearer segue funcionando.
- Autorização negar-por-padrão (recurso/ação/escopo/campo/alçada/validade; negação explícita vence), administração de acessos
  versionada, proteções contra autopromoção/escalada/último admin (com trava contra corrida), auditoria imutável com ator real.
- Filtro de linha no servidor (`ScopeSql`) e 404 indistinguível para registro fora do escopo.
- Contrato `packages/contracts/openapi.json` dos endpoints existentes, conferido contra os controllers por teste.

**F03 (fatia)** — clientes: `POST/GET /api/v1/customers`, `GET /api/v1/customers/{id}`; escopo por unidade; contato/endereço/documento
mascarados conforme política de campo e recusados na gravação por quem não os vê; auditoria sem dado pessoal.

**F02 (web)** — `apps/web-admin` em React 19 + Vite 8 + TypeScript 7 (ADR-015, pedido do responsável):
- Entrada segura (W01) via BFF; estados NÃO CONFIGURADO, sessão expirada, conta sem acesso ao site.
- Abas horizontais Visão geral/Vendas/Operação/Financeiro/Relatórios/Inteligência/Equipe e subabas montadas por
  `/me/capabilities`; rota sem capacidade não chama a API. Módulos de fases futuras aparecem como "em implementação — Fxx",
  sem números de exemplo.
- Telas reais: Visão geral (equipe e últimos movimentos; KPIs financeiros pendentes F06/F09), Clientes (busca, paginação,
  cadastro, campos ocultos), Pessoas (bloquear/reativar com motivo e If-Match), Mapa de acessos W15 (modelos, áreas
  recolhíveis, escopo, alçada, campos, apps, resumo antes de salvar, conflito 412), Histórico W16 (filtro por pessoa, cursor).
- Tokens/ícones/marca do pacote; fontes locais; tema escuro; 360/768/1440 px; skip link e foco visível.
- Nenhum token no navegador (cookie HttpOnly); CSRF automático nas mutações.

**F02 (apps)** — workspace Dart (`pubspec.yaml` raiz) com `packages/mobile-design|mobile-api|mobile-auth|mobile-core` e os
4 apps `apps/mobile-owner|counter|delivery|accountant` (`br.com.engenheiro.gestao|retirada|entregas|contabil`), cada um com
client OIDC público próprio (PKCE S256, redirect `<applicationId>:/oauth2redirect`), tokens em armazenamento seguro, shell com até
5 destinos por capacidade. Endereços via `--dart-define` (`ENGENHEIRO_API`, `ENGENHEIRO_ISSUER`); release recusa HTTP.
API: `/audit-events?client_id=` (atividades por app de origem, para a Central do App Patrão), com teste e contrato.

**Toolchain móvel** — Flutter 3.50.0 + Android SDK 36 instalados em `~/development/flutter` e `~/Android/Sdk`
(sem sudo, downloads verificados por hash); APK debug de prova gerado. Licenças do Android SDK aceitas via `sdkmanager --licenses`.

**F03** — cadastros, fornecedores e estoque (ADR-016):
- V4: `products` + `unit_conversions` (fator por produto, vigência), `suppliers`, `inventory_receipts(+lines)`,
  `inventory_balances` (físico ≥ reservado ≥ 0, valor ≥ 0), `stock_movements` imutável com origem única,
  `stock_reservations`, `inventory_losses`, `idempotency_keys`.
- `StockLedger`: custo médio móvel, saída pelo médio, devolução pelo custo histórico, reserva atômica,
  `fulfillReservation` pronto para o cumprimento da F04.
- 20 endpoints novos no contrato (produtos, fornecedores, recebimentos, saldos/resumo, movimentos, perdas);
  `Idempotency-Key` nos comandos, `If-Match` nas transições; custo mascarado sem `purchase_cost`.
- Site: Operação > Estoque (W05), Recebimentos (W06 + detalhe com conferência e devolução), Produtos, Fornecedores.
- Apps: telas comuns `StockScreen`, `ReceiptsScreen`, `NewReceiptScreen` (R07), `ReceiptDetailScreen` em `mobile-core`;
  Retirada ganhou "Fornecedores" e "Estoque"; Patrão abre Estoque e Recebimentos em "Mais".

## Validação

- `./mvnw verify` → **75 testes, 0 falhas** (`docs/evidence/F01-mvnw-verify.txt`).
- Testes de mutação (trava de admins, CSRF, escopo por unidade): os testes falham com a mutação e passam com o código real
  (`F01-testes-de-mutacao.txt`).
- Ponta a ponta com Keycloak 26.8.1 real: formulário de login, cadastro de TOTP do patrão, PKCE, sessão, `/me` 200, mutação sem CSRF 403 /
  com CSRF 200, logout → 401, contador no site do patrão → 403 auditado (`F01-e2e-login-web-keycloak.txt`).
- Smoke de infraestrutura (`F01-smoke-local.txt`).
- Backend `./mvnw verify` → **77 testes** (inclui `LocalSeedIntegrationTest`).
- Site: Vitest **19 testes** (`F02-web-vitest.txt`); Playwright **31 testes** no Chrome real contra Keycloak+API
  (`F02-web-playwright.txt`), capturas em `docs/evidence/telas-web/`.
- Toolchain móvel: `F02-toolchain-mobile.txt`.
- Apps: `flutter analyze` limpo, **29 testes Dart**; 4 APKs debug gerados, instalados e abertos no emulador API 36 (processo vivo,
  MainActivity em primeiro plano) — `F02-mobile-apks-emulador.txt`, capturas em `docs/evidence/telas-mobile/`.
- Login móvel ponta a ponta com Keycloak real nos 4 apps (balcao, motorista.a, contador, patrao com cadastro de TOTP): shell por
  capacidade, campos ocultos pela política, sessão persistente, Sair; balcao no app Contábil → "Sem permissão" com código igual
  ao `request_id` auditado; Central do Patrão filtra por app (`client_id`). Corrigido: `taskAffinity=""` nos manifests quebrava o
  retorno do login (AppAuth "No stored state").
  Sem rota ao IdP: erro de conexão, sem sessão falsa.
- Backend com filtro `client_id`: `./mvnw verify` → **78 testes, 0 falhas**.

- **F03**: `./mvnw verify` → **90 testes, 0 falhas** (`F03-mvnw-verify.txt`), incluindo os critérios de aceite
  (reservas concorrentes, 100 recebidas/20 vendidas, perda baixada uma vez); mutação da reserva pega pelo teste
  (`F03-testes-de-mutacao.txt`). Site: Vitest **24**, Playwright **47** no Chrome real (`F03-web-vitest.txt`,
  `F03-web-playwright.txt`). Apps: `flutter analyze` limpo, **33** testes Dart; APK Retirada no emulador com o usuário
  `recebimento` real: rascunho sem custo, diferença registrada, auditoria com app de origem (`F03-mobile.txt`).

Problemas encontrados e corrigidos nesta etapa: seed local como `V1000` impedia novas migrations (agora `R__` idempotente,
com teste); tabela estourava a largura no celular por texto `visually-hidden` absoluto; matriz de acessos longa demais
(agora agrupada por área).

## F03 — limites resolvidos (16/11/2026) e pendências restantes

Resolvidos: conta a pagar criada na confirmação (vencimento opcional; site Financeiro > Contas a pagar); ajuste por
contagem com aprovação (site e API); cadastro de unidades (Equipe > Unidades, com trava de pessoas/estoque); anexos
privados no recebimento (site e app, câmera/galeria); app Patrão exercitado no emulador (estoque e conferência com custo
de um rascunho feito no app Retirada). Validação: `./mvnw verify` **99 testes** (`F03-mvnw-verify.txt`), Vitest **26**,
Playwright **54**, Dart **34**, evidências móveis em `F03-mobile.txt`. Decisões: ADR-016, ADR-017.

Pendências que não dependem desta fase:
- Antivírus do anexo: NÃO CONFIGURADO (registrado no documento); armazenamento S3 privado de produção: F16.
- Liquidação da conta a pagar: F06. Venda real a partir de pedido (SALE_ISSUE): F04 (próxima).
- `payables`/unidades sem recurso no contrato de permissões: restritos ao administrador até o contrato mudar (ADR-017).

## Limites do gate F01 (explícitos)

- Login móvel testado por script adb (`infra/local/e2e_login_mobile.sh`), não por `integration_test`; só Android.
- Critério "pedido/anexo de outra unidade por ID" provado com **clientes**; reverificar em pedidos (F04) e documentos (F07)
  usando o mesmo `requireRecord`/`ScopeSql`.
- Canais SSE/websocket ainda não existem; revogação nesses canais será testada quando forem criados.
- Sessão HTTP em memória (uma instância). Para mais de uma instância: Spring Session JDBC (F16).
- `INTERNAL_ERROR` (500) não consta em `erro.schema.json`: propor inclusão no contrato.

## Bloqueios e decisões

- iOS exige macOS + Xcode (não há nesta máquina). Android OK.
- `PATH` do Flutter não foi gravado no `~/.bashrc` (ação bloqueada pela política de permissões); usar caminho completo ou adicionar manualmente.
- npm 9.2.0 do sistema falha com Vite 8 (`edgesOut`); usar `npx -y npm@11.21.1`.
- Ambiente local: o Keycloak foi recriado a partir da nova pasta (o container antigo apontava para o caminho
  anterior); senha de admin local em `~/.config/engenheiro/kc-admin` (fora do repo). A linha legada `V1000` do
  histórico do Flyway local foi removida (vinha de um `target/classes` antigo; usar `./mvnw clean package`).
- `docker start` do container antigo criou, como root, um diretório vazio em `/home/louvens/distribuidora-engenheiro`;
  pode ser apagado com `sudo rm -r /home/louvens/distribuidora-engenheiro`.
- Porta 8080 ocupada nesta máquina; local usa `PORT=8081` (redirect 8081 incluído no realm).
- D01–D15 em `docs/F00_INVENTARIO.md` §4.

## Como reproduzir localmente

```bash
export KC_ADMIN_PASSWORD=<aleatória>            # só para o container local
docker compose -f infra/local/docker-compose.yml up -d
ENGENHEIRO_SENHA_LOCAL=<aleatória> infra/local/definir-senhas-locais.sh
# segredo do client web: gerado pelo Keycloak (kcadm get clients/<id>/client-secret) -> ENGENHEIRO_WEB_CLIENT_SECRET
cd services/api && ./mvnw -q -DskipTests package
PORT=8081 SPRING_PROFILES_ACTIVE=local ENGENHEIRO_WEB_CLIENT_SECRET=... java -jar target/engenheiro-api-0.1.0-SNAPSHOT.jar
ENGENHEIRO_SENHA_LOCAL=... python3 -I ../../infra/local/e2e_login_web.py
```

## Próxima ação

1. **F04**: pedidos, retirada, pagamentos e caixa — reservar com `StockLedger.reserve` e baixar com
   `fulfillReservation` no cumprimento (CMV por item); reverificar escopo por ID em pedidos.

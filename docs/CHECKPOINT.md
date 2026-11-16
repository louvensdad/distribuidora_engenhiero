# Checkpoint

Data/hora e fuso: 16/11/2026 16:20 America/Sao_Paulo
Agente e repositório: Claude Code (Opus 5.5) — `/home/louvens/Ivan/distribuidora-engenheiro` (movido de `~/distribuidora-engenheiro`)
Branch e commit atual: `main` — ver `git log -1`
Fase: F06 concluída (financeiro e reconciliação); próxima F07
Gate: F00 **PASS** · F01 **PASS** (com limites) · F02 **PASS** (Android; iOS sem macOS) · F03 **PASS** · F04 **PASS** (pendências abaixo) · F05 **PASS** (pendências abaixo) · F06 **PASS** (pendências abaixo) · F07 **NOT_STARTED**

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

## F04 — pedidos, retirada, pagamentos e caixa (16/11/2026)

Entregue (ADR-018): preço de venda com vigência; pedido com reserva atômica e número por unidade; desconto por alçada
explícita; retirada total/parcial só do que está pago ou a prazo autorizado; receita e CMV do entregue; cancelamento e
devolução pelo custo histórico; pagamentos (dinheiro com troco no caixa aberto, Pix pendente até conferência manual,
cartão com NSU/taxa/líquido, dividido = saldo devedor, a prazo só administrador), estorno com alçada; caixa do turno com
esperado/contado/diferença/justificativa e conferente; livro financeiro balanceado e imutável (também para recebimento,
perda, ajuste e devolução ao fornecedor); histórico do pedido com autor e app.
Superfícies: site (Vendas > Pedidos W03/W04, Financeiro > Caixa do turno, preço em Produtos); app Retirada (Pedidos,
Caixa) e Patrão (Mais > Pedidos, Meu caixa).
Validação: `./mvnw verify` **110** (`F04-mvnw-verify.txt`), Vitest **27**, Playwright **56** (`F04-web-playwright.txt`),
Dart **35**, emulador com usuário `balcao` (`F04-mobile.txt`); mutação da retirada concorrente em `F03-testes-de-mutacao.txt`.

Pendências F04:
- Provedor Pix/webhook (assinatura, repetição): NÃO CONFIGURADO (D04) → 503; conferência manual autorizada funciona.
- Solicitação/aprovação de desconto fora da alçada: hoje só bloqueia (quem tem alçada faz o pedido).
- Anexo de comprovante no pedido: o pagamento aceita `document_id` do pedido, mas ainda não há envio de anexo no pedido.
- A prazo: registrado com vencimento; contas a receber/cobrança e liquidação na F06.
- App: Pix pendente é registrado, mas conferir Pix, retirada parcial, cancelamento e devolução estão só no site; app
  Patrão com Pedidos/Caixa compilado e com teste de widget, não exercitado no emulador nesta fase.
- Concessões do balcão no emulador aplicadas direto no banco local (equivalentes ao mapa de acessos).

## F06 — financeiro, competência e reconciliação (16/11/2026)

Entregue (ADR-020), migration V8: contas financeiras (caixas de turno, cofre, bancos, adquirente) com saldo = soma do
livro e extrato; competência separada da data do lançamento; despesas por categoria/subcategoria com aprovação por
alçada (despesa uma vez + conta a pagar); pagamento de contas a pagar sem nova despesa, estorno do pagamento e da
despesa; devolução ao fornecedor abate a conta (excedente = crédito); recebíveis de cartão em parcelas líquidas e a
prazo, com liquidação/conciliação do repasse; transferências internas (consolidado igual) e estorno; resultado
gerencial por competência com caixa à parte e drilldown de cada total; despesas por subcategoria; conciliação
(livro, a pagar, cartões, estoque, caixas de turno); fechamento mensal com retrato, trava no livro (serviço + gatilho)
e reabertura com solicitação + aprovação por outra pessoa.
Superfícies: site (Financeiro › Caixa e contas W08, Resultado e fechamento, Despesas, Contas a pagar, Contas a receber;
Visão geral com os números reais do mês); app Patrão (Mais › Financeiro).
Validação: `./mvnw clean verify` **127** (`F06-mvnw-verify.txt`), mutação 4/4 pegas (`F06-testes-de-mutacao.txt`),
Vitest **36** (`F06-web-vitest.txt`), Playwright **76** (`F06-web-playwright.txt`, capturas `telas-web/F06-*.png`),
Dart **39**, app Patrão no emulador com login TOTP real (`F06-mobile.txt`).
Fixtures do pacote usados como teste: FECHAMENTO-01, CAIXA-01, CARTAO-01, OBRIGACAO-01, RATEIO-01 (F05).

Pendências F06:
- Dados locais anteriores ao livro (F03/antes da V6) aparecem como divergência explicada (R$ 1.035,10 a pagar; R$ 1.794,74
  estoque) — saldos de abertura de legado na implantação (F16). Em banco novo a conciliação fecha.
- Conciliação bancária por extrato importado (OFX/CNAB), antecipação de recebíveis e chargeback: fora da F06.
- Acerto do adiantamento de funcionário (gasto de motorista recusado) ainda sem tela própria (fica na conta
  ADIANTAMENTOS_FUNCIONARIOS); folha/guias reais na F07.
- Despesas/pagamentos pelo app (Patrão/Contador): F13/F14 (no app Patrão, F06 entrega só a leitura do Financeiro).

## F05 — frota, viagens e entregas (16/11/2026)

Entregue (ADR-019), migration V7: pedido de entrega com endereço/referência/contato/janela/pagamento combinado (campos
mascarados pela política); veículos com placa, capacidade em medida, licenciamento/seguro, situação; odômetro somente
inclusão e monotônico (serviço + gatilho; correção só por administrador com motivo); viagem com vários pedidos, veículo e
motorista autorizados (capacidade por conversão do produto, indisponibilidade, um pedido por viagem aberta); saída,
entrega total/parcial pelo `OrderService` (com recebimento na parada no caixa do motorista), ocorrências (nunca marca
entregue), retorno com prestação do dinheiro e conciliação por outra pessoa; gastos (combustível, manutenção, pneus,
seguro, documentos, pedágio) registrados uma vez — empresa → despesa + conta a pagar na aprovação; dinheiro do motorista →
adiantamento no registro e despesa na aprovação; km/l só entre tanques cheios; rateio analítico versionado (TRIP_COST v1)
sem lançamento; históricos por veículo, viagem e pedido (autor e app).
Superfícies: site (Operação > Frota W07 com detalhe do veículo; Operação > Viagens com despacho, saída, paradas,
retorno, conferência, gastos e custos; pedido de entrega no formulário e bloco Entrega/viagem no W04); app Entregas
(Viagens, Veículos/abastecimento, Caixa).
Validação: `./mvnw clean verify` **118** (`F05-mvnw-verify.txt`), mutação (`F05-testes-de-mutacao.txt`), Vitest **33**
(`F05-web-vitest.txt`), Playwright **63** (`F05-web-playwright.txt`, capturas `telas-web/F05-*.png`), Dart **38**,
emulador com `motorista.b` + conferência do patrão e conferência do livro no banco (`F05-mobile.txt`).
Defeito encontrado e corrigido: "45.200" digitado virava 45,2 (site e app); agora vírgula é decimal e ponto seguido de
grupos de 3 dígitos é milhar, em todos os formulários.

Pendências F05:
- Despacho e cadastro de pedido de entrega pelo app (E02–E04) e ocorrência sem parada: F12 (no site já funcionam).
- Foto da entrega: enviada pelo app (câmera/galeria) e autorizada pela viagem; no site ainda não há botão de anexo da
  parada nem do comprovante do gasto (endpoints prontos e testados no backend) — F10.
- Cartão na entrega: manual com NSU (provedor NÃO CONFIGURADO — D04).
- Concessões extras do `motorista.b` aplicadas direto no banco local (equivalentes ao mapa de acessos).
- Pedido de teste preso na viagem V2 (local) de uma execução E2E interrompida — sem impacto, só dado fictício local.

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

Atalho (pilha já configurada nesta máquina): `infra/local/subir-tudo.sh` sobe Postgres + Keycloak, compila e inicia a
API em :8081 (segredo do client web lido do Keycloak, só em memória) e o site em :4200; `--emulador` liga também o
emulador `engenheiro_api36` com `adb reverse`; `--parar` desliga API, site e emulador. Logs em `~/.local/state/engenheiro/`.
Primeira configuração ou Keycloak recriado: passos abaixo.

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

1. **F07**: contabilidade, folha e documentos — guias e folha como obrigações por competência (mesmo caminho da F06:
   reconhecer uma vez, pagar sem nova despesa), documentos do contador com quarentena/antivírus NÃO CONFIGURADO explícito.

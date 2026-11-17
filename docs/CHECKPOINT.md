# Checkpoint

Data/hora e fuso: 17/11/2026 15:02 America/Sao_Paulo
Agente e repositório: Codex — `/home/louvens/Ivan/distribuidora-engenheiro`
Branch e commit atual: `main` — alterações locais não commitadas; ver `git status`
Fase: F12 concluída; próxima F13
Gate: F00 **PASS** · F01 **PASS** (com limites) · F02 **PASS** (Android; iOS sem macOS) · F03 **PASS** · F04 **PASS** (pendências abaixo) · F05 **PASS** (pendências abaixo) · F06 **PASS** (pendências abaixo) · F07 **PASS** (pendências abaixo) · F08 **PASS** (critérios provados; integração real BLOCKED: D02/D03/D04) · F09 **PASS** (IA depende da F15) · F10 **PASS** (IA real: F15) · F11 **PASS** (homologação HTTPS: F16) · F12 **PASS** (Android; ponta a ponta real em Keycloak/API local, widget/API e APK aberto)

## F12 — app Entregas (17/11/2026)

Ligados ao app Entregas os pedidos de entrega (busca de cliente/produto, endereço e pagamento combinado) e o despacho pelo
app (pedidos abertos, veículo e motorista elegíveis, criação/atribuição da viagem). O erro de capacidade conserva a
viagem em rascunho e a nova tentativa reaproveita a mesma viagem/chave; o servidor segue autoridade para capacidade,
documentação, escopo e disponibilidade. Adicionado registro de manutenção e outros gastos de veículo, com aprovação
financeira posterior. Os fluxos existentes de motorista, parada, entrega, ocorrência, abastecimento, retorno e caixa são
reutilizados; APIs continuam compartilhadas com site e App Patrão.

Validação observada nesta retomada: `flutter analyze apps/mobile-delivery packages/mobile-core packages/mobile-api` limpo;
suíte do app Entregas passou (6 testes); suíte `mobile-core` passou (29 testes); `./mvnw -Dtest=EntregasAppIntegrationTest
test` passou (4 testes com PostgreSQL/Testcontainers), cobrindo motorista sem acesso a outra viagem, Pix já pago,
reenvio concorrente da entrega sem baixa dupla e dados mínimos. APK debug compilado, instalado e aberto no AVD
`engenheiro_api36`. Login real pelo Keycloak local com contas individuais `coordenador` e `motorista.b`: pedido #76 criado
pelo coordenador; viagem V23 atribuída a Motorista B, saída 1.001 km, entrega com R$ 10,00 apresentados para total de
R$ 7,50 (troco calculado pelo servidor), retorno em 1.002 km e prestação de R$ 7,50 (diferença zero). O pedido ficou
FULFILLED/PAID; a tela do motorista mostrou “Pago — não cobrar novamente”. Manutenção preventiva de R$ 50,00 gravada
como PENDING/COMPANY pelo coordenador. Consulta do banco confirmou os atores e `client_id=engenheiro-entregas` em
`orders.create`, `trips.assign`, `trips.depart`, `payments.register`, `orders.fulfill`, `trips.deliver`, `trips.return` e
`vehicles.expense` (todos SUCCESS); trilha disponível à central de auditoria existente. Capturas: `docs/evidence/telas-mobile/F12-entregas-pedido-criado.png`,
`F12-entregas-viagem-atribuida.png`, `F12-entregas-motorista.png`, `F12-entregas-entregue.png`,
`F12-entregas-retornada.png` e `F12-entregas-manutencao-registrada.png`; comandos e resultados em
`docs/evidence/F12-validacao.txt`.

Gate **PASS (Android)**. iOS requer macOS/Xcode; HTTPS de homologação e chave de assinatura de produção continuam em F16.

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

## Pedido do responsável — funcionários, funções e cargos (17/11/2026, ADR-027)

Entregue (migration V13): Equipe › **Funcionários** (lista com busca/situação/cargo, ficha, novo, edição com versão,
desligamento) e **Cargos** (nome, setor, acesso sugerido); calendário de folgas foi para **Folgas e férias**.
Funcionário existe sem login; CPF/nascimento/endereço, telefone e salário são campos protegidos (mascarados, recusados
na gravação, mantidos ao salvar, fora da trilha). "Dar acesso aos apps" cria a conta individual no Keycloak por um
client de serviço (`engenheiro-provisioner`; local: `infra/local/definir-provisionamento-local.sh`), senha temporária
mostrada uma vez e troca obrigatória; sem credencial = NÃO CONFIGURADO (também em Integrações). Desligamento bloqueia a
conta na hora.
Validação: `./mvnw verify` **156** + caso de escalada (`ADR027-mvnw-verify.txt`), mutação 3/3, Vitest **69**,
Playwright completo (`ADR027-web-playwright.txt`), Dart mobile-core 26; telas `telas-web/ADR027-*.png`.
Defeito real encontrado na rodada completa e corrigido: formulários de pedido, recebimento, perda e contagem
carregavam só os 100 primeiros clientes/produtos/fornecedores — com a base crescendo, o item novo sumia. Agora são
seletores com busca no servidor (`usePicker`), testados (`usePicker.test.tsx`) e usados pelos E2E.
Pendências: validar com o dono os cargos e com o contador os campos de RH/folha (folha segue por vínculo); App Patrão
(F14); client de serviço e política de e-mail no realm de produção (F16).

## F11 — app Retirada, balcão e fornecedores (17/11/2026, ADR-026)

Entregue: "Meus pedidos" com busca (número/cliente), período Hoje/7/30/Todos (`GET /orders?from&to`, dia civil SP) e
paginação; resultado incerto após queda de conexão (nada dado como pago/retirado no aparelho, confere no servidor,
mesma chave na nova tentativa); comprovante não fiscal; APK release instalável (`br.com.engenheiro.retirada`, sem
debuggable/cleartext/backup). Teste antigo instável corrigido (`IntegrationsIntegrationTest`: assinatura "inválida"
podia coincidir com a válida 1/256).
Validação: `./mvnw verify` **152** (`F11-mvnw-verify.txt`), mutação 2/2 (`F11-testes-de-mutacao.txt`), Dart **53**
(`F11-mobile-testes.txt`), E2E `retirada.spec.ts`, emulador (`F11-mobile.txt`, `telas-mobile/F11-*.png`).
Critérios: recebedor vê recebimentos com custo oculto e balcão não vê recebimentos/fornecedores/relatórios/saldo; sem
seletor de perfil (teste de código + release); pedido #65 do balcão aparece no site e no App Patrão com autor "Balcão
Exemplo" e app Retirada (banco: 1 pagamento, 1 retirada mesmo após a queda).
Pendências F11: servidor HTTPS de homologação e chave de assinatura de produção (F16); impressão/compartilhamento do
comprovante (hoje só na tela); concessões locais do balcão são revogadas pelo E2E do mapa de acessos (reaplicar).

## F10 — site integral do patrão (17/11/2026, ADR-025)

Lacunas encontradas no levantamento e fechadas: listas presas aos 50/100 primeiros registros, filtros perdidos ao
recarregar, clientes/fornecedores sem edição, histórico sem setor/registro/período, sem busca geral, sem central de
aprovações e sessão expirada derrubando a tela (perdendo o digitado); página inexistente aparecia como "sem permissão".
Entregue (sem migration):
- Visão geral › **Setores** (Retirada, Entregas, Contabilidade): pendências, últimos movimentos com autor/app/"abrir" e
  entrada nos módulos, sempre com a conta do patrão. `GET /audit-events` ganhou `sector`, `entity_type`, `entity_id`,
  `result` e período em dia civil de São Paulo.
- Visão geral › **Aprovações** (`GET /approvals`): só o que o ator pode decidir (guias, folhas, perdas, ajustes,
  despesas com aviso de alçada, gastos de veículo, caixas, pagamentos a conferir, reabertura de mês, folgas), no escopo e
  com as máscaras de cada módulo; a decisão continua na tela do módulo.
- **Busca geral** no topo (`GET /search`): número, nome, SKU e placa só nos módulos permitidos, nunca em campo sensível;
  curinga literal. `GET /orders?q=` e `GET /trips?q=`.
- **Editar/arquivar** cliente e fornecedor (`PUT`, If-Match, campo oculto mantido e recusado se enviado).
- Listas paginadas com "Carregar mais" e filtros na URL (pedidos, clientes, fornecedores, produtos, viagens,
  recebimentos, despesas, contas a pagar, histórico, guias); após cadastrar, a lista mostra o registro criado.
- Totais da Visão geral abrem os lançamentos (`/reports?abrir=`); histórico com "Abrir origem" e "Só este registro";
  "Perguntar à IA" desligado e rotulado até a F15.
- **Sessão expirada** sem desmontar a tela (aviso com entrar em outra aba / já entrei / entrar nesta aba com volta ao
  endereço); outra conta → recarrega do zero; erro de rede com "Tentar novamente" (também no "Carregar mais").
- Rótulos do histórico unificados entre site e apps (notas fiscais apareciam como `invoices.review`).
Validação: `./mvnw verify` **148** (`F10-mvnw-verify.txt`), mutação 4/4 pegas (`F10-testes-de-mutacao.txt`), Vitest
**64** (`F10-web-vitest.txt`), Playwright **137** (+2 pulados; `F10-web-playwright.txt`, inclui 360/768/1440 nas
rotas novas), Dart mobile-core **22** (`F10-mobile.txt`). Telas: `telas-web/F10-*.png`.
Critérios: o patrão percorre os três setores sem trocar de identidade (E2E confere o nome no topo); a correção feita
pelo patrão num cadastro do balcão fica no setor de Retirada como do patrão, pelo site (integração + E2E); sessão
expirada (cookie de sessão removido no Chrome real) e rede abortada se recuperam sem perder dados.
Defeito encontrado na rodada completa e corrigido: com paginação, o fornecedor recém-cadastrado podia não aparecer
na primeira página — agora a lista filtra pelo registro criado.

Pendências F10:
- IA da gestão (W14 e "Perguntar à IA"): F15. Central/busca/histórico no App Patrão: F14.
- `audit.view` continua mostrando a organização inteira (setor é filtro de leitura, não permissão).

## F09 — relatórios, metas e gráficos 3D (17/11/2026, ADR-024)

Entregue (migration V12): relatório canônico único (`ReportService`) por dia/semana/mês/período em America/Sao_Paulo
(`[início, fim)`), por unidade conforme o escopo; categorias e subcategorias antes do fechamento; CMV, despesas,
perdas, lucro/prejuízo, margem e quantidade vendida (cumprida − devolvida) com quantidade em carteira separada;
comparação com o período anterior com fórmula; linha do tempo; drilldown até o pedido/registro e o comprovante;
PDF/XLSX/CSV gerados no servidor do mesmo objeto (auditados; `reports.export`/`reports.print`); retrato de mês fechado
com política versionada (`RESULTADO-GERENCIAL-1`) conferido com o livro; metas semanais/mensais (rascunho → ativa →
cancelada) com realizado, faltante, %, ritmo e fórmula; melhor cliente por medidas separadas (sem nota única).
Campos ocultos: sem `purchase_cost` e `margin`, CMV/perdas/lucro/margem somem de tela, gráfico, arquivos, linhas a custo,
ranking e metas — também no resumo da F06, que passou a exigir escopo de organização. Data do lançamento agora vem do
`Clock` injetado no fuso comercial.
Superfícies: site (Relatórios › Relatório completo, Gastos por categoria, Metas, Clientes; Visão geral usa o mesmo
relatório) e app Patrão (aba Relatórios: Resultado com colunas 3D/2D e Metas).
Validação: `./mvnw verify` **144** (`F09-mvnw-verify.txt`), mutação 3/3 pegas (`F09-testes-de-mutacao.txt`), Vitest
**54** (`F09-web-vitest.txt`), Playwright **118** (+2 pulados; `F09-web-playwright.txt`), Dart **45** e emulador (`F09-mobile.txt`).
Critérios: FECHAMENTO-01 (40.000 − 15.000 − 15.000 − 500) = 9.500 na API, nas colunas, na linha do tempo, no CSV, no
XLSX e no PDF; 23:30 em São Paulo (02:30 UTC) conta no dia de São Paulo; campo oculto ausente de PDF/CSV/XLSX.
Defeitos encontrados e corrigidos: ranking usava coluna inexistente; relatório em transação só-leitura falhava ao
garantir as contas padrão; resumo antigo quebrava a tela quando o lucro vinha oculto; rótulos do gráfico cortados,
caixa "2D" deformada e prejuízo em verde no site; sobreposição e quantidades com seis zeros no app.

Pendências F09:
- "Analisar com a IA" fica NÃO CONFIGURADA até a F15 (usará o mesmo serviço, com escopo e campos); busca global: F10/F15.
- Validar com o dono quais metas acompanhar e lucro por unidade (despesa da empresa lançada na Matriz conta na Matriz).
- Metas são criadas/ativadas pelo site; no app, só leitura (paridade de edição na F14).

## F08 — integrações fiscais e de pagamento (16/11/2026, ADR-023)

Respostas do responsável: provedor fiscal, tipo de nota e provedor de pagamentos ainda não definidos → tudo pronto
por adaptador e BLOQUEADO até contratar (nada simulado).
Entregue (migration V11): configuração fiscal (só administrador) e NCM do produto pelo contador; nota a partir do
pedido com estados distintos (rascunho, conferida, transmitindo, incerta, autorizada, rejeitada, cancelamento pendente,
cancelada, descartada); XML/protocolo/chave na autorizada; envio fora da transação com trava de estado/versão e
referência estável (sem emissão duplicada); incerta só se consulta (manual ou job), nunca reenvia; cobrança Pix por
provedor e avisos assinados idempotentes (inválido/repetido/valor diferente não mexem no caixa; autor = identidade
técnica SERVICE); histórico de tentativas por integração; painel de integrações com estado honesto e triagem.
Superfícies: site (Financeiro › Notas fiscais, Integrações), app Contábil (aba Notas: conferir e informar NCM).
Validação: `./mvnw clean verify` **138** (`F08-mvnw-verify.txt`), mutação 3/3 pegas — M3 só depois de tornar a corrida
determinística (`F08-testes-de-mutacao.txt`), Vitest **48**, Playwright **103** (+2 do fluxo cruzado da F07),
Dart **42**, emulador contador + patrão (`F08-mobile.txt`).
Defeitos encontrados e corrigidos: NCM corrigido no produto não aparecia no rascunho aberto; telas novas rolavam para o
lado no celular (360 px). Observação: uma execução do Playwright falhou no login por reaproveitar o código TOTP da mesma
janela de 30 s (repetição passou; não é defeito do produto).

Pendências F08:
- INT-01: definir com o contador o documento fiscal, regime e dados do emitente (D02) e contratar provedor + certificado
  (D03); depois, adaptador real e evidência em HOMOLOGAÇÃO. Pagamentos automáticos: contratar provedor Pix/adquirente
  (D04). Até lá, conferência manual autorizada.
- Cartão por provedor usa o mesmo contrato de aviso, sem adaptador ainda. Notas no app Patrão: F14.

## F07 — contabilidade, folha e documentos (16/11/2026, ADR-022)

Entregue (migration V10): guias em etapas (preparar → enviar com arquivo → aprovar/devolver → pagar), despesa uma vez
na aprovação, guia aprovada continua não paga; guia de encargos ligada à folha paga o passivo sem nova despesa; folha por
rubricas (lançada ou importada de CSV validado, tudo ou nada) com aprovação, passivos de líquido/benefícios/encargos e
compensação de adiantamento; valores por pessoa só com `employee_salary` (JSON e exportação); central de documentos com
versões, solicitações e download autorizado/auditado; envio sem arquivo real recusado (422, antes 500).
Superfícies: app Contábil (Agenda, Guias, Folha, Documentos; seletor de arquivo do Android), site (Financeiro › Guias,
Folha, Documentos), Central do app Patrão (aba Contabilidade).
Validação: `./mvnw clean verify` **134** (`F07-mvnw-verify.txt`), mutação 3/3 pegas (`F07-testes-de-mutacao.txt`),
Vitest **44** (`F07-web-vitest.txt`), Playwright **95** + 2 do fluxo cruzado que dependem de pendência vinda do app
(executados com sucesso no fluxo do emulador; `F07-web-playwright.txt`), Dart **41**, emulador com contador + patrão no
site (`F07-mobile.txt`).
Defeito encontrado e corrigido: o site sugeria "hoje" pelo UTC do navegador; às 21h+ de Brasília o pagamento era
recusado como "no futuro". Agora todas as datas padrão usam o fuso de São Paulo (`todayBr`/`monthStartBr`, com teste).

Pendências F07:
- Notas fiscais: F08 (adaptador fiscal). Antivírus de anexos: NÃO CONFIGURADO; armazenamento S3 privado: F16.
- Layout do CSV da folha a confirmar com o sistema do contador; folha e guias no app Patrão (aprovar pelo celular): F14.
- Fluxo cruzado app→site no Playwright depende de pendência criada no app (marcado como "skipped" quando não há).

## Pedido do responsável após a F06 — funcionários e Open Finance (16/11/2026, ADR-021)

Entregue (migration V9): Equipe › Funcionários com folgas/férias/afastamentos (registro, aprovação por outra pessoa,
sem sobreposição, aviso de setor sem ninguém), calendário de disponíveis por dia e setor, dias de férias no ano e
desempenho por função (medidas separadas com fórmula pública, sem nota única — antecipa parte de REP-08).
Financeiro › Open Finance: conexão por conta bancária com CNPJ e consentimento, leitura do extrato, conciliação com
pagamentos/transferências/recebimentos já registrados, saída nova como despesa sugerida (aprovação já paga pelo banco),
entrada sem par para conferência, saldo do banco × livro na conciliação. Sem agregador contratado: NÃO CONFIGURADO
(503), nada importado ou simulado.
Validação: `./mvnw clean verify` **131** (`ADR021-mvnw-verify.txt`), Vitest **39** (`ADR021-web-vitest.txt`),
Playwright **84** (`ADR021-web-playwright.txt`, capturas `telas-web/ADR021-*.png`). O fluxo com agregador é provado
com um agregador falso que existe só no código de teste.
Pendente: contratar agregador e implementar o `OpenFinanceProvider` real; funcionários no app Patrão (F14); pedido de
férias pelo próprio funcionário nos apps (F11–F13); regras da CLT de férias com o contador (F07).

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
- Despacho e cadastro de pedido de entrega pelo app (E02–E04): resolvidos na F12 (pedido e despacho pelo app Entregas).
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

1. **F13**: aplicativo independente do contador (guias, folha, documentos e fluxos autorizados).
2. Validar com o dono/contador os cargos e campos de RH (ADR-027).

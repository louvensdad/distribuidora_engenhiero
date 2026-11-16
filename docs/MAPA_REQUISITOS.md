# Mapa requisito → módulo → teste

Estado: NOT_STARTED / IN_PROGRESS / PARTIAL / PASS / BLOCKED. Só PASS com evidência em `docs/evidence/`.
Requisitos de F02 em diante seguem NOT_STARTED (lista completa em `docs/especificacao/plano/requisitos.json`).

| Req | Módulo / superfície | Teste / evidência | Estado |
|---|---|---|---|
| PROD-01 | Todas; `application.yaml` `engenheiro.empresa.nome`, realm, seeds | `AuthenticationIntegrationTest.tokenValidoComVinculoAtivoAutentica` | PASS (backend); revalidar em web/apps |
| PROD-02 | `apps/web-admin` + 4 `apps/mobile-*`, um `services/api` | `docs/F00_INVENTARIO.md` §3; 5 clients no realm | PASS (mapa); builds em F02/F11–F14 |
| PROD-03 | `apps/mobile-owner` + central | F14 | NOT_STARTED |
| PROD-04 | Recebimento interno no app Retirada (`RECEBIMENTO`) | `RoleTemplate.RECEBIMENTO` | PASS (mapa) |
| PROD-05 | `memberships` sem teto | `AuthorizationIntegrationTest.suportaMaisDeOitoFuncionarios` | PASS |
| PROD-06 | Inventário | `docs/F00_INVENTARIO.md`, `F00-validar_pacote.txt` | PASS |
| SEC-01 | `shared/security` (bearer + BFF `oauth2Login` PKCE), realm Keycloak (5 clients) | `AuthenticationIntegrationTest`, `WebBffIntegrationTest`, `F01-e2e-login-web-keycloak.txt` | PASS (web e API); login nos apps móveis BLOCKED (sem Flutter) |
| SEC-02 | `AccessPolicy`, `AuthorizationService`, `ScopeSql` (filtro de linha) | `AccessPolicyTest`, `AuthorizationIntegrationTest`, `CustomersIntegrationTest` | PASS (motor + primeira entidade); aplicar a cada novo módulo |
| SEC-03 | Modelos sem dashboard/relatórios/folha; campos negados por padrão | `balcaoRecebeSomente...`, `contadorNaoRecebe...`, `ContractConsistencyTest` | PASS (identidade); revalidar quando os endpoints existirem |
| SEC-04 | `RoleTemplate`, `POST /users/{id}/access-policy`, `GET /role-templates` | `AuthorizationIntegrationTest` | PARTIAL — API pronta; tela de mapa de acessos em F02/F10 |
| SEC-05 | Ator recarregado do banco por requisição | `revogacaoImpedeAProximaChamada`, `bloqueioDeConta...`, `removerAplicativo...` | PASS (HTTP); canais SSE quando existirem |
| SEC-06 | BFF: tokens só na sessão do servidor; cookie HttpOnly/SameSite=Lax; CSRF | `WebBffIntegrationTest.loginCompleto...SemExporTokens`, e2e Keycloak | PASS (web); segredo de app móvel: não há (clients públicos) |
| SEC-07 | Escopo ASSIGNED | `AccessPolicyTest.motoristaSoVeViagemAtribuidaASi` | PARTIAL — falta teste HTTP com viagens (F05) |
| SEC-08 | `requireRecord` (404 = inexistente), `ScopeSql` em listas/busca | `CustomersIntegrationTest` | PARTIAL — busca/listas OK; downloads, relatórios, eventos e IA nas fases próprias |
| SEC-09 | Ator real na auditoria; sem impersonação | `campoExtraNoCorpoNaoMudaAtor...`, `historicoRegistraAtorReal...` | PASS (identidade) |

| INV-01 | `catalogparties.products` (unidade-base confirmada, `unit_conversions` com vigência); site Produtos | `InventoryIntegrationTest.produtoComConversao...`; `inventory.spec.ts` | PASS |
| INV-02 | `suppliers`, `inventory.ReceiptService` (rascunho → conferência, lote, documento, custo, frete rateado); W06; app Retirada R06/R07 | `recebimentoQuantitativoSemCustoVisivel...`, `idempotenciaDoRecebimento`, `inventory.spec.ts`, `inventory_test.dart`, `F03-mobile.txt` | PASS (obrigação a pagar: F06) |
| INV-03 | `StockLedger` (físico/reservado/disponível, reserva atômica), W05 | `duasReservasConcorrentesNaoNegativamEstoque` (×3, com mutação), `reservaRepetida...`, `livroDeEstoqueImutavel...` | PASS |
| INV-04 | Custo médio móvel; CMV = saídas por venda | `recebimentoDe100EVendaDe20NaoLancaAs100ComoCmv` | PASS (cumprimento real de pedido: F04) |
| INV-05 | `LossService` (motivo, aprovação, custo, autor) | `perdaBaixaFisicoUmaVezPeloCustoMedioEForaDoCmv`, `inventory.spec.ts` | PASS |
| INV-06 | Devolução ao fornecedor e estorno de perda com `reversal_of` e custo histórico | `devolucaoAoFornecedorEEstornoDePerda...`, `inventory.spec.ts` | PASS (ajuste por contagem: pendente, ADR-016) |
| clientes (F03) | `catalogparties.customers` — cliente com campos classificados | `CustomersIntegrationTest` (11) | PASS |

| UX-01 | `apps/web-admin` abas horizontais + subabas por capacidade | `navigation.test.ts`, `site.spec.ts` (ordem das 7 abas) | PASS (web) |
| UX-02 | CSS responsivo; tabelas viram cartões ≤720 px | `layout.spec.ts` em 360/768/1440 (sem rolagem horizontal, ações visíveis) | PASS (web); apps em F11–F14 |
| UX-03 | Ícones/nomes/IDs dos 4 apps | realm + `F00_INVENTARIO.md`; ícones em `docs/especificacao/assets/apps` | PARTIAL — builds dos apps em F11–F14 |
| UX-04 | Estados carregando/vazio/erro/sem permissão/conflito/offline/pendente | `App.test.tsx` (403, 412, pendente), `States.tsx` | PASS (web) |
| UX-05 | Skip link, foco visível, labels, aria-live, alvo 44 px, movimento reduzido | `site.spec.ts` (teclado) | PARTIAL — falta auditoria com leitor de tela |
| WEB-01..03 | Site integrado a serviços reais (sem botões falsos; módulos futuros rotulados por fase) | Playwright 47 testes (`F03-web-playwright.txt`) | PARTIAL — só módulos já existentes no backend |

Testes adicionais de F01: imutabilidade de `audit_events` (trigger), paginação por cursor, 412 por versão,
concorrência entre administradores (`ConcurrencyIntegrationTest`, com teste de mutação).

Contrato dos endpoints implementados: `packages/contracts/openapi.json`, verificado por `OpenApiContractTest`.

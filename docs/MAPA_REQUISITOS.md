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
| INV-02 | `suppliers`, `inventory.ReceiptService` (rascunho → conferência, lote, documento, custo, frete rateado); W06; app Retirada R06/R07 | `recebimentoQuantitativoSemCustoVisivel...`, `idempotenciaDoRecebimento`, `inventory.spec.ts`, `inventory_test.dart`, `F03-mobile.txt` | PASS (obrigação a pagar criada na confirmação; liquidação F06); anexos privados `F03LimitsIntegrationTest`, `f03-limites.spec.ts` |
| INV-03 | `StockLedger` (físico/reservado/disponível, reserva atômica), W05 | `duasReservasConcorrentesNaoNegativamEstoque` (×3, com mutação), `reservaRepetida...`, `livroDeEstoqueImutavel...` | PASS |
| INV-04 | Custo médio móvel; CMV = saídas por venda | `recebimentoDe100EVendaDe20NaoLancaAs100ComoCmv` | PASS (cumprimento real de pedido: F04) |
| INV-05 | `LossService` (motivo, aprovação, custo, autor) | `perdaBaixaFisicoUmaVezPeloCustoMedioEForaDoCmv`, `inventory.spec.ts` | PASS |
| INV-06 | Devolução ao fornecedor e estorno de perda com `reversal_of` e custo histórico | `devolucaoAoFornecedorEEstornoDePerda...`, `inventory.spec.ts` | PASS; ajuste por contagem `F03LimitsIntegrationTest.ajustePorContagem...`, `f03-limites.spec.ts` |
| clientes (F03) | `catalogparties.customers` — cliente com campos classificados | `CustomersIntegrationTest` (11) | PASS |
| ORD-01 | `orders.OrderService` (itens, total e preço do servidor, autor); W03/W04; app Retirada | `OrdersIntegrationTest.precoDoServidor...`, `orders.spec.ts`, `orders_test.dart`, `F04-mobile.txt` | PASS (retirada; entrega física na F05) |
| ORD-02 | Reserva na criação, cumprimento separado, venda só no cumprimento | `pedidoDe75PagoCom100...` (B03), `entregaParcial...` | PASS |
| ORD-03 | Dinheiro: valor apresentado, troco, caixa só com o valor do pedido | `pedidoDe75PagoCom100DaTroco25ECaixaSobe75` (B02), `orders.spec.ts`, emulador | PASS |
| ORD-04 | Pix (pendente/conferência), débito/crédito (NSU, taxa), dividido, a prazo autorizado | `pixSoComprovanteNaoPaga...` (B07/B09), `cartaoReceitaBruta...` (B08), `escopoProprioEPrazo...` | PASS (provedor Pix/webhook NÃO CONFIGURADO — D04) |
| ORD-05 | `payment_state` separado de `state` | `pedidoDe75...`, `pixSoComprovante...` | PASS |
| ORD-06 | Desconto e estorno por alçada; cancelamento por capacidade | `precoDoServidorEDescontoPorAlcada`, `cancelarLiberaReserva...` | PASS (solicitação de aprovação de desconto: pendente) |
| ORD-07 | Entrega parcial e devolução pelo custo histórico sem duplicar CMV | `entregaParcialReconhece...` (B06), `cancelarLiberaReserva...` (B12) | PASS |
| ORD-08 | Adiantamento em CLIENTES; entrega reclassifica sem nova entrada de caixa | `pedidoDe75...` (B04), emulador (`F04-mobile.txt`) | PASS |
| ORD-09 | Caixa próprio: fundo, esperado, contado, diferença, justificativa, conferente | `pedidoDe75...`, `orders.spec.ts`, emulador | PASS |
| ORD-10 | Idempotency-Key em pedido, pagamento, conferência, retirada, cancelamento, devolução, estorno | `entregaParcial...` (repetição), `retiradasConcorrentes...` (B05), `pixSoComprovante...` | PASS |
| LOG-01 | Pedido de entrega com endereço, janela, contato e pagamento combinado (`OrderService`, W03/W04) | `pedidoDeEntregaExigeEnderecoEPagamentoCombinado...`, `trips.spec.ts` | PASS |
| LOG-02 | Viagem com vários pedidos, placa/veículo, motorista e capacidade (`TripService.assign`, site Operação > Viagens) | `veiculoIncapazOuIndisponivel...`, `mesmoPedidoEmDuasViagens...`, `trips.spec.ts`, `Fleet.test.tsx` | PASS (despacho pelo app: F12) |
| LOG-03 | Saída/retorno com odômetro, destinatário e evidência (anexo da parada) | `odometroRegressivoRejeitado`, `fluxoCompleto...`, emulador (`F05-mobile.txt`) | PASS |
| LOG-04 | Ocorrência, ausente, recusa, reagendamento (volta para despacho), parcial | `fluxoCompleto...`, `delivery_test.dart`, emulador | PASS (devolução = cancelamento autorizado do pedido após retorno) |
| LOG-05 | Combustível: litros, km, posto, comprovante, responsável; km/l só com tanques cheios | `consumoKmPorLitroSoEntreTanquesCheios`, `delivery_test.dart`, emulador | PASS |
| LOG-06 | Manutenção, pneus, seguro, documentos, pedágio; avisos por veículo | `consumoKm...` (manutenção e aviso), `gastoRateado...` (pedágio), `Fleet.test.tsx` | PASS |
| LOG-07 | Prestação de contas do motorista e dinheiro recebido; conferência por outra pessoa | `fluxoCompleto...`, `trips.spec.ts`, emulador + conciliação | PASS |
| LOG-08 | Rateio analítico versionado sem repetir despesa | `gastoRateadoSomaAoOriginalSemDuplicarDespesa` | PASS |

| UX-01 | `apps/web-admin` abas horizontais + subabas por capacidade | `navigation.test.ts`, `site.spec.ts` (ordem das 7 abas) | PASS (web) |
| UX-02 | CSS responsivo; tabelas viram cartões ≤720 px | `layout.spec.ts` em 360/768/1440 (sem rolagem horizontal, ações visíveis) | PASS (web); apps em F11–F14 |
| UX-03 | Ícones/nomes/IDs dos 4 apps | realm + `F00_INVENTARIO.md`; ícones em `docs/especificacao/assets/apps` | PARTIAL — builds dos apps em F11–F14 |
| UX-04 | Estados carregando/vazio/erro/sem permissão/conflito/offline/pendente | `App.test.tsx` (403, 412, pendente), `States.tsx` | PASS (web) |
| UX-05 | Skip link, foco visível, labels, aria-live, alvo 44 px, movimento reduzido | `site.spec.ts` (teclado) | PARTIAL — falta auditoria com leitor de tela |
| WEB-01..03 | Site integrado a serviços reais (sem botões falsos; módulos futuros rotulados por fase) | Playwright 63 testes (`F05-web-playwright.txt`) | PARTIAL — só módulos já existentes no backend |

Testes adicionais de F01: imutabilidade de `audit_events` (trigger), paginação por cursor, 412 por versão,
concorrência entre administradores (`ConcurrencyIntegrationTest`, com teste de mutação).

Contrato dos endpoints implementados: `packages/contracts/openapi.json`, verificado por `OpenApiContractTest`.

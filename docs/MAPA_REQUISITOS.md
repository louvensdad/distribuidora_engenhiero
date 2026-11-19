# Mapa requisito → módulo → teste

Estado: NOT_STARTED / IN_PROGRESS / PARTIAL / PASS / BLOCKED. Só PASS com evidência em `docs/evidence/`.
Estados dos requisitos de F02 em diante são registrados individualmente na tabela (lista completa em
`docs/especificacao/plano/requisitos.json`).

| Req | Módulo / superfície | Teste / evidência | Estado |
|---|---|---|---|
| PROD-01 | Todas; `application.yaml` `engenheiro.empresa.nome`, realm, seeds | `AuthenticationIntegrationTest.tokenValidoComVinculoAtivoAutentica` | PASS (backend); revalidar em web/apps |
| PROD-02 | `apps/web-admin` + 4 `apps/mobile-*`, um `services/api` | `docs/F00_INVENTARIO.md` §3; 5 clients no realm | PASS (mapa); builds em F02/F11–F14 |
| PROD-03 | `apps/mobile-owner` + central | `packages/mobile-core/lib/src/screens/owner/*`, `apps/mobile-owner/lib/main.dart`; análise, 45 testes `mobile-core`, 3 `mobile-owner`, 4 integrações Java e fluxo no AVD — `docs/evidence/F14-validacao.txt` | IN_PROGRESS — IA está explicitamente planejada para F15 |
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
| SEC-08 | `requireRecord` (404 = inexistente), `ScopeSql` em listas/busca; busca geral por módulo permitido | `CustomersIntegrationTest`, `buscaGeralRespeitaEscopoECamposSensiveis`, `campoOcultoNaoVazaEmNenhumaSaida` | PARTIAL — busca, listas, downloads e relatórios OK; eventos em tempo real e IA nas fases próprias |
| SEC-09 | Ator real na auditoria; sem impersonação; ação do patrão no setor fica em nome dele | `campoExtraNoCorpoNaoMudaAtor...`, `historicoRegistraAtorReal...`, `patraoVeOsTresSetoresComAPropriaIdentidade...`, `central.spec.ts` | PASS |

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
| APP-E-01 | App Entregas independente: pedidos, despacho, viagens e veículos por capacidade | `apps/mobile-delivery`; `delivery_test.dart`; `entregas_test.dart`; fluxo Keycloak/API no AVD; `F12-validacao.txt` | PASS (Android; iOS indisponível sem macOS) |
| APP-E-02 | Motorista restrito às viagens atribuídas e aos dados mínimos | `EntregasAppIntegrationTest.motoristaNaoAbreViagemAlheiaPorIdNemPelosRecursosDela`; login `motorista.b`; capturas F12 | PASS |
| APP-C-01 | App Contábil separado com agenda, guias, folha, notas e documentos | `apps/mobile-accountant`; `accounting_screens.dart`; `contabil_test.dart`; `ContabilAppIntegrationTest`; fluxo no AVD; `F13-validacao.txt` | PASS (Android; transmissão fiscal real BLOQUEADA: D02/D03) |
| APP-C-02 | Contador acompanha aprovação e rejeição sem saldo/dashboard geral | `ContabilAppIntegrationTest.guiaEnviada…`, `.contadorNaoRecebeValorGlobal`; `IntegrationsIntegrationTest` (nota rejeitada vista pelo contador); capturas F13 | PASS |
| APP-P-01 | App Patrão contém todas as funções do site | `apps/mobile-owner/lib/main.dart`; `packages/mobile-core/lib/src/screens/owner/*`; matriz preenchida em `docs/PARIDADE_WEB_APP.csv`; `patrao_test.dart`; `owner_test.dart`; capturas F14 | PASS (Android; IA presente desde F15, NÃO CONFIGURADA sem chave) |
| APP-P-02 | Central reúne Retirada, Entregas e Contabilidade com cada movimento | `central.dart`; `PatraoAppIntegrationTest.trilhaEncadeadaDoPedidoAteADespesaDaViagem` (cada elo exige a capacidade do próprio recurso); central no AVD; `F14-validacao.txt` | PASS (Android) |
| APP-P-03 | Filtros por pessoa, cliente, pedido, fornecedor, placa e período | `AuditController.list` (ator, app, setor, tipo, registro, período); `/api/v1/search`; `patrao_test.dart` (tipo de registro, placa) | PASS |
| APP-P-04 | Atualizações compartilhadas entre dispositivos com identidade real do ator | `PatraoAppIntegrationTest.mudancaNoSiteChegaAoAppEVersaoVelhaERecusada`, `.quemAlterouCadaCampoComCampoSensivelMascarado`, `.eventoNaoAutorizadoNaoChegaAOutroUsuario` | PASS |
| AI-01 | IA real só no servidor, chave protegida e orçamento configurado | `ai/AnthropicLlmProvider` (chave só por `ANTHROPIC_API_KEY`), `AiService` (orçamento mensal com reserva sob trava, cota diária); `AiIntegrationTest.orcamentoZeroOuEsgotado…`, `.perguntasSimultaneasRespeitamOrcamentoECotaDiaria`; `AnthropicLlmProviderTest`; ADR-029 | PASS (chave real e orçamento: responsável/F16) |
| AI-02 | Ferramentas somente leitura com dados calculados e autorização | `ai/AiTools` (7 ferramentas sobre os serviços do site, `Actor` do token); `AiIntegrationTest.ferramentaRespeitaPermissaoEFaltaViraLimitacao`, `.respostaComPeriodoFontesCustoENumeroIgualAoRelatorio` | PASS |
| AI-03 | Respostas com período, fontes e links rastreáveis | `ai_questions` (V14), `AiPage.tsx`, `owner/ai.dart`; `AiIntegrationTest.respostaCom…`; `AiPage.test.tsx`; `ai_test.dart` | PASS |
| AI-04 | Sem dados suficientes, resposta explicita a limitação | limitações calculadas pelo sistema (`AiTools.Outcome.limitations`); `AiIntegrationTest.ferramentaRespeitaPermissao…` (sem permissão, sem metas) | PASS |
| AI-05 | Sem chave ou provedor, estado não configurado visível | `AiService.status` (`NOT_CONFIGURED`, 503 sem envio); `AiIntegrationTest.semProvedorFicaNaoConfigurada…`; `App.test.tsx`; `ai_test.dart` | PASS |
| AI-06 | Prompt injection não altera acesso nem executa pagamentos | `AiIntegrationTest.promptInjectionNaoAlteraAcessoNemExecutaAcao` (ferramentas inexistentes, outra organização, contagens de grants/pagamentos inalteradas) | PASS |
| APP-E-03 | Entrega, combustível e ocorrência compartilhados com a central do patrão | `EntregasAppIntegrationTest`; trilha de auditoria com client/ator real; fluxos F05; `F12-validacao.txt` | PASS (serviço/auditoria compartilhados; evidência em F05/F12) |
| FIN-01 | Despesas por categoria/subcategoria com competência, vencimento, situação e comprovante (`ExpenseService`, Financeiro › Despesas) | `pagamentoDeObrigacao...`, `Finance.test.tsx`, `finance.spec.ts` | PASS |
| FIN-02 | Contas a pagar/receber e saldo/extrato por conta (`PayableService`, `ReceivableService`, `FinAccountService`) | `caixaDisponivelSeparadoDoResultado` (CAIXA-01), `devolucaoAoFornecedor...`, `finance.spec.ts` | PASS |
| FIN-03 | Cartão: bruto, taxa, líquido, parcelas e conciliação do repasse | `venda100ComTaxa3ReceitaDespesaERecebivel97...` (CARTAO-01), mutação M3 | PASS (provedor/adquirente real: D04) |
| FIN-04 | Resultado gerencial por competência separado do caixa disponível; drilldown de todo total | `caixaDisponivelSeparadoDoResultado`, `calculoDoResultadoBateComOFixtureDoPacote` (FECHAMENTO-01), app Patrão (`F06-mobile.txt`) | PASS |
| FIN-05 | Transferência interna não muda o consolidado | `transferenciaNaoMudaSaldoConsolidado`, mutação M2, `finance.spec.ts` | PASS |
| FIN-06 | Obrigação paga não gera despesa duplicada | `pagamentoDeObrigacaoReconhecidaNaoGeraSegundaDespesa` (OBRIGACAO-01), mutação M1 | PASS (guia/folha reais na F07) |
| FIN-07 | Fechamento e reabertura aprovada com histórico; livro travado no mês fechado | `periodoFechadoBloqueia...`, `fechamentoBloqueadoComConciliacaoDivergente`, mutação M4 | PASS |
| FIN-08 | Correção por estorno vinculado, sem exclusão | `pagamentoDeObrigacao...` (estorno de pagamento e de despesa), transferência estornada | PASS |
| RESP-01 (pedido, ADR-021) | Folgas e férias com calendário de disponibilidade por setor (Equipe › Funcionários) | `feriasEFolgasComCalendarioPorSetorESemSobreposicao`, `Employees.test.tsx`, `team-openfinance.spec.ts` | PASS (web; app na F14) |
| RESP-02 (pedido, ADR-021) | Open Finance pelo CNPJ: consentimento, extrato, conciliação, despesa sugerida | `extratoImportadoConciliaSugereDespesaENaoDuplica` (agregador de teste), `openFinanceSemAgregadorFicaNaoConfigurado`, E2E | PARTIAL — NÃO CONFIGURADO até contratar agregador |
| RESP-03 (pedido, ADR-027) | Cadastro de funcionários e cargos (Equipe › Funcionários/Cargos); dados pessoais e salário protegidos; conta de acesso pelo provedor de identidade; desligamento bloqueia | `EmployeesIntegrationTest` (4), mutação 3/3, `EmployeesRegistry.test.tsx`, `employees.spec.ts` (conta real no Keycloak local) | PASS (web; App Patrão na F14; produção: credencial de serviço na F16) |
| REP-01 | Gastos primeiro por categoria/subcategoria e depois fechamento (Relatórios › Gastos por categoria, W13) | `fixtureDoPacote...` (categorias do pacote), `Reports.test.tsx`, `reports.spec.ts` | PASS |
| REP-02 | Lucro/prejuízo, despesas, perdas, CMV e quantidade vendida (`ReportService`) | `fixtureDoPacoteResulta9500...` (FECHAMENTO-01: 9.500, margem 23,75, 2.000 dz), `mesFechadoVemDoRetrato...` | PASS |
| REP-03 | Colunas 3D proporcionais, rótulos, unidades, versão 2D e tabela acessível (site SVG, app CustomPainter) | `Reports.test.tsx` (alturas proporcionais, 2D), `reports_test.dart`, `reports.spec.ts`, emulador | PASS |
| REP-04 | Dia/semana/mês/período no fuso de São Paulo, início inclusivo/fim exclusivo, unidade por escopo | `filtroDeDiaRespeitaFronteiraDeSaoPaulo` (23:30 SP = 02:30 UTC), `escopoDeUnidadeNoRelatorio`, mutação M2 | PASS |
| REP-05 | Drilldown até o registro de origem e comprovante | `fixtureDoPacote...` (linhas → pedido), `reports.spec.ts` (link do pedido) | PASS |
| REP-06 | PDF/XLSX/CSV com os mesmos números e sem campo oculto; auditados | `fixtureDoPacote...` (CSV/XLSX/PDF lidos), `campoOcultoNaoVazaEmNenhumaSaida`, mutação M1, `reports.spec.ts` (download) | PASS (IA/busca: F15/F10) |
| REP-07 | Metas semanais/mensais com realizado, faltante e fórmula | `metasComRealizadoFaltanteEFormula`, mutação M3, `Reports.test.tsx`, emulador | PASS |
| REP-08 | Melhor cliente/funcionário só com medidas por função, sem nota única | `desempenhoPorFuncao...` (ADR-021), `fixtureDoPacote...`/`filtroDeDia...` (ranking por medida), `Reports.test.tsx` | PASS |
| CONT-01 | Contador prepara guia com competência, vencimento, valor e anexo (app Contábil) | `guiaEmEtapasComUploadReal...`, `accounting_test.dart`, emulador (`F07-mobile.txt`) | PASS |
| CONT-02 | Preparar, aprovar e pagar independentes; aprovada continua não paga | `guiaEmEtapas...`, mutação M1/M3, `accounting.spec.ts` | PASS |
| CONT-03 | Folha por rubricas/importação validada; individual só com employee_salary (JSON e export) | `folhaPorRubricasComDadoIndividualProtegido`, mutação M2, emulador | PASS |
| CONT-04 | Documentos privados reais, solicitações, versões, download autorizado | `centralDeDocumentosComVersoesESolicitacoes`, `accounting.spec.ts` | PASS (antivírus NÃO CONFIGURADO; S3 F16) |
| CONT-05 | Patrão acompanha cada movimento do contador (autor e app) | `guiaEmEtapas...` (audit-events por client_id), trilha no banco (`F07-mobile.txt`) | PASS |
| INT-01 | Tipo fiscal e provedor definidos com contador e dados da empresa | configuração fiscal + `semProvedorTudoBloqueado...` (sem tipo = BLOQUEADO) | BLOCKED — decisão D02/D03 pendente (implementação pronta) |
| INT-02 | Autorizada/rejeitada com XML, protocolo e chave | `autorizadaComXmlProtocoloEChave...` (provedor de teste), `Invoices.test.tsx` | PASS com provedor de teste; sandbox real BLOCKED (sem credencial) |
| INT-03 | Cancelamento separado com resultado confirmado | `autorizadaComXml...` (recusado mantém autorizada; pendente; cancelada com protocolo) | PASS com provedor de teste; sandbox real BLOCKED |
| INT-04 | Webhooks assinados e reenvios idempotentes | `webhookAssinadoIdempotenteEInvalidoNaoAlteraCaixa` (5 simultâneos = 1 efeito), mutação M2 | PASS (provedor de teste); real BLOCKED (D04) |
| INT-05 | Indisponibilidade/incerto não viram sucesso | `timeoutAposEnvioNaoViraEmissaoDuplicada`, `semProvedor...`, mutações M1/M3, `invoices.spec.ts`, emulador | PASS |

| APP-R-01 | `apps/mobile-counter` (br.com.engenheiro.retirada, client `engenheiro-retirada`, ícone próprio), APK release | `config_test.dart`, `funcoes_test.dart`, `F11-mobile.txt` (aapt2 + instalação release no emulador) | PASS (homologação HTTPS e chave de produção: F16) |
| APP-R-02 | Balconista e recebedor no mesmo app por capacidade; custo de fornecedor oculto | `RetiradaAppIntegrationTest.balconistaERecebedorNoMesmoAppComFuncoesSeparadas`, `funcoes_test.dart`, emulador (recebedor) | PASS |
| APP-R-03 | Tablet/celular, caixa, comprovante, desconexão sem confirmação local, meus pedidos | `novaTentativaAposPerderARespostaNaoDuplica`, `meusPedidosPorBuscaEPeriodo`, `pedidoDoBalcaoApareceNoSiteENoAppPatrao...`, `retirada_test.dart`, mutação 2/2, emulador | PASS |

| UX-01 | `apps/web-admin` abas horizontais + subabas por capacidade | `navigation.test.ts`, `site.spec.ts` (ordem das 7 abas) | PASS (web) |
| UX-02 | CSS responsivo; tabelas viram cartões ≤720 px | `layout.spec.ts` em 360/768/1440 (sem rolagem horizontal, ações visíveis) | PASS (web); apps em F11–F14 |
| UX-03 | Ícones/nomes/IDs dos 4 apps | realm + `F00_INVENTARIO.md`; ícones em `docs/especificacao/assets/apps` | PARTIAL — builds dos apps em F11–F14 |
| UX-04 | Estados carregando/vazio/erro/sem permissão/conflito/offline/pendente | `App.test.tsx` (403, 412, pendente), `States.tsx` | PASS (web) |
| UX-05 | Skip link, foco visível, labels, aria-live, alvo 44 px, movimento reduzido | `site.spec.ts` (teclado) | PARTIAL — falta auditoria com leitor de tela |
| WEB-01 | Todas as áreas do site ligadas à API real; totais levam aos registros; IA rotulada "não configurada" (F15), sem número de exemplo; setores e central de aprovações (`central/*`, ADR-025) | `CentralIntegrationTest` (4), `Central.test.tsx`, `central.spec.ts`, Playwright completo (`F10-web-playwright.txt`) | PASS (IA real: F15) |
| WEB-02 | Listas com busca, filtros na URL e paginação por cursor; edição/arquivamento de clientes e fornecedores com versão; busca geral sem campo sensível | `buscaGeralRespeitaEscopoECamposSensiveis`, `buscaDePedidosPorNumeroOuClienteNoEscopo`, `patraoVe...` (412, campo oculto 422), `Central.test.tsx` (Carregar mais com falha de rede), `layout.spec.ts` (novas rotas em 360/768/1440) | PASS |
| WEB-03 | Navegação profunda (filtros e registro na URL, volta ao endereço após entrar), sessão expirada sem perder o digitado, erro de rede com nova tentativa, 404 de página | `Central.test.tsx` (sessão, outra conta, volta segura), `central.spec.ts` (sessão real expirada no Chrome, rede abortada) | PASS |
| OPS-01 | Perfis `local`/`homologacao`/`prod`; `ProductionGuard` antes da migração; segredos só por ambiente/cofre (`infra/homologacao/preparar.sh` fora do repositório); borda TLS; imagens por digest; `infra/ci/verificar.sh` + `.github/workflows/ci.yml` | `ProductionHardeningTest` (5), fumaça HTTPS 17 itens, Trivy segredos/vulnerabilidades em repositório e imagens, `F16-validacao.txt` | PASS em homologação; produção BLOCKED (D09 cofre/infra) |
| OPS-02 | `infra/operacao/backup.sh`, `restaurar.sh`, `backup-agendado.sh` + `engenheiro-backup.timer` (banco do sistema, IdP e documentos, cifrado) | `F16-backup-restauracao.txt` (perda total simulada, RTO 40,9 s, cada documento conferido pelo hash, mesmo TOTP; adulterado/senha errada/destino com dados recusados) | PASS (cópia externa NÃO CONFIGURADA até D09) |
| OPS-03 | Logs JSON com `request_id` e ator pseudonimizado, `LogSanitizer`, borda sem query string; `vigia.sh` + `engenheiro-vigia.timer` e `engenheiro-alerta@` | `ProductionHardeningTest.sanitizadorRemoveTokenSegredoDocumentoEmailEDetalheDoBanco`, `FailSafeFilterTest` (3), `F16-ensaio-falhas.txt`, `F16-vigia-alerta.txt` (IdP parado → alerta pelo systemd) | PASS (envio externo do alerta NÃO CONFIGURADO: comando do responsável) |
| OPS-04 | Migrações só expansivas (V15 sessão no banco, V16 concessão de jobs); `construir.sh` (checkout limpo, manifesto sha256) e `implantar.sh` (backup pré-migração, prontidão + fumaça, rollback automático) | `JobLeaseIntegrationTest`, `F16-migracao-rollback.txt` (N-1 sobre schema novo, implantação com defeito revertida, volta à frente), `F16-carga.txt` (2 instâncias) | PASS em homologação |
| OPS-05 | Sem seletor de perfil nem fixture em produção: guarda recusa perfil local, seed fictício, HTTP e localhost; bootstrap do dono em banco vazio; release Android recusa build sem chave do ambiente e tráfego em texto puro | `ProductionHardeningTest.producaoComVestigioLocalOuSemParametroERecusadaSemExporSegredo`, `seedFicticioSoExisteForaDasMigrationsDeProducao`; release sem keystore = BUILD FAILED; `F16-apps-release.txt` | PASS (APK de produção BLOCKED: D07; iOS sem macOS) |

Testes adicionais de F01: imutabilidade de `audit_events` (trigger), paginação por cursor, 412 por versão,
concorrência entre administradores (`ConcurrencyIntegrationTest`, com teste de mutação).

Contrato dos endpoints implementados: `packages/contracts/openapi.json`, verificado por `OpenApiContractTest`.

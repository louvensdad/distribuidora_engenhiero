# Matriz de aceite — cenários de `especificacao/testes/CENARIOS_DE_ACEITE.md` × evidência

Estado em 19/11/2026 (F17). Cada linha aponta o teste automatizado ou a evidência gravada que prova o cenário **no
código atual** (suíte inteira roda em `infra/ci/verificar.sh`). Legenda:

- **PASS**: provado por teste ou ensaio reproduzível.
- **PASS (teste)**: provado com provedor de teste; a integração real depende de credencial externa e **não** está
  concluída (ver `PENDENCIAS.md`).
- **PARCIAL**: parte provada; o que falta está escrito na linha.
- **CLIENTE**: só o cliente pode executar (uso real no piloto, aparelhos da loja).

Abreviações de evidência: `T:` teste da API (`services/api/src/test`), `W:` teste do site (`apps/web-admin`),
`M:` teste dos apps (`packages/mobile-core/test`), `E:` evidência em `docs/evidence/`.

## Segurança

| # | Cenário | Evidência | Estado |
|---|---|---|---|
| S01 | Token ausente/expirado/assinatura/emissor/público/vínculo inativo negados, sem lista no erro | T: `AuthenticationIntegrationTest` (15: `semToken…`, `tokenExpirado…`, `assinaturaDeOutraChave…`, `emissorIncorreto…`, `audienciaIncorreta…`, `vinculoInativo…`, `subjectSemPessoaCadastradaRetorna403SemDados`) | PASS |
| S02 | Balconista tenta dashboard, lucro, folha, exportação, histórico por URL/API | T: `AuthorizationIntegrationTest.balcaoNaoAcessaHistoricoUsuariosNemPermissoes`, `balcaoRecebeSomenteCapacidadesOperacionais…`, `WebBffIntegrationTest.balconistaNaoEntraNoSiteDoPatrao`; E: `F17-ensaio-piloto.txt` (403 no relatório e na entrada com custo) | PASS |
| S03 | Motorista A tenta viagem/documento/entrega do B, inclusive por UUID | T: `EntregasAppIntegrationTest.motoristaNaoAbreViagemAlheiaPorIdNemPelosRecursosDela`, `AccessPolicyTest.motoristaSoVeViagemAtribuidaASi` | PASS |
| S04 | Contador sem dados individuais: só rubricas agregadas, nada pessoal em JSON/CSV/PDF/log | T: `AccountingIntegrationTest.folhaPorRubricasComDadoIndividualProtegido`, `ContabilAppIntegrationTest.contadorNaoRecebeValorGlobal`, `LogHygieneIntegrationTest`, `ReportsIntegrationTest.campoOcultoNaoVazaEmNenhumaSaida` | PASS (push não existe no sistema) |
| S05 | Revogar durante sessão ativa impede a próxima ação | T: `revogacaoImpedeAProximaChamada`, `bloqueioDeContaImpedeAProximaChamadaComOMesmoToken`, `WebBffIntegrationTest.bloqueioNoSistemaDerrubaSessaoWebNaProximaChamada`, `EntregasAppIntegrationTest.acessoRevogadoNoMeioDaViagemParaNaHora` | PASS |
| S06 | Alterar organização/unidade/ator/papel no corpo não amplia acesso nem muda o ator | T: `campoExtraNoCorpoNaoMudaAtorNemOrganizacao`, `CustomersIntegrationTest.organizacaoUnidadeEAutorDoCorpoSaoIgnorados`, `naoPromoveAPropriaContaNemComCapacidadeDeGestao` | PASS |
| S07 | Exportação revalidada após revogação; arquivo privado nunca público | T: `AcceptanceGapsIntegrationTest.exportacaoRevalidaPermissaoNaHoraEArquivoNaoEPublico` (F17), `F03LimitsIntegrationTest.anexoDoRecebimentoPrivadoValidadoEAuditado` | PASS |

## Pedido, estoque e dinheiro

| # | Cenário | Evidência | Estado |
|---|---|---|---|
| B01 | Físico 100: reservar 60 e 50 ao mesmo tempo, só uma passa, nunca negativo | T: `AcceptanceGapsIntegrationTest.reservasConcorrentesDe60E50SobreEstoque100SoUmaPassa` (5 repetições, F17); E: `F16-carga.txt` (4.599 pedidos concorrentes, reservado = pedidos) | PASS |
| B02 | Pedido 75 pago com 100: troco 25, caixa +75, sem despesa de 25 | T: `OrdersIntegrationTest.pedidoDe75PagoCom100DaTroco25ECaixaSobe75`; E: ensaio (R$ 2.050 → troco 50, caixa fecha em 2.100) | PASS |
| B03 | Pedido cadastrado não é venda; estoque fica reservado até cumprir | T: `PilotReconciliationIntegrationTest.periodoPilotoConcilia…` (pedido pago e não entregue fora da venda), `OrdersIntegrationTest.entregaParcialReconheceSoOEntregue…` | PASS |
| B04 | Antecipado 75: adiantamento, não lucro; retirada reclassifica sem 2ª entrada de caixa | T: `FinanceIntegrationTest.caixaDisponivelSeparadoDoResultado`, `PilotReconciliationIntegrationTest` (adiantamento conta no caixa, não na venda) | PASS |
| B05 | Cumprir entrega duas vezes (inclusive após timeout): 1 saída, 1 CMV, 1 venda | T: `EntregasAppIntegrationTest.reenvioDaEntregaNaoBaixaEstoqueDuasVezes` (4 reenvios simultâneos), `RetiradaAppIntegrationTest.novaTentativaAposPerderARespostaNaoDuplica` | PASS |
| B06 | 10 dz, entrega parcial 6: venda/CMV de 6, 4 pendentes | T: `OrdersIntegrationTest.entregaParcialReconheceSoOEntregueERepeticaoNaoDuplica`, `LogisticsIntegrationTest.fluxoCompletoComEntregaParcial…` | PASS |
| B07 | Comprovante Pix não confirma; webhook repetido não duplica | T: `OrdersIntegrationTest.pixSoComprovanteNaoPagaEDivididoPrecisaFecharOValor`, `IntegrationsIntegrationTest.webhookAssinadoIdempotenteEInvalidoNaoAlteraCaixa` | PASS (teste) — PSP real: D04 |
| B08 | Cartão 100, taxa 3: receita 100, despesa 3, recebível 97 | T: `OrdersIntegrationTest.cartaoReceitaBrutaTaxaUmaVezRecebivelLiquido`, `FinanceIntegrationTest.venda100ComTaxa3…` | PASS |
| B09 | 40 dinheiro + 60 Pix = 100; 99 ou 101 recusados | T: `OrdersIntegrationTest.pixSoComprovanteNaoPagaEDivididoPrecisaFecharOValor` | PASS |
| B10 | Receber 100 dz por 1000, vender 20: CMV 200, estoque 800 | T: `InventoryIntegrationTest.recebimentoDe100EVendaDe20NaoLancaAs100ComoCmv` | PASS |
| B11 | Perda 5 dz a 10: estoque −5, perda 50, fora do CMV | T: `InventoryIntegrationTest.perdaBaixaFisicoUmaVezPeloCustoMedioEForaDoCmv`, `PilotReconciliationIntegrationTest` (perda × CMV) | PASS |
| B12 | Cancelar libera reserva; venda cumprida só com devolução formal | T: `OrdersIntegrationTest.cancelarLiberaReservaEVendaCumpridaSoComDevolucao` | PASS |
| B13 | Viagem 100 rateada 1,1,1: 33,33/33,33/33,34; total 100 | T: `LogisticsIntegrationTest.gastoRateadoSomaAoOriginalSemDuplicarDespesa` | PASS |
| B14 | Transferir 500 caixa→banco: consolidado não muda | T: `FinanceIntegrationTest.transferenciaNaoMudaSaldoConsolidado` | PASS |
| B15 | Imposto 850 reconhecido e pago: despesa uma vez, caixa −850 | T: `FinanceIntegrationTest.pagamentoDeObrigacaoReconhecidaNaoGeraSegundaDespesa`; E: ensaio (DAS 850 enviada, aprovada e paga) | PASS |
| B16 | Período fechado nega edição; reabertura autorizada | T: `FinanceIntegrationTest.periodoFechadoBloqueiaLancamentoEReaberturaExigeAprovacao`, `fechamentoBloqueadoComConciliacaoDivergente` | PASS |

## Fluxos integrados

| # | Cenário | Evidência | Estado |
|---|---|---|---|
| I01 | Retirada: pedido → dono vê ator e reserva → pagamento/retirada → dono acompanha no site e no app | T: `RetiradaAppIntegrationTest.pedidoDoBalcaoApareceNoSiteENoAppPatraoComAutorEApp`; E: ensaio (central COUNTER com a balconista pelo app Retirada) | PASS |
| I02 | Recebedor confere sem custo; dono vê quantidade, documento, custo e autor | T: `InventoryIntegrationTest.recebimentoQuantitativoSemCustoVisivelEConferenciaComCustoEFrete`; E: ensaio | PASS |
| I03 | Coordenador atribui; motorista só a sua viagem; confirmação em todas as superfícies | T: `LogisticsIntegrationTest.motoristaConsultaSomenteViagensAtribuidas`, `PatraoAppIntegrationTest.mudancaNoSiteChegaAoApp…`; E: ensaio | PASS |
| I04 | Abastecimento na placa/viagem, financeiro e categoria, sem duplicar no rateio | T: `PatraoAppIntegrationTest.trilhaEncadeadaDoPedidoAteADespesaDaViagem`, `gastoRateadoSomaAoOriginal…`, `consumoKmPorLitroSoEntreTanquesCheios` | PASS |
| I05 | Contador envia guia → dono aprova → contador vê aprovada sem pagamento → dono paga | T: `ContabilAppIntegrationTest.guiaEnviadaApareceNoDonoEDecisaoVoltaAoContadorComHistorico`; E: ensaio (os 4 passos, com contas reais do IdP) | PASS |
| I06 | Folha enviada, aprovada e paga com ator e documento, sem campo não autorizado | T: `AccountingIntegrationTest.folhaPorRubricasComDadoIndividualProtegido`, `ContabilAppIntegrationTest.folhaDevolvidaPodeSerCanceladaEReimportadaSemDuplicar` | PASS |
| I07 | Rejeição fiscal: motivo, histórico e correção; sem protocolo inventado | T: `IntegrationsIntegrationTest.autorizadaComXmlProtocoloEChaveECancelamentoSeparado`, `semProvedorTudoBloqueado…` | PASS (teste) — fiscal real: D02/D03 |
| I08 | Timeout após transmissão: consultar antes de reenviar | T: `IntegrationsIntegrationTest.timeoutAposEnvioNaoViraEmissaoDuplicada` | PASS (teste) — fiscal real: D02/D03 |
| I09 | Dia/semana/mês corretos na fronteira UTC × São Paulo | T: `ReportsIntegrationTest.filtroDeDiaRespeitaFronteiraDeSaoPaulo` | PASS (horário de verão: não se aplica — extinto em 2019, dados a partir de 2026) |
| I10 | Todo módulo da web no App Patrão, com autorização equivalente | `docs/PARIDADE_WEB_APP.csv` (inclui a conferência do mês, F17); E: `F14-validacao.txt`, `F16-release-gestao-*.png` | PASS (Android) |

## IA e UX

| # | Cenário | Evidência | Estado |
|---|---|---|---|
| A01 | Lucro perguntado = relatório, mesmo após nova despesa e filtro | T: `AiIntegrationTest.respostaComPeriodoFontesCustoENumeroIgualAoRelatorio` | PASS (teste) — chamada real: chave/orçamento |
| A02 | Sem km/litros suficientes: declara a falta | T: `AiIntegrationTest.ferramentaRespeitaPermissaoEFaltaViraLimitacao`, `LogisticsIntegrationTest.consumoKmPorLitroSoEntreTanquesCheios` | PASS (teste) |
| A03 | Comprovante com "ignore as regras…": nenhuma mutação nem segredo | T: `AiIntegrationTest.promptInjectionNaoAlteraAcessoNemExecutaAcao` | PASS |
| A04 | Sem permissão de IA, o endpoint nega mesmo conhecendo a URL | T: `AiIntegrationTest.ferramentaRespeitaPermissao…`; W: `AiPage.test.tsx` | PASS |
| A05 | Sem chave: NÃO CONFIGURADA, nada simulado | T: `AiIntegrationTest.semProvedorFicaNaoConfiguradaENadaESimulado`; E: `F16-release-gestao-ia.png` (APK de release) | PASS |
| U01 | Teclado, leitor de tela e toque; foco após modal; erro sem depender de cor | W: `site.spec.ts` (Tab → "Pular para o conteúdo"); E: `F16-navegador-a11y.txt` (axe WCAG 2.1 AA, 0 violação em 6 telas); M: widgets com `Semantics`/`liveRegion` | PARCIAL — falta sessão com leitor de tela real (NVDA/TalkBack) |
| U02 | 360, 390, 768, 1024, 1440 px, conteúdo longo e moeda grande, sem corte | W: `layout.spec.ts` — 38 telas × 5 larguras (F17 acrescentou 390 e 1024: 81/81) | PASS (dados do seed; moeda acima de R$ 1 milhão não exercitada) |
| U03 | Falha do 3D mantém tabela e alternativa 2D; redução de movimento | W: `Reports.test.tsx` (mesmos números no 3D e na tabela; botão "Versão 2D"); `Columns3D.tsx` é SVG sem WebGL e sem animação | PASS |
| U04 | Offline: rascunho/pendente, nunca confirmação local | M: `retirada_test.dart` (queda de conexão sem confirmar; nova tentativa com a mesma chave); T: `novaTentativaAposPerderARespostaNaoDuplica` | PASS |

## Operação

| # | Cenário | Evidência | Estado |
|---|---|---|---|
| O01 | Restaurar banco e documentos em base vazia, conferindo vínculos, saldos e checksum | E: `F16-backup-restauracao.txt` (RTO 40,9 s, cada documento pelo hash, mesmo TOTP) | PASS |
| O02 | Instalar os 4 artefatos: nome, ícone, client OIDC e redirecionamento | E: `F16-apps-release.txt` (4 APKs assinados, login real do Gestão; os outros 3 abrem o IdP), `F17-ensaio-piloto.txt` (os 4 clients OIDC com PKCE) | PASS (Android) — iOS sem macOS; produção: D07 |
| O03 | Atualizar app preservando sessão; revogado não volta pelo cache | E: `F16-apps-release.txt` (build 42 sobre o 39 manteve a sessão); T: `acessoRevogadoNoMeioDaViagemParaNaHora`, `revogacaoImpedeAProximaChamada` | PASS |
| O04 | Sem seletor de perfil, fixture pessoal ou sucesso fiscal simulado no release | T: `ProductionHardeningTest` (6); E: release recusa build sem chave; IA/fiscal NÃO CONFIGURADO | PASS |

## Resumo

46 cenários: 40 PASS · 5 PASS (teste) com integração real pendente (B07, I07, I08, A01, A02) · 1 PARCIAL (U01) ·
e o aceite final depende do **uso real** no piloto (UAT-01/02, abaixo), que só o cliente faz.

| Requisito | O que já está provado | O que é do cliente |
|---|---|---|
| UAT-01 | Cinco superfícies com contas distintas, criadas pelo próprio sistema, em clients OIDC distintos (`F17-ensaio-piloto.txt`); site em 5 larguras; APK de release real | Rodar o roteiro nos aparelhos da loja, com as pessoas reais |
| UAT-02 | Conciliação do período (API, site e app) fechando com o relatório no ensaio; adulteração detectada em teste | Conciliar o período piloto real e assinar |
| UAT-03 | Guias por perfil, suporte, runbooks, inventário e pendências em `docs/entrega/` e `docs/aceite/` | Treinamento presencial e recebimento da administração |

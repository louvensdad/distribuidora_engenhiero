# Requisitos rastreáveis

Estado inicial: nenhum requisito está certificado como implementado por este pacote.

## PROD

- **PROD-01**: Nome completo da distribuidora em todas as superfícies e documentos.
- **PROD-02**: Um site e quatro aplicativos móveis independentes, sem quinto app de motorista.
- **PROD-03**: App Patrão com paridade integral da web e central dos outros três setores.
- **PROD-04**: Fornecedor é módulo interno de recebimento junto ao balcão, sem portal externo implícito.
- **PROD-05**: Pelo menos oito funcionários suportados sem limite fixo de oito.
- **PROD-06**: Estado atual inventariado, sem confundir demonstração com sistema pronto.

## SEC

- **SEC-01**: Contas individuais e autenticação OIDC/PKCE apropriada a web/mobile.
- **SEC-02**: Permissões por recurso, ação, campo, registro, unidade e condição no servidor.
- **SEC-03**: Dashboard, totais, lucro, dados pessoais e exportação global bloqueados por padrão.
- **SEC-04**: Modelos editáveis e mapa completo de acessos por funcionário.
- **SEC-05**: Revogação de acesso e bloqueio de conta impedem novas operações.
- **SEC-06**: Nenhum token web persistente em localStorage nem segredo em aplicativo.
- **SEC-07**: Escopo atribuído limita o motorista às suas viagens.
- **SEC-08**: Downloads, relatórios, eventos, busca e IA respeitam autorização.
- **SEC-09**: Dono administra setores com identidade própria, sem impersonação.

## UX

- **UX-01**: Web com abas futuristas horizontais, ícones e subabas contextuais.
- **UX-02**: Layouts próprios para computador, tablet e celular.
- **UX-03**: Quatro ícones, nomes e identificadores móveis distintos.
- **UX-04**: Telas com loading, vazio, erro, sem permissão, conflito e offline.
- **UX-05**: Teclado, leitor de tela, foco, contraste e alvos de toque acessíveis.

## INV

- **INV-01**: Produtos, medidas e conversões por produto com unidade comercial confirmada.
- **INV-02**: Fornecedor, lote, quantidade recebida, conferência, documento, custo e frete.
- **INV-03**: Estoque físico, reservado e disponível com transações concorrentes.
- **INV-04**: Custo médio móvel e CMV histórico sem lançar toda a compra como despesa.
- **INV-05**: Perdas por motivo com baixa, custo e autor.
- **INV-06**: Devoluções e ajustes vinculados ao movimento original.

## ORD

- **ORD-01**: Pedido de retirada e entrega com itens, total de servidor e responsável.
- **ORD-02**: Reserva distinta de cumprimento e de reconhecimento da venda.
- **ORD-03**: Dinheiro no balcão com valor apresentado e troco correto.
- **ORD-04**: Pix, débito, crédito, dividido e a prazo autorizado.
- **ORD-05**: Situação de pagamento separada da situação do pedido.
- **ORD-06**: Desconto, cancelamento e estorno sujeitos à alçada.
- **ORD-07**: Entrega/retirada parcial e devolução sem duplicar CMV.
- **ORD-08**: Recebimento antecipado tratado como adiantamento.
- **ORD-09**: Caixa próprio com esperado, contado, diferença e conferente.
- **ORD-10**: Idempotência em confirmações e pagamentos.

## LOG

- **LOG-01**: Pedidos de entrega com endereço, janela, contato e pagamento combinado.
- **LOG-02**: Várias entregas por viagem com placa, veículo, motorista e capacidade.
- **LOG-03**: Saída/retorno, odômetro, destinatário e evidência da entrega.
- **LOG-04**: Ocorrência, cliente ausente, recusa, reagendamento e devolução.
- **LOG-05**: Combustível, litros, quilômetros, posto, comprovante e responsável.
- **LOG-06**: Manutenção, pneus, seguro, documentos e avisos por veículo.
- **LOG-07**: Prestação de contas do motorista e dinheiro recebido.
- **LOG-08**: Rateio analítico de viagem sem repetir despesa financeira.

## FIN

- **FIN-01**: Despesas e subcategorias com competência, situação e comprovante.
- **FIN-02**: Contas a pagar/receber e saldos por conta.
- **FIN-03**: Taxas/parcelas de cartão com bruto, líquido e reconciliação.
- **FIN-04**: Resultado gerencial separado de caixa disponível.
- **FIN-05**: Transferências internas não alteram saldo consolidado.
- **FIN-06**: Imposto/folha pagos não geram despesa duplicada.
- **FIN-07**: Fechamento e reabertura por aprovação com histórico.
- **FIN-08**: Correções por estorno/reversão, não exclusão destrutiva.

## CONT

- **CONT-01**: Contador prepara guia com período, vencimento, valor e anexo.
- **CONT-02**: Preparar, aprovar e pagar são etapas independentes.
- **CONT-03**: Folha por rubricas e dados individuais liberados separadamente.
- **CONT-04**: Documentos reais privados, solicitações, versões e download autorizado.
- **CONT-05**: Patrão acompanha cada movimento feito pelo contador.

## INT

- **INT-01**: Tipo fiscal e provedor definidos com contador e dados da empresa.
- **INT-02**: Emissão autorizada/rejeitada com XML, protocolo e chave quando aplicável.
- **INT-03**: Cancelamento fiscal tem fluxo separado e resultado confirmado.
- **INT-04**: Webhooks assinados e reenvios idempotentes.
- **INT-05**: Indisponibilidade e resultado incerto não viram sucesso fictício.

## REP

- **REP-01**: Gastos primeiro por categoria/subcategoria e depois fechamento total.
- **REP-02**: Lucro/prejuízo, despesas totais, perdas totais, CMV e quantidade vendida.
- **REP-03**: Gráficos3D proporcionais com rótulos, unidades e tabela acessível.
- **REP-04**: Filtros por dia/semana/mês/período e fuso comercial.
- **REP-05**: Drilldown de indicador para os registros e comprovantes de origem.
- **REP-06**: PDF, XLSX e CSV respeitam dados/permissões e reconciliam com a tela.
- **REP-07**: Metas semanais/mensais com realizado, faltante e fórmula clara.
- **REP-08**: Melhor cliente/funcionário somente com critérios mensuráveis por função.

## WEB

- **WEB-01**: Todos os módulos web integrados a serviços reais, sem botões falsos.
- **WEB-02**: Listas, detalhes, formulários, pesquisa, filtros e paginação completos.
- **WEB-03**: Navegação profunda e recuperação de sessão/erro.

## APP-R

- **APP-R-01**: Aplicativo Retirada com build/ícone/client próprios.
- **APP-R-02**: Balcão e recebimento de fornecedores no mesmo app com capacidades distintas.
- **APP-R-03**: Operação em tablet e celular, caixa e comprovantes autorizados.

## APP-E

- **APP-E-01**: Aplicativo Entregas reúne pedidos, despacho, motoristas e veículos.
- **APP-E-02**: Motorista só recebe dados necessários às entregas atribuídas.
- **APP-E-03**: Entrega, combustível e ocorrência chegam à central do patrão.

## APP-C

- **APP-C-01**: Aplicativo Contábil separado com agenda, guias, folha, notas e documentos.
- **APP-C-02**: Contador acompanha aprovação e rejeição sem saldo/dashboard geral.

## APP-P

- **APP-P-01**: Aplicativo Patrão contém todas as funções do site.
- **APP-P-02**: Central reúne Retirada, Entregas e Contabilidade com cada movimento.
- **APP-P-03**: Filtros por pessoa, cliente, pedido, fornecedor, placa e período.
- **APP-P-04**: Atualizações compartilhadas entre dispositivos com identidade real do ator.

## AI

- **AI-01**: IA real apenas no backend, chave protegida e orçamento configurado.
- **AI-02**: Ferramentas somente leitura com dados calculados e autorização.
- **AI-03**: Respostas com período, fontes e links rastreáveis.
- **AI-04**: Sem dados suficientes, resposta explicita a limitação.
- **AI-05**: Sem chave ou provedor, estado não configurado visível.
- **AI-06**: Prompt injection não altera acesso nem executa pagamentos.

## OPS

- **OPS-01**: Builds e ambientes separados com segredos gerenciados.
- **OPS-02**: Backup e restauração exercitados para banco e documentos.
- **OPS-03**: Logs sanitizados e alertas de falha de serviço/integração.
- **OPS-04**: Migrations e rollback testados antes da produção.
- **OPS-05**: Sem seletor de perfil ou fixture no caminho de produção.

## UAT

- **UAT-01**: Aceite com cinco superfícies e usuários/dispositivos distintos.
- **UAT-02**: Relatório do piloto reconciliado com estoque, vendas e caixa.
- **UAT-03**: Treinamento, runbooks, suporte e pendências entregues ao titular.

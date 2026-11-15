# Visão canônica do produto

## Identidade e objetivo

Empresa: Distribuidora de Cana Engenheiro Ltda. Atividade: distribuição e venda de cana. Pelo menos oito funcionários; não codificar limite máximo de oito. Operação inicial em uma empresa e uma unidade, com `organization_id` e `branch_id` nos dados para permitir expansão. Novas organizações não ficam acessíveis automaticamente.

O dono precisa saber o que entrou, foi reservado, vendido, entregue, recebido, gasto ou perdido, quem participou e quais documentos justificam cada valor. Precisa fazer isso no computador e no próprio celular.

## Cinco superfícies, uma operação

| Superfície | Uso e autoridade |
|---|---|
| Web do patrão | Gestão integral, abas futuristas horizontais, relatórios e administração |
| App Patrão | Paridade funcional com a web; central Retirada, Entregas e Contabilidade; histórico completo |
| App Retirada | Balcão, pedidos de retirada, recebimentos autorizados, caixa próprio, entrada/conferência de fornecedores |
| App Entregas | Cadastro de entrega, despacho, viagens, motoristas, placas, abastecimento, manutenção e ocorrências |
| App Contador | Guias, impostos, folha, notas, documentos, solicitações e acompanhamento de aprovações |

O setor de fornecedores é interno ao aplicativo de retirada. Não foi solicitado um portal externo para fornecedores. O motorista pertence ao aplicativo de entregas, não a um quinto aplicativo. Dentro de Retirada e Entregas, pessoas diferentes veem capacidades diferentes.

O patrão não precisa usar contas de funcionários para supervisionar. No aplicativo dele, cada setor é uma área nativa de consulta, administração e aprovação. Toda ação executada pelo patrão registra a identidade dele, mesmo quando o histórico é filtrado por um funcionário.

## O que fica só com o administrador por padrão

Dashboard, total vendido, consolidados de quantidade e dinheiro, custos, margens, lucros, perdas financeiras, despesas gerais, saldos, folha individual, dados pessoais, comparações de desempenho, histórico completo, exportação global e IA de gestão. O contador não recebe acesso geral por ser contador.

O administrador escolhe permissões por pessoa, recurso, ação, campo, escopo e condição. Permissões operacionais predefinidas são apenas modelos editáveis. O servidor é a autoridade. Menus escondidos não protegem uma API.

## Informações essenciais

- Cliente, contato e endereço necessários ao pedido.
- Produto, medida, quantidade, preço, desconto e custo rastreável.
- Pedido, separação, retirada, entrega e cancelamento.
- Pagamento: dinheiro, Pix, débito, crédito, dividido ou a prazo autorizado.
- Fornecedor, quantidade recebida, custo, frete, conferência, documento e perdas.
- Veículo, placa, motorista, viagem, quilometragem, combustível, manutenção e documentos.
- Despesas por categorias e subcategorias, contas a receber/pagar e caixa.
- Folha, encargos, guias, tributos, notas e comprovantes.
- Metas, gráficos 3D, relatórios detalhados e fechamento consolidado.
- Evidências e histórico encadeado de todos os movimentos relevantes.

## Direção de experiência

Web com abas principais Visão geral, Vendas, Operação, Financeiro, Relatórios, Inteligência e Equipe. Subabas mostram as seções do contexto. Aparência futurista sóbria, fundo escuro, acentos verde e ciano, ícones consistentes e espaço para leitura. Não transformar o site em landing page comercial nem empilhar cartões genéricos sem operação real.

Aplicativos com entradas próprias, ícones e identificadores distintos. Navegação de no máximo cinco destinos principais; detalhes se abrem progressivamente. Tablet usa colunas e painéis auxiliares quando houver espaço. O site não deve parecer uma tela de celular esticada.

## Situação de partida

Existe uma demonstração visual privada, com dados fictícios locais, perfis simulados, gráficos com profundidade, respostas de IA simuladas, impressão pelo navegador e documentos representados pelo nome. Isso não comprova backend, isolamento, aplicativo nativo, sincronização, emissão fiscal ou pagamentos reais.

Não migrar fixtures como clientes reais. Não copiar identidade, tokens ou metadados de hospedagem da demonstração para o projeto novo. O pacote não contém código de produção do sistema.

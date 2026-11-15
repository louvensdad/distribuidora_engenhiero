# Contas, aplicativos e autorização

## Quatro distribuições independentes

| App | Identificador proposto | Entrada e usuários |
|---|---|---|
| Engenheiro Patrão | `br.com.engenheiro.gestao` | Proprietário e administradores autorizados |
| Engenheiro Retirada | `br.com.engenheiro.retirada` | Balcão e recebimento interno de fornecedores |
| Engenheiro Entregas | `br.com.engenheiro.entregas` | Coordenador, atendente de entrega e motorista |
| Engenheiro Contábil | `br.com.engenheiro.contabil` | Contador e auxiliares expressamente autorizados |

Identificadores são propostas técnicas. Confirmar domínio/assinatura da empresa antes de publicação. Código e componentes podem ser compartilhados; binários, ícones, configuração e sessões são próprios. Android e iOS são alvos, mas builds iOS exigem ambiente Apple e credenciais do titular. Não declarar IPA/AAB prontos sem produzir e verificar os arquivos.

## Paridade do patrão

Web e App Patrão compartilham capacidades de negócio, filtros, relatórios, aprovações, cadastro de acessos, IA e histórico. No celular pode haver outra disposição e seleção de arquivo; a função não pode desaparecer por falta de espaço.

Central Retirada / Entregas / Contabilidade no app do dono mostra listas, detalhes e atividades de cada setor. O dono consegue filtrar por usuário, comparar período e abrir o registro original. Deve haver marcação clara do ator real e das ações feitas por integrações. A visualização não usa token nem senha do funcionário.

## Regra de autorização

Permitir somente se: conta ativa + organização/unidade corretas + client/app permitido + recurso liberado + ação liberada + escopo do registro atendido + campos permitidos + condições/alçada satisfeitas. Caso contrário, negar. Interface recebe `/me/capabilities`, mas o servidor recalcula em todas as requisições.

Recursos: dashboard, orders.pickup, orders.delivery, customers, suppliers, inventory.receipts, inventory.stock, inventory.losses, trips, vehicles, fuel, maintenance, payments, own_cash, company_cash, expenses, receivables, payables, payroll, employee_personal, taxes, invoices, documents, reports, goals, audit, ai, users, permissions.

Ações: view, create, edit_draft, request, approve, confirm, cancel, reverse, export, print, attach, close_period, reopen_period. Não mostrar 'excluir' genérico para registros financeiros confirmados. Cada recurso só expõe ações aplicáveis.

Escopos: próprio criador, atribuído, setor, unidade, organização; múltiplas unidades somente por concessão. Escopo atribuído não é equivalente a 'todos os pedidos da empresa'. Negação explícita vence permissão de modelo. Ausência de permissão significa negar.

Campos independentes: CPF/documentos pessoais; contato; endereço; preço de venda; custo/margem; salário individual; contas bancárias; dados de fornecedor; comprovantes. O motorista pode receber endereço e contato da entrega atribuída, sem receber cadastro completo do cliente.

## Modelos iniciais

- Balcão: criar/consultar próprios pedidos de retirada; ver preço de venda; confirmar recebimentos e caixa próprio apenas se o dono liberar. Não vê dashboard, total vendido, custo, dados pessoais, notas da equipe ou lucro.
- Recebimento: ver fornecedor operacional, quantidade e documento necessário; cadastrar entrada em rascunho e confirmar quando autorizado. Custo/preço de compra podem ficar mascarados.
- Entregas: criar/consultar pedidos do setor, registrar endereço e solicitar despacho. Atribuir veículo/motorista só com capacidade específica.
- Motorista: viagens atribuídas, ocorrências, confirmação e recebimento permitido. Abastecimento próprio autorizado. Sem despesas globais ou outras viagens.
- Contador: guias, documentos fiscais e folha autorizada. Por padrão não vê dashboard, vendas totais, lucro, saldo geral ou dados individuais de funcionários. Dados necessários para a tarefa são liberados separadamente pelo dono.
- Administrador: acesso integral, exceto segredo bruto de integração, senha de usuário e material secreto de chaves. Acesso integral não inclui ler senhas.

## Tela de mapa de acessos

Buscar pessoa; aplicar modelo; expandir setor; marcar ações individualmente; escolher escopo; selecionar campos; definir limite monetário e validade temporária; visualizar resumo; salvar; registrar histórico. Oferecer 'visualizar experiência autorizada' sem impersonar. Mudança de acesso invalida cache e sessão quando necessário; testes devem provar revogação no próximo pedido protegido.

Usuário comum não pode conceder direitos, alterar próprio papel nem enviar outro `organization_id` para ampliar acesso. API de permissões exige `permissions.manage`. Exportação, websocket, busca, anexos e IA obedecem à mesma política. A marca da empresa no app não é prova de autorização.

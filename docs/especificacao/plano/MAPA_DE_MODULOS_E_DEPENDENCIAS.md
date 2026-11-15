# Mapa de dependências por domínio

| Domínio | Depende de | Publica/compartilha | Superfícies principais |
|---|---|---|---|
| Identidade e acesso | IdP e vínculos locais | Capabilities e revogação | Todas |
| Catálogo e pessoas | Identidade | Produtos, medidas, clientes e fornecedores | Web, Patrão, Retirada, Entregas |
| Estoque | Catálogo e transações | Saldo, reservas, custo e perdas | Web, Patrão, Retirada |
| Pedidos | Estoque e catálogo | Reserva, cumprimento e recebíveis | Web, Patrão, Retirada, Entregas |
| Caixa e pagamentos | Pedidos e contas | Recebimentos e conciliação | Web, Patrão, Retirada, Entregas autorizadas |
| Viagens e frota | Pedidos, pessoas e veículos | Entregas, ocorrências, despesas | Web, Patrão, Entregas |
| Financeiro | Eventos de venda, caixa e despesas | Razão gerencial e fechamento | Web, Patrão e Contador autorizado |
| Contabilidade | Financeiro e documentos | Guias, folha e aprovações | Web, Patrão, Contador |
| Fiscal | Pedidos, documentos e provedor | Protocolo, XML, rejeição | Web, Patrão, Contador |
| Relatórios | Projeções reconciliadas | Gráficos, arquivos e snapshots | Web, Patrão |
| IA | Relatórios e ferramentas autorizadas | Explicações com fontes | Web, Patrão |
| Auditoria e eventos | Contexto autenticado e outbox | Histórico e invalidação de consultas | Todas dentro do escopo |

O frontend não replica política financeira nem calcula um lucro alternativo ao servidor. Quatro aplicativos não significam quatro bancos ou quatro cópias isoladas do estoque.

# F04: Pedidos, retirada, recebimentos e caixa

Implemente esta fase no sistema da Distribuidora de Cana Engenheiro Ltda. Faça o trabalho no repositório real, preservando o que existe. Este arquivo é um prompt executável por agente, não um relatório de fase concluída.

## Contexto obrigatório

Leia `COMECE_AQUI.md`, `cerebro/01_VISAO_CANONICA.md`, `cerebro/04_ACESSOS_E_APLICATIVOS.md`, `plano/GATES.md` e o checkpoint. Consulte ainda:
- `cerebro/02_FLUXOS_OPERACIONAIS.md`
- `cerebro/03_REGRAS_FINANCEIRAS_ESTOQUE.md`
- `testes/CENARIOS_DE_ACEITE.md`

Dependências: F03.
Antes de iniciar, confira evidências dessas dependências e o estado atual do código. Se alguma estiver parcial, delimite a lacuna. Não marque PASS por herdar uma alegação antiga.

## Execute

1. Criar pedidos de retirada/entrega, preço do servidor, descontos por alçada e reserva atômica.
2. Separar estados operacionais e financeiros, recebimentos divididos, antecipados e a prazo autorizado.
3. Implementar dinheiro/troco, fechamento próprio, pagamento manual autorizado e adaptador de pagamentos sem sucesso fictício.
4. Implementar cumprimento, entrega parcial, cancelamento, devolução e reversões com idempotência.

## Entregáveis concretos

- Pedido até retirada com histórico e persistência.
- Recebíveis e pagamentos alocados sem duplicação.
- Caixa esperado/contado/diferença/conferente.

## Critérios de aceite específicos

- R$75 pagos com R$100 geram R$25 de troco e R$75 no caixa.
- Comprovante Pix sozinho não marca pago.
- Repetir confirmação não baixa estoque nem recebe dinheiro novamente.

## Invariantes desta implementação

Um site e quatro apps independentes. Patrão com paridade web/app e central dos três setores. Conta individual e autorização real no servidor. Dados pessoais e consolidados bloqueados por padrão para a operação. A identidade do ator não muda ao supervisionar uma pessoa.

Dinheiro decimal exato. Reserva, cumprimento, pagamento e resultado são estados distintos. Sem duplicar despesa, taxa, perda ou CMV. Integração indisponível gera estado bloqueado/pendente, não sucesso simulado. Nunca inclua chaves, senhas ou documentos reais em fixtures ou evidências.

## Validação e fechamento

Associe a implementação aos requisitos ORD-01, ORD-02, ORD-03, ORD-04, ORD-05, ORD-06, ORD-07, ORD-08, ORD-09, ORD-10. Inclua testes negativos, concorrência ou transação quando afetar dados/dinheiro. Teste a interface pertinente e atualize contratos se necessário. Não se limite a mudar labels ou criar serviços sem conexão com as telas.

Execute os comandos adequados ao projeto, grave saídas reais sanitizadas e caminhos das evidências. Atualize cobertura, ADRs afetados e checkpoint com arquivos/commit. Relate o que funciona, como foi testado, limites e próxima fase. Só avance após gate comprovado. Trabalho independente deve continuar mesmo que uma credencial externa esteja pendente.

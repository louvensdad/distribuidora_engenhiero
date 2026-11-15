# F06: Financeiro, competência e reconciliação

Implemente esta fase no sistema da Distribuidora de Cana Engenheiro Ltda. Faça o trabalho no repositório real, preservando o que existe. Este arquivo é um prompt executável por agente, não um relatório de fase concluída.

## Contexto obrigatório

Leia `COMECE_AQUI.md`, `cerebro/01_VISAO_CANONICA.md`, `cerebro/04_ACESSOS_E_APLICATIVOS.md`, `plano/GATES.md` e o checkpoint. Consulte ainda:
- `cerebro/03_REGRAS_FINANCEIRAS_ESTOQUE.md`
- `testes/dados-financeiros.json`

Dependências: F04, F05.
Antes de iniciar, confira evidências dessas dependências e o estado atual do código. Se alguma estiver parcial, delimite a lacuna. Não marque PASS por herdar uma alegação antiga.

## Execute

1. Implementar despesas por categoria, contas, passivos, recebíveis e pagamentos.
2. Separar competência, vencimento e caixa; implementar lançamento/reversão e períodos fechados.
3. Implementar cartões/parcelas/taxas e transferências internas.
4. Criar drilldown de todo total e mecanismo de reconciliação financeira e de estoque.

## Entregáveis concretos

- Subledger gerencial com invariantes e fontes únicas.
- Extrato por conta e resumo por categoria.
- Fechamento e reabertura aprovados com auditoria.

## Critérios de aceite específicos

- Venda de100 com taxa3 produz receita100, despesa3 e recebível97.
- Pagamento de obrigação reconhecida não gera segunda despesa.
- Transferência entre contas não muda saldo consolidado.

## Invariantes desta implementação

Um site e quatro apps independentes. Patrão com paridade web/app e central dos três setores. Conta individual e autorização real no servidor. Dados pessoais e consolidados bloqueados por padrão para a operação. A identidade do ator não muda ao supervisionar uma pessoa.

Dinheiro decimal exato. Reserva, cumprimento, pagamento e resultado são estados distintos. Sem duplicar despesa, taxa, perda ou CMV. Integração indisponível gera estado bloqueado/pendente, não sucesso simulado. Nunca inclua chaves, senhas ou documentos reais em fixtures ou evidências.

## Validação e fechamento

Associe a implementação aos requisitos FIN-01, FIN-02, FIN-03, FIN-04, FIN-05, FIN-06, FIN-07, FIN-08. Inclua testes negativos, concorrência ou transação quando afetar dados/dinheiro. Teste a interface pertinente e atualize contratos se necessário. Não se limite a mudar labels ou criar serviços sem conexão com as telas.

Execute os comandos adequados ao projeto, grave saídas reais sanitizadas e caminhos das evidências. Atualize cobertura, ADRs afetados e checkpoint com arquivos/commit. Relate o que funciona, como foi testado, limites e próxima fase. Só avance após gate comprovado. Trabalho independente deve continuar mesmo que uma credencial externa esteja pendente.

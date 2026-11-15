# F05: Despacho, entregas, frota e viagens

Implemente esta fase no sistema da Distribuidora de Cana Engenheiro Ltda. Faça o trabalho no repositório real, preservando o que existe. Este arquivo é um prompt executável por agente, não um relatório de fase concluída.

## Contexto obrigatório

Leia `COMECE_AQUI.md`, `cerebro/01_VISAO_CANONICA.md`, `cerebro/04_ACESSOS_E_APLICATIVOS.md`, `plano/GATES.md` e o checkpoint. Consulte ainda:
- `cerebro/02_FLUXOS_OPERACIONAIS.md`
- `cerebro/03_REGRAS_FINANCEIRAS_ESTOQUE.md`

Dependências: F04.
Antes de iniciar, confira evidências dessas dependências e o estado atual do código. Se alguma estiver parcial, delimite a lacuna. Não marque PASS por herdar uma alegação antiga.

## Execute

1. Implementar atribuição de vários pedidos à viagem, veículo/placa, motorista e capacidade.
2. Implementar saída/retorno, odômetro, ocorrências, confirmação parcial e prestação de contas.
3. Implementar combustível, manutenção, seguro e despesas vinculadas.
4. Implementar rateio analítico por entrega com regra versionada sem duplicar a despesa real.

## Entregáveis concretos

- Fluxo cadastro -> despacho -> motorista -> entrega -> conferência.
- Histórico por veículo, pedido e funcionário.
- Cálculo de custo por viagem e limites de consumo por disponibilidade de dados.

## Critérios de aceite específicos

- Motorista consulta somente viagens atribuídas.
- Veículo incapaz ou indisponível não recebe atribuição inválida.
- Odômetro regressivo rejeitado e gasto rateado soma ao original.

## Invariantes desta implementação

Um site e quatro apps independentes. Patrão com paridade web/app e central dos três setores. Conta individual e autorização real no servidor. Dados pessoais e consolidados bloqueados por padrão para a operação. A identidade do ator não muda ao supervisionar uma pessoa.

Dinheiro decimal exato. Reserva, cumprimento, pagamento e resultado são estados distintos. Sem duplicar despesa, taxa, perda ou CMV. Integração indisponível gera estado bloqueado/pendente, não sucesso simulado. Nunca inclua chaves, senhas ou documentos reais em fixtures ou evidências.

## Validação e fechamento

Associe a implementação aos requisitos LOG-01, LOG-02, LOG-03, LOG-04, LOG-05, LOG-06, LOG-07, LOG-08. Inclua testes negativos, concorrência ou transação quando afetar dados/dinheiro. Teste a interface pertinente e atualize contratos se necessário. Não se limite a mudar labels ou criar serviços sem conexão com as telas.

Execute os comandos adequados ao projeto, grave saídas reais sanitizadas e caminhos das evidências. Atualize cobertura, ADRs afetados e checkpoint com arquivos/commit. Relate o que funciona, como foi testado, limites e próxima fase. Só avance após gate comprovado. Trabalho independente deve continuar mesmo que uma credencial externa esteja pendente.

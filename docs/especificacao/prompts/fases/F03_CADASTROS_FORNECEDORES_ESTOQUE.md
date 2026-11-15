# F03: Cadastros, fornecedores e estoque

Implemente esta fase no sistema da Distribuidora de Cana Engenheiro Ltda. Faça o trabalho no repositório real, preservando o que existe. Este arquivo é um prompt executável por agente, não um relatório de fase concluída.

## Contexto obrigatório

Leia `COMECE_AQUI.md`, `cerebro/01_VISAO_CANONICA.md`, `cerebro/04_ACESSOS_E_APLICATIVOS.md`, `plano/GATES.md` e o checkpoint. Consulte ainda:
- `cerebro/03_REGRAS_FINANCEIRAS_ESTOQUE.md`
- `arquitetura/02_DADOS_E_AUDITORIA.md`

Dependências: F01, F02.
Antes de iniciar, confira evidências dessas dependências e o estado atual do código. Se alguma estiver parcial, delimite a lacuna. Não marque PASS por herdar uma alegação antiga.

## Execute

1. Implementar produtos, medidas/conversões, clientes, fornecedores e unidades.
2. Implementar entrada/conferência de fornecedor, frete de compra, custo médio, saldo físico e reservas.
3. Implementar perdas, ajustes aprovados e devoluções com custo histórico.
4. Entregar telas correspondentes web e componentes operacionais móveis; anexos autorizados desde o início.

## Entregáveis concretos

- Cadastros e estoque persistidos com histórico.
- Transações concorrentes e invariantes de saldo.
- Recebimento quantitativo sem custo visível para usuário sem acesso.

## Critérios de aceite específicos

- Duas reservas concorrentes não produzem estoque negativo.
- Recebimento de 100 e venda posterior de 20 não lança as 100 como CMV.
- Perda baixa físico e não é registrada duas vezes no resultado.

## Invariantes desta implementação

Um site e quatro apps independentes. Patrão com paridade web/app e central dos três setores. Conta individual e autorização real no servidor. Dados pessoais e consolidados bloqueados por padrão para a operação. A identidade do ator não muda ao supervisionar uma pessoa.

Dinheiro decimal exato. Reserva, cumprimento, pagamento e resultado são estados distintos. Sem duplicar despesa, taxa, perda ou CMV. Integração indisponível gera estado bloqueado/pendente, não sucesso simulado. Nunca inclua chaves, senhas ou documentos reais em fixtures ou evidências.

## Validação e fechamento

Associe a implementação aos requisitos INV-01, INV-02, INV-03, INV-04, INV-05, INV-06. Inclua testes negativos, concorrência ou transação quando afetar dados/dinheiro. Teste a interface pertinente e atualize contratos se necessário. Não se limite a mudar labels ou criar serviços sem conexão com as telas.

Execute os comandos adequados ao projeto, grave saídas reais sanitizadas e caminhos das evidências. Atualize cobertura, ADRs afetados e checkpoint com arquivos/commit. Relate o que funciona, como foi testado, limites e próxima fase. Só avance após gate comprovado. Trabalho independente deve continuar mesmo que uma credencial externa esteja pendente.

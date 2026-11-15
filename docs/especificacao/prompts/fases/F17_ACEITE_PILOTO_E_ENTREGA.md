# F17: Aceite, piloto e operação assistida

Implemente esta fase no sistema da Distribuidora de Cana Engenheiro Ltda. Faça o trabalho no repositório real, preservando o que existe. Este arquivo é um prompt executável por agente, não um relatório de fase concluída.

## Contexto obrigatório

Leia `COMECE_AQUI.md`, `cerebro/01_VISAO_CANONICA.md`, `cerebro/04_ACESSOS_E_APLICATIVOS.md`, `plano/GATES.md` e o checkpoint. Consulte ainda:
- `testes/CENARIOS_DE_ACEITE.md`
- `design/PARIDADE_WEB_APP.csv`
- `operacao/PRODUCAO_E_ENTREGA.md`

Dependências: F16.
Antes de iniciar, confira evidências dessas dependências e o estado atual do código. Se alguma estiver parcial, delimite a lacuna. Não marque PASS por herdar uma alegação antiga.

## Execute

1. Executar roteiro do cliente com web e quatro aplicativos em dispositivos diferentes.
2. Conciliar pedidos, estoque, caixas, despesas, impostos e relatórios do período piloto.
3. Treinar perfis, documentar suporte e entregar administração ao dono.
4. Registrar limitações remanescentes e encerrar somente critérios comprovados.

## Entregáveis concretos

- Termo de aceite funcional e lista objetiva de pendências.
- Inventário de artefatos, versões, suporte e acesso do titular.
- Checkpoint final e operação assistida definida.

## Critérios de aceite específicos

- Patrão acompanha todos os movimentos dos três apps em sua central.
- Funcionários e contador operam sem acesso indevido.
- Integração bloqueada não aparece como concluída no aceite.

## Invariantes desta implementação

Um site e quatro apps independentes. Patrão com paridade web/app e central dos três setores. Conta individual e autorização real no servidor. Dados pessoais e consolidados bloqueados por padrão para a operação. A identidade do ator não muda ao supervisionar uma pessoa.

Dinheiro decimal exato. Reserva, cumprimento, pagamento e resultado são estados distintos. Sem duplicar despesa, taxa, perda ou CMV. Integração indisponível gera estado bloqueado/pendente, não sucesso simulado. Nunca inclua chaves, senhas ou documentos reais em fixtures ou evidências.

## Validação e fechamento

Associe a implementação aos requisitos UAT-01, UAT-02, UAT-03. Inclua testes negativos, concorrência ou transação quando afetar dados/dinheiro. Teste a interface pertinente e atualize contratos se necessário. Não se limite a mudar labels ou criar serviços sem conexão com as telas.

Execute os comandos adequados ao projeto, grave saídas reais sanitizadas e caminhos das evidências. Atualize cobertura, ADRs afetados e checkpoint com arquivos/commit. Relate o que funciona, como foi testado, limites e próxima fase. Só avance após gate comprovado. Trabalho independente deve continuar mesmo que uma credencial externa esteja pendente.

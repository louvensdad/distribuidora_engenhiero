# F07: Contabilidade, folha e documentos privados

Implemente esta fase no sistema da Distribuidora de Cana Engenheiro Ltda. Faça o trabalho no repositório real, preservando o que existe. Este arquivo é um prompt executável por agente, não um relatório de fase concluída.

## Contexto obrigatório

Leia `COMECE_AQUI.md`, `cerebro/01_VISAO_CANONICA.md`, `cerebro/04_ACESSOS_E_APLICATIVOS.md`, `plano/GATES.md` e o checkpoint. Consulte ainda:
- `cerebro/04_ACESSOS_E_APLICATIVOS.md`
- `arquitetura/02_DADOS_E_AUDITORIA.md`

Dependências: F06.
Antes de iniciar, confira evidências dessas dependências e o estado atual do código. Se alguma estiver parcial, delimite a lacuna. Não marque PASS por herdar uma alegação antiga.

## Execute

1. Criar obrigações/guia com competência, vencimento, documento e valores.
2. Implementar preparação, devolução, aprovação, pagamento e comprovação separados.
3. Implementar folha por rubricas/importação validada e acesso individual específico.
4. Implementar central de documentos, solicitações, versões, download autorizado e validação de arquivo.

## Entregáveis concretos

- Contador executa tarefas sem dashboard geral.
- Patrão recebe aprovação, consulta documento e vê cada alteração.
- Bytes reais de anexos guardados de forma privada em homologação.

## Critérios de aceite específicos

- Upload real difere de mero nome de arquivo.
- Guia aprovada continua não paga até confirmação.
- Contador sem permissão individual não recebe salários pessoais em JSON ou export.

## Invariantes desta implementação

Um site e quatro apps independentes. Patrão com paridade web/app e central dos três setores. Conta individual e autorização real no servidor. Dados pessoais e consolidados bloqueados por padrão para a operação. A identidade do ator não muda ao supervisionar uma pessoa.

Dinheiro decimal exato. Reserva, cumprimento, pagamento e resultado são estados distintos. Sem duplicar despesa, taxa, perda ou CMV. Integração indisponível gera estado bloqueado/pendente, não sucesso simulado. Nunca inclua chaves, senhas ou documentos reais em fixtures ou evidências.

## Validação e fechamento

Associe a implementação aos requisitos CONT-01, CONT-02, CONT-03, CONT-04, CONT-05. Inclua testes negativos, concorrência ou transação quando afetar dados/dinheiro. Teste a interface pertinente e atualize contratos se necessário. Não se limite a mudar labels ou criar serviços sem conexão com as telas.

Execute os comandos adequados ao projeto, grave saídas reais sanitizadas e caminhos das evidências. Atualize cobertura, ADRs afetados e checkpoint com arquivos/commit. Relate o que funciona, como foi testado, limites e próxima fase. Só avance após gate comprovado. Trabalho independente deve continuar mesmo que uma credencial externa esteja pendente.

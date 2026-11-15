# F16: Segurança, desempenho e implantação

Implemente esta fase no sistema da Distribuidora de Cana Engenheiro Ltda. Faça o trabalho no repositório real, preservando o que existe. Este arquivo é um prompt executável por agente, não um relatório de fase concluída.

## Contexto obrigatório

Leia `COMECE_AQUI.md`, `cerebro/01_VISAO_CANONICA.md`, `cerebro/04_ACESSOS_E_APLICATIVOS.md`, `plano/GATES.md` e o checkpoint. Consulte ainda:
- `operacao/PRODUCAO_E_ENTREGA.md`
- `plano/GATES.md`

Dependências: F08, F14, F15.
Antes de iniciar, confira evidências dessas dependências e o estado atual do código. Se alguma estiver parcial, delimite a lacuna. Não marque PASS por herdar uma alegação antiga.

## Execute

1. Executar regressão, segurança, carga proporcional, dependências e acessibilidade.
2. Configurar pipeline, segredos, monitoramento, logs sanitizados e ambientes.
3. Produzir builds assináveis dos quatro apps, implantação web/API e migrations controladas.
4. Testar backup/restauração e falha de IdP, fiscal, banco, storage e LLM.

## Entregáveis concretos

- Release candidate com evidências e runbooks.
- Plano de rollback e restauração exercitado.
- Artefatos identificados por commit e checksum.

## Critérios de aceite específicos

- Nenhum segredo ou dado pessoal em logs/artefatos.
- Restauração inclui banco e documentos.
- Build Android/iOS só marcado entregue se foi produzido e verificado no ambiente necessário.

## Invariantes desta implementação

Um site e quatro apps independentes. Patrão com paridade web/app e central dos três setores. Conta individual e autorização real no servidor. Dados pessoais e consolidados bloqueados por padrão para a operação. A identidade do ator não muda ao supervisionar uma pessoa.

Dinheiro decimal exato. Reserva, cumprimento, pagamento e resultado são estados distintos. Sem duplicar despesa, taxa, perda ou CMV. Integração indisponível gera estado bloqueado/pendente, não sucesso simulado. Nunca inclua chaves, senhas ou documentos reais em fixtures ou evidências.

## Validação e fechamento

Associe a implementação aos requisitos OPS-01, OPS-02, OPS-03, OPS-04, OPS-05. Inclua testes negativos, concorrência ou transação quando afetar dados/dinheiro. Teste a interface pertinente e atualize contratos se necessário. Não se limite a mudar labels ou criar serviços sem conexão com as telas.

Execute os comandos adequados ao projeto, grave saídas reais sanitizadas e caminhos das evidências. Atualize cobertura, ADRs afetados e checkpoint com arquivos/commit. Relate o que funciona, como foi testado, limites e próxima fase. Só avance após gate comprovado. Trabalho independente deve continuar mesmo que uma credencial externa esteja pendente.

# F00: Inventário, escopo e decisões

Implemente esta fase no sistema da Distribuidora de Cana Engenheiro Ltda. Faça o trabalho no repositório real, preservando o que existe. Este arquivo é um prompt executável por agente, não um relatório de fase concluída.

## Contexto obrigatório

Leia `COMECE_AQUI.md`, `cerebro/01_VISAO_CANONICA.md`, `cerebro/04_ACESSOS_E_APLICATIVOS.md`, `plano/GATES.md` e o checkpoint. Consulte ainda:
- `cerebro/01_VISAO_CANONICA.md`
- `arquitetura/01_ARQUITETURA.md`
- `plano/DECISOES_ABERTAS.md`

Dependências: nenhuma fase anterior; inventário obrigatório.
Antes de iniciar, confira evidências dessas dependências e o estado atual do código. Se alguma estiver parcial, delimite a lacuna. Não marque PASS por herdar uma alegação antiga.

## Execute

1. Inspecionar repositório existente, git, mudanças locais, instruções e dependências; mapear implementado/parcial/faltante sem apagar código.
2. Confirmar unidade de venda, política de crédito/desconto, unidades da empresa e enquadramento/obrigações com o contador.
3. Registrar stack e patches verificáveis, domínios, IDs dos quatro apps, ambiente Android/iOS e infraestrutura escolhida.
4. Criar mapa requisito -> módulo -> teste e ADR das decisões. Não assumir integrações contratadas.

## Entregáveis concretos

- Inventário com caminhos e evidência do ambiente.
- Matriz de decisões com bloqueios externos isolados.
- Toolchain lock proposto e dependências compatíveis.

## Critérios de aceite específicos

- Quatro apps e web aparecem separados no mapa.
- Nenhum segredo ou dado real copiado para fixtures.
- Itens desconhecidos têm estado explícito.

## Invariantes desta implementação

Um site e quatro apps independentes. Patrão com paridade web/app e central dos três setores. Conta individual e autorização real no servidor. Dados pessoais e consolidados bloqueados por padrão para a operação. A identidade do ator não muda ao supervisionar uma pessoa.

Dinheiro decimal exato. Reserva, cumprimento, pagamento e resultado são estados distintos. Sem duplicar despesa, taxa, perda ou CMV. Integração indisponível gera estado bloqueado/pendente, não sucesso simulado. Nunca inclua chaves, senhas ou documentos reais em fixtures ou evidências.

## Validação e fechamento

Associe a implementação aos requisitos PROD-01, PROD-02, PROD-03, PROD-04, PROD-05, PROD-06. Inclua testes negativos, concorrência ou transação quando afetar dados/dinheiro. Teste a interface pertinente e atualize contratos se necessário. Não se limite a mudar labels ou criar serviços sem conexão com as telas.

Execute os comandos adequados ao projeto, grave saídas reais sanitizadas e caminhos das evidências. Atualize cobertura, ADRs afetados e checkpoint com arquivos/commit. Relate o que funciona, como foi testado, limites e próxima fase. Só avance após gate comprovado. Trabalho independente deve continuar mesmo que uma credencial externa esteja pendente.

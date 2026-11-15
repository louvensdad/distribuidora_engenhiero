# F11: Aplicativo de retirada, balcão e fornecedores

Implemente esta fase no sistema da Distribuidora de Cana Engenheiro Ltda. Faça o trabalho no repositório real, preservando o que existe. Este arquivo é um prompt executável por agente, não um relatório de fase concluída.

## Contexto obrigatório

Leia `COMECE_AQUI.md`, `cerebro/01_VISAO_CANONICA.md`, `cerebro/04_ACESSOS_E_APLICATIVOS.md`, `plano/GATES.md` e o checkpoint. Consulte ainda:
- `cerebro/04_ACESSOS_E_APLICATIVOS.md`
- `design/catalogo-telas.csv`

Dependências: F03, F04, F10.
Antes de iniciar, confira evidências dessas dependências e o estado atual do código. Se alguma estiver parcial, delimite a lacuna. Não marque PASS por herdar uma alegação antiga.

## Execute

1. Criar binário independente Retirada com login real, ícone e client próprios.
2. Implementar pedido rápido, dinheiro/troco, retirada, caixa próprio e recebimento de fornecedores por capacidade.
3. Adaptar tablet e celular, comprovante e anexos privados.
4. Tratar desconexão sem confirmar estoque/pagamento localmente e provar isolamento.

## Entregáveis concretos

- App Retirada instalável de homologação no alvo disponível.
- Funções separadas entre balconista e recebedor sem segundo aplicativo.
- Contrato e fluxo compartilhados com web.

## Critérios de aceite específicos

- Balcão não vê custo de fornecedor se esse campo estiver bloqueado.
- Sem seletor de patrão/contador no release.
- Pedido do balcão aparece no site e App Patrão com autor correto.

## Invariantes desta implementação

Um site e quatro apps independentes. Patrão com paridade web/app e central dos três setores. Conta individual e autorização real no servidor. Dados pessoais e consolidados bloqueados por padrão para a operação. A identidade do ator não muda ao supervisionar uma pessoa.

Dinheiro decimal exato. Reserva, cumprimento, pagamento e resultado são estados distintos. Sem duplicar despesa, taxa, perda ou CMV. Integração indisponível gera estado bloqueado/pendente, não sucesso simulado. Nunca inclua chaves, senhas ou documentos reais em fixtures ou evidências.

## Validação e fechamento

Associe a implementação aos requisitos APP-R-01, APP-R-02, APP-R-03. Inclua testes negativos, concorrência ou transação quando afetar dados/dinheiro. Teste a interface pertinente e atualize contratos se necessário. Não se limite a mudar labels ou criar serviços sem conexão com as telas.

Execute os comandos adequados ao projeto, grave saídas reais sanitizadas e caminhos das evidências. Atualize cobertura, ADRs afetados e checkpoint com arquivos/commit. Relate o que funciona, como foi testado, limites e próxima fase. Só avance após gate comprovado. Trabalho independente deve continuar mesmo que uma credencial externa esteja pendente.

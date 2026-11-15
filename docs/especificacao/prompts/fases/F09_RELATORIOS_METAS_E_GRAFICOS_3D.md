# F09: Relatórios completos, metas e gráficos 3D

Implemente esta fase no sistema da Distribuidora de Cana Engenheiro Ltda. Faça o trabalho no repositório real, preservando o que existe. Este arquivo é um prompt executável por agente, não um relatório de fase concluída.

## Contexto obrigatório

Leia `COMECE_AQUI.md`, `cerebro/01_VISAO_CANONICA.md`, `cerebro/04_ACESSOS_E_APLICATIVOS.md`, `plano/GATES.md` e o checkpoint. Consulte ainda:
- `cerebro/03_REGRAS_FINANCEIRAS_ESTOQUE.md`
- `design/01_DIRECAO_VISUAL.md`
- `testes/dados-financeiros.json`

Dependências: F06, F07.
Antes de iniciar, confira evidências dessas dependências e o estado atual do código. Se alguma estiver parcial, delimite a lacuna. Não marque PASS por herdar uma alegação antiga.

## Execute

1. Criar agregação canônica por período/competência/fuso e filtros autorizados.
2. Mostrar categorias/subcategorias antes do fechamento CMV+despesas+perdas e lucro.
3. Implementar colunas 3D proporcionais, tabela acessível, impressão/PDF e XLSX/CSV reais.
4. Criar metas semanais/mensais, comparações justificadas e rankings com critérios explícitos, sem dados inventados.

## Entregáveis concretos

- Relatório com drilldown, filtros e arquivos correspondentes.
- Projeções/snapshots fechados com política versionada.
- Gráficos web e móveis usando os mesmos números.

## Critérios de aceite específicos

- Fixture de40000-15000-15000-500 resulta9500 em tela, gráfico e exportação.
- Filtro de fronteira de dia respeita Sao_Paulo.
- Campo oculto não vaza em PDF, CSV, XLSX, busca nem IA.

## Invariantes desta implementação

Um site e quatro apps independentes. Patrão com paridade web/app e central dos três setores. Conta individual e autorização real no servidor. Dados pessoais e consolidados bloqueados por padrão para a operação. A identidade do ator não muda ao supervisionar uma pessoa.

Dinheiro decimal exato. Reserva, cumprimento, pagamento e resultado são estados distintos. Sem duplicar despesa, taxa, perda ou CMV. Integração indisponível gera estado bloqueado/pendente, não sucesso simulado. Nunca inclua chaves, senhas ou documentos reais em fixtures ou evidências.

## Validação e fechamento

Associe a implementação aos requisitos REP-01, REP-02, REP-03, REP-04, REP-05, REP-06, REP-07, REP-08. Inclua testes negativos, concorrência ou transação quando afetar dados/dinheiro. Teste a interface pertinente e atualize contratos se necessário. Não se limite a mudar labels ou criar serviços sem conexão com as telas.

Execute os comandos adequados ao projeto, grave saídas reais sanitizadas e caminhos das evidências. Atualize cobertura, ADRs afetados e checkpoint com arquivos/commit. Relate o que funciona, como foi testado, limites e próxima fase. Só avance após gate comprovado. Trabalho independente deve continuar mesmo que uma credencial externa esteja pendente.

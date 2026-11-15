# F02: Design system e navegação

Implemente esta fase no sistema da Distribuidora de Cana Engenheiro Ltda. Faça o trabalho no repositório real, preservando o que existe. Este arquivo é um prompt executável por agente, não um relatório de fase concluída.

## Contexto obrigatório

Leia `COMECE_AQUI.md`, `cerebro/01_VISAO_CANONICA.md`, `cerebro/04_ACESSOS_E_APLICATIVOS.md`, `plano/GATES.md` e o checkpoint. Consulte ainda:
- `design/01_DIRECAO_VISUAL.md`
- `design/catalogo-telas.csv`
- `design/tokens.json`

Dependências: F01.
Antes de iniciar, confira evidências dessas dependências e o estado atual do código. Se alguma estiver parcial, delimite a lacuna. Não marque PASS por herdar uma alegação antiga.

## Execute

1. Implementar tokens, ícones SVG, componentes e quatro entradas móveis independentes.
2. Criar cabeçalho web e abas futuristas com subabas contextuais e estados ativos.
3. Criar shells móveis e layouts tablet, mantendo capacidades e acessibilidade.
4. Gerar primeiras telas reais com loading, vazio, erro e sem permissão, usando contratos e dados claramente fictícios só no ambiente demo.

## Entregáveis concretos

- Componentes compartilhados web/Flutter segundo a plataforma.
- Web tabs e quatro shells navegáveis, sem destinos falsos.
- Capturas e inspeção em 360, 768 e 1440 px.

## Critérios de aceite específicos

- Toque mínimo e foco visível, texto legível e sem overflow.
- Nenhuma área financeira aparece ao usuário operacional por padrão.
- Ícone/nome/client distintos nas quatro aplicações.

## Invariantes desta implementação

Um site e quatro apps independentes. Patrão com paridade web/app e central dos três setores. Conta individual e autorização real no servidor. Dados pessoais e consolidados bloqueados por padrão para a operação. A identidade do ator não muda ao supervisionar uma pessoa.

Dinheiro decimal exato. Reserva, cumprimento, pagamento e resultado são estados distintos. Sem duplicar despesa, taxa, perda ou CMV. Integração indisponível gera estado bloqueado/pendente, não sucesso simulado. Nunca inclua chaves, senhas ou documentos reais em fixtures ou evidências.

## Validação e fechamento

Associe a implementação aos requisitos UX-01, UX-02, UX-03, UX-04, UX-05. Inclua testes negativos, concorrência ou transação quando afetar dados/dinheiro. Teste a interface pertinente e atualize contratos se necessário. Não se limite a mudar labels ou criar serviços sem conexão com as telas.

Execute os comandos adequados ao projeto, grave saídas reais sanitizadas e caminhos das evidências. Atualize cobertura, ADRs afetados e checkpoint com arquivos/commit. Relate o que funciona, como foi testado, limites e próxima fase. Só avance após gate comprovado. Trabalho independente deve continuar mesmo que uma credencial externa esteja pendente.

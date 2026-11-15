# F14: Aplicativo do patrão com paridade integral

Implemente esta fase no sistema da Distribuidora de Cana Engenheiro Ltda. Faça o trabalho no repositório real, preservando o que existe. Este arquivo é um prompt executável por agente, não um relatório de fase concluída.

## Contexto obrigatório

Leia `COMECE_AQUI.md`, `cerebro/01_VISAO_CANONICA.md`, `cerebro/04_ACESSOS_E_APLICATIVOS.md`, `plano/GATES.md` e o checkpoint. Consulte ainda:
- `cerebro/01_VISAO_CANONICA.md`
- `design/PARIDADE_WEB_APP.csv`

Dependências: F09, F10, F11, F12, F13.
Antes de iniciar, confira evidências dessas dependências e o estado atual do código. Se alguma estiver parcial, delimite a lacuna. Não marque PASS por herdar uma alegação antiga.

## Execute

1. Criar binário Patrão com todas as capacidades da web adaptadas ao celular/tablet.
2. Implementar Central Retirada/Entregas/Contabilidade com filtros por pessoa e detalhe de cada movimento.
3. Integrar relatórios, gráficos3D, metas, permissões, aprovações, anexos e auditoria.
4. Provar atualização entre dispositivos e manter a identidade real do dono nas ações.

## Entregáveis concretos

- Matriz de paridade web/app integral preenchida.
- Dono consulta e administra os três setores sem outra senha.
- Trilha encadeada de pedido, estoque, entrega, recebimento e despesa.

## Critérios de aceite específicos

- Nenhuma função essencial existe só na web.
- Dono identifica quem alterou cada campo e abre a origem do total.
- Evento não autorizado não é enviado ao app de outro usuário.

## Invariantes desta implementação

Um site e quatro apps independentes. Patrão com paridade web/app e central dos três setores. Conta individual e autorização real no servidor. Dados pessoais e consolidados bloqueados por padrão para a operação. A identidade do ator não muda ao supervisionar uma pessoa.

Dinheiro decimal exato. Reserva, cumprimento, pagamento e resultado são estados distintos. Sem duplicar despesa, taxa, perda ou CMV. Integração indisponível gera estado bloqueado/pendente, não sucesso simulado. Nunca inclua chaves, senhas ou documentos reais em fixtures ou evidências.

## Validação e fechamento

Associe a implementação aos requisitos APP-P-01, APP-P-02, APP-P-03, APP-P-04. Inclua testes negativos, concorrência ou transação quando afetar dados/dinheiro. Teste a interface pertinente e atualize contratos se necessário. Não se limite a mudar labels ou criar serviços sem conexão com as telas.

Execute os comandos adequados ao projeto, grave saídas reais sanitizadas e caminhos das evidências. Atualize cobertura, ADRs afetados e checkpoint com arquivos/commit. Relate o que funciona, como foi testado, limites e próxima fase. Só avance após gate comprovado. Trabalho independente deve continuar mesmo que uma credencial externa esteja pendente.

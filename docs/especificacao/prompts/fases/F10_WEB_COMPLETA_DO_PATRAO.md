# F10: Site integral do patrão

Implemente esta fase no sistema da Distribuidora de Cana Engenheiro Ltda. Faça o trabalho no repositório real, preservando o que existe. Este arquivo é um prompt executável por agente, não um relatório de fase concluída.

## Contexto obrigatório

Leia `COMECE_AQUI.md`, `cerebro/01_VISAO_CANONICA.md`, `cerebro/04_ACESSOS_E_APLICATIVOS.md`, `plano/GATES.md` e o checkpoint. Consulte ainda:
- `design/catalogo-telas.csv`
- `design/telas`
- `contratos/openapi.yaml`

Dependências: F02, F03, F04, F05, F07, F09.
Antes de iniciar, confira evidências dessas dependências e o estado atual do código. Se alguma estiver parcial, delimite a lacuna. Não marque PASS por herdar uma alegação antiga.

## Execute

1. Integrar todas as áreas web às APIs reais e remover placeholders do caminho autenticado.
2. Concluir abas/subabas, listas/detalhes, formulários, central de aprovações, acessos e histórico.
3. Implementar pesquisa, paginação, filtros, exportação e anexos com estados completos.
4. Validar funcionamento em desktop/tablet e navegação profunda por URL.

## Entregáveis concretos

- Web integral funcional de ponta a ponta.
- Links de totais para registros e histórico, sem botões falsos.
- Teste de regressão dos fluxos e acessibilidade básica.

## Critérios de aceite específicos

- Patrão navega pelos três setores sem trocar de identidade.
- Mudança feita por administrador é registrada como administrador.
- Sessão expirada e erro de rede têm recuperação segura.

## Invariantes desta implementação

Um site e quatro apps independentes. Patrão com paridade web/app e central dos três setores. Conta individual e autorização real no servidor. Dados pessoais e consolidados bloqueados por padrão para a operação. A identidade do ator não muda ao supervisionar uma pessoa.

Dinheiro decimal exato. Reserva, cumprimento, pagamento e resultado são estados distintos. Sem duplicar despesa, taxa, perda ou CMV. Integração indisponível gera estado bloqueado/pendente, não sucesso simulado. Nunca inclua chaves, senhas ou documentos reais em fixtures ou evidências.

## Validação e fechamento

Associe a implementação aos requisitos WEB-01, WEB-02, WEB-03. Inclua testes negativos, concorrência ou transação quando afetar dados/dinheiro. Teste a interface pertinente e atualize contratos se necessário. Não se limite a mudar labels ou criar serviços sem conexão com as telas.

Execute os comandos adequados ao projeto, grave saídas reais sanitizadas e caminhos das evidências. Atualize cobertura, ADRs afetados e checkpoint com arquivos/commit. Relate o que funciona, como foi testado, limites e próxima fase. Só avance após gate comprovado. Trabalho independente deve continuar mesmo que uma credencial externa esteja pendente.

# F01: Fundação, identidade e autorização

Implemente esta fase no sistema da Distribuidora de Cana Engenheiro Ltda. Faça o trabalho no repositório real, preservando o que existe. Este arquivo é um prompt executável por agente, não um relatório de fase concluída.

## Contexto obrigatório

Leia `COMECE_AQUI.md`, `cerebro/01_VISAO_CANONICA.md`, `cerebro/04_ACESSOS_E_APLICATIVOS.md`, `plano/GATES.md` e o checkpoint. Consulte ainda:
- `cerebro/04_ACESSOS_E_APLICATIVOS.md`
- `arquitetura/03_SEGURANCA_IA_E_SINCRONIZACAO.md`

Dependências: F00.
Antes de iniciar, confira evidências dessas dependências e o estado atual do código. Se alguma estiver parcial, delimite a lacuna. Não marque PASS por herdar uma alegação antiga.

## Execute

1. Criar estrutura modular, banco, migrations e ambiente local reprodutível.
2. Implementar OIDC/PKCE, BFF web, sessões seguras, quatro clients mobile e vínculo local ativo.
3. Implementar autorização por ação, recurso, escopo, campo, organização e unidade; negar por padrão.
4. Criar auditoria básica, correlação e testes de acesso negativo; sem seletor de papel em produção.

## Entregáveis concretos

- API autentica e nega operações fora de escopo.
- Migrations do zero, health/readiness e seeds fictícios.
- Capabilities consumíveis por web e apps, com revogação.

## Critérios de aceite específicos

- 401 sem credencial; 403 ou 404 conforme política para acesso indevido; nenhum payload sensível.
- Um usuário de outra unidade não consulta pedido/anexo nem por ID conhecido.
- Revogar acesso impede a próxima chamada protegida.

## Invariantes desta implementação

Um site e quatro apps independentes. Patrão com paridade web/app e central dos três setores. Conta individual e autorização real no servidor. Dados pessoais e consolidados bloqueados por padrão para a operação. A identidade do ator não muda ao supervisionar uma pessoa.

Dinheiro decimal exato. Reserva, cumprimento, pagamento e resultado são estados distintos. Sem duplicar despesa, taxa, perda ou CMV. Integração indisponível gera estado bloqueado/pendente, não sucesso simulado. Nunca inclua chaves, senhas ou documentos reais em fixtures ou evidências.

## Validação e fechamento

Associe a implementação aos requisitos SEC-01, SEC-02, SEC-03, SEC-04, SEC-05, SEC-06, SEC-07, SEC-08, SEC-09. Inclua testes negativos, concorrência ou transação quando afetar dados/dinheiro. Teste a interface pertinente e atualize contratos se necessário. Não se limite a mudar labels ou criar serviços sem conexão com as telas.

Execute os comandos adequados ao projeto, grave saídas reais sanitizadas e caminhos das evidências. Atualize cobertura, ADRs afetados e checkpoint com arquivos/commit. Relate o que funciona, como foi testado, limites e próxima fase. Só avance após gate comprovado. Trabalho independente deve continuar mesmo que uma credencial externa esteja pendente.

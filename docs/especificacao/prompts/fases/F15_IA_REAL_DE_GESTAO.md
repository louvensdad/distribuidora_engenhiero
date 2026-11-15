# F15: IA real integrada ao site e App Patrão

Implemente esta fase no sistema da Distribuidora de Cana Engenheiro Ltda. Faça o trabalho no repositório real, preservando o que existe. Este arquivo é um prompt executável por agente, não um relatório de fase concluída.

## Contexto obrigatório

Leia `COMECE_AQUI.md`, `cerebro/01_VISAO_CANONICA.md`, `cerebro/04_ACESSOS_E_APLICATIVOS.md`, `plano/GATES.md` e o checkpoint. Consulte ainda:
- `arquitetura/03_SEGURANCA_IA_E_SINCRONIZACAO.md`
- `testes/CENARIOS_DE_ACEITE.md`

Dependências: F09, F14.
Antes de iniciar, confira evidências dessas dependências e o estado atual do código. Se alguma estiver parcial, delimite a lacuna. Não marque PASS por herdar uma alegação antiga.

## Execute

1. Criar adaptador LLM de servidor com configuração, cotas, custo e falha segura.
2. Implementar ferramentas somente leitura com dados calculados, filtros e permissões do usuário.
3. Implementar respostas com período, fontes e links para registros.
4. Testar prompt injection, ausência de dados, indisponibilidade, limite de orçamento e vazamento entre organizações.

## Entregáveis concretos

- Chat real quando houver chave, separado do modo demo.
- Rastreabilidade de ferramentas, fontes, custo e versão do modelo.
- Mesma experiência funcional no site e app do dono.

## Critérios de aceite específicos

- Modelo não recebe chave do banco nem executa SQL arbitrário.
- Resposta numérica coincide com o relatório; dados faltantes são declarados.
- Sem chave, tela informa não configurado em vez de inventar integração.

## Invariantes desta implementação

Um site e quatro apps independentes. Patrão com paridade web/app e central dos três setores. Conta individual e autorização real no servidor. Dados pessoais e consolidados bloqueados por padrão para a operação. A identidade do ator não muda ao supervisionar uma pessoa.

Dinheiro decimal exato. Reserva, cumprimento, pagamento e resultado são estados distintos. Sem duplicar despesa, taxa, perda ou CMV. Integração indisponível gera estado bloqueado/pendente, não sucesso simulado. Nunca inclua chaves, senhas ou documentos reais em fixtures ou evidências.

## Validação e fechamento

Associe a implementação aos requisitos AI-01, AI-02, AI-03, AI-04, AI-05, AI-06. Inclua testes negativos, concorrência ou transação quando afetar dados/dinheiro. Teste a interface pertinente e atualize contratos se necessário. Não se limite a mudar labels ou criar serviços sem conexão com as telas.

Execute os comandos adequados ao projeto, grave saídas reais sanitizadas e caminhos das evidências. Atualize cobertura, ADRs afetados e checkpoint com arquivos/commit. Relate o que funciona, como foi testado, limites e próxima fase. Só avance após gate comprovado. Trabalho independente deve continuar mesmo que uma credencial externa esteja pendente.

# F13: Aplicativo independente do contador

Implemente esta fase no sistema da Distribuidora de Cana Engenheiro Ltda. Faça o trabalho no repositório real, preservando o que existe. Este arquivo é um prompt executável por agente, não um relatório de fase concluída.

## Contexto obrigatório

Leia `COMECE_AQUI.md`, `cerebro/01_VISAO_CANONICA.md`, `cerebro/04_ACESSOS_E_APLICATIVOS.md`, `plano/GATES.md` e o checkpoint. Consulte ainda:
- `cerebro/04_ACESSOS_E_APLICATIVOS.md`
- `design/catalogo-telas.csv`

Dependências: F07, F08, F10.
Antes de iniciar, confira evidências dessas dependências e o estado atual do código. Se alguma estiver parcial, delimite a lacuna. Não marque PASS por herdar uma alegação antiga.

## Execute

1. Criar binário Contábil com entrada e sessão próprias.
2. Integrar agenda de vencimentos, guias, folha, notas e documentos autorizados.
3. Implementar solicitações, anexos reais e acompanhamento das decisões do patrão.
4. Exercitar documentos privados, permissões de campo e fluxo de integração rejeitada.

## Entregáveis concretos

- Contador prepara guia/folha e acompanha aprovação sem acesso global.
- Patrão monitora cada mudança da contabilidade.
- Build e fluxo integrado verificável.

## Critérios de aceite específicos

- Guia enviada aparece no dono e aprovação retorna ao contador.
- Nenhum valor global chega por notificação, API ou telemetria sem permissão.
- Rejeição fiscal aparece como rejeição, não emissão.

## Invariantes desta implementação

Um site e quatro apps independentes. Patrão com paridade web/app e central dos três setores. Conta individual e autorização real no servidor. Dados pessoais e consolidados bloqueados por padrão para a operação. A identidade do ator não muda ao supervisionar uma pessoa.

Dinheiro decimal exato. Reserva, cumprimento, pagamento e resultado são estados distintos. Sem duplicar despesa, taxa, perda ou CMV. Integração indisponível gera estado bloqueado/pendente, não sucesso simulado. Nunca inclua chaves, senhas ou documentos reais em fixtures ou evidências.

## Validação e fechamento

Associe a implementação aos requisitos APP-C-01, APP-C-02. Inclua testes negativos, concorrência ou transação quando afetar dados/dinheiro. Teste a interface pertinente e atualize contratos se necessário. Não se limite a mudar labels ou criar serviços sem conexão com as telas.

Execute os comandos adequados ao projeto, grave saídas reais sanitizadas e caminhos das evidências. Atualize cobertura, ADRs afetados e checkpoint com arquivos/commit. Relate o que funciona, como foi testado, limites e próxima fase. Só avance após gate comprovado. Trabalho independente deve continuar mesmo que uma credencial externa esteja pendente.

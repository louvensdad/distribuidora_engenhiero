# F08: Integrações fiscais e de pagamentos

Implemente esta fase no sistema da Distribuidora de Cana Engenheiro Ltda. Faça o trabalho no repositório real, preservando o que existe. Este arquivo é um prompt executável por agente, não um relatório de fase concluída.

## Contexto obrigatório

Leia `COMECE_AQUI.md`, `cerebro/01_VISAO_CANONICA.md`, `cerebro/04_ACESSOS_E_APLICATIVOS.md`, `plano/GATES.md` e o checkpoint. Consulte ainda:
- `plano/DECISOES_ABERTAS.md`
- `arquitetura/03_SEGURANCA_IA_E_SINCRONIZACAO.md`

Dependências: F07.
Antes de iniciar, confira evidências dessas dependências e o estado atual do código. Se alguma estiver parcial, delimite a lacuna. Não marque PASS por herdar uma alegação antiga.

## Execute

1. Escolher com o contador o documento fiscal/provedor e modelar capacidades do adaptador.
2. Implementar sandbox, submissão, rejeição, autorização, XML/protocolo e cancelamento.
3. Implementar webhooks de pagamento assinados, reconciliação, idempotência e estornos.
4. Implementar retentativas seguras, indisponibilidade, timeout e triagem de resultado incerto.

## Entregáveis concretos

- Contratos de adaptação e testes de sandbox reais quando houver credenciais.
- Jobs/retries sem duplicação e histórico por integração.
- Segredos no servidor e estados honestos na interface.

## Critérios de aceite específicos

- Timeout após envio fiscal não vira nova emissão duplicada.
- Webhook repetido ou assinatura inválida não altera caixa.
- Credencial ausente mantém integração BLOCKED, nunca PASS simulado.

## Invariantes desta implementação

Um site e quatro apps independentes. Patrão com paridade web/app e central dos três setores. Conta individual e autorização real no servidor. Dados pessoais e consolidados bloqueados por padrão para a operação. A identidade do ator não muda ao supervisionar uma pessoa.

Dinheiro decimal exato. Reserva, cumprimento, pagamento e resultado são estados distintos. Sem duplicar despesa, taxa, perda ou CMV. Integração indisponível gera estado bloqueado/pendente, não sucesso simulado. Nunca inclua chaves, senhas ou documentos reais em fixtures ou evidências.

## Validação e fechamento

Associe a implementação aos requisitos INT-01, INT-02, INT-03, INT-04, INT-05. Inclua testes negativos, concorrência ou transação quando afetar dados/dinheiro. Teste a interface pertinente e atualize contratos se necessário. Não se limite a mudar labels ou criar serviços sem conexão com as telas.

Execute os comandos adequados ao projeto, grave saídas reais sanitizadas e caminhos das evidências. Atualize cobertura, ADRs afetados e checkpoint com arquivos/commit. Relate o que funciona, como foi testado, limites e próxima fase. Só avance após gate comprovado. Trabalho independente deve continuar mesmo que uma credencial externa esteja pendente.

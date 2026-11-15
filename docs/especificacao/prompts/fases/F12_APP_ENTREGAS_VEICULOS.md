# F12: Aplicativo de entregas, motoristas e carros

Implemente esta fase no sistema da Distribuidora de Cana Engenheiro Ltda. Faça o trabalho no repositório real, preservando o que existe. Este arquivo é um prompt executável por agente, não um relatório de fase concluída.

## Contexto obrigatório

Leia `COMECE_AQUI.md`, `cerebro/01_VISAO_CANONICA.md`, `cerebro/04_ACESSOS_E_APLICATIVOS.md`, `plano/GATES.md` e o checkpoint. Consulte ainda:
- `cerebro/02_FLUXOS_OPERACIONAIS.md`
- `arquitetura/03_SEGURANCA_IA_E_SINCRONIZACAO.md`

Dependências: F05, F10.
Antes de iniciar, confira evidências dessas dependências e o estado atual do código. Se alguma estiver parcial, delimite a lacuna. Não marque PASS por herdar uma alegação antiga.

## Execute

1. Criar binário independente Entregas com áreas de atendente, despacho e motorista por capacidade.
2. Implementar pedidos, viagens, paradas, recebimentos, provas de entrega e ocorrências.
3. Implementar abastecimento, manutenção autorizada e prestação de contas.
4. Tratar sincronização, repetição, acesso revogado e dados mínimos por viagem.

## Entregáveis concretos

- Mesmo aplicativo serve coordenador e motorista sem acesso indevido.
- Entrega/gasto compartilhados com o patrão.
- Build de homologação e evidência em dispositivo/simulador.

## Critérios de aceite específicos

- Motorista não abre outra viagem alterando UUID ou deep link.
- Pix pago não é cobrado novamente.
- Reenvio de entrega não baixa estoque duas vezes.

## Invariantes desta implementação

Um site e quatro apps independentes. Patrão com paridade web/app e central dos três setores. Conta individual e autorização real no servidor. Dados pessoais e consolidados bloqueados por padrão para a operação. A identidade do ator não muda ao supervisionar uma pessoa.

Dinheiro decimal exato. Reserva, cumprimento, pagamento e resultado são estados distintos. Sem duplicar despesa, taxa, perda ou CMV. Integração indisponível gera estado bloqueado/pendente, não sucesso simulado. Nunca inclua chaves, senhas ou documentos reais em fixtures ou evidências.

## Validação e fechamento

Associe a implementação aos requisitos APP-E-01, APP-E-02, APP-E-03. Inclua testes negativos, concorrência ou transação quando afetar dados/dinheiro. Teste a interface pertinente e atualize contratos se necessário. Não se limite a mudar labels ou criar serviços sem conexão com as telas.

Execute os comandos adequados ao projeto, grave saídas reais sanitizadas e caminhos das evidências. Atualize cobertura, ADRs afetados e checkpoint com arquivos/commit. Relate o que funciona, como foi testado, limites e próxima fase. Só avance após gate comprovado. Trabalho independente deve continuar mesmo que uma credencial externa esteja pendente.

# Regras financeiras e de estoque

## Princípios numéricos

Usar BRL e tipos decimais exatos, nunca `float` para dinheiro. Contrato JSON transporta valores decimais como strings: `"125.30"`. Banco: `numeric(19,2)` para dinheiro, `numeric(19,6)` para quantidades/custos de cálculo. Persistir a regra de arredondamento e aplicar HALF_UP a centavos em totais monetários. Não arredondar custo unitário antes de multiplicar. Diferença de rateio vai para a última parcela, mantendo soma idêntica ao original.

Datas civis de competência/vencimento usam `date`; eventos usam `timestamptz` UTC, exibidos em America/Sao_Paulo. Filtro de intervalo usa início inclusivo e fim exclusivo, convertido explicitamente. Nunca usar o fuso do servidor como fuso comercial implícito.

## Quantidade e custo

Unidade-base inicial proposta: dúzia. Conversão 1 dúzia = 12 unidades só se o produto usar essa medida. Feixes/peso exigem fator por produto, sem supor equivalência universal. Confirmar a unidade comercial na F00.

Estoque disponível = físico - reservado. Todas as confirmações usam transação, bloqueio por saldo ou controle otimista, e chave idempotente. Reserva expira apenas por política explícita; expirar gera evento e aviso. Estoque negativo bloqueado por padrão.

Custo médio móvel proposto por produto/unidade: (valor contábil anterior + custo da entrada + frete de compra apropriado) / quantidade resultante. Devolução e perdas usam vínculo e custo histórico do movimento. Produto perdido antes de venda não entra no CMV da venda: sai como perda. Frete de compra incorporado ao estoque não reaparece como despesa operacional no resultado.

CMV = soma dos custos históricos dos itens efetivamente retirados/entregues no período. Receber 100 dúzias e vender 20 não torna as 100 dúzias despesa do período. A quantidade restante permanece estoque.

## Reconhecimento de venda

Pedido cadastrado reserva estoque; não gera automaticamente venda realizada. Reconhecimento gerencial no cumprimento da retirada/entrega, por linha e quantidade. Entrega parcial reconhece apenas parte cumprida. Quantidade em carteira e venda prevista aparecem separadas da quantidade efetivamente vendida.

Pagamento antes da entrega é adiantamento de cliente. Ao cumprir, reclassificar como recebimento da venda. Fiado depende de permissão, vencimento e conta a receber. Não tratar dinheiro no caixa como lucro.

## Fechamento por categoria

Modelo de resultado gerencial inicial:

1. Vendas cumpridas brutas, incluindo receita de entrega quando cobrada.
2. Menos descontos comerciais e devoluções/cancelamentos de vendas já reconhecidas.
3. Receita líquida gerencial.
4. Menos CMV.
5. Menos despesas por categoria: funcionários; veículos; estrutura; impostos e taxas; outras aprovadas.
6. Menos perdas de mercadoria que não estejam no CMV.
7. Resultado gerencial do período, denominado lucro ou prejuízo na interface com legenda clara.

Tributos sobre vendas podem ser uma dedução da receita OU uma categoria de despesa, conforme política validada. Escolher exatamente uma apresentação; nunca descontar nas duas. O modelo inicial os mantém em impostos/despesas. Isso não substitui demonstração contábil ou apuração fiscal do contador.

Categorias expansíveis mostram subcategorias, data, competência, valor, centro de custo, veículo/viagem quando aplicável, responsável, situação, comprovante e vínculo. O fechamento final soma CMV + despesas + perdas e reconcilia com a receita. Todo gráfico usa os mesmos dados agregados do relatório, sem contas diferentes no cliente.

## Dinheiro, Pix e cartões

- Dinheiro: pagamento de R$ 75 com nota de R$ 100 aumenta caixa em R$ 75, informa R$ 25 de troco e guarda o valor apresentado para conferência.
- Pix: comprovante anexado não confirma crédito. Estado pendente até confirmação manual autorizada ou webhook conciliado. Pix confirmado por webhook deve validar assinatura, idempotência, valor e pedido.
- Cartão: registrar bruto, taxa, líquido, NSU/transação, adquirente, bandeira autorizada, parcelas e previsão. Taxa vira despesa uma vez. Venda de R$ 100 com taxa de R$ 3 gera receita de R$ 100, taxa de R$ 3 e recebível líquido de R$ 97. Não registrar receita de R$ 97 e descontar taxa novamente.
- Dividido: soma dos meios deve igualar o total. Sobras/faltas rejeitadas, salvo saldo a prazo expressamente autorizado.
- Parcelas: datas e valores próprios, com soma bruta idêntica à venda. Liquidações parciais preservam saldo.
- Chargeback, estorno e devolução são eventos separados. Toda reversão aponta para o lançamento de origem.

## Caixa, contas e saldos

Saldo final por conta = saldo inicial + entradas efetivas - saídas efetivas. Transferências internas não mudam o saldo consolidado. Fechamento de turno tem responsável, fundo, recebido esperado, contado, diferença, justificativa e conferente. O saldo da empresa nunca é liberado junto com o caixa próprio por acidente.

Registrar obrigação de imposto/folha afeta competência na conta apropriada. Pagar a obrigação baixa passivo/conta a pagar e caixa, sem repetir a despesa. Adiantamento salarial é compensado sem dupla dedução. Retirada pessoal do sócio fica separada de despesas operacionais.

## Rateio de viagem

Despesa real registrada uma vez no veículo/viagem. Ratear para análise de margem por entrega segundo critério versionado, inicialmente proporcional à quantidade efetivamente entregue. Rateio é uma visão analítica, não novos pagamentos/despesas. Sem quantidade entregue, manter não alocado e sinalizar, em vez de dividir por zero. Contas a pagar não podem duplicar comprovantes do motorista.

## Correção, encerramento e auditoria

Sem exclusão destrutiva de venda, pagamento ou lançamento confirmado. Corrigir com estorno e novo movimento, motivo e autor. Período fechado exige solicitação de reabertura aprovada, com histórico. Mudança futura de categoria não reescreve o passado sem reclassificação explícita.

Indicadores de melhor cliente/funcionário têm fórmula pública por função: volume, margem, recorrência, pontualidade e erros são medidas distintas. Não criar nota única arbitrária, pontuação de caráter ou ranking sem base.

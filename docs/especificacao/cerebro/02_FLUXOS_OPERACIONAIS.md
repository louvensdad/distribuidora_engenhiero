# Fluxos operacionais completos

## Retirada no balcão

1. Usuário autenticado no App Retirada, capacidade `orders.pickup.create`.
2. Seleciona ou registra cliente com os campos autorizados. Venda avulsa só com regra fiscal aprovada pelo contador.
3. Informa itens e quantidades. Preço vem do servidor. Desconto fora da alçada exige solicitação aprovada.
4. Servidor calcula total e reserva estoque numa transação. Negar saldo insuficiente e repetição da mesma chave idempotente.
5. Funcionário separa e marca como pronto. Recebimento é registrado independentemente da separação.
6. Dinheiro é o padrão sugerido, não obrigação. Informar recebido e calcular troco sem somar troco como receita ou despesa.
7. Confirmar retirada baixa estoque e reconhece venda/custo na quantidade efetivamente entregue. Se pago antecipadamente, reclassificar o adiantamento.
8. Registrar funcionário, horário, caixa, itens, documentos e cada mudança no histórico. Gerar comprovante, se autorizado.

## Fornecedor e recebimento

1. Usuário interno com `inventory.receipts.create` seleciona fornecedor.
2. Registra documento, produto, medida, lote, quantidade prevista/recebida/recusada, custo e frete.
3. Confere recebimento. Uma entrega incompleta registra diferença; não inventar estoque para fechar documento.
4. Confirmação aumenta estoque físico, cria obrigação a pagar e recalcula custo médio na regra definida.
5. Devolução ao fornecedor e ajuste são movimentos próprios com vínculo ao original. Sem editar quantidades históricas confirmadas.
6. Dados financeiros do fornecedor ficam escondidos para quem só pode conferir quantidade.

## Entregas, despacho e veículos

1. Atendente autorizado cria pedido de entrega com endereço, contato, janela e pagamento combinado.
2. Servidor reserva mercadoria. Coordenador agrupa vários pedidos numa viagem e escolhe motorista e veículo.
3. Validar capacidade, disponibilidade, documentos e vínculos. Não atribuir pedido cancelado ou já concluído.
4. Motorista recebe somente a viagem atribuída. Vê endereço/contato necessários, quantidade e valor a cobrar quando autorizado.
5. Saída registra hora e odômetro. Entrega registra itens efetivos, recebimento, destinatário e evidência autorizada.
6. Pix já confirmado aparece como pago, sem nova cobrança. Cartão na entrega exige transação/NSU ou confirmação definida pelo provedor.
7. Cliente ausente, endereço errado, recusa e entrega parcial produzem ocorrência. Reagendar ou devolver com autorização. Não marcar como entregue para encerrar a viagem.
8. Retorno registra odômetro, devoluções e prestação de contas. Motorista presta dinheiro recebido e despesas autorizadas.

## Abastecimento e manutenção

Veículo e viagem vinculados quando aplicável, data, odômetro, litros, combustível, valor, posto e comprovante. Bloquear odômetro regressivo sem procedimento de correção aprovado. Calcular km/l apenas com abastecimentos comparáveis e informação suficiente; não inferir consumo preciso de uma única compra parcial.

Manutenção tem tipo, preventiva/corretiva, oficina, peça, valor, odômetro e próxima revisão. Seguro, documentos e pedágios têm classificações próprias. Cadastro do gasto não equivale a pagamento. Alertas de manutenção não bloqueiam entrega sem política configurada.

## Contador, guia e pagamento

Contador prepara obrigação com competência, tipo, vencimento, valor, documento e observação. Envia para conferência. Administrador aprova ou devolve com motivo. Pagamento é outra etapa, com confirmação, data, conta, valor, comprovante e responsável. Cadastro de comprovante pode ser manual e não prova conciliação bancária automática. Obrigação reconhecida e sua liquidação não podem gerar despesa duas vezes.

## Folha

Preparação pelo contador, conferência, aprovação do patrão, pagamento e reconciliação. Salários, adicionais, descontos, adiantamentos, benefícios e encargos têm rubricas separadas. Cada pessoa só vê dados individuais quando houver permissão específica. Não implementar cálculo trabalhista universal inventado; usar regras validadas/importação ou integração contratada.

## Notas fiscais

Pedido elegível gera rascunho com informações necessárias, revisão contábil e envio pelo adaptador fiscal contratado. Estados transmitido, autorizado, rejeitado, cancelamento pendente e cancelado são distintos. Guardar XML, protocolo, chave e representação visual quando aplicáveis. Falha do provedor não pode aparecer como emissão bem-sucedida. Não escolher tipo fiscal apenas pelo nome da empresa.

## Patrão e supervisão

O site e o App Patrão abrem os mesmos setores, detalhes, aprovações e configurações. Histórico com filtros por funcionário, pedido, cliente, fornecedor, veículo, viagem e período. Cada indicador abre os lançamentos que formam o total. A central não faz impersonação de funcionários. Exportações respeitam filtros e permissões.

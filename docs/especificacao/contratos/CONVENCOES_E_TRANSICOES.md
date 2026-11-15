# Contratos e transições

`openapi.yaml` é o contrato inicial de desenho. Não prova que exista servidor. Na implementação, completar os DTOs de cada comando e validar contract tests antes de gerar clientes. Proibido usar um DTO aberto/genérico como validação final de dinheiro ou identidade.

REST sob `/api/v1`; sessão/capabilities sob esse prefixo. JSON UTF-8, IDs opacos, moeda string decimal, quantidade string decimal. Paginação cursor-based, limite padrão25 e máximo100. Ordenação por allowlist, filtros por allowlist, intervalo de datas com fim exclusivo. Erros no formato de `erro.schema.json` e request_id.

Comandos financeiros/estoque/fiscais têm `Idempotency-Key`. Mesma chave+payload devolve resultado original. Mesma chave com payload diferente retorna409. Comando usa `If-Match`/version e recusa409 ou412 conforme contrato registrado. Não repetir automaticamente comando não idempotente após timeout.

## Estados canônicos

Pedido: DRAFT -> CONFIRMED -> PREPARING -> READY/PENDING_DISPATCH -> PARTIALLY_FULFILLED -> FULFILLED. CANCELLED apenas segundo etapa; venda cumprida exige devolução/reversão. RETIRADA e ENTREGA são tipos, não estados de pagamento.

Pagamento: PENDING -> CONFIRMED -> PARTIALLY_REFUNDED/REFUNDED; FAILED/REJECTED conforme provedor. Compromisso de crédito não significa dinheiro recebido. Recebimento manual inclui quem conferiu e evidência.

Viagem: DRAFT -> ASSIGNED -> DEPARTED -> RETURNED -> RECONCILED; cancelamento antes da saída. Entrega individual pode estar FAILED/RESCHEDULED/PARTIAL sem encerrar todos os pedidos.

Guia/folha: DRAFT -> SUBMITTED -> APPROVED/RETURNED -> PARTIALLY_PAID/PAID -> RECONCILED. Quem prepara, aprova e paga são campos separados, mesmo que a política permita uma pessoa em mais de uma etapa.

Fiscal: DRAFT -> VALIDATING -> SUBMITTED -> AUTHORIZED/REJECTED/UNKNOWN -> CANCELLATION_REQUESTED -> CANCELLED/REJECTION_OF_CANCELLATION. UNKNOWN exige consulta ao provedor.

Documento: UPLOADING -> QUARANTINED -> AVAILABLE/REJECTED -> SUPERSEDED/ARCHIVED. Só AVAILABLE é acessível por download quando autorizado.

## Ações e auditoria

Autor vem da sessão, não do corpo do pedido. IDs de empresa/unidade são validados. Contratos de consulta têm schemas por capacidade para campos sensíveis. Evento do servidor atualiza as outras superfícies autorizadas. Alterar papel no aplicativo não é uma operação de negócio válida.

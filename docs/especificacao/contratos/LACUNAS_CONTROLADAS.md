# Limites controlados do contrato inicial

O contrato OpenAPI é um ponto de partida rastreável. `DomainCommand`, `CommandResult` e `Page.items` precisam ser substituídos ou especializados por DTOs fechados em cada fase. Não podem ser promovidos a produção sem validação de campos, estados e autorização. Esta é uma tarefa explícita, não uma promessa de API já finalizada.

Completar schemas de fulfillment parcial, recebimento/custo, guia/folha, rateio, política de campos, viagem/capacidade, anexo em quarentena e exportação assíncrona. Documentar endpoints de sessão OIDC e webhooks conforme o provedor efetivamente contratado.

Antes de gerar cliente final, validar OpenAPI com ferramenta suportada, exemplos, respostas negativas e testes de compatibilidade. Queries de relatório precisam registrar filtros admitidos e limite de período. Uma resposta de leitura não deve usar CommandResult genérico no produto final.

O contrato de tipo de pedido é dinâmico: PICKUP e DELIVERY exigem recursos diferentes. A anotação x-type-aware-policy registra essa obrigação. Não permitir que o chamador altere tipo ou ID para trocar a política.

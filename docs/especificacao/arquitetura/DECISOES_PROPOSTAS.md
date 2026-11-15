# Decisões propostas para validação

| ADR | Decisão inicial | Razão e limite |
|---|---|---|
| ADR-001 | Monólito modular Java | Transações e equipe pequena; extração de serviço depende de evidência |
| ADR-002 | Angular web e Flutter mobile | Web de gestão própria; quatro apps compartilham bibliotecas Dart |
| ADR-003 | PostgreSQL + objeto privado | Consistência do dinheiro/estoque e anexos separados |
| ADR-004 | OIDC + BFF web + PKCE móvel | Conta individual, sem segredos em clientes |
| ADR-005 | Permissão por ação/campo/escopo | Modelo editável sem confiar só no papel |
| ADR-006 | Custo médio móvel | Proposta simples rastreável, confirmar com contador |
| ADR-007 | Reconhecimento no cumprimento | Separar pedido previsto de venda realizada |
| ADR-008 | Outbox transacional | Atualizar superfícies sem perder evento após commit |
| ADR-009 | IA de leitura com fontes | Explicação auditável, sem autonomia financeira |
| ADR-010 | Online primeiro | Evitar confirmação local falsa de estoque e pagamento |
| ADR-011 | Gráfico 3D extrudado + tabela | Aparência solicitada com valores e acessibilidade preservados |
| ADR-012 | Quatro aplicações independentes | Distribuição por função, sem seletor público de papéis |

São propostas deste pacote. Não são decisões já aprovadas pelo contador, provedor ou equipe. Formalizar mudanças com `templates/ADR.md` e atualizar contratos e testes afetados.

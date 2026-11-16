# Registro de decisões (ADR)

As ADR-001 a ADR-012 vêm de `docs/especificacao/arquitetura/DECISOES_PROPOSTAS.md`. Na F00 foram verificadas
contra o ambiente real (`toolchain.lock.json`) e adotadas como ponto de partida. Não são aprovações do contador
ou de provedores; mudanças exigem novo ADR com o modelo `docs/especificacao/templates/ADR.md`.

| ADR | Decisão | Estado | Observação F00 |
|---|---|---|---|
| ADR-001 | Monólito modular Java | ADOTADA | Spring Boot 4.1.2 / Java 21 disponíveis |
| ADR-002 | Angular web e Flutter mobile | PARCIALMENTE SUBSTITUÍDA | Web → ADR-015 (React); mobile Flutter 3.50.0 instalado |
| ADR-003 | PostgreSQL + objeto privado | ADOTADA | postgres:18.6-alpine; armazenamento de objetos a definir (F07) |
| ADR-004 | OIDC + BFF web + PKCE móvel | ADOTADA | Keycloak 26.8.1 local; provedor de produção em D06/D09 |
| ADR-005 | Permissão por ação/campo/escopo | ADOTADA | Implementação em `services/api` módulo `identityaccess` |
| ADR-006 | Custo médio móvel | PROPOSTA | Confirmar com contador (D12) |
| ADR-007 | Reconhecimento no cumprimento | PROPOSTA | Confirmar com contador (D12) |
| ADR-008 | Outbox transacional | ADOTADA | Tabela na F01; publicação a partir da F04 |
| ADR-009 | IA de leitura com fontes | ADOTADA | Provedor NÃO CONFIGURADO (D13) |
| ADR-010 | Online primeiro | ADOTADA | |
| ADR-011 | Gráfico 3D extrudado + tabela | ADOTADA | F09 |
| ADR-012 | Quatro aplicações independentes | ADOTADA | Clients OIDC distintos no realm local |
| ADR-013 | Capacidade `permissions.edit_draft` = gestão de permissões | ADOTADA | Ver `ADR-013.md` |
| ADR-014 | Acesso a dados com Spring JDBC (sem ORM) na F01 | ADOTADA | Ver `ADR-014.md` |
| ADR-015 | Site em React + Vite (Node) em vez de Angular | ADOTADA (pedido do responsável) | Ver `ADR-015.md`; substitui a parte web da ADR-002 |
| ADR-016 | Regras de estoque da F03 (custo médio, frete, reservas, perdas, devoluções) | ADOTADA (validar com contador, D12) | Ver `ADR-016.md` |
| ADR-017 | Funções sem recurso no contrato (contas a pagar, unidades) só para administrador | ADOTADA (provisória) | Ver `ADR-017.md` |
| ADR-018 | Regras de pedido, pagamento e caixa da F04 | ADOTADA (validar D04/D12/D15) | Ver `ADR-018.md` |
| ADR-019 | Regras de frota, viagens, entregas, prestação e rateio da F05 | ADOTADA (validar com dono/contador) | Ver `ADR-019.md` |
| ADR-020 | Subledger financeiro gerencial da F06 (contas, competência, despesas, a pagar/receber, períodos) | ADOTADA (validar com dono/contador, D12) | Ver `ADR-020.md`; substitui ADR-017 para contas a pagar |

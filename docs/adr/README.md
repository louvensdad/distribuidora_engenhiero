# Registro de decisões (ADR)

As ADR-001 a ADR-012 vêm de `docs/especificacao/arquitetura/DECISOES_PROPOSTAS.md`. Na F00 foram verificadas
contra o ambiente real (`toolchain.lock.json`) e adotadas como ponto de partida. Não são aprovações do contador
ou de provedores; mudanças exigem novo ADR com o modelo `docs/especificacao/templates/ADR.md`.

| ADR | Decisão | Estado | Observação F00 |
|---|---|---|---|
| ADR-001 | Monólito modular Java | ADOTADA | Spring Boot 4.1.2 / Java 21 disponíveis |
| ADR-002 | Angular web e Flutter mobile | ADOTADA (mobile BLOCKED por ambiente) | Angular 22.2.6 no registro; Flutter ausente |
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

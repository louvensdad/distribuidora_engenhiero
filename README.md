# Distribuidora de Cana Engenheiro Ltda — Sistema de gestão

Monorepo do site do patrão, dos quatro aplicativos móveis (Patrão, Retirada, Entregas, Contábil) e do backend único.

| Caminho | Conteúdo | Situação |
|---|---|---|
| `services/api` | Backend Java 21 / Spring Boot (monólito modular) | F01 em andamento |
| `infra/local` | PostgreSQL + Keycloak (OIDC) para desenvolvimento | F01 |
| `apps/web-admin` | Site do patrão (Angular) | Não iniciado (F02/F10) |
| `apps/mobile-*` | Quatro apps Flutter independentes | Não iniciado; Flutter ausente na máquina (BLOCKED) |
| `docs/especificacao` | Pacote de especificação original (autoridade do produto) | Referência |
| `docs/` | Inventário, ADRs, evidências, checkpoint | Vivo |

## Rodar localmente

```bash
# infraestrutura (PostgreSQL 18 + Keycloak 26)
docker compose -f infra/local/docker-compose.yml up -d

# testes do backend (usa Testcontainers; requer Docker)
cd services/api && ./mvnw verify

# subir a API com dados fictícios de desenvolvimento
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Veja `docs/CHECKPOINT.md` para o estado atual e próximos passos.

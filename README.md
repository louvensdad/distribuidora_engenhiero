# Distribuidora de Cana Engenheiro Ltda — Sistema de gestão

Monorepo do site do patrão, dos quatro aplicativos móveis (Patrão, Retirada, Entregas, Contábil) e do backend único.

| Caminho | Conteúdo | Situação |
|---|---|---|
| `services/api` | Backend Java 21 / Spring Boot (monólito modular) | F01 concluída; clientes (F03) |
| `packages/contracts` | OpenAPI dos endpoints implementados | Conferido por teste |
| `infra/local` | PostgreSQL + Keycloak (OIDC) para desenvolvimento | F01 |
| `apps/web-admin` | Site do patrão (React + Vite, ADR-015) | Login, abas, clientes, pessoas, mapa de acessos, histórico |
| `apps/mobile-*` | Quatro apps Flutter independentes | Não iniciado; Flutter 3.50.0 + Android SDK instalados |
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

### Site (desenvolvimento)

```bash
cd apps/web-admin
npx -y npm@11.21.1 ci          # o npm 9 do sistema não resolve as dependências do Vite 8
npm run dev                    # http://localhost:4200 (proxy para a API em :8081, mesma origem)
npm test                       # unitários/componentes
ENGENHEIRO_SENHA_LOCAL=... npx playwright test   # E2E contra a pilha local real
```

Veja `docs/CHECKPOINT.md` para o estado atual e próximos passos.

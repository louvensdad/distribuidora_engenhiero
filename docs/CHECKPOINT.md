# Checkpoint

Data/hora e fuso: 14/11/2026 21:05 America/Sao_Paulo
Agente e repositório: Claude Code (Opus 5.5) — `/home/louvens/distribuidora-engenheiro`
Branch e commit atual: `main` — ver `git log -1`
Fase: F00 concluída; F01 em andamento
Gate: F00 **PASS** · F01 **IN_PROGRESS**

## Entregue e comprovado

**F00** — `docs/F00_INVENTARIO.md`, `toolchain.lock.json`, `docs/adr/`, `docs/MAPA_REQUISITOS.md`.
Projeto greenfield (não havia código da Distribuidora); pacote íntegro (`F00-validar_pacote.txt`).

**F01 (fatia 1: backend de identidade e autorização)** — `services/api`:
- Spring Boot 4.1.2 / Java 21, Maven Wrapper, Flyway (`V1__identity_access`, `V2__audit_outbox`), seed fictício só no perfil `local`.
- Validação JWT: assinatura (JWKS), emissor, audiência `engenheiro-api`, expiração.
- Vínculo local recarregado a cada requisição: conta/vínculo/organização ativos + app (client `azp`) liberado.
- Motor de autorização negar‑por‑padrão: recurso/ação do contrato `permissoes.json`, escopos OWN/ASSIGNED/SECTOR/BRANCH/ORGANIZATION,
  negação explícita vence (inclusive admin), validade temporária, alçada em decimal, classes de campo sensível.
- Endpoints: `GET /api/v1/me/capabilities`, `GET /api/v1/users`, `GET|POST /api/v1/users/{id}/access-policy` (If-Match, motivo),
  `POST /api/v1/users/{id}/status`, `GET /api/v1/role-templates`, `GET /api/v1/audit-events` (cursor, máx. 100), health/readiness.
- Proteções: ninguém altera o próprio acesso; não concede o que não possui; só admin altera admin; último admin preservado
  (com trava ordenada contra corrida); outra organização → 404.
- Auditoria com ator real, request_id, negativas em transação própria; trigger impede UPDATE/DELETE/TRUNCATE.
- Erros no formato `erro.schema.json` com `X-Request-Id`.
- `infra/local`: PostgreSQL 18.6 + Keycloak 26.8.1, realm com 5 clients (BFF confidencial; 4 apps públicos com PKCE S256,
  sem password grant), audiência da API, proteção contra força bruta, TOTP exigido ao patrão. Sem senhas no repositório.

## Validação

- `./mvnw verify` → 52 testes, 0 falhas (`docs/evidence/F01-mvnw-verify.txt`).
- Mutação da trava de admins → 5/5 falham; restaurado → 5/5 passam (`F01-mutacao-concorrencia.txt`).
- Smoke local com Keycloak e PostgreSQL reais: discovery/JWKS, readiness UP, 401 padronizado, migrations, seed, trigger
  de auditoria (`F01-smoke-local.txt`).

## Bloqueios e decisões

- **Flutter/Android SDK/Xcode ausentes** → builds móveis BLOCKED (não impede backend/web).
- Porta 8080 ocupada nesta máquina; a API local foi testada em `PORT=8081`. O redirect do BFF no realm usa 8080 — ajustar quando o BFF existir.
- Código `INTERNAL_ERROR` (500) não consta em `erro.schema.json`: propor inclusão no contrato.
- Decisões D01–D15 em `docs/F00_INVENTARIO.md` §4 (nenhuma integração assumida).

## Próxima ação

Concluir F01:
1. **BFF web**: cadeia Spring Security separada (`/bff/**`) com `oauth2Login` (client `engenheiro-web`, PKCE), sessão em cookie
   HttpOnly/Secure/SameSite=Lax, CSRF em mutações, logout; sem token no navegador. Teste: login simulado, CSRF ausente → 403.
2. Teste ponta a ponta com token emitido pelo Keycloak local (Authorization Code + PKCE).
3. Prova HTTP de escopo por registro com uma entidade real (primeira entidade de F03) — "outra unidade por ID conhecido → 404".
4. Fechar o gate F01 só após 1–3; então F02 (design system + shells web; mobile segue BLOCKED até instalar Flutter 3.50.0).

# Checkpoint

Data/hora e fuso: 14/11/2026 21:20 America/Sao_Paulo
Agente e repositório: Claude Code (Opus 5.5) — `/home/louvens/distribuidora-engenheiro`
Branch e commit atual: `main` — ver `git log -1`
Fase: F01 concluída; próxima F02
Gate: F00 **PASS** · F01 **PASS** (com limites abaixo) · F03 **IN_PROGRESS** (fatia clientes)

## Entregue e comprovado

**F00** — inventário, decisões D01–D15, `toolchain.lock.json`, ADRs, mapa de requisitos.

**F01** — `services/api` (Spring Boot 4.1.2 / Java 21 / Spring Security 7.1.2):
- Apps: bearer JWT validado (assinatura, emissor, audiência, validade) + vínculo local ativo + app liberado, recarregado a cada chamada.
- Site: BFF com login OIDC no servidor (Authorization Code + PKCE S256), tokens e refresh só na sessão do servidor,
  cookie `ENGENHEIRO_SESSION` HttpOnly/SameSite=Lax (Secure fora do perfil local), CSRF (`X-XSRF-TOKEN`) em mutações por cookie,
  troca de ID de sessão no login, renovação automática do access token, logout com URL de logout do IdP.
  Sem `ENGENHEIRO_WEB_CLIENT_SECRET` o login web fica `NAO_CONFIGURADO` e a API bearer segue funcionando.
- Autorização negar-por-padrão (recurso/ação/escopo/campo/alçada/validade; negação explícita vence), administração de acessos
  versionada, proteções contra autopromoção/escalada/último admin (com trava contra corrida), auditoria imutável com ator real.
- Filtro de linha no servidor (`ScopeSql`) e 404 indistinguível para registro fora do escopo.
- Contrato `packages/contracts/openapi.json` dos endpoints existentes, conferido contra os controllers por teste.

**F03 (fatia)** — clientes: `POST/GET /api/v1/customers`, `GET /api/v1/customers/{id}`; escopo por unidade; contato/endereço/documento
mascarados conforme política de campo e recusados na gravação por quem não os vê; auditoria sem dado pessoal.

## Validação

- `./mvnw verify` → **75 testes, 0 falhas** (`docs/evidence/F01-mvnw-verify.txt`).
- Testes de mutação (trava de admins, CSRF, escopo por unidade): os testes falham com a mutação e passam com o código real
  (`F01-testes-de-mutacao.txt`).
- Ponta a ponta com Keycloak 26.8.1 real: formulário de login, cadastro de TOTP do patrão, PKCE, sessão, `/me` 200, mutação sem CSRF 403 /
  com CSRF 200, logout → 401, contador no site do patrão → 403 auditado (`F01-e2e-login-web-keycloak.txt`).
- Smoke de infraestrutura (`F01-smoke-local.txt`).

## Limites do gate F01 (explícitos)

- Login nos 4 apps móveis não exercitado: Flutter ausente (**BLOCKED** por ambiente). A API já aceita/nega por client (`azp`).
- Critério "pedido/anexo de outra unidade por ID" provado com **clientes**; reverificar em pedidos (F04) e documentos (F07)
  usando o mesmo `requireRecord`/`ScopeSql`.
- Canais SSE/websocket ainda não existem; revogação nesses canais será testada quando forem criados.
- Sessão HTTP em memória (uma instância). Para mais de uma instância: Spring Session JDBC (F16).
- `INTERNAL_ERROR` (500) não consta em `erro.schema.json`: propor inclusão no contrato.

## Bloqueios e decisões

- Flutter 3.50.0 / Android SDK / Xcode ausentes. Para destravar: instalar o SDK fixado em `toolchain.lock.json`.
- Porta 8080 ocupada nesta máquina; local usa `PORT=8081` (redirect 8081 incluído no realm).
- D01–D15 em `docs/F00_INVENTARIO.md` §4.

## Como reproduzir localmente

```bash
export KC_ADMIN_PASSWORD=<aleatória>            # só para o container local
docker compose -f infra/local/docker-compose.yml up -d
ENGENHEIRO_SENHA_LOCAL=<aleatória> infra/local/definir-senhas-locais.sh
# segredo do client web: gerado pelo Keycloak (kcadm get clients/<id>/client-secret) -> ENGENHEIRO_WEB_CLIENT_SECRET
cd services/api && ./mvnw -q -DskipTests package
PORT=8081 SPRING_PROFILES_ACTIVE=local ENGENHEIRO_WEB_CLIENT_SECRET=... java -jar target/engenheiro-api-0.1.0-SNAPSHOT.jar
ENGENHEIRO_SENHA_LOCAL=... python3 -I ../../infra/local/e2e_login_web.py
```

## Próxima ação

**F02 — design system e shells.** Criar `apps/web-admin` (Angular 22.2.6, lockfile commitado) com tokens de
`docs/especificacao/design/tokens.css`, ícones de `assets/`, abas horizontais Visão geral/Vendas/Operação/Financeiro/Relatórios/
Inteligência/Equipe montadas a partir de `/api/v1/me/capabilities`, estados loading/vazio/erro/sem permissão/conflito/offline,
proxy de desenvolvimento para `/api`, `/bff`, `/oauth2`, `/login` (mesma origem) e primeira tela real: mapa de acessos
(`/api/v1/users/{id}/access-policy`). Testes: unitários + Playwright em 360/768/1440 px e teclado. Mobile segue BLOCKED.

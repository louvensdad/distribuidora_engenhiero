# Mapa requisito → módulo → teste

Estado: NOT_STARTED / IN_PROGRESS / PARTIAL / PASS / BLOCKED. Só PASS com evidência em `docs/evidence/`.
Requisitos de F02 em diante seguem NOT_STARTED (lista completa em `docs/especificacao/plano/requisitos.json`).

| Req | Módulo / superfície | Teste / evidência | Estado |
|---|---|---|---|
| PROD-01 | Todas; `application.yaml` `engenheiro.empresa.nome`, realm, seeds | `AuthenticationIntegrationTest.tokenValidoComVinculoAtivoAutentica` | PASS (backend); revalidar em web/apps |
| PROD-02 | `apps/web-admin` + 4 `apps/mobile-*`, um `services/api` | `docs/F00_INVENTARIO.md` §3; 5 clients no realm | PASS (mapa); builds em F02/F11–F14 |
| PROD-03 | `apps/mobile-owner` + central | F14 | NOT_STARTED |
| PROD-04 | Recebimento interno no app Retirada (`RECEBIMENTO`) | `RoleTemplate.RECEBIMENTO` | PASS (mapa) |
| PROD-05 | `memberships` sem teto | `AuthorizationIntegrationTest.suportaMaisDeOitoFuncionarios` | PASS |
| PROD-06 | Inventário | `docs/F00_INVENTARIO.md`, `F00-validar_pacote.txt` | PASS |
| SEC-01 | `shared/security`, realm Keycloak (PKCE S256 nos 4 apps; BFF confidencial) | `AuthenticationIntegrationTest` (13 casos) + `F01-smoke-local.txt` | PARTIAL — API valida JWT; BFF web e login mobile não implementados |
| SEC-02 | `identityaccess.domain.AccessPolicy`, `AuthorizationService` | `AccessPolicyTest`, `AuthorizationIntegrationTest` | PARTIAL — modelo e motor prontos; aplicar em cada módulo de domínio (F03+) |
| SEC-03 | Modelos sem dashboard/relatórios/folha; campos negados por padrão | `balcaoRecebeSomente...`, `contadorNaoRecebe...`, `ContractConsistencyTest` | PASS (identidade); revalidar quando os endpoints existirem |
| SEC-04 | `RoleTemplate`, `POST /users/{id}/access-policy`, `GET /role-templates` | `AuthorizationIntegrationTest` | PARTIAL — API pronta; tela de mapa de acessos em F02/F10 |
| SEC-05 | Ator recarregado do banco por requisição | `revogacaoImpedeAProximaChamada`, `bloqueioDeConta...`, `removerAplicativo...` | PASS (HTTP); canais SSE quando existirem |
| SEC-06 | API stateless bearer; sem segredo no repositório | revisão + `.gitignore`; script de senhas por variável | PARTIAL — falta BFF web com cookie HttpOnly |
| SEC-07 | Escopo ASSIGNED | `AccessPolicyTest.motoristaSoVeViagemAtribuidaASi` | PARTIAL — falta teste HTTP com viagens (F05) |
| SEC-08 | `AuthorizationService.requireRecord` (404 fora do escopo) | `AccessPolicyTest` | PARTIAL — downloads/relatórios/eventos/IA nas fases próprias |
| SEC-09 | Ator real na auditoria; sem impersonação | `campoExtraNoCorpoNaoMudaAtor...`, `historicoRegistraAtorReal...` | PASS (identidade) |

Testes adicionais de F01: imutabilidade de `audit_events` (trigger), paginação por cursor, 412 por versão,
concorrência entre administradores (`ConcurrencyIntegrationTest`, com teste de mutação).

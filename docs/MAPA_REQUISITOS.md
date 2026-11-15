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
| SEC-01 | `shared/security` (bearer + BFF `oauth2Login` PKCE), realm Keycloak (5 clients) | `AuthenticationIntegrationTest`, `WebBffIntegrationTest`, `F01-e2e-login-web-keycloak.txt` | PASS (web e API); login nos apps móveis BLOCKED (sem Flutter) |
| SEC-02 | `AccessPolicy`, `AuthorizationService`, `ScopeSql` (filtro de linha) | `AccessPolicyTest`, `AuthorizationIntegrationTest`, `CustomersIntegrationTest` | PASS (motor + primeira entidade); aplicar a cada novo módulo |
| SEC-03 | Modelos sem dashboard/relatórios/folha; campos negados por padrão | `balcaoRecebeSomente...`, `contadorNaoRecebe...`, `ContractConsistencyTest` | PASS (identidade); revalidar quando os endpoints existirem |
| SEC-04 | `RoleTemplate`, `POST /users/{id}/access-policy`, `GET /role-templates` | `AuthorizationIntegrationTest` | PARTIAL — API pronta; tela de mapa de acessos em F02/F10 |
| SEC-05 | Ator recarregado do banco por requisição | `revogacaoImpedeAProximaChamada`, `bloqueioDeConta...`, `removerAplicativo...` | PASS (HTTP); canais SSE quando existirem |
| SEC-06 | BFF: tokens só na sessão do servidor; cookie HttpOnly/SameSite=Lax; CSRF | `WebBffIntegrationTest.loginCompleto...SemExporTokens`, e2e Keycloak | PASS (web); segredo de app móvel: não há (clients públicos) |
| SEC-07 | Escopo ASSIGNED | `AccessPolicyTest.motoristaSoVeViagemAtribuidaASi` | PARTIAL — falta teste HTTP com viagens (F05) |
| SEC-08 | `requireRecord` (404 = inexistente), `ScopeSql` em listas/busca | `CustomersIntegrationTest` | PARTIAL — busca/listas OK; downloads, relatórios, eventos e IA nas fases próprias |
| SEC-09 | Ator real na auditoria; sem impersonação | `campoExtraNoCorpoNaoMudaAtor...`, `historicoRegistraAtorReal...` | PASS (identidade) |

| INV (F03) | `catalogparties.customers` — cliente com campos classificados | `CustomersIntegrationTest` (11) | PARTIAL — só clientes |

Testes adicionais de F01: imutabilidade de `audit_events` (trigger), paginação por cursor, 412 por versão,
concorrência entre administradores (`ConcurrencyIntegrationTest`, com teste de mutação).

Contrato dos endpoints implementados: `packages/contracts/openapi.json`, verificado por `OpenApiContractTest`.

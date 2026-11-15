# F00 — Inventário, escopo e decisões

Data: 14/11/2026 (America/Sao_Paulo). Repositório: `/home/louvens/distribuidora-engenheiro` (git `main`, criado nesta fase).

## 1. Estado de partida (PROD-06)

| Item | Onde | Situação | Evidência |
|---|---|---|---|
| Pacote de especificação v1.0 | `docs/especificacao/` (cópia íntegra do ZIP `Distribuidora_Engenheiro_Cerebro_Prompts_e_Design`) | Íntegro | `docs/evidence/F00-validar_pacote.txt` — `PASS: 99 requisitos; 18 fases; 58 telas; SVGs, links estruturados e checksums verificados.` |
| Código de produção da Distribuidora | — | **Inexistente**. Busca por "Cana Engenheiro" em `~/SaasLdcn-os`, `~/Downloads/Ai-devop`, `~/Downloads/Project-aiDeveloper-*` e `~/Documentos` sem resultado. Esses diretórios são outros produtos e não foram tocados. | comando `grep -rli "Cana Engenheiro" ...` sem saída |
| Demonstração visual anterior | URL em `docs/especificacao/referencias/PROTOTIPO_E_FONTES.md` | Somente referência visual. localStorage, seletor de perfis, IA simulada e anexos por nome. **Não comprova** backend, isolamento, apps, emissão ou pagamentos. Nada foi copiado dela. | documento de referência |
| Ativos de design | `docs/especificacao/design`, `docs/especificacao/assets` | Tokens, 58 telas SVG/PNG, 4 ícones de app, ícones de interface | validador acima |

Conclusão: projeto greenfield. Todas as fases começam NOT_STARTED; nada é herdado como PASS.

## 2. Ambiente

Ver `docs/evidence/F00-ambiente.txt` e `toolchain.lock.json`.

| Ferramenta | Situação |
|---|---|
| Java 21.0.13, Maven 3.9.12, Docker 29.9 | Disponíveis — backend e testes de integração (Testcontainers) executáveis |
| PostgreSQL 18.6, Keycloak 26.8.1 | Imagens locais disponíveis |
| Node 22.22.1 / Angular 22.2.6 | Node disponível; Angular a instalar na F02 |
| Flutter 3.50.0 / Android SDK / Xcode | **AUSENTES → BLOCKED** para build móvel (F02 mobile, F11–F14). Trabalho de backend/web segue independente. |

## 3. Superfícies separadas (PROD-02, PROD-03, PROD-04)

| Superfície | Pasta | Client OIDC | Bundle ID proposto | Usuários |
|---|---|---|---|---|
| Site do patrão | `apps/web-admin` | `engenheiro-web` (BFF, confidencial no servidor) | — | Proprietário/administradores |
| App Engenheiro Patrão | `apps/mobile-owner` | `engenheiro-gestao` (público + PKCE) | `br.com.engenheiro.gestao` | Proprietário/administradores; central Retirada/Entregas/Contabilidade |
| App Engenheiro Retirada | `apps/mobile-counter` | `engenheiro-retirada` | `br.com.engenheiro.retirada` | Balcão + recebimento interno de fornecedores (sem portal externo) |
| App Engenheiro Entregas | `apps/mobile-delivery` | `engenheiro-entregas` | `br.com.engenheiro.entregas` | Coordenador, atendente e **motorista** (não há quinto app) |
| App Engenheiro Contábil | `apps/mobile-accountant` | `engenheiro-contabil` | `br.com.engenheiro.contabil` | Contador e auxiliares autorizados |

Um backend (`services/api`), um banco PostgreSQL. O vínculo de cada pessoa define quais clients (apps) pode usar; o client sozinho não concede permissão.

PROD-01: nome "Distribuidora de Cana Engenheiro Ltda" registrado em `services/api` (`engenheiro.empresa.nome`), seeds, README e realm OIDC.
PROD-05: nenhum limite de funcionários no modelo (`memberships` sem teto; teste com > 8 vínculos em `IdentityAccessIntegrationTest`).

## 4. Decisões (D01–D15)

| ID | Decisão | Estado | Padrão aplicado até confirmação | Bloqueia | Responsável |
|---|---|---|---|---|---|
| D01 | Unidade de venda/conversões | ABERTA | Dúzia como exemplo; fator por produto | Importação/operação real (F03) | Dono |
| D02 | CNPJ, regime, nota, obrigações | ABERTA | Não inferir | Fiscal real (F08) | Contador |
| D03 | Provedor fiscal/certificado | ABERTA | Adaptador NÃO CONFIGURADO | Emissão (F08) | Contador/Dono |
| D04 | Adquirente, Pix, banco | ABERTA | Confirmação manual auditada | Conciliação automática (F06/F08) | Dono |
| D05 | Quem recebe/desconta/cancela | PADRÃO SEGURO | Negar salvo concessão do dono | Usuários reais | Dono |
| D06 | Cadastro/acesso do dono | PADRÃO SEGURO | OIDC (Keycloak local) + MFA exigido no realm de produção | Produção | Dono |
| D07 | Domínios/conta de lojas | ABERTA | IDs da tabela acima | Assinatura/lojas | Dono |
| D08 | Dispositivos/internet da loja | PADRÃO | Online primeiro; rascunho offline | Certificação offline | Dono |
| D09 | Infraestrutura backend | ABERTA | Java/PostgreSQL em destino compatível; local via Docker | Implantação (F16) | Dono |
| D10 | Folha/obrigações trabalhistas | ABERTA | Importação/validação com contador | Folha real (F07) | Contador |
| D11 | Retenção documental | ABERTA | Mínimo necessário | Produção com dados pessoais | Dono/Contador |
| D12 | Custo e reconhecimento | PROPOSTA | Custo médio móvel; venda no cumprimento por item | Fechamento real | Contador |
| D13 | Provedor/modelo IA, orçamento | ABERTA | Somente leitura; NÃO CONFIGURADO | IA real (F15) | Dono |
| D14 | Metas/desempenho | ABERTA | Fórmulas separadas | Ranking definitivo (F09) | Dono |
| D15 | Prazo de crédito/alçadas | PADRÃO SEGURO | Bloqueado por padrão | Venda a prazo | Dono |

Nenhuma integração foi assumida como contratada.

## 5. Stack e ADRs

ADRs em `docs/adr/`. Stack confirmada no ambiente: ver `toolchain.lock.json`. Divergência registrada: `ADR-013` (nome da capacidade de gestão de permissões).

## 6. Mapa requisito → módulo → teste

`docs/MAPA_REQUISITOS.md`.

## 7. Gate F00

Critérios: quatro apps e web separados no mapa ✔; nenhum segredo ou dado real em fixtures ✔ (seeds usam nomes fictícios e e-mails `@exemplo.invalid`); desconhecidos com estado explícito ✔.
**Gate F00: PASS** (documental/inventário). Mobile permanece BLOCKED por ambiente, sem impedir F01.

# Arquitetura proposta e decisões de implementação

## Estrutura inicial

Backend Java 21 com Spring Boot; modularização por domínio num monólito. PostgreSQL para dados transacionais; armazenamento privado compatível com S3 para anexos; provedor OIDC para identidade; worker de tarefas para relatórios, notificações, integrações e IA; web Angular; quatro aplicações Flutter com pacotes compartilhados.

Essa é uma decisão proposta para começar com uma equipe pequena. Não introduzir microsserviços, Kafka ou Kubernetes só para preencher arquitetura. Separar fisicamente serviços depois de medir necessidade. Redis é opcional para cache/distribuição de tarefas; não é a fonte do estoque ou das permissões.

## Versões e compatibilidade

Referência consultada em 14/11/2026: a documentação oficial de Spring Boot identificava 4.1.2 e admitia Java 21 dentro da faixa informada. A tabela oficial do Angular apresentava a linha 22 e sua compatibilidade com Node/TypeScript. Isso é referência documental, não um lockfile pronto. Na F00, validar disponibilidade real no registro, suporte, dependências do ambiente e registrar patches exatos em `toolchain.lock.json`, sem usar tags latest. Se a versão não estiver disponível/compatível, escolher linha suportada e documentar ADR antes de construir.

Flutter stable com SDK e Dart fixados por versão/commit na F00. Android e iOS usam configuração própria por aplicação; compartilhar bibliotecas Dart, não um seletor de papéis. Maven Wrapper, lockfile web e `pubspec.lock` devem ser commitados. PostgreSQL e provedor OIDC também recebem tags/digests fixos.

## Fronteiras do backend

- identity_access: vínculos internos, capacidades e políticas; não armazena senha do IdP.
- catalog_parties: produtos, medidas, clientes, fornecedores e unidades.
- inventory: recebimentos, saldo, reserva, movimentação e custo.
- orders: itens, preços, cumprimento, devoluções e cancelamentos.
- payments_cash: alocação de recebimentos, conciliação e caixa.
- logistics_fleet: veículos, viagens, atribuições, combustível e manutenção.
- finance: contas, despesas, passivos, lançamentos e períodos.
- accounting_fiscal: folha, impostos e adaptador fiscal.
- documents: metadados, autorização, armazenamento e varredura.
- reporting: projeções, consultas e exportações autorizadas.
- audit_notifications: trilha, eventos e avisos.
- ai: ferramentas de leitura autorizada e explicações com fontes.

Interfaces explícitas por módulo. Nenhum controller acessa tabela alheia para contornar regra de domínio. Entidades de persistência não são DTOs públicos.

## Organização do repositório a implementar

```
apps/web-admin
apps/mobile-owner
apps/mobile-counter
apps/mobile-delivery
apps/mobile-accountant
packages/mobile-core
packages/mobile-design
packages/mobile-api
packages/mobile-auth
packages/contracts
services/api
services/jobs
infra/local
infra/deploy
docs/adr
docs/evidence
docs/runbooks
tests/contract
tests/security
tests/e2e
```

Os quatro projetos móveis possuem `main`, manifesto, bundle ID, nome, ícone e configuração de build próprios. Não repetir código de dinheiro/contratos/segurança em quatro locais. Web e Flutter consomem contratos versionados; geração de cliente é verificada em CI.

## Autenticação e transporte

OIDC com Authorization Code + PKCE. Web usa sessão de servidor/BFF com cookie HttpOnly, Secure e SameSite apropriado; proteção CSRF em mutações. Evitar tokens persistentes em localStorage. Mobile usa navegador de sistema, PKCE, armazenamento seguro do SO e redirecionamentos vinculados a cada app. O `client_id` e o pacote do app não são segredos nem concedem permissão automaticamente.

API valida assinatura, emissor, público, expiração e vínculo local ativo. Usuário inexistente/desabilitado não ganha acesso por possuir token válido. Integrações e jobs usam identidades de serviço com escopos mínimos. Segredos somente no servidor/secret manager.

## Consistência e atualização

PostgreSQL é fonte de verdade. Pedido/reserva, cumprimento/CMV e recebimento/alocação são transações atômicas com idempotência. Outbox transacional publica eventos após commit. SSE/websocket autenticado invalida consultas por entidade, não transmite dashboard inteiro a toda a empresa. Clientes fazem fallback por polling quando não houver canal.

Notificação é posterior à confirmação, não substitui persistência. Não alegar 'tempo real' sem medir latência e reconexão. Alvo inicial mensurável: mudança confirmada refletida no app autorizado em até 5 s no ambiente de homologação, com p95 e condições documentadas.

## Implantação

O produto real pode usar infraestrutura dedicada compatível com Java/PostgreSQL, com HTTPS, domínio e backups. A hospedagem da demonstração estática atual não implica capacidade de executar JVM, PostgreSQL ou publicar aplicativos nativos. Escolher destino antes de escrever automação final. Não embutir credenciais de uma hospedagem no ZIP.

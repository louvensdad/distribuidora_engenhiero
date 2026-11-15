# Dados, histórico e eventos

## Convenções

IDs UUID/UUIDv7 gerados no servidor, nunca confiar em sequencial para isolamento. Número legível de pedido pode ser sequencial por unidade e coexistir com UUID. `organization_id`, `branch_id`, `created_at`, `created_by`, `updated_at`, `version` nas entidades aplicáveis. `deleted_at` somente para arquivos/cadastros arquiváveis, não para apagar livro financeiro.

Entidades e restrições estão em `modelo-dados.csv`. Especificação lógica inicial, não migração pronta. Transformar em migrations versionadas e exercitar criação do zero e atualização incremental.

## Núcleo de relações

Organização contém unidades e vínculos de pessoas. Pessoa é separada da identidade externa. Pedido contém itens, reservas, cumprimentos, pagamentos alocados e documentos fiscais. Viagem contém atribuições de pedidos e veículo/motorista. Despesa pode apontar para veículo/viagem e comprovante. Guia, folha e nota fiscal têm aprovações próprias e vínculo financeiro.

## Eventos e trilha

Eventos de domínio com ID, versão, horário, organização, unidade, ator humano/serviço, correlação e entidade. Eventos não carregam senhas, certificado fiscal ou dados pessoais completos. Outbox com tentativas e estado de publicação; consumidor idempotente por event_id. Ordenação por agregado/version, não confiar apenas no relógio do telefone.

AuditEvent: ator real; identidade de serviço quando houver; ação; entidade/ID; horário do servidor; versão anterior/nova; campos alterados permitidos; motivo; request_id; resultado; origem web/app/integração. Registrar tentativas negadas relevantes sem copiar token ou corpo sensível. IP/dispositivo só com necessidade e retenção definidas.

Imutabilidade de auditoria: conta de aplicação não atualiza/apaga eventos; restringir privilégios e encaminhar cópia para armazenamento de retenção apropriado. Uma tabela com botão desabilitado não é prova de trilha inviolável. Hash encadeado pode apoiar detecção, mas não substitui controle operacional.

## Anexos

Metadados no banco e bytes em objeto privado. Upload com tamanho/tipo limitados, checksum, validação real de MIME, quarentena e análise de conteúdo conforme risco. URL temporária curta, gerada depois da autorização do registro. Sem bucket público ou URL permanente compartilhada em notificações. Download e exportação de documentos sensíveis entram no histórico.

Trocar um comprovante mantém versão anterior quando a política exige, com motivo e autor. Não permitir HTML/SVG ativo arbitrário de usuário como documento executável. Ícones SVG distribuídos pelo produto são assets controlados, não anexos livres.

## Livro financeiro gerencial

Propor lançamentos balanceados por diário para caixa, recebíveis, estoque, CMV, despesas e obrigações, ou um subledger equivalente com invariantes comprováveis. Cada evento financeiro gera um lançamento único; `source_type + source_id + event_kind` possui unicidade. Conta de reversão aponta à origem. Consultas de relatório e telas de detalhe derivam da mesma base.

Relatórios fechados guardam versão da política, filtros, data de corte, IDs/versão dos dados ou snapshot auditável. O usuário consegue distinguir resultado ao vivo de snapshot fechado. Reabertura exige alçada e novo fechamento.

## Dados mínimos e descarte

Guardar apenas o necessário a operação/fiscal. Política de retenção definida com contador e responsável por dados na F00/F16, por classe documental; não inventar prazo legal único. Pedido de acesso/correção e anonimização preserva obrigações documentais e relações financeiras. Nunca expor folha em logs, rastreamento ou prompt do modelo.

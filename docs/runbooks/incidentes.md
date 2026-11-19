# Runbook — incidentes e indisponibilidade de serviços

Comportamento verificado em homologação (19/11/2026): `docs/evidence/F16-ensaio-falhas.txt`. Regra geral: nenhum
serviço fora vira "sucesso"; o usuário recebe erro claro, nada fica gravado pela metade, e o sistema volta sozinho
quando o serviço volta.

| Falha | O que o usuário vê | O que o sistema garante | Ação |
|---|---|---|---|
| **IdP (login) fora** | Quem já está logado continua; login novo falha com página de indisponibilidade (502/504 da borda) | Nenhum acesso sem login; nada é liberado por falta do IdP | Restabelecer o Keycloak (contêiner/banco do IdP); sem reiniciar API nem site |
| **Banco fora** | "Serviço temporariamente indisponível (banco de dados). Nada foi gravado" (503) em segundos | Nenhuma transação parcial; instância sai de rota (readiness DOWN) sem ser reiniciada | Restabelecer o PostgreSQL; a API se reconecta sozinha e as sessões continuam |
| **Armazenamento de documentos** | Envio recusado: "Armazenamento de documentos indisponível. Nada foi gravado" (503) | Nenhum documento registrado sem arquivo | Corrigir volume/permissão/disco; arquivos já gravados voltam a ser baixáveis |
| **Fiscal (provedor/SEFAZ)** | Nota "em transmissão"/"incerta", nunca "emitida" sem protocolo | Sem emissão duplicada; consulta periódica (uma instância por vez) | Ver integração; sem provedor contratado = NÃO CONFIGURADO (D02/D03) |
| **Pagamentos (Pix/adquirente)** | Pagamento pendente até confirmação | Sem baixa sem confirmação | NÃO CONFIGURADO até D04 |
| **IA (LLM)** | "O provedor de IA não respondeu" ou NÃO CONFIGURADA | Nenhum dado alterado; reserva de orçamento devolvida | Ver chave/orçamento; nada a restaurar |
| **Sem internet na loja** | Apps mostram SEM CONEXÃO; nada é confirmado localmente | Pedido/pagamento não aparecem como confirmados sem o servidor | Restabelecer conexão; reenviar (chaves idempotentes) |

## Diagnóstico

- Cada resposta traz `X-Request-Id`; o log de acesso (JSON) tem `request_id`, rota sem query string, status, latência,
  app e ator pseudonimizado (HMAC). Procurar pelo `request_id` informado pelo usuário.
- Logs nunca contêm token, senha, CPF/CNPJ, e-mail, corpo de requisição ou valores de linha do banco (sanitizados).
- Saúde: `/actuator/health/readiness` (inclui banco) e `/actuator/health/liveness` (não depende do banco);
  `/actuator/info` mostra o commit em execução.

## Vigia e alertas

- `engenheiro-vigia.timer` roda `infra/operacao/vigia.sh` a cada 5 min: site e IdP pela borda TLS, cada instância da
  API saudável, último backup OK com menos de 26 h e disco com ao menos 10% livre. Qualquer falha → `OnFailure` →
  `engenheiro-alerta@` (o mesmo alerta do backup), com o nome da unidade e o horário.
- Instalação: copiar as unidades de `infra/operacao/systemd/` para `/etc/systemd/system/`, criar
  `/etc/engenheiro/vigia.env` (`VIGIA_DOMINIO`, `VIGIA_PORTA`, `VIGIA_PROJETO`, `BACKUP_DESTINO`) e
  `/etc/engenheiro/alerta.env` (`ALERTA_COMANDO` = comando que recebe a mensagem e a envia ao responsável);
  `systemctl enable --now engenheiro-vigia.timer engenheiro-backup.timer`.
- Ao receber o alerta: `journalctl -u engenheiro-vigia -n 20` mostra qual item falhou (✗) e seguir a linha da tabela
  acima. Sem `ALERTA_COMANDO` o alerta fica só no journal como "alerta externo NÃO CONFIGURADO".
- Integrações externas (fiscal, IA) não derrubam o vigia: falham de forma visível nas telas (pendente/incerta/NÃO
  CONFIGURADA) e no log com `request_id`.

## Acesso de pessoas

- **Desligamento/afastamento:** desativar o vínculo no sistema (Equipe → Pessoas) e a conta no IdP. A sessão do site
  deixa de valer na próxima requisição; tokens de app expiram no tempo de vida do realm (curto).
- **Perda de celular:** desativar a conta no IdP e revogar as sessões dela (Keycloak → usuário → sessões); TOTP do
  dono: recadastrar (`required action` CONFIGURE_TOTP).
- **Último administrador:** o sistema não permite remover o último administrador ativo.

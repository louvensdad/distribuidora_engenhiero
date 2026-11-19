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

## Acesso de pessoas

- **Desligamento/afastamento:** desativar o vínculo no sistema (Equipe → Pessoas) e a conta no IdP. A sessão do site
  deixa de valer na próxima requisição; tokens de app expiram no tempo de vida do realm (curto).
- **Perda de celular:** desativar a conta no IdP e revogar as sessões dela (Keycloak → usuário → sessões); TOTP do
  dono: recadastrar (`required action` CONFIGURE_TOTP).
- **Último administrador:** o sistema não permite remover o último administrador ativo.

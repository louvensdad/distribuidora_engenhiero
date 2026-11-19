# Pendências objetivas para a entrega (F17)

Atualizado em 19/11/2026. Cada item diz **quem** resolve, **o que exatamente** falta e **o que já está pronto** para
quando chegar. Nada aqui aparece como concluído no termo de aceite.

## A. Insumos do titular (bloqueiam a produção)

| # | Pendência | Insumo exato | Já pronto | Decisão |
|---|---|---|---|---|
| A1 | Servidor de produção | Destino (VPS/nuvem) com Docker, domínio próprio e certificado TLS público | Roteiros `construir.sh`/`implantar.sh`, borda TLS, guarda de produção, runbooks | D09 |
| A2 | Cofre de segredos | Onde ficam senha do banco, segredos do IdP/provisionamento, pepper de log e senha do backup | Todos os segredos já entram só por variável de ambiente | D09 |
| A3 | Cópia do backup em outro local | Bucket/armazenamento com versionamento + comando em `BACKUP_COPIA_REMOTA` | Backup diário cifrado, retenção 14, restauração ensaiada | D09 |
| A4 | Chave de assinatura Android do titular | Conta Google Play do titular (Play App Signing) e chave de envio | Release recusa build sem chave; script dos 4 apps com verificação e manifesto | D07 |
| A5 | Envio de alertas | Comando que entrega a mensagem ao responsável (e-mail, WhatsApp ou telefone) em `ALERTA_COMANDO` | Vigia a cada 5 min e alerta de backup ensaiados (hoje só no journal) | — |
| A6 | Contas reais das pessoas | Nome, função e usuário de cada funcionário; e-mail é opcional | O dono cadastra e dá acesso pelo próprio site (ensaiado em homologação) | D05/D06 |
| A7 | iOS | Mac com Xcode + conta Apple do titular | Código compartilhado; nada iOS foi produzido nem é declarado entregue | D07 |

## B. Integrações (o sistema funciona sem elas, com estado NÃO CONFIGURADO)

| # | Pendência | Insumo exato | Comportamento até lá | Decisão |
|---|---|---|---|---|
| B1 | Nota fiscal real | CNPJ/regime, provedor fiscal contratado, certificado A1, sandbox | Nota fica bloqueada/pendente; nunca "emitida" sem protocolo | D02/D03 |
| B2 | Pix/cartão/banco automáticos | Adquirente/PSP com webhook assinado; agregador Open Finance | Confirmação manual auditada por quem tem permissão | D04 |
| B3 | IA da gestão real | `ANTHROPIC_API_KEY` no servidor e orçamento mensal (`ENGENHEIRO_IA_ORCAMENTO_USD`) | Tela mostra NÃO CONFIGURADA; nada enviado nem simulado | D13 |

## C. Validações com o dono e o contador (não bloqueiam o piloto)

| # | Pendência | Com quem |
|---|---|---|
| C1 | Aprovar metas de recuperação RPO 24 h / RTO 4 h (medido: 40,9 s) | Dono |
| C2 | Cargos e campos de RH, férias, folha por rubricas e obrigações (D10) | Contador |
| C3 | Política de custo médio e reconhecimento por item entregue (D12) | Dono + contador |
| C4 | Retenção de documentos e dados pessoais (D11) | Dono + contador |
| C5 | Quem pode receber, dar desconto, cancelar e vender a prazo, com alçadas (D05/D15) | Dono |
| C6 | Unidades de venda e conversões reais por produto (D01) | Dono |
| C7 | Critérios de metas e desempenho (D14) | Dono |

## D. Do piloto (só acontece com uso real)

| # | Pendência | Critério para encerrar |
|---|---|---|
| D1 | Roteiro do cliente nos aparelhos da loja, com as pessoas reais (UAT-01) | Cada pessoa completa a sua parte do roteiro em `docs/entrega/GUIAS_POR_PERFIL.md` no próprio aparelho |
| D2 | Conciliação do período piloto (UAT-02) | Conferência do mês "tudo confere" (ou divergência explicada e corrigida) e contagem física do estoque igual à do sistema |
| D3 | Treinamento e entrega da administração (UAT-03) | Dono cria, altera e desliga uma conta sozinho; dono restaura o acesso de alguém que perdeu o celular |
| D4 | Leitor de tela real (U01) | Uma sessão com NVDA (site) e TalkBack (app) nas telas principais |
| D5 | Carga no servidor de produção | Repetir `carga_teste.py` no destino real antes de abrir o uso |

## Achados do ensaio já corrigidos (para registro)

- Console e API de administração do IdP expostos pela borda pública: bloqueados (404); provisionamento pela rede
  interna (`cdde647`).
- "Dar acesso" falhava para nome com parênteses (IdP recusava o nome): nome do IdP limpo (`a79ef78`).
- E-mail obrigatório no perfil do IdP prendia o primeiro acesso de quem não tem e-mail: opcional
  (`definir-provisionamento.sh`).
- APK de release sem conexão com a API de homologação (cliente HTTP do Dart): corrigido na F16 (`2f93a51`).

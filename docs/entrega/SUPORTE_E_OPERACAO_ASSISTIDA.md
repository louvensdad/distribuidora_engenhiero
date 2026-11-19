# Suporte e operação assistida

Proposta para o piloto, a aprovar pelo dono. Prazos são de projeto, não promessa contratual.

## 1. Como pedir ajuda

Ao chamar, informe: **quem** (nome e função), **qual app ou tela**, **o que tentava fazer**, **horário** e, se a tela
mostrar, o **Código para suporte** que aparece no aviso de erro. Com isso o suporte acha o registro exato sem
pedir senha nem print de dado pessoal.

| Gravidade | Exemplo | Primeira resposta | Contorno |
|---|---|---|---|
| **1 — parado** | Ninguém entra; site ou apps fora do ar; caixa não abre | 30 min no horário da loja | Anotar vendas em papel numerado e lançar depois; **não** confirmar pagamento sem o sistema |
| **2 — função travada** | Uma função não grava; conferência do mês divergente; nota pendente | 4 h úteis | Seguir com o resto; o item fica pendente, nunca "concluído" |
| **3 — dúvida/ajuste** | Como fazer; texto confuso; pedido de melhoria | 2 dias úteis | — |

O vigia (`infra/operacao/vigia.sh`, a cada 5 min) já alerta site, IdP, API, backup e disco; quando o comando de
envio estiver definido (pendência A5), o alerta chega ao responsável antes de alguém ligar.

## 2. Operação assistida (primeiras 4 semanas do piloto)

| Quando | O que o suporte faz | Onde |
|---|---|---|
| Diário, fim do dia | Conferência do mês com a operação (deve dizer "tudo confere"); caixas do dia fechados e conferidos; vigia sem falha nas últimas 24 h; último backup OK | Site → Resultado e fechamento; `journalctl -u engenheiro-vigia`; `ULTIMO_BACKUP.json` |
| Diário | Ler o Histórico dos três setores atrás de `access.denied` repetido (alguém tentando o que não pode ou acesso mal liberado) | Site → Equipe → Histórico |
| Semanal | Contagem física de uma amostra do estoque × sistema; revisar despesas e perdas pendentes de aprovação | Operação → Estoque; Visão geral → Aprovações |
| Semanal | Restauração de teste do backup mais recente em ambiente separado (RTO medido) | `docs/runbooks/backup-e-restauracao.md` |
| Fim do mês | Fechar o mês só com conferência "tudo confere"; registrar o resultado no termo de aceite | Site → Resultado e fechamento → Fechar mês |

**Critério para sair da operação assistida:** 2 semanas seguidas sem incidente de gravidade 1, conferência do mês
"tudo confere" todos os dias úteis, contagem de estoque batendo e o dono tendo feito sozinho: dar acesso, bloquear
alguém, aprovar e pagar uma guia e fechar o caixa conferido.

## 3. Rotinas do dono (sem suporte)

- **Novo funcionário:** Equipe → Funcionários → Novo → Acesso aos apps → Criar conta e dar acesso; Mapa de acessos
  para o que for além do modelo. (Guia do dono em `GUIAS_POR_PERFIL.md`.)
- **Saída de funcionário ou celular perdido:** Equipe → Pessoas → bloquear. Vale na próxima ação.
- **Fechamento do mês:** conferência "tudo confere" → Fechar mês. Reabrir exige pedido e aprovação registrados.

## 4. Rotinas técnicas (responsável técnico)

Todas em `docs/runbooks/`: implantação e rollback (`implantacao-e-rollback.md`), backup e restauração
(`backup-e-restauracao.md`), incidentes e indisponibilidade (`incidentes.md`). Administração do IdP só por dentro do
servidor (`kcadm`); o console não fica exposto na internet.

## 5. Atualização dos apps

Cada versão sai de um commit limpo, assinada e com checksum (`INVENTARIO.md`). Instalar por cima mantém a sessão; um
acesso revogado não volta pelo cache. Antes de publicar na loja é preciso a conta e a chave do titular (pendência A4).

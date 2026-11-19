# Termo de aceite funcional — Distribuidora de Cana Engenheiro Ltda

**Situação deste termo: MINUTA para o piloto.** Só vale depois do período piloto, com os itens marcados pelo titular.
Itens bloqueados por insumo externo aparecem separados e **não** fazem parte do que está sendo aceito.

- Sistema: um site (gestão do dono) e quatro aplicativos Android: Patrão (`br.com.engenheiro.gestao`), Retirada
  (`br.com.engenheiro.retirada`), Entregas (`br.com.engenheiro.entregas`) e Contábil (`br.com.engenheiro.contabil`).
- Versão: ____________ (commit e checksums em `docs/entrega/INVENTARIO.md`)
- Período piloto: de ____/____/______ a ____/____/______
- Ambiente: ( ) homologação ( ) produção em ____________________

## 1. O que foi verificado no período piloto

Marque **Sim** só se viu funcionar com dados da loja. **Não** exige anotar o motivo em "Observações".

| # | Critério | Sim | Não |
|---|---|---|---|
| 1 | O dono acompanha, no site e no app Patrão, cada movimento dos apps Retirada, Entregas e Contábil, com o nome de quem fez e o app de origem | ☐ | ☐ |
| 2 | Cada pessoa entra com a própria conta, no próprio app, e não vê nem faz o que não é da sua função (custo, lucro, folha, viagem alheia) | ☐ | ☐ |
| 3 | O contador envia guias e folha, vê a decisão do dono e não aprova nem paga | ☐ | ☐ |
| 4 | Pedido só vira venda quando entregue; pagamento, reserva e entrega aparecem separados | ☐ | ☐ |
| 5 | Troco, pagamento dividido e taxa de cartão estão certos no caixa e no relatório | ☐ | ☐ |
| 6 | O caixa de cada turno fecha com o dinheiro contado (ou a diferença fica registrada e justificada) | ☐ | ☐ |
| 7 | A conferência do mês com a operação (Resultado e fechamento → Conferência do mês) mostrou "tudo confere" ou cada divergência foi explicada | ☐ | ☐ |
| 8 | A contagem física do estoque bateu com o sistema | ☐ | ☐ |
| 9 | O relatório do mês (receita, CMV, despesas, perdas, lucro) bate com a conferência e com o que o dono esperava | ☐ | ☐ |
| 10 | O dono criou, alterou e desligou uma conta sozinho | ☐ | ☐ |
| 11 | Sem internet, o app não confirmou nada sozinho; com a conexão de volta, nada duplicou | ☐ | ☐ |
| 12 | O dono recebeu os guias por perfil, o contato de suporte, os runbooks e a lista de pendências | ☐ | ☐ |

## 2. Fora deste aceite (dependem de insumo externo — continuam pendentes)

Estes itens **não** estão sendo aceitos como concluídos. No sistema eles aparecem como NÃO CONFIGURADO, pendente ou
bloqueado, e nunca como sucesso:

- Emissão de nota fiscal real (provedor, certificado e regime — D02/D03).
- Confirmação automática de Pix, cartão e extrato bancário (D04); até lá, conferência manual registrada.
- IA da gestão com provedor real (chave e orçamento — D13).
- Publicação nas lojas e assinatura com a chave do titular (D07); iOS (sem Mac).
- Produção com cofre de segredos, cópia de backup em outro local e alerta enviado ao responsável (D09).

## 3. Pendências aceitas para depois do piloto

Lista objetiva em `docs/aceite/PENDENCIAS.md`. Anote aqui as que o titular aceita conviver no início da operação:

____________________________________________________________________________________________________

## 4. Observações

____________________________________________________________________________________________________

## 5. Assinaturas

| Papel | Nome | Data | Assinatura |
|---|---|---|---|
| Titular (dono) | | | |
| Contador | | | |
| Responsável técnico | | | |

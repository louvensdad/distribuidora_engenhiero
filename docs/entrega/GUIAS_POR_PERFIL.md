# Guias por perfil — treinamento do piloto

Um guia curto por função, para o treinamento presencial e para consulta no balcão. Cada guia tem três partes: como
entrar, o roteiro do dia (é o mesmo roteiro do aceite, UAT-01) e o que fazer quando algo dá errado. Nomes de telas
iguais aos da tela.

**Regra para todos:** a conta é pessoal. Não empreste senha nem celular desbloqueado. Tudo o que você faz fica
registrado com o seu nome e o app de onde fez. Se a tela disser **SEM CONEXÃO**, nada foi confirmado: espere voltar e
toque em tentar de novo (o sistema não duplica).

---

## Dono (site + app Patrão)

**Entrar:** site no endereço da empresa, ou app **Engenheiro Patrão**. Senha + código do autenticador (TOTP) no
celular do dono.

**Entregar acesso a um funcionário (feito uma vez por pessoa):**
1. Site → **Equipe → Cargos**: confira os cargos (Balconista, Recebedor, Coordenador de entregas, Motorista,
   Contador).
2. **Equipe → Funcionários → Novo**: nome, cargo, unidade e admissão. Salve.
3. No funcionário, **Acesso aos apps → Criar conta e dar acesso**: escolha o usuário (ex.: `paula.balcao`) e o
   modelo de acesso. Aparece **uma senha temporária só nessa tela**: entregue em mãos. No primeiro acesso a pessoa
   troca a senha.
4. **Equipe → Mapa de acessos**: libere o que a função precisa além do modelo. Exemplos usados no ensaio:
   balconista recebe e fecha o próprio caixa e entrega o que vendeu; motorista recebe na entrega e presta contas da
   viagem; coordenador atribui viagens e cadastra veículos. Sempre com motivo.

**Roteiro do dia:**
1. **Visão geral → Setores**: o que Retirada, Entregas e Contabilidade fizeram, com nome e app de cada um.
2. **Visão geral → Aprovações**: guias, folha, despesas e perdas esperando decisão. **Aprovar não paga.**
3. **Operação → Recebimentos**: confira o custo das entradas registradas pelo recebedor.
4. **Financeiro → Guias**: pague a guia aprovada escolhendo a conta.
5. **Financeiro → Resultado e fechamento**: o resultado do mês e a **Conferência do mês com a operação** (entregas,
   estoque, caixas e livro). "Tudo confere" é o esperado; uma divergência mostra onde olhar.
6. App Patrão: **Início** (o mesmo resultado), **Central** (os três setores) e **Relatórios → Conferência do mês com a
   operação**.

**Quando algo dá errado:**
- *Funcionário saiu ou perdeu o celular:* **Equipe → Pessoas** → bloquear. A próxima ação dele já é recusada.
- *Funcionário esqueceu a senha:* peça ao suporte a redefinição no provedor de identidade (gera nova senha
  temporária); o acesso e o histórico continuam os mesmos.
- *Divergência na conferência do mês:* abra a linha divergente; ela diz o que foi comparado (ex.: dinheiro confirmado
  × entradas nos caixas). Chame o suporte com o horário e o código da tela.

---

## Balconista (app Retirada)

**Entrar:** app **Engenheiro Retirada**, usuário e senha que o dono entregou; troque a senha no primeiro acesso.

**Roteiro do dia:**
1. **Caixa → Abrir caixa** com o troco inicial contado.
2. **Atendimento → Novo pedido**: cliente, produto e quantidade (o preço vem do sistema).
3. **Registrar pagamento**: dinheiro (informe quanto o cliente entregou; o troco é calculado), Pix (anexar comprovante
   **não** confirma: quem confere confirma) ou cartão. Pode dividir entre meios; a soma tem de fechar.
4. **Registrar retirada**: só depois de entregar a mercadoria. Entrega parcial é permitida; o resto fica pendente.
5. **Pedidos**: os seus pedidos por período e busca.
6. Fim do turno: **Caixa → Fechar e enviar para conferência** contando o dinheiro. Se não bater, escreva a
   justificativa.

**Você não vê:** custo de compra, lucro, relatórios da empresa, folha. Isso é normal.

---

## Recebedor (app Retirada)

**Roteiro do dia:**
1. **Fornecedores → Receber mercadoria**: fornecedor, número do documento e a quantidade **que chegou** (e a
   esperada); **Registrar recebimento**.
2. Fotografe/anexe o documento quando houver.
3. O custo é conferido pelo dono no site; você não vê custo.

---

## Coordenador de entregas (app Entregas)

**Roteiro do dia:**
1. **Veículos → Novo veículo**: placa, capacidade e odômetro.
2. **Pedidos → Novo pedido de entrega**: cliente, endereço e forma de pagamento combinada.
3. **Viagens → Criar e atribuir viagem**: veículo, motorista e pedidos. O motorista só vê a viagem dele.
4. Acompanhe entregas, ocorrências e a volta da viagem.

---

## Motorista (app Entregas)

**Roteiro do dia:**
1. **Caixa → Abrir caixa** (troco, se houver).
2. **Viagens**: abra a sua viagem → **Registrar saída** informando o odômetro.
3. Em cada parada: **Confirmar entrega** com o nome de quem recebeu; receba o que está combinado. Pedido já pago
   aparece como **não cobrar**.
4. Problema na entrega: **Ocorrência** (cliente ausente, recusa, avaria) com foto.
5. **Registrar abastecimento** e **Registrar gasto** da viagem, com o comprovante.
6. Na volta: odômetro final e **Enviar prestação**.

**Você não vê:** pedidos de outros motoristas, relatórios, custos da empresa.

---

## Contador (app Contábil)

**Roteiro do mês:**
1. **Agenda**: vencimentos e pendências.
2. **Guias → Preparar guia**: tipo (DAS, ICMS, INSS…), competência, vencimento e valor; **Anexar arquivo da guia**;
   **Enviar ao patrão**.
3. Acompanhe a decisão: aprovada (ainda não paga), devolvida (**Corrigir guia** e reenviar) ou paga.
4. **Folha**: importe por rubricas; o dono aprova e paga.
5. **Documentos**: pedidos de documentos e versões.

**Você não vê:** resultado da empresa, saldos bancários, dados individuais fora das rubricas autorizadas. Você não
aprova nem paga.

---

## Primeiro acesso (todos os apps)

1. Abra o app e toque em **Entrar com segurança**. Abre a página de login da empresa.
2. Usuário e senha temporária → o sistema pede uma senha nova (duas vezes).
3. Pronto: o app volta sozinho. Na próxima vez, só usuário e senha.
4. Na homologação (teste), o aparelho precisa ter o certificado de teste instalado — o técnico faz isso. Em produção,
   não.
5. Celular ou tablet novo: na primeira vez o navegador (Chrome) pode abrir a própria configuração antes do login —
   escolha **Usar sem conta** e toque de novo em **Entrar com segurança**.
6. Tablet deitado: o menu fica na lateral; em pé, na parte de baixo. iPhone/iPad: mesmo app, quando publicado na
   App Store pela conta do titular.

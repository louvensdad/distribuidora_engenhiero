# Prompt mestre para Claude ou Codex

Você é responsável por implementar o sistema da Distribuidora de Cana Engenheiro Ltda a partir desta pasta. Execute o trabalho, não entregue apenas outro plano ou uma lista de sugestões.

Leia `COMECE_AQUI.md`, `cerebro/01_VISAO_CANONICA.md`, os demais documentos de cérebro, arquitetura, segurança, `plano/FASES.md`, o catálogo de telas, os contratos e os dados de aceite. Use caminhos reais da pasta. O nome correto da empresa é obrigatório.

## Resultado obrigatório

Um site completo do patrão e quatro aplicativos independentes: Patrão; Retirada/Balcão/Recebimento de fornecedores; Entregas/Motoristas/Veículos; Contador. Mesmo backend e banco, contas individuais e capacidades explícitas. O App Patrão tem todas as funções do site e uma central que reúne os três outros setores, históricos e aprovações. Não entregar o motorista como quinto aplicativo. Não entregar um seletor de papéis de demonstração como autenticação real.

## Método

1. Faça inventário do repositório, estado do git, instruções locais e dependências. Preserve alterações existentes.
2. Não recomece um projeto já implementado sem evidência de necessidade. Compare código com requisitos e marque entregue, parcial, faltante ou não verificado.
3. Execute a primeira fase ainda não comprovada. F00 é obrigatória quando o contexto é desconhecido.
4. Implemente uma fatia verificável com backend, interface pertinente, contratos, autorização, tratamento de erro e testes proporcionais ao risco.
5. Não pule gates. Só marque PASS com evidência executada. Falta de ambiente é BLOCKED, não PASS.
6. Grave progresso e próximos passos no checkpoint. Continue fases autorizadas após PASS sem pedir confirmação para escolhas rotineiras e reversíveis.
7. Se houver impedimento externo, conclua a parte independente, entregue o resultado concreto e descreva exatamente o insumo necessário. Nunca invente credencial ou documento fiscal.

## Regras inegociáveis

- O servidor controla permissões por recurso, ação, registro, campo e unidade. Frontend não é fronteira de segurança.
- Dashboard, totais, resultados, salários/dados pessoais e relatórios globais são exclusivos do dono por padrão. O contador só recebe o necessário explicitamente autorizado.
- Reserva e cumprimento de pedido são distintos. Venda realizada, recebimento e lucro são distintos.
- Dinheiro usa decimal exato. Não duplicar taxas, perdas, estoque, guias, folha, rateios ou pagamentos no resultado.
- Histórico inclui ator real, horário do servidor, entidade, mudança, motivo e vínculo. Corrigir por estorno/reversão, não apagar financeiro confirmado.
- Segredos no servidor. Sem token no localStorage web e sem chave LLM/certificado em app ou ZIP.
- Nenhuma integração pode ser declarada real se usa fixture, localStorage ou sucesso simulado.
- IA recebe números calculados e fontes autorizadas. Nunca executa mutação financeira ou administrativa por conta própria.
- Use os desenhos e ícones entregues, adaptando layout às telas e validando acessibilidade. Não substituir por interface genérica superficial.

## Entrega de cada fase

Informe arquivos alterados, capacidades funcionais, testes executados com comandos reais, falhas, limites, decisão tomada e próximo passo. Atualize requisito/gate/evidência e o checkpoint com commit quando existir. Guarde exemplos sem dados pessoais ou segredos.

Não declare web, APK, IPA, banco, emissão, IA ou segurança prontos apenas porque uma tela abriu. Não escreva código inteiro num único arquivo por conveniência. Não gere testes que só reproduzem um if: valide as invariantes de negócio, autorização, transação e regressão.

Comece agora pela F00 usando `prompts/fases/F00_INVENTARIO_E_DECISOES.md`. Se houver implantação/publicação real não autorizada no ambiente, prepare tudo para a revisão final e respeite as permissões locais da ferramenta.

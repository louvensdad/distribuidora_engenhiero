# Segurança, IA e funcionamento dos aplicativos

## Segurança verificável

Autorização negativa por padrão e filtro de linha no servidor. Testar IDOR/BOLA: pessoa de outro setor/empresa/unidade, outro pedido, outro documento, outro veículo e outra viagem. Não confiar em filtros enviados pelo aplicativo. Requisição deve derivar a identidade do token/sessão, não de `userId` no corpo.

Revalidar acesso em exportação assíncrona e no download. Revogar acesso deve encerrar canais e impedir consultas futuras. Assinatura de webhook, replay e correlação são verificados. Proteções para CSRF, XSS, injeção SQL, upload inseguro e força bruta em endpoints sensíveis. Não usar CORS aberto com credenciais.

Conta do dono com autenticação reforçada e recuperação documentada. Funcionários não podem se promover. Último administrador ativo não pode ser removido por acidente. Limites de desconto, estorno e aprovação são políticas, não ifs espalhados na interface. Ato de aprovação não mascara o ator.

## IA integrada ao App Patrão e à web

Criar adaptador de LLM no backend com fornecedor/modelo configuráveis, chave exclusivamente no secret manager, limites por usuário e orçamento. Nada de chave no Flutter, JavaScript, histórico ou ZIP. Sem chave válida: mostrar INTEGRAÇÃO NÃO CONFIGURADA. Modo demo deve ter rótulo explícito e separado.

Ferramentas iniciais somente leitura: consultar_resumo, listar_despesas_por_categoria, detalhar_veiculo, consultar_pendencias_contador, consultar_estoque_perdas, comparar_periodos e consultar_meta. Todas recebem intervalo e filtros tipados, mas organização/usuário vêm do contexto autenticado. O servidor aplica permissões antes de coletar dados. Não liberar SQL arbitrário nem acesso direto a bucket ao modelo.

Valores monetários são calculados pelo sistema e passados em JSON. O modelo explica o resultado, sem recalcular ou inventar número. Cada resposta mostra período, filtros, horário de corte, fontes e links autorizados para os lançamentos. Se dados forem insuficientes, dizer quais faltam. Registrar pergunta, ferramentas, custo/latência e resposta conforme política de privacidade, sem segredos.

Documentos e textos de terceiros são dados não confiáveis. Instruções num comprovante não podem mudar permissões, chamar endpoints administrativos ou pedir envio de dados. Redigir testes de prompt injection. Modelo não faz pagamentos, emite notas, altera estoque ou concede acessos. Uma sugestão pode abrir um formulário com valores pré-preenchidos; a ação final usa fluxo humano normal e autorização de servidor.

## Conexão, offline e concorrência

Operação online primeiro. Se offline for necessária, liberar rascunhos de pedido e fila limitada de ocorrências, nunca apresentar reserva, pagamento, aprovação ou emissão fiscal como confirmados localmente. Tela mostra SEM CONEXÃO, último sync e pendências.

Toda mutação reenviável tem chave idempotente persistida no cliente. No retorno, o servidor revalida estoque, preço, permissões e versão. Conflitos viram estado revisável; não escolher silenciosamente 'última edição vence' para dinheiro ou estoque. Separar `client_observed_at` de `server_recorded_at`.

Revogação com dispositivo offline: limpar dados locais na próxima reconexão/autorização e limitar cache sensível. Não prometer revogação instantânea de bytes já copiados offline. Dados pessoais de folha/bancos não devem ficar em cache offline por padrão.

SSE/websocket usa autenticação e tópicos filtrados. Eventos podem chegar repetidos ou fora de ordem; descartar versões antigas e reconsultar a entidade. Push no celular mostra apenas aviso mínimo, sem salário, CPF ou saldo na tela bloqueada.

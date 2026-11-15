# Estados e comportamentos de tela

## Estados compartilhados obrigatórios

Loading: skeleton de estrutura, nunca valores financeiros inventados como placeholder.
Vazio: explicar ausência de registros e oferecer ação autorizada.
Erro: mensagem útil, request_id, tentar novamente quando seguro; sem stack trace/segredo.
Sem permissão: sem carregar os dados antes de ocultar; caminho de volta claro.
Offline: mostrar último sync e rascunhos pendentes, sem confirmar operações de servidor.
Conflito: apresentar versão atual e motivo, recarregar/revisar; sem sobrescrever dinheiro/estoque silenciosamente.
Integração não configurada: rótulo e dependência faltante, sem imitar resultado real.

## Listas

Pesquisa e filtros preservados ao abrir/voltar, paginação no servidor, estados operacionais/financeiros distintos, datas locais e número legível. Ação por linha conforme capacidade. Total geral nunca aparece numa lista operacional por consequência acidental.

## Formulários

Labels persistentes, validação cliente+servidor, dinheiro decimal, máscara sem alterar significado, confirmação de ação irreversível quando aplicável. Desabilitar envio repetido e conservar chave idempotente. Erro por campo e resumo anunciado. Dados digitados preservados em falha recuperável.

## Detalhes e histórico

Origem do registro, responsável real, documentos e mudanças antes/depois autorizadas. Histórico geral é do patrão por padrão. Funcionário vê apenas histórico do próprio registro dentro do escopo autorizado. O dono consegue navegar da Central ao registro original sem assumir a conta do funcionário.

## Interação dos desenhos

Os SVG/PNG são especificações visuais estáticas, com dados fictícios. Na implementação, todos os botões ganham ação real, API e estados. Não marcar funcionalidade como entregue porque existe um botão no desenho.

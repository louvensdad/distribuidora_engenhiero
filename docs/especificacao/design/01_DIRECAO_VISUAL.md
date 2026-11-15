# Direção visual e componentes

## Identidade proposta

Nome obrigatório: Distribuidora de Cana Engenheiro Ltda. Nome curto de navegação: ENGENHEIRO. Marca E com seta e folha é uma proposta gráfica deste pacote, não uma afirmação de marca registrada aprovada. Não trocar o nome por Cana Nexus/Cana Gestão.

Paleta: fundo #09131C, superfície #11232F, superfície elevada #1A3040, borda #2A4354, texto #EDF5F7, texto secundário #A3BAC8, verde #B3F18A, ciano #67D7E8, violeta #A9B4FA, âmbar #E8BE75, erro #FA9A9A. Tokens completos em `tokens.json` e `tokens.css`.

Futurista sem ruído: abas horizontais com ícone vetorial, fundo em camadas, brilho discreto e linha ciano/violeta na aba ativa. Fundo com grade sutil somente em áreas amplas. Não usar neon em texto longo nem animação constante. Sem alertas/valores de negócio inventados só para encher tela.

Tipografia proposta: Manrope para títulos e DM Sans para corpo. Fazer distribuição local licenciada na implementação; não depender de fonte remota para o app abrir. Fallback system-ui. Tamanhos mínimos: corpo 14 web, 16 em campos móveis, auxiliar 12; moeda com números tabulares. Toque mínimo 44x44. Contraste, foco visível, leitor de tela e navegação por teclado fazem parte da entrega.

## Web

Cabeçalho amplo com marca, contexto/unidade, notificações e perfil. Abas Visão geral, Vendas, Operação, Financeiro, Relatórios, Inteligência e Equipe. Subabas aparecem no contexto, com seleção preservada ao abrir detalhes. Fluxos de cadastro e detalhe são páginas/painéis próprios, não uma landing page com links falsos.

Conteúdo com largura útil de até 1440/1600 px; gráficos lado a lado quando legíveis; formulário em duas colunas; tabela com cabeçalho claro e ações por linha. No tablet, reduzir colunas. No celular, quebrar sem sobreposição. Não esconder funções essenciais por viewport.

## Aplicativos

Patrão: Início, Central, Relatórios, IA e Mais. Aprovações ficam visíveis na Central e no Início. Central tem Retirada, Entregas e Contabilidade, com detalhe e histórico de cada registro.

Retirada: Atendimento, Pedidos, Fornecedores/Recebimentos, Caixa e Conta, conforme capacidade. Entregas: Viagem/Pedidos, Despacho quando autorizado, Veículos/Gastos e Conta. Contador: Agenda, Guias, Folha, Notas e Documentos. Botões e atalhos somem quando a capacidade falta; o servidor também nega acesso.

## Gráficos 3D

Colunas extrudadas com frente, topo e lateral; altura frontal representa valor em escala única. Dar profundidade visual sem esconder rótulos ou mudar proporção numérica. Mesmo conjunto de dados no gráfico, legenda, tabela e exportação. Evitar perspectiva que faça categoria pequena parecer maior. Não usar volume do cubo para representar dinheiro quando altura já representa o mesmo dado.

Rótulos diretos, unidades, período e totais obrigatórios. Oferecer tabela acessível e versão 2D para acessibilidade, impressão e baixa capacidade gráfica. Honrar redução de movimento. Renderizar fallback vetorial em vez de tela vazia se WebGL não existir. Não depender de biblioteca 3D sem licença e compatibilidade verificadas.

## Componentes e estados

AppShell, WebTabs, ContextTabs, CapabilityGuard, MoneyField, QuantityField, PermissionMatrix, EntityTimeline, AttachmentCard, ApprovalPanel, DeliveryStops, VehicleCard, ReportFilters, CategoryDetails, ReconciledSummary, ExtrudedBarChart, AIChatWithSources, EmptyState, LoadingState, ErrorState, OfflineBanner e ConflictResolver.

Todo componente de dados tem loading, vazio, erro, sem permissão e sucesso. Toda ação demorada bloqueia submissão duplicada, permite recuperação de erro e anuncia resultado ao leitor de tela. Tela de dinheiro não pode confirmar só com mudança de cor.

## Referências entregues

`telas/*.svg` são desenhos vetoriais editáveis com dados fictícios e limites de layout. PNGs auxiliam revisão sem editor vetorial. `GALERIA.html` abre todos os desenhos sem internet. Eles não comprovam implementação de formulários, API ou segurança. O catálogo de telas completa comportamentos que um desenho estático não mostra.

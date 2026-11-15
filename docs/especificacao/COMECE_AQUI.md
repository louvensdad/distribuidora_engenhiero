# Distribuidora de Cana Engenheiro Ltda
## Pacote completo de especificação e prompts de implementação

Versão 1.0 | 14/11/2026 | Português do Brasil | Horários: America/Sao_Paulo.

Este pacote é o cérebro do projeto: requisitos, regras, arquitetura, contratos iniciais, telas, ícones, dados de teste, fases e critérios de aceite. Não é o sistema de produção implementado nem contém APK, IPA, credenciais, certificado fiscal ou chave de IA.

### O produto final

1. Site de gestão completo para o patrão.
2. Aplicativo independente do patrão com as mesmas funções do site e uma central dos outros três aplicativos.
3. Aplicativo independente de retirada, balcão e recebimento de fornecedores.
4. Aplicativo independente de entregas, motoristas e veículos.
5. Aplicativo independente do contador.

São quatro aplicativos móveis, não quatro modos de um aplicativo de demonstração. Os quatro usam os mesmos serviços e banco. Cada pessoa entra com a própria conta. Não existe seletor público de função nem troca para a conta do patrão.

### Como mandar para Claude ou Codex

1. Extraia o ZIP numa pasta de trabalho e abra a pasta inteira no agente.
2. Se existir código atual, coloque-o num repositório separado e forneça o caminho. Não substitua o projeto existente sem inventário.
3. Cole primeiro `prompts/PROMPT_MESTRE.md`.
4. Execute `prompts/fases/F00_...md` e avance na ordem do `plano/FASES.md`.
5. Em cada fase, exija código, testes proporcionais ao risco, evidências e atualização do progresso. Uma promessa não fecha fase.
6. Se trocar de agente, use `prompts/PROMPT_RETOMADA.md` e envie os checkpoints, decisões e evidências existentes.

### Onde está cada coisa

| Pasta | Conteúdo |
|---|---|
| `cerebro` | Verdade do produto, requisitos e regras detalhadas |
| `arquitetura` | Stack proposta, dados, segurança, eventos e decisões |
| `contratos` | OpenAPI inicial, erros, permissões e eventos estruturados |
| `plano` | Fases, dependências, gates, cobertura e decisões em aberto |
| `prompts` | Prompt mestre, prompts de 18 fases e retomada |
| `design` | Tokens, especificação de telas e desenhos SVG/PNG |
| `assets` | Logo proposto, quatro ícones de aplicativo e ícones de interface |
| `testes` | Cenários de aceite e exemplos financeiros com resultados esperados |
| `operacao` | Implantação, backups, suporte e checklist de produção |
| `templates` | Modelos de evidência, ADR, checkpoint e variáveis de ambiente |
| `referencias` | Limites da demonstração atual e documentação oficial |
| `ferramentas` | Validação de arquivos e integridade do pacote |

Abra `design/GALERIA.html` no navegador para ver os desenhos. Eles são referências visuais editáveis, não telas já integradas ao servidor. As medidas e comportamentos finais estão no catálogo de telas.

### Autoridade dos documentos

`cerebro/01_VISAO_CANONICA.md` > regras de negócio > segurança > contratos > fases > desenhos. Havendo conflito, registre a divergência e aplique a regra mais restritiva para dados e dinheiro. Não reduza escopo silenciosamente. O código da antiga demonstração, se usado como referência, não tem autoridade sobre estas regras.

### Como considerar pronto

O sistema só é completo depois que a versão web e os quatro aplicativos autenticados passarem pelos fluxos compartilhados, permissões reais no servidor, relatórios reconciliados e integrações contratadas. IA simulada, dados locais, nomes de anexos e emissão fiscal fictícia devem permanecer identificados como demonstração até serem substituídos.

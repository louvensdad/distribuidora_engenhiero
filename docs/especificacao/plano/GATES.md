# Gates verificáveis

## Gate de entrada

Inventário e checkpoint lidos; dependências identificadas; alterações do usuário preservadas; requisitos e cenários da fase mapeados; ambiente e versões registrados. Lacuna de documento ou credencial não deve ser preenchida por suposição silenciosa.

## Gate de saída comum

1. Capacidade funciona com persistência real no ambiente pertinente, não só dados hardcoded.
2. Interface e API aplicam capacidades, escopo e campos. Testes negativos acompanham recurso sensível.
3. Contratos, migrations, tratamento de erro e recuperação são coerentes.
4. Testes proporcionais ao risco foram executados; evidências têm comando, commit e resultado observado.
5. Nenhum segredo/dado pessoal exposto. Sem demomode no caminho de produção.
6. Checkpoint, cobertura e decisões atualizados.

## Gates especiais

- Estoque: concorrência e replay não produzem saldo negativo ou baixa dupla.
- Financeiro: tela, gráfico, arquivo e somatório de linhas reconciliam a centavos.
- Acesso: ID conhecido não contorna autorização em API, anexo, exportação, websocket ou ferramenta IA.
- Fiscal: sandbox precisa retornar protocolo real do provedor para marcar integração PASS.
- Mobile: build precisa existir e abrir em dispositivo/simulador suportado; screenshot não substitui artefato.
- App Patrão: matriz de paridade web/app precisa estar integral ou justificar pendência explícita no gate, sem chamar a fase de completa.
- IA: ferramenta, fonte, período e número são verificáveis; ausência de chave aparece como não configurado.
- Release: restauração de banco+documentos, política de acesso, observabilidade e regressão ponta a ponta comprovadas.

## Relatório final de fase

Use `templates/EVIDENCIA.md` e `templates/CHECKPOINT.md`. Não fabricar logs. Um teste pulado ou infraestrutura ausente é limite explícito. Se o critério for obrigatório ao release, esse limite bloqueia o release.

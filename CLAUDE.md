# Instruções para agentes

Projeto: sistema da **Distribuidora de Cana Engenheiro Ltda**.

A especificação (cérebro, contratos, fases, design) está em `docs/especificacao/` e é a autoridade do produto
(`docs/especificacao/cerebro/01_VISAO_CANONICA.md` > regras > segurança > contratos > fases > desenhos).
Não edite a especificação para "fazer passar" código; registre divergências como ADR em `docs/adr/`.

- Progresso: `docs/CHECKPOINT.md` (atualizar ao fim de cada fase/fatia).
- Evidências: `docs/evidence/` (saídas reais de comandos, sem segredos).
- Decisões: `docs/adr/`.
- Mapa requisito → módulo → teste: `docs/MAPA_REQUISITOS.md`.

Regras: português do Brasil; dinheiro em decimal exato; servidor é autoridade de permissão (negar por padrão);
sem segredos no repositório; integração sem provedor = estado NÃO CONFIGURADO, nunca sucesso simulado.
Ao retomar, use `docs/especificacao/prompts/PROMPT_RETOMADA.md`.

# Roteiro de implementação

Estado inicial de todas as fases: NOT_STARTED. O pacote define a execução; não certifica código real.

Cada fase exige evidência para fechar. As fases visuais começam cedo e cada módulo deve entregar uma fatia funcional, não deixar toda a experiência para o final. F08 pode permanecer bloqueada por provedor, enquanto trabalhos independentes continuam; gates integrados de release dependem da solução dos bloqueios obrigatórios.

| Fase | Entrega | Depende de | Prompt |
|---|---|---|---|
| F00 | Inventário, escopo e decisões | Inventário | [F00](../prompts/fases/F00_INVENTARIO_E_DECISOES.md) |
| F01 | Fundação, identidade e autorização | F00 | [F01](../prompts/fases/F01_FUNDACAO_IDENTIDADE_E_ACESSO.md) |
| F02 | Design system e navegação | F01 | [F02](../prompts/fases/F02_DESIGN_SYSTEM_E_SHELLS.md) |
| F03 | Cadastros, fornecedores e estoque | F01, F02 | [F03](../prompts/fases/F03_CADASTROS_FORNECEDORES_ESTOQUE.md) |
| F04 | Pedidos, retirada, recebimentos e caixa | F03 | [F04](../prompts/fases/F04_PEDIDOS_RETIRADA_PAGAMENTOS_CAIXA.md) |
| F05 | Despacho, entregas, frota e viagens | F04 | [F05](../prompts/fases/F05_ENTREGAS_VEICULOS_E_VIAGENS.md) |
| F06 | Financeiro, competência e reconciliação | F04, F05 | [F06](../prompts/fases/F06_FINANCEIRO_E_RECONCILIACAO.md) |
| F07 | Contabilidade, folha e documentos privados | F06 | [F07](../prompts/fases/F07_CONTABILIDADE_FOLHA_E_DOCUMENTOS.md) |
| F08 | Integrações fiscais e de pagamentos | F07 | [F08](../prompts/fases/F08_INTEGRACOES_FISCAIS_E_PAGAMENTOS.md) |
| F09 | Relatórios completos, metas e gráficos 3D | F06, F07 | [F09](../prompts/fases/F09_RELATORIOS_METAS_E_GRAFICOS_3D.md) |
| F10 | Site integral do patrão | F02, F03, F04, F05, F07, F09 | [F10](../prompts/fases/F10_WEB_COMPLETA_DO_PATRAO.md) |
| F11 | Aplicativo de retirada, balcão e fornecedores | F03, F04, F10 | [F11](../prompts/fases/F11_APP_RETIRADA_FORNECEDORES.md) |
| F12 | Aplicativo de entregas, motoristas e carros | F05, F10 | [F12](../prompts/fases/F12_APP_ENTREGAS_VEICULOS.md) |
| F13 | Aplicativo independente do contador | F07, F08, F10 | [F13](../prompts/fases/F13_APP_CONTADOR.md) |
| F14 | Aplicativo do patrão com paridade integral | F09, F10, F11, F12, F13 | [F14](../prompts/fases/F14_APP_PATRAO_CENTRAL_COMPLETA.md) |
| F15 | IA real integrada ao site e App Patrão | F09, F14 | [F15](../prompts/fases/F15_IA_REAL_DE_GESTAO.md) |
| F16 | Segurança, desempenho e implantação | F08, F14, F15 | [F16](../prompts/fases/F16_ENDURECIMENTO_IMPLANTACAO.md) |
| F17 | Aceite, piloto e operação assistida | F16 | [F17](../prompts/fases/F17_ACEITE_PILOTO_E_ENTREGA.md) |

## Estados

NOT_STARTED: não iniciado. IN_PROGRESS: trabalho em curso. PASS: critérios executados e comprovados. FAIL: critério executado e falhou. BLOCKED: insumo/ambiente obrigatório ausente. PARTIAL pode ser usado no checkpoint para descrever cobertura, mas não equivale a PASS do gate.

## Caminho crítico

F00 -> F01 -> F02 -> F03 -> F04 -> F05/F06 -> F07 -> F08/F09 -> F10 -> F11/F12/F13 -> F14 -> F15 -> F16 -> F17.

Paralelismo de equipes só quando o responsável autorizar e contratos estiverem estáveis. Não é necessário usar múltiplos agentes para executar este pacote.

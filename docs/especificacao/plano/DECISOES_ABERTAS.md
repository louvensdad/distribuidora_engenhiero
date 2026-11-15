# Decisões a confirmar na F00 e antes das integrações

| ID | Decisão | Padrão proposto enquanto falta confirmação | O que bloqueia |
|---|---|---|---|
| D01 | Unidade de venda e conversões | Dúzia como exemplo, fatores por produto | Importação e operação real |
| D02 | CNPJ, regime, tipo de nota e obrigações | Não inferir a partir do nome | Fiscal/tributos reais |
| D03 | Contador/provedor fiscal/certificado | Adaptador com estado não configurado | Emissão real |
| D04 | Adquirente, Pix e banco | Confirmação manual auditada em piloto autorizado | Conciliação automática |
| D05 | Quem pode receber/descontar/cancelar | Negar salvo concessão do dono | Liberação de usuários reais |
| D06 | Cadastro e acesso do dono | OIDC e autenticação reforçada | Produção |
| D07 | Domínios e conta de publicação móvel | IDs propostos no documento de apps | Assinatura e lojas |
| D08 | Dispositivos e internet da loja | Online primeiro; rascunho offline | Certificação offline |
| D09 | Infraestrutura de backend | Java/PostgreSQL em destino compatível | Implantação produtiva |
| D10 | Folha e obrigações trabalhistas | Importação/validação com contador | Cálculo real |
| D11 | Retenção documental e acesso pessoal | Mínimo necessário, política a validar | Produção com dados pessoais |
| D12 | Política de custo e reconhecimento | Custo médio móvel e cumprimento por item | Fechamento real |
| D13 | Provedor/modelo de IA e orçamento | Ferramentas somente leitura; não configurado | IA real |
| D14 | Metas e critérios de desempenho | Fórmulas separadas, sem nota única inventada | Ranking operacional definitivo |
| D15 | Prazo de crédito e aprovação por valor | Bloqueado por padrão | Venda a prazo e alçadas |

Não parar todo o projeto por uma decisão que só afeta fase posterior. Isolar dependência, avançar no trabalho independente e trazer a escolha concreta ao responsável certo.

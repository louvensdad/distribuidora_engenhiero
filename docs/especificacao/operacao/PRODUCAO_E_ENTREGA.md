# Operação, implantação e entrega

## Ambientes

Local, teste automatizado, homologação e produção separados. Bases, buckets, usuários, chaves, domínios e clientes OIDC diferentes. Seeds fictícios somente fora de produção. Migração deve falhar se parâmetros essenciais não existirem; nunca iniciar aplicação de produção em modo demo silenciosamente.

Contêineres e dependências fixados; health/readiness reais; migrations controladas; rollback de aplicação testado junto com compatibilidade de schema. Infraestrutura Java/PostgreSQL escolhida em ADR. HTTPS em todos os trajetos externos. Segredos no gerenciador de segredos, com rotação e mínimo privilégio.

## Backup e continuidade

Definir metas de recuperação propostas: RPO 24 h e RTO 4 h para piloto, sujeitas à aprovação do dono. São metas de projeto, não promessas já verificadas. Backup automático do banco e objetos; retenção por política; criptografia; cópia em local separado; teste de restauração antes de abrir produção e periodicamente. Medir o tempo real e registrar evidência.

Restaurar banco sem objetos/documentos não conta como restauração completa. Não armazenar chave de descriptografia no mesmo lugar sem controle. Documentar perda de conexão, indisponibilidade do IdP, do fiscal, de pagamentos e do provedor LLM.

## Observabilidade

Logs estruturados com correlation_id, request_id e ator pseudonimizado. Métricas de erro, latência, filas, estoque bloqueado, reconciliação e falha de integração. Sem tokens, CPF, conteúdo de folha, comprovantes ou segredo nos logs. Alertas de falha de backup, fila parada e webhook inválido.

## Distribuição dos quatro apps

Quatro identificadores e ícones, quatro artefatos Android assinados pelo titular. iOS com quatro targets/schemes e signing válido no ambiente Apple. Compartilhar pacotes, sem expor seletor de função no release. Verificar links de autenticação, client OIDC e política de sessão para cada artefato.

Entregar instruções de instalação, atualização, recuperação de sessão, suporte e desativação de usuário. Não subir APK/IPA para loja nem pagar taxas sem autorização e conta do titular. Antes disso, deixar os builds e metadados concretos para revisão.

## Checklist de entrega real

- Site e App Patrão com paridade funcional comprovada.
- Três áreas da Central do Patrão rastreiam os outros três aplicativos com a identidade real de cada ator.
- Autorização por linha/campo e exportação testada negativamente.
- Banco compartilhado e atualizações entre dispositivos medidos.
- Pedidos, estoque, entregas, caixa e finanças reconciliados.
- Contabilidade com documentos reais de homologação e estados corretos.
- Integração fiscal configurada em sandbox e evidência do provedor.
- IA com chave no servidor, fontes, orçamento e testes de acesso/injeção.
- Gráficos 3D legíveis e alternativa acessível.
- Restauração completa, runbooks, contas individuais e treinamento.
- Dados e migrações sem fixtures ou seletor de perfil em produção.

Se uma integração estiver bloqueada por credencial/certificado/contrato, informar a dependência e manter a fase BLOCKED. Não substituir silenciosamente por 'sucesso' local. A operação básica pode ser demonstrada com rótulo, mas não certificada como integrada.

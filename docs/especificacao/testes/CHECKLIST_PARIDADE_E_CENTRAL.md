# Aceite do App Patrão e dos três setores

Execute com contas distintas em pelo menos dois dispositivos e no navegador.

1. Balcão cria pedido e reserva estoque. Patrão abre Central > Retirada e identifica pessoa, hora, itens, pagamento e situação.
2. Recebedor confere fornecedor. Patrão vê entrada, custo autorizado, diferença e documento; o recebedor sem acesso de custo não recebe esse campo.
3. Atendente de entrega cria pedido. Coordenador atribui viagem e placa. Motorista confirma saída/entrega. Patrão acompanha Central > Entregas e abre o histórico inteiro.
4. Motorista registra abastecimento. Patrão encontra o mesmo gasto por placa, viagem, pessoa e categoria. O somatório contém uma despesa, não uma por tela.
5. Contador envia guia. Patrão abre Central > Contabilidade, consulta anexo e aprova. O contador vê aprovação, mas não saldo bancário. Pagamento posterior tem responsável/comprovante próprios.
6. Contador prepara folha. Patrão vê totais/rubricas autorizadas e acompanha a aprovação, sem liberar dados pessoais de toda a empresa a outros usuários.
7. No App Patrão, executar cada função do arquivo `design/PARIDADE_WEB_APP.csv`, incluindo acesso/permissão, anexo, exportação, relatório e IA.
8. Comparar relatório da web e do app com os mesmos filtros, versão e corte. Todos os totais e gráficos coincidem a centavos.
9. Filtrar histórico por funcionário, cliente, fornecedor, pedido, placa e período; abrir o registro de origem sem trocar identidade.
10. Revogar uma capacidade do funcionário e confirmar efeito em API, app, eventos, anexos e exportação.

Registrar resultados observados, latência real, screenshots sem dados pessoais, requests sanitizados e commit. Não presumir sincronização por terem o mesmo layout.

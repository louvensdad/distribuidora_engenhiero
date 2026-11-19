// Rótulos em português (mesmos do site).
const auditActionLabels = {
  'access.denied': 'Acesso negado',
  'permissions.replace': 'Alterou permissões',
  'permissions.template_applied': 'Aplicou modelo de perfil',
  'membership.status': 'Alterou situação da conta',
  'customers.create': 'Cadastrou cliente',
  'catalog.products.price': 'Alterou preço de venda',
  'orders.create': 'Cadastrou pedido',
  'orders.ready': 'Marcou pedido como pronto',
  'orders.fulfill': 'Registrou retirada/entrega',
  'orders.cancel': 'Cancelou pedido',
  'orders.return': 'Registrou devolução de venda',
  'payments.register': 'Registrou pagamento',
  'payments.confirm': 'Conferiu pagamento',
  'payments.reject': 'Recusou pagamento',
  'payments.reverse': 'Estornou pagamento',
  'cash.open': 'Abriu caixa',
  'cash.close': 'Fechou caixa',
  'cash.review': 'Conferiu fechamento de caixa',
  'vehicles.create': 'Cadastrou veículo',
  'vehicles.fuel': 'Registrou abastecimento',
  'vehicles.expense': 'Registrou gasto do veículo',
  'vehicles.expense.approve': 'Aprovou gasto do veículo',
  'trips.create': 'Criou viagem',
  'trips.assign': 'Atribuiu veículo, motorista e pedidos',
  'trips.order_assigned': 'Pedido entrou na viagem',
  'trips.depart': 'Registrou saída da viagem',
  'trips.deliver': 'Confirmou entrega',
  'trips.incident': 'Registrou ocorrência',
  'trips.return': 'Registrou retorno e prestação',
  'trips.reconcile': 'Conferiu prestação de contas',
  'taxes.create': 'Preparou guia',
  'taxes.attach': 'Anexou arquivo na guia',
  'taxes.submit': 'Enviou guia para aprovação',
  'taxes.approve': 'Aprovou guia',
  'taxes.return': 'Devolveu guia',
  'taxes.pay': 'Registrou pagamento da guia',
  'goals.create': 'Criou meta (rascunho)',
  'goals.edit_draft': 'Editou rascunho de meta',
  'goals.activate': 'Ativou meta',
  'goals.cancel': 'Cancelou meta',
  'reports.export': 'Exportou relatório (planilha/CSV)',
  'reports.print': 'Gerou PDF do relatório',
  'payroll.create': 'Preparou/importou folha',
  'payroll.submit': 'Enviou folha para aprovação',
  'payroll.approve': 'Aprovou folha',
  'payroll.pay': 'Pagou salários',
  'documents.upload': 'Enviou documento',
  'documents.request': 'Solicitou documento',
  'invoices.create': 'Gerou rascunho de nota',
  'invoices.review': 'Conferiu nota',
  'invoices.transmit': 'Transmitiu nota',
  'invoices.authorized': 'Nota autorizada',
  'invoices.rejected': 'Nota rejeitada',
  'invoices.resolve': 'Consultou nota incerta',
  'invoices.cancel': 'Pediu cancelamento de nota',
  'invoices.cancelled': 'Nota cancelada',
  'invoices.discard': 'Descartou nota',
  'products.fiscal_data': 'Informou NCM do produto',
  'fiscal.settings': 'Alterou configuração fiscal',
  'payments.charge': 'Criou cobrança Pix',
  'catalog.products.create': 'Cadastrou produto',
  'catalog.products.conversion': 'Alterou conversão de medida',
  'catalog.products.confirm_unit': 'Confirmou unidade comercial',
  'suppliers.create': 'Cadastrou fornecedor',
  'inventory.receipts.create': 'Registrou recebimento',
  'inventory.receipts.confirm': 'Conferiu e confirmou recebimento',
  'inventory.receipts.return': 'Devolveu ao fornecedor',
  'inventory.losses.create': 'Registrou perda',
  'inventory.losses.approve': 'Aprovou perda',
  'inventory.losses.reject': 'Recusou perda',
  'inventory.losses.reverse': 'Estornou perda',
  'inventory.adjustments.create': 'Registrou contagem',
  'inventory.adjustments.approve': 'Aplicou ajuste de contagem',
  'inventory.adjustments.reject': 'Recusou ajuste de contagem',
  'payables.create': 'Criou conta a pagar',
  'documents.attach': 'Anexou documento',
  'documents.download': 'Baixou documento',
  'branches.create': 'Criou unidade',
  'branches.status': 'Alterou situação da unidade',
  'customers.archive': 'Arquivou cliente',
  'customers.update': 'Corrigiu cliente',
  'documents.new_version': 'Enviou nova versão de documento',
  'documents.request_cancel': 'Cancelou solicitação de documento',
  'documents.request_fulfilled': 'Atendeu solicitação de documento',
  'expenses.approve': 'Aprovou despesa',
  'expenses.create': 'Cadastrou despesa',
  'expenses.reject': 'Recusou despesa',
  'expenses.reverse': 'Estornou despesa',
  'finance.accounts.create': 'Criou conta financeira',
  'finance.transfers.create': 'Transferiu entre contas',
  'finance.transfers.reverse': 'Estornou transferência',
  'open_finance.connect': 'Conectou conta bancária (Open Finance)',
  'open_finance.ignore': 'Ignorou transação do extrato',
  'open_finance.revoke': 'Revogou conexão bancária',
  'open_finance.sync': 'Leu extrato do banco',
  'payables.pay': 'Pagou conta',
  'payables.payment_reverse': 'Estornou pagamento de conta',
  'payroll.export': 'Exportou folha',
  'payroll.return': 'Devolveu folha',
  'periods.close': 'Fechou o mês',
  'periods.reopen': 'Reabriu o mês',
  'periods.reopen_reject': 'Recusou reabertura do mês',
  'periods.reopen_request': 'Pediu reabertura do mês',
  'receivables.settle': 'Registrou recebimento/repasse',
  'receivables.settlement_reverse': 'Estornou recebimento',
  'suppliers.archive': 'Arquivou fornecedor',
  'suppliers.update': 'Corrigiu fornecedor',
  'taxes.cancel': 'Cancelou guia',
  'taxes.update': 'Ajustou guia',
  'payroll.cancel': 'Cancelou folha',
  'inventory.sale_issue': 'Baixou estoque (venda)',
  'inventory.sale_return': 'Devolveu ao estoque (devolução)',
  'team.absence.approve': 'Aprovou folga/férias',
  'team.absence.cancel': 'Cancelou folga/férias',
  'team.absence.create': 'Registrou folga/férias',
  'team.absence.reject': 'Recusou folga/férias',
  'trips.cancel': 'Cancelou viagem',
  'trips.order_removed': 'Pedido saiu da viagem',
  'vehicles.expense.reject': 'Recusou gasto do veículo',
  'vehicles.odometer_correction': 'Corrigiu odômetro',
  'vehicles.status': 'Alterou situação do veículo',
  'employees.access_granted': 'Criou conta de acesso do funcionário',
  'employees.account_linked': 'Ligou conta ao funcionário',
  'employees.create': 'Cadastrou funcionário',
  'employees.terminate': 'Registrou desligamento',
  'employees.update': 'Alterou cadastro de funcionário',
  'job_positions.archive': 'Arquivou cargo',
  'job_positions.create': 'Cadastrou cargo',
  'job_positions.update': 'Alterou cargo',
};

const clientLabels = {
  'engenheiro-web': 'Site do patrão',
  'engenheiro-gestao': 'App Engenheiro Patrão',
  'engenheiro-retirada': 'App Engenheiro Retirada',
  'engenheiro-entregas': 'App Engenheiro Entregas',
  'engenheiro-contabil': 'App Engenheiro Contábil',
};

const sectorLabels = {
  'ADMINISTRACAO': 'Administração',
  'RETIRADA': 'Retirada e fornecedores',
  'ENTREGAS': 'Entregas',
  'CONTABILIDADE': 'Contabilidade',
};

const templateLabels = {
  'ADMIN': 'Administrador',
  'BALCAO': 'Balcão',
  'RECEBIMENTO': 'Recebimento de fornecedor',
  'COORDENADOR_ENTREGAS': 'Coordenação de entregas',
  'MOTORISTA': 'Motorista',
  'CONTADOR': 'Contador',
};

String labelOf(Map<String, String> map, String key) => map[key] ?? key;

/// Data/hora no fuso comercial da empresa (America/Sao_Paulo, UTC-3 sem horário de verão desde 2019).
String formatDateTime(DateTime utc) {
  final t = utc.toUtc().subtract(const Duration(hours: 3));
  String two(int v) => v.toString().padLeft(2, '0');
  return '${two(t.day)}/${two(t.month)}/${t.year} ${two(t.hour)}:${two(t.minute)}';
}

// ---- F03: medidas e quantidades (texto decimal do servidor; só troca separadores) ----
const _unitNames = {
  'UNIDADE': ('unidade', 'unidades'),
  'DUZIA': ('dúzia', 'dúzias'),
  'FEIXE': ('feixe', 'feixes'),
  'MACO': ('maço', 'maços'),
  'CAIXA': ('caixa', 'caixas'),
  'SACO': ('saco', 'sacos'),
  'KG': ('kg', 'kg'),
  'TONELADA': ('tonelada', 'toneladas'),
};

String unitLabel(String unit, [String? qty]) {
  final n = _unitNames[unit];
  if (n == null) return unit;
  final one = qty != null && (qty == '1' || qty == '-1');
  return one ? n.$1 : n.$2;
}

String formatDecimal(String? v) {
  if (v == null) return '—';
  final neg = v.startsWith('-');
  final parts = (neg ? v.substring(1) : v).split('.');
  final grouped = parts[0].replaceAllMapped(
    RegExp(r'\B(?=(\d{3})+(?!\d))'),
    (_) => '.',
  );
  return '${neg ? '−' : ''}$grouped${parts.length > 1 ? ',${parts[1]}' : ''}';
}

String formatQty(String v, String unit) =>
    '${formatDecimal(v)} ${unitLabel(unit, v)}';

String formatMoney(String? v) {
  if (v == null) return '—';
  final neg = v.startsWith('-');
  final parts = (neg ? v.substring(1) : v).split('.');
  final cents = (parts.length > 1 ? parts[1] : '00')
      .padRight(2, '0')
      .substring(0, 2);
  return '${neg ? '−' : ''}R\$ ${formatDecimal(parts[0])},$cents';
}

const receiptStatusLabels = {
  'DRAFT': 'Rascunho — aguardando conferência',
  'CONFIRMED': 'Confirmado',
};

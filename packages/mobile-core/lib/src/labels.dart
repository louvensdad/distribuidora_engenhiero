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
  'UNIDADE': ('unidade', 'unidades'), 'DUZIA': ('dúzia', 'dúzias'), 'FEIXE': ('feixe', 'feixes'), 'MACO': ('maço', 'maços'),
  'CAIXA': ('caixa', 'caixas'), 'SACO': ('saco', 'sacos'), 'KG': ('kg', 'kg'), 'TONELADA': ('tonelada', 'toneladas'),
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
  final grouped = parts[0].replaceAllMapped(RegExp(r'\B(?=(\d{3})+(?!\d))'), (_) => '.');
  return '${neg ? '−' : ''}$grouped${parts.length > 1 ? ',${parts[1]}' : ''}';
}

String formatQty(String v, String unit) => '${formatDecimal(v)} ${unitLabel(unit, v)}';

String formatMoney(String? v) {
  if (v == null) return '—';
  final neg = v.startsWith('-');
  final parts = (neg ? v.substring(1) : v).split('.');
  final cents = (parts.length > 1 ? parts[1] : '00').padRight(2, '0').substring(0, 2);
  return '${neg ? '−' : ''}R\$ ${formatDecimal(parts[0])},$cents';
}

const receiptStatusLabels = {'DRAFT': 'Rascunho — aguardando conferência', 'CONFIRMED': 'Confirmado'};

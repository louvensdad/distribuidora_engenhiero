// Rótulos em português (mesmos do site).
const auditActionLabels = {
  'access.denied': 'Acesso negado',
  'permissions.replace': 'Alterou permissões',
  'permissions.template_applied': 'Aplicou modelo de perfil',
  'membership.status': 'Alterou situação da conta',
  'customers.create': 'Cadastrou cliente',
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

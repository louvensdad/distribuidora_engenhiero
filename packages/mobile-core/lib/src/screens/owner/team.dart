import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import '../../labels.dart';
import '../../scope.dart';
import 'approvals.dart';
import 'central.dart';
import 'kit.dart';

const resourceLabels = {
  'orders.pickup': 'Pedidos de retirada', 'orders.delivery': 'Pedidos de entrega', 'inventory.receipts': 'Recebimentos de fornecedor',
  'inventory.stock': 'Estoque', 'inventory.losses': 'Perdas de estoque', 'trips': 'Viagens', 'vehicles': 'Veículos', 'fuel': 'Abastecimentos',
  'payments': 'Recebimentos de clientes', 'own_cash': 'Caixa próprio', 'company_cash': 'Caixa da empresa', 'expenses': 'Despesas',
  'taxes': 'Guias e impostos', 'payroll': 'Folha de pagamento', 'invoices': 'Notas fiscais', 'documents': 'Documentos',
  'reports': 'Relatórios', 'dashboard': 'Visão geral (dashboard)', 'goals': 'Metas', 'audit': 'Histórico', 'ai': 'Inteligência (IA)',
  'users': 'Pessoas', 'permissions': 'Permissões', 'identity': 'Identidade', 'catalog': 'Produtos e medidas', 'customers': 'Clientes',
  'suppliers': 'Fornecedores',
};
const actionLabels = {
  'view': 'Ver', 'create': 'Cadastrar', 'edit_draft': 'Editar rascunho', 'request': 'Solicitar', 'approve': 'Aprovar',
  'confirm': 'Confirmar', 'cancel': 'Cancelar', 'reverse': 'Estornar', 'export': 'Exportar', 'print': 'Imprimir', 'attach': 'Anexar',
  'assign': 'Atribuir', 'close': 'Fechar', 'close_period': 'Fechar período', 'reopen_period': 'Reabrir período',
};
const scopeLabels = {'OWN': 'Próprios registros', 'ASSIGNED': 'Atribuídos a ele', 'SECTOR': 'Do setor', 'BRANCH': 'Da unidade',
  'ORGANIZATION': 'Toda a empresa'};
const fieldLabels = {
  'operational_contact': 'Contato operacional', 'delivery_address': 'Endereço de entrega', 'personal_document': 'CPF/CNPJ e documentos pessoais',
  'employee_salary': 'Salário individual', 'bank_data': 'Dados bancários', 'purchase_cost': 'Custo de compra', 'margin': 'Custo/margem',
};
const _effects = {'NONE': 'Sem acesso', 'ALLOW': 'Permitir', 'DENY': 'Bloquear'};

class TeamHubScreen extends StatelessWidget {
  const TeamHubScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    Widget item(String icon, String title, String sub, Widget screen) => Card(child: ListTile(
        leading: EngIcon(icon, color: EngColors.cyan), title: Text(title), subtitle: Text(sub),
        onTap: () => pushScreen(context, title, screen)));
    return ListView(padding: const EdgeInsets.all(16), children: [
      if (me.can('users.view')) item('lock', 'Pessoas e acessos', 'Escolher acesso a dedo, bloquear conta', const PeopleAccessScreen()),
      if (me.can('users.view')) item('users', 'Funcionários', 'Cadastro, cargo, desligamento e conta de acesso', const EmployeesScreen()),
      if (me.can('users.view')) item('shield', 'Cargos', 'Funções e modelo de acesso sugerido', const PositionsScreen()),
      if (me.can('users.view')) item('clock', 'Folgas e férias', 'Calendário por setor e pedidos', const AbsencesScreen()),
      if (me.can('reports.view')) item('report', 'Desempenho', 'Medidas por função, com a fórmula', const PerformanceScreen()),
      item('home', 'Unidades', 'Filiais e situação', const BranchesScreen()),
    ]);
  }
}

class PeopleAccessScreen extends StatelessWidget {
  const PeopleAccessScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return JsonView(load: (api) => api.getJson('/api/v1/users'), builder: (context, d, reload) => [
      const Text('O servidor nega tudo que não estiver concedido. A mudança vale na próxima ação da pessoa.',
          style: TextStyle(color: EngColors.muted)),
      for (final u in items(d))
        RowCard(
          title: str(u['display_name']),
          subtitle: '${labelOf(templateLabels, str(u['role_template']))} · ${u['branch']}${u['admin'] == true ? ' · administrador' : ''}'
              '${u['status'] == 'DISABLED' ? ' · BLOQUEADA' : ''}',
          onTap: () async {
            if (u['id'] == me.userId) {
              ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
                  content: Text('Você não pode alterar o próprio acesso: outro administrador precisa fazer essa mudança.')));
              return;
            }
            await pushScreen(context, str(u['display_name']), AccessEditorScreen(userId: str(u['id'])));
            reload();
          },
        ),
    ]);
  }
}

class _Cell {
  _Cell({this.effect = 'NONE', this.scope = 'OWN', this.max = '', List<String>? extra, this.expires})
      : extra = extra ?? <String>[];
  String effect;
  String scope;
  String max;
  final List<String> extra;
  String? expires;
  String describe() => effect == 'NONE' ? 'sem acesso' : effect == 'DENY' ? 'bloqueado'
      : 'permitido (${labelOf(scopeLabels, scope)}${max.isEmpty ? '' : ', até R\$ $max'})';
  _Cell copy() => _Cell(effect: effect, scope: scope, max: max, extra: [...extra], expires: expires);
}

/// P11 — escolher acesso a dedo: matriz recurso × ação (só o que o próprio editor possui), escopo, alçada, campos e apps.
/// Mostra o resumo antes de salvar; salvar exige motivo e versão (If-Match).
class AccessEditorScreen extends StatefulWidget {
  const AccessEditorScreen({super.key, required this.userId});
  final String userId;
  @override
  State<AccessEditorScreen> createState() => _AccessEditorScreenState();
}

class _AccessEditorScreenState extends State<AccessEditorScreen> {
  J? _policy;
  Map<String, _Cell> _orig = {};
  Map<String, _Cell> _cells = {};
  Map<String, String> _origFields = {};
  Map<String, String> _fields = {};
  List<String> _origClients = [];
  List<String> _clients = [];
  final _reason = TextEditingController();
  String? _template;
  String? _error;
  bool _busy = false;
  /// Geração da carga: campos de formulário guardam o próprio valor; a chave nova os recria com o valor do servidor.
  int _gen = 0;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_policy == null && _error == null) _load();
  }

  Future<void> _load() async {
    try {
      final p = await EngScope.of(context).api.getJson('/api/v1/users/${widget.userId}/access-policy');
      final cells = <String, _Cell>{};
      for (final g in items(p, 'grants')) {
        final k = '${g['resource']}.${g['action']}';
        final c = cells[k] ?? _Cell();
        if (g['effect'] == 'DENY') {
          c.effect = 'DENY';
        } else if (c.effect == 'NONE') {
          c.effect = 'ALLOW';
          c.scope = str(g['scope']);
          c.max = str(g['max_amount']);
          c.expires = strOrNull(g['expires_at']);
        } else if (c.effect == 'ALLOW' && g['scope'] != c.scope && !c.extra.contains(g['scope'])) {
          c.extra.add(str(g['scope']));
        }
        cells[k] = c;
      }
      if (!mounted) return;
      setState(() {
        _policy = p;
        _orig = cells;
        _cells = {for (final e in cells.entries) e.key: e.value.copy()};
        _origFields = {for (final f in items(p, 'fields')) str(f['field']): str(f['effect'])};
        _fields = {..._origFields};
        _origClients = [for (final c in (p['clients'] as List? ?? const [])) str(c)]..sort();
        _clients = [..._origClients];
        _error = null;
        _gen++;
      });
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e.isForbidden ? 'Sem permissão para ver os acessos.' : errorText(e));
    }
  }

  _Cell _cell(String k) => _cells[k] ?? _Cell();

  List<String> _changes() {
    final out = <String>[];
    final keys = {..._orig.keys, ..._cells.keys}.toList()..sort();
    for (final k in keys) {
      final a = (_orig[k] ?? _Cell()).describe();
      final b = (_cells[k] ?? _Cell()).describe();
      final dot = k.lastIndexOf('.');
      if (a != b) out.add('${labelOf(resourceLabels, k.substring(0, dot))} · ${labelOf(actionLabels, k.substring(dot + 1))}: $a → $b');
    }
    String fx(String? e) => e == 'ALLOW' ? 'visível' : e == 'DENY' ? 'bloqueado' : 'oculto (padrão)';
    for (final f in fieldLabels.keys) {
      if ((_origFields[f] ?? 'NONE') != (_fields[f] ?? 'NONE')) {
        out.add('Campo ${fieldLabels[f]}: ${fx(_origFields[f])} → ${fx(_fields[f])}');
      }
    }
    for (final c in clientLabels.keys) {
      if (_origClients.contains(c) != _clients.contains(c)) out.add('${clientLabels[c]}: ${_clients.contains(c) ? 'liberado' : 'removido'}');
    }
    return out;
  }

  Map<String, dynamic> _payload() {
    final grants = <Map<String, dynamic>>[];
    for (final e in _cells.entries) {
      final c = e.value;
      if (c.effect == 'NONE') continue;
      final dot = e.key.lastIndexOf('.');
      final base = {'resource': e.key.substring(0, dot), 'action': e.key.substring(dot + 1), 'expires_at': c.expires};
      if (c.effect == 'DENY') {
        grants.add({...base, 'scope': 'ORGANIZATION', 'effect': 'DENY', 'max_amount': null});
        continue;
      }
      for (final s in [c.scope, ...c.extra]) {
        grants.add({...base, 'scope': s, 'effect': 'ALLOW', 'max_amount': c.max.trim().isEmpty ? null : decimalText(c.max)});
      }
    }
    return {
      'grants': grants,
      'fields': [for (final e in _fields.entries) if (e.value != 'NONE') {'field': e.key, 'effect': e.value}],
      'clients': _clients,
      'reason': _reason.text.trim(),
    };
  }

  Future<void> _save(Map<String, dynamic> body) async {
    final api = EngScope.of(context).api;
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _busy = true);
    try {
      await api.sendJson('POST', '/api/v1/users/${widget.userId}/access-policy', body, ifMatch: (_policy!['user'] as J)['version']);
      messenger.showSnackBar(const SnackBar(content: Text('Permissões salvas. A mudança vale na próxima ação da pessoa.')));
      _reason.clear();
      _template = null;
      await _load();
    } on ApiException catch (e) {
      if (e.status == 412) {
        messenger.showSnackBar(const SnackBar(content: Text('Outra pessoa alterou estes acessos. Recarreguei a versão atual.')));
        await _load();
      } else {
        messenger.showSnackBar(SnackBar(content: Text(errorText(e)), duration: const Duration(seconds: 6)));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _status(J u) async {
    final to = u['status'] == 'DISABLED' ? 'ACTIVE' : 'DISABLED';
    final why = await askText(context, title: to == 'DISABLED' ? 'Bloquear conta' : 'Desbloquear conta',
        action: to == 'DISABLED' ? 'Bloquear' : 'Desbloquear',
        message: to == 'DISABLED' ? 'A pessoa perde o acesso em todos os apps na próxima ação.' : null);
    if (why == null || !mounted) return;
    final r = await runCommand(context, (api) => api.sendJson('POST', '/api/v1/users/${widget.userId}/status',
        {'status': to, 'reason': why}, ifMatch: u['version']), ok: to == 'DISABLED' ? 'Conta bloqueada.' : 'Conta desbloqueada.');
    if (r != null) await _load();
  }

  @override
  Widget build(BuildContext context) {
    if (_error != null) return ErrorStateView(message: _error!, onRetry: _load);
    final p = _policy;
    if (p == null) return const LoadingState();
    final me = EngScope.of(context).me;
    final u = p['user'] as J;
    final canEdit = me.can('permissions.edit_draft');
    final matrix = <String, List<String>>{};
    for (final c in me.capabilities) {
      (matrix[c.resource] ??= <String>[]).add(c.action);
    }
    final resources = matrix.keys.toList()..sort((a, b) => labelOf(resourceLabels, a).compareTo(labelOf(resourceLabels, b)));
    final changes = _changes();
    return ListView(padding: const EdgeInsets.all(16), children: [
      Text(str(u['display_name']), style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
      Text('${labelOf(templateLabels, str(u['role_template']))} · ${u['branch']}', style: const TextStyle(color: EngColors.muted)),
      if (u['admin'] == true) const Text('Administrador — acesso integral', style: TextStyle(color: EngColors.violet)),
      if (u['status'] == 'DISABLED') const Text('Conta bloqueada', style: TextStyle(color: EngColors.error, fontWeight: FontWeight.w700)),
      if (me.can('users.edit_draft'))
        OutlinedButton(onPressed: _busy ? null : () => _status(u), child: Text(u['status'] == 'DISABLED' ? 'Desbloquear conta' : 'Bloquear conta')),
      if (canEdit) ...[
        const SectionTitle('Aplicar modelo'),
        const Text('Substitui as permissões atuais pelas do modelo. Depois você ajusta individualmente.', style: TextStyle(color: EngColors.muted)),
        DropdownButtonFormField<String>(
          key: ValueKey('t$_gen'),
          initialValue: _template,
          isExpanded: true,
          decoration: const InputDecoration(labelText: 'Modelo'),
          items: [for (final e in templateLabels.entries) if (e.key != 'ADMIN' || me.admin) DropdownMenuItem(value: e.key, child: Text(e.value))],
          onChanged: (v) => setState(() => _template = v),
        ),
      ],
      const SectionTitle('Permissões'),
      for (final r in resources)
        ExpansionTile(
          tilePadding: EdgeInsets.zero,
          title: Text(labelOf(resourceLabels, r)),
          subtitle: Text('${matrix[r]!.where((a) => _cell('$r.$a').effect == 'ALLOW').length} permitida(s)'
              '${matrix[r]!.any((a) => _cell('$r.$a').effect == 'DENY') ? ' · bloqueios' : ''}'),
          children: [
            for (final a in matrix[r]!)
              Padding(padding: const EdgeInsets.only(bottom: 8), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(labelOf(actionLabels, a), style: const TextStyle(fontWeight: FontWeight.w600)),
                Row(children: [
                  Expanded(child: DropdownButtonFormField<String>(
                    key: ValueKey('e$_gen-$r.$a'),
                    initialValue: _cell('$r.$a').effect,
                    isExpanded: true,
                    decoration: const InputDecoration(labelText: 'Efeito'),
                    items: [for (final e in _effects.entries) DropdownMenuItem(value: e.key, child: Text(e.value))],
                    onChanged: canEdit ? (v) => setState(() => (_cells['$r.$a'] ??= _Cell()).effect = v ?? 'NONE') : null,
                  )),
                  const SizedBox(width: 8),
                  if (_cell('$r.$a').effect == 'ALLOW')
                    Expanded(child: DropdownButtonFormField<String>(
                      key: ValueKey('s$_gen-$r.$a'),
                      initialValue: _cell('$r.$a').scope,
                      isExpanded: true,
                      decoration: const InputDecoration(labelText: 'Escopo'),
                      items: [for (final e in scopeLabels.entries) DropdownMenuItem(value: e.key, child: Text(e.value))],
                      onChanged: canEdit ? (v) => setState(() => _cells['$r.$a']!.scope = v ?? 'OWN') : null,
                    )),
                ]),
                if (_cell('$r.$a').effect == 'ALLOW' && (a == 'approve' || a == 'confirm'))
                  TextFormField(
                    key: ValueKey('m$_gen-$r.$a'),
                    initialValue: _cell('$r.$a').max,
                    enabled: canEdit,
                    keyboardType: const TextInputType.numberWithOptions(decimal: true),
                    decoration: const InputDecoration(labelText: 'Alçada máxima (R\$, vazio = sem limite)'),
                    onChanged: (v) => setState(() => _cells['$r.$a']!.max = v),
                  ),
              ])),
          ],
        ),
      const SectionTitle('Campos sensíveis'),
      for (final f in fieldLabels.entries)
        DropdownButtonFormField<String>(
          key: ValueKey('f$_gen-${f.key}'),
          initialValue: _fields[f.key] ?? 'NONE',
          isExpanded: true,
          decoration: InputDecoration(labelText: f.value),
          items: const [DropdownMenuItem(value: 'NONE', child: Text('Oculto (padrão)')), DropdownMenuItem(value: 'ALLOW', child: Text('Visível')),
            DropdownMenuItem(value: 'DENY', child: Text('Bloqueado'))],
          onChanged: canEdit ? (v) => setState(() => _fields[f.key] = v ?? 'NONE') : null,
        ),
      const SectionTitle('Aplicativos liberados'),
      for (final c in clientLabels.entries)
        CheckboxListTile(
          contentPadding: EdgeInsets.zero,
          value: _clients.contains(c.key),
          title: Text(c.value),
          onChanged: canEdit ? (v) => setState(() => v == true ? (_clients..add(c.key)..sort()) : _clients.remove(c.key)) : null,
        ),
      if (canEdit) ...[
        const SectionTitle('Resumo antes de salvar'),
        if (_template != null) Text('Aplicar o modelo ${labelOf(templateLabels, _template!)} (substitui tudo acima).'),
        if (changes.isEmpty && _template == null) const Text('Nenhuma alteração.', style: TextStyle(color: EngColors.muted)),
        for (final c in changes) Text('• $c'),
        TextField(controller: _reason, onChanged: (_) => setState(() {}), decoration: const InputDecoration(labelText: 'Motivo *')),
        const SizedBox(height: 8),
        FilledButton(
          onPressed: _busy || _reason.text.trim().isEmpty || (changes.isEmpty && _template == null)
              ? null
              : () => _save(_template != null ? {'template': _template, 'reason': _reason.text.trim()} : _payload()),
          child: Text(_busy ? 'Salvando…' : _template != null ? 'Aplicar modelo' : 'Salvar acessos'),
        ),
        if (changes.isNotEmpty)
          TextButton(onPressed: () => setState(() {
            _cells = {for (final e in _orig.entries) e.key: e.value.copy()};
            _fields = {..._origFields};
            _clients = [..._origClients];
            _gen++;
          }), child: const Text('Desfazer alterações')),
      ],
    ]);
  }
}

const employeeStatus = {'ACTIVE': 'Ativo', 'ON_LEAVE': 'Afastado', 'TERMINATED': 'Desligado'};

Future<Map<String, String>> _positions(BuildContext context) async {
  final j = await EngScope.of(context).api.getJson('/api/v1/job-positions');
  return {for (final p in items(j)) if (p['status'] == 'ACTIVE') str(p['id']): '${p['name']} (${labelOf(sectorLabels, str(p['sector']))})'};
}

List<FieldSpec> _employeeFields(Map<String, String> positions, J? e) => [
      FieldSpec('full_name', 'Nome completo', required: true, initial: strOrNull(e?['full_name'])),
      FieldSpec('job_position_id', 'Cargo', kind: FieldKind.select, required: true, options: positions, initial: strOrNull(e?['job_position_id'])),
      FieldSpec('admission_date', 'Admissão', kind: FieldKind.date, required: true, initial: strOrNull(e?['admission_date'])),
      FieldSpec('duties', 'Funções', kind: FieldKind.multiline, initial: strOrNull(e?['duties'])),
      FieldSpec('work_schedule', 'Jornada', initial: strOrNull(e?['work_schedule'])),
      if (e != null) FieldSpec('status', 'Situação', kind: FieldKind.select, options: const {'ACTIVE': 'Ativo', 'ON_LEAVE': 'Afastado'},
          initial: strOrNull(e['status'])),
      FieldSpec('phone', 'Telefone', initial: strOrNull(e?['phone'])),
      FieldSpec('cpf', 'CPF', initial: strOrNull(e?['cpf'])),
      FieldSpec('birth_date', 'Nascimento', kind: FieldKind.date, initial: strOrNull(e?['birth_date'])),
      FieldSpec('address', 'Endereço', initial: strOrNull(e?['address'])),
      FieldSpec('base_salary', 'Salário base (R\$)', kind: FieldKind.money, initial: strOrNull(e?['base_salary'])),
      FieldSpec('notes', 'Observação', initial: strOrNull(e?['notes'])),
    ];

class EmployeesScreen extends StatefulWidget {
  const EmployeesScreen({super.key});
  @override
  State<EmployeesScreen> createState() => _EmployeesScreenState();
}

class _EmployeesScreenState extends State<EmployeesScreen> {
  final _q = TextEditingController();
  String _query = '';
  Key _k = UniqueKey();
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return Scaffold(
      floatingActionButton: me.can('users.edit_draft')
          ? FloatingActionButton.extended(
              icon: const EngIcon('plus', color: EngColors.background),
              label: const Text('Novo funcionário'),
              onPressed: () async {
                final pos = await _positions(context);
                if (!context.mounted) return;
                final r = await openForm(context, title: 'Novo funcionário', fields: _employeeFields(pos, null),
                    submit: (api, v, key) => api.sendJson('POST', '/api/v1/employees', v));
                if (r != null) setState(() => _k = UniqueKey());
              })
          : null,
      body: CursorList(
        key: _k,
        path: '/api/v1/employees',
        query: {'q': _query},
        empty: 'Nenhum funcionário.',
        header: [TextField(controller: _q, decoration: const InputDecoration(labelText: 'Buscar nome'),
            onSubmitted: (v) => setState(() => _query = v.trim()))],
        itemBuilder: (context, e, reload) => RowCard(
          title: str(e['full_name']),
          subtitle: '${e['job_position']} · ${labelOf(employeeStatus, str(e['status']))}${e['account'] == null ? ' · sem conta de acesso' : ''}',
          onTap: () async {
            await pushScreen(context, str(e['full_name']), EmployeeScreen(id: str(e['id'])));
            reload();
          },
        ),
      ),
    );
  }
}

class EmployeeScreen extends StatefulWidget {
  const EmployeeScreen({super.key, required this.id});
  final String id;
  @override
  State<EmployeeScreen> createState() => _EmployeeScreenState();
}

class _EmployeeScreenState extends State<EmployeeScreen> {
  Key _k = UniqueKey();
  void _reload() => setState(() => _k = UniqueKey());

  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    final edit = me.can('users.edit_draft');
    return JsonView(key: _k, load: (api) => api.getJson('/api/v1/employees/${widget.id}'), builder: (context, e, reload) {
      final masked = (e['masked_fields'] as List? ?? const []).cast<String>();
      final account = e['account'] as J?;
      String v(String k) => masked.contains(k) ? 'oculto pela permissão' : str(e[k]);
      return [
        Text(str(e['full_name']), style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
        KV('Cargo', '${e['job_position']} · ${labelOf(sectorLabels, str(e['sector']))}'),
        KV('Situação', labelOf(employeeStatus, str(e['status']))),
        KV('Unidade', str(e['branch'])),
        KV('Admissão', brDate(e['admission_date'])),
        if (e['termination_date'] != null) KV('Desligamento', brDate(e['termination_date'])),
        if (e['duties'] != null) KV('Funções', str(e['duties'])),
        if (e['work_schedule'] != null) KV('Jornada', str(e['work_schedule'])),
        KV('Telefone', v('phone')),
        KV('CPF', v('cpf')),
        KV('Endereço', v('address')),
        KV('Salário base', masked.contains('base_salary') ? 'oculto pela permissão' : formatMoney(strOrNull(e['base_salary']))),
        KV('Conta de acesso', account == null ? 'Sem conta' : '${account['display_name'] ?? account['username'] ?? ''} ${account['status'] ?? ''}'),
        if (edit && e['status'] != 'TERMINATED') ...[
          const SizedBox(height: 12),
          OutlinedButton(onPressed: () async {
            final pos = await _positions(context);
            if (!context.mounted) return;
            final r = await openForm(context, title: 'Editar funcionário', fields: _employeeFields(pos, e),
                submit: (api, x, key) => api.sendJson('PUT', '/api/v1/employees/${widget.id}', x, ifMatch: e['version']));
            if (r != null) _reload();
          }, child: const Text('Editar')),
          if (account == null && me.can('permissions.edit_draft'))
            OutlinedButton(onPressed: () async {
              final r = await openForm(context, title: 'Criar conta de acesso', submitLabel: 'Criar conta',
                  intro: 'A senha temporária aparece uma única vez; a pessoa troca no primeiro acesso.', fields: const [
                FieldSpec('username', 'Usuário', required: true),
                FieldSpec('role_template', 'Modelo de acesso', kind: FieldKind.select, required: true,
                    options: {'BALCAO': 'Balcão', 'RECEBIMENTO': 'Recebimento de fornecedor', 'COORDENADOR_ENTREGAS': 'Coordenação de entregas',
                      'MOTORISTA': 'Motorista', 'CONTADOR': 'Contador'}),
              ], submit: (api, x, key) => api.sendJson('POST', '/api/v1/employees/${widget.id}/access', x));
              if (r != null && context.mounted) {
                await showDialog<void>(context: context, builder: (d) => AlertDialog(
                  title: const Text('Conta criada'),
                  content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text('Usuário: ${r['username']}'),
                    SelectableText('Senha temporária: ${r['temporary_password']}'),
                    Text(str(r['note']), style: const TextStyle(color: EngColors.muted)),
                  ]),
                  actions: [
                    TextButton(onPressed: () => Clipboard.setData(ClipboardData(text: str(r['temporary_password']))), child: const Text('Copiar senha')),
                    FilledButton(onPressed: () => Navigator.of(d).pop(), child: const Text('Entreguei à pessoa')),
                  ],
                ));
                _reload();
              }
            }, child: const Text('Criar conta de acesso')),
          if (account == null)
            OutlinedButton(onPressed: () async {
              final un = await runCommand(context, (api) => api.getJson('/api/v1/employees/unlinked-accounts'));
              if (un == null || !context.mounted) return;
              final r = await openForm(context, title: 'Vincular conta existente', fields: [
                FieldSpec('user_id', 'Conta', kind: FieldKind.select, required: true,
                    options: {for (final a in items(un)) str(a['user_id']): '${a['display_name']} · ${labelOf(templateLabels, str(a['role_template']))}'}),
              ], submit: (api, x, key) => api.sendJson('POST', '/api/v1/employees/${widget.id}/account-link', x));
              if (r != null) _reload();
            }, child: const Text('Vincular conta existente')),
          OutlinedButton(onPressed: () async {
            final r = await openForm(context, title: 'Desligar funcionário', submitLabel: 'Desligar',
                intro: 'A conta de acesso vinculada é bloqueada no mesmo ato.', fields: const [
              FieldSpec('termination_date', 'Data do desligamento', kind: FieldKind.date, required: true),
              FieldSpec('reason', 'Motivo', required: true),
            ], submit: (api, x, key) => api.sendJson('POST', '/api/v1/employees/${widget.id}/termination', x, ifMatch: e['version']));
            if (r != null) _reload();
          }, child: const Text('Desligar')),
        ],
        const SectionTitle('Histórico'),
        OutlinedButton(onPressed: () => pushScreen(context, 'Histórico do funcionário', HistoryScreen(entityType: 'employee', entityId: widget.id)),
            child: const Text('Quem alterou o cadastro')),
      ];
    });
  }
}

class PositionsScreen extends StatefulWidget {
  const PositionsScreen({super.key});
  @override
  State<PositionsScreen> createState() => _PositionsScreenState();
}

class _PositionsScreenState extends State<PositionsScreen> {
  Key _k = UniqueKey();
  List<FieldSpec> _fields(J? p) => [
        FieldSpec('name', 'Nome', required: true, initial: strOrNull(p?['name'])),
        FieldSpec('sector', 'Setor', kind: FieldKind.select, required: true, options: sectorLabels, initial: strOrNull(p?['sector'])),
        FieldSpec('suggested_template', 'Modelo de acesso sugerido', kind: FieldKind.select, initial: strOrNull(p?['suggested_template']),
            options: const {'BALCAO': 'Balcão', 'RECEBIMENTO': 'Recebimento', 'COORDENADOR_ENTREGAS': 'Coordenação de entregas',
              'MOTORISTA': 'Motorista', 'CONTADOR': 'Contador'}),
        FieldSpec('description', 'Descrição', initial: strOrNull(p?['description'])),
        if (p != null) FieldSpec('status', 'Situação', kind: FieldKind.select, options: const {'ACTIVE': 'Ativo', 'ARCHIVED': 'Arquivado'},
            initial: strOrNull(p['status'])),
      ];
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    final edit = me.can('users.edit_draft');
    return Scaffold(
      floatingActionButton: edit
          ? FloatingActionButton.extended(
              icon: const EngIcon('plus', color: EngColors.background),
              label: const Text('Novo cargo'),
              onPressed: () async {
                final r = await openForm(context, title: 'Novo cargo', fields: _fields(null),
                    submit: (api, v, key) => api.sendJson('POST', '/api/v1/job-positions', v));
                if (r != null) setState(() => _k = UniqueKey());
              })
          : null,
      body: JsonView(key: _k, load: (api) => api.getJson('/api/v1/job-positions', {'include_archived': 'true'}), builder: (context, d, reload) => [
        if (items(d).isEmpty) const EmptyState(title: 'Nenhum cargo'),
        for (final p in items(d))
          RowCard(
            title: str(p['name']),
            subtitle: '${labelOf(sectorLabels, str(p['sector']))} · ${p['employees'] ?? 0} pessoa(s)'
                '${p['suggested_template'] == null ? '' : ' · modelo ${labelOf(templateLabels, str(p['suggested_template']))}'}'
                '${p['status'] == 'ARCHIVED' ? ' · arquivado' : ''}',
            onTap: edit
                ? () async {
                    final r = await openForm(context, title: 'Editar cargo', fields: _fields(p),
                        submit: (api, v, key) => api.sendJson('PUT', '/api/v1/job-positions/${p['id']}', v, ifMatch: p['version']));
                    if (r != null) reload();
                  }
                : null,
          ),
      ]),
    );
  }
}

class AbsencesScreen extends StatefulWidget {
  const AbsencesScreen({super.key});
  @override
  State<AbsencesScreen> createState() => _AbsencesScreenState();
}

class _AbsencesScreenState extends State<AbsencesScreen> {
  Key _k = UniqueKey();
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return JsonView(key: _k, load: (api) => api.getJson('/api/v1/team/calendar'), builder: (context, d, reload) {
      final members = items(d, 'members');
      return [
        Text('Período ${brDate(d['from'])} a ${brDate(d['to'])}', style: const TextStyle(color: EngColors.muted)),
        if (me.can('users.edit_draft'))
          OutlinedButton(onPressed: () async {
            final r = await openForm(context, title: 'Registrar folga/férias', fields: [
              FieldSpec('membership_id', 'Pessoa', kind: FieldKind.select, required: true,
                  options: {for (final m in members) str(m['membership_id']): '${m['person']} · ${labelOf(sectorLabels, str(m['sector']))}'}),
              const FieldSpec('kind', 'Tipo', kind: FieldKind.select, required: true, options: absenceKindLabels),
              const FieldSpec('start_date', 'Início', kind: FieldKind.date, required: true),
              const FieldSpec('end_date', 'Fim', kind: FieldKind.date, required: true),
              const FieldSpec('notes', 'Observação'),
            ], submit: (api, v, key) => api.sendJson('POST', '/api/v1/team/absences', v));
            if (r != null) setState(() => _k = UniqueKey());
          }, child: const Text('Registrar folga/férias')),
        const SectionTitle('Ausências'),
        if (items(d, 'absences').isEmpty) const Text('Nenhuma no período.', style: TextStyle(color: EngColors.muted)),
        for (final a in items(d, 'absences'))
          RowCard(
            title: '${a['person']} · ${labelOf(absenceKindLabels, str(a['kind']))}',
            subtitle: '${brDate(a['start_date'])} a ${brDate(a['end_date'])} · ${labelOf(genericStateLabels, str(a['state']))}'
                '${(a['warnings'] as List? ?? const []).isEmpty ? '' : ' · ⚠ ${(a['warnings'] as List).join('; ')}'}',
            onTap: a['state'] == 'REQUESTED' || a['state'] == 'APPROVED'
                ? () async {
                    if (a['state'] == 'REQUESTED') {
                      await pushScreen(context, 'Folga/férias', DecisionView(kind: 'ABSENCE', id: str(a['id']),
                          item: {'detail': '${a['start_date']} a ${a['end_date']}'}));
                    } else {
                      final why = await askText(context, title: 'Cancelar ausência aprovada', action: 'Cancelar ausência');
                      if (why == null || !context.mounted) return;
                      await runCommand(context, (api) => api.sendJson('POST', '/api/v1/team/absences/${a['id']}/cancel', {'note': why},
                          ifMatch: a['version']), ok: 'Ausência cancelada.');
                    }
                    setState(() => _k = UniqueKey());
                  }
                : null,
          ),
        const SectionTitle('Disponíveis por dia'),
        for (final day in items(d, 'days').take(14))
          KV(brDate(day['date']), [for (final s in (day['sectors'] as List? ?? const []).cast<J>())
            '${labelOf(sectorLabels, str(s['sector']))}: ${s['available']}/${s['total']}${s['uncovered'] == true ? ' ⚠ sem ninguém' : ''}'].join(' · ')),
        const SectionTitle('Férias nos últimos 12 meses'),
        for (final m in members) KV(str(m['person']), '${m['vacation_days_last_12m'] ?? 0} dia(s)'),
      ];
    });
  }
}

class PerformanceScreen extends StatelessWidget {
  const PerformanceScreen({super.key});
  @override
  Widget build(BuildContext context) => JsonView(load: (api) => api.getJson('/api/v1/team/performance'), builder: (context, d, reload) => [
        Text('Período ${brDate(d['from'])} a ${brDate(d['to'])}. ${d['rule'] ?? ''}', style: const TextStyle(color: EngColors.muted)),
        for (final m in items(d, 'measures'))
          Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('${m['label']} · ${m['function']}', style: const TextStyle(fontWeight: FontWeight.w700)),
            Text('Fórmula: ${m['formula']}', style: const TextStyle(color: EngColors.muted, fontSize: 12)),
            for (final v in (m['values'] as List? ?? const []).cast<J>())
              KV(str(v['person'] ?? v['name']), '${formatDecimal(strOrNull(v['value']))} ${m['unit'] ?? ''}'),
            if (m['note'] != null) Text(str(m['note']), style: const TextStyle(color: EngColors.muted)),
          ]))),
      ]);
}

class BranchesScreen extends StatefulWidget {
  const BranchesScreen({super.key});
  @override
  State<BranchesScreen> createState() => _BranchesScreenState();
}

class _BranchesScreenState extends State<BranchesScreen> {
  Key _k = UniqueKey();
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return Scaffold(
      floatingActionButton: me.admin
          ? FloatingActionButton.extended(
              icon: const EngIcon('plus', color: EngColors.background),
              label: const Text('Nova unidade'),
              onPressed: () async {
                final r = await openForm(context, title: 'Nova unidade', fields: const [FieldSpec('name', 'Nome', required: true)],
                    submit: (api, v, key) => api.sendJson('POST', '/api/v1/branches', v));
                if (r != null) setState(() => _k = UniqueKey());
              })
          : null,
      body: JsonView(key: _k, load: (api) => api.getJson('/api/v1/branches'), builder: (context, d, reload) => [
        for (final b in items(d))
          RowCard(
            title: str(b['name']),
            subtitle: '${labelOf(genericStateLabels, str(b['status']))} · ${b['active_people'] ?? 0} pessoa(s) ativa(s) · ${b['timezone']}',
            onTap: me.admin
                ? () async {
                    final to = b['status'] == 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
                    final why = await askText(context, title: to == 'INACTIVE' ? 'Desativar unidade' : 'Reativar unidade');
                    if (why == null || !context.mounted) return;
                    if (await runCommand(context, (api) => api.sendJson('POST', '/api/v1/branches/${b['id']}/status',
                        {'status': to, 'reason': why}), ok: 'Unidade atualizada.') != null) {
                      reload();
                    }
                  }
                : null,
          ),
      ]),
    );
  }
}

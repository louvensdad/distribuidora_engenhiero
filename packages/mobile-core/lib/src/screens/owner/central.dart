import 'dart:convert';

import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:material_ui/material_ui.dart';

import '../../labels.dart';
import '../../scope.dart';
import '../inventory_screens.dart' show ReceiptDetailScreen;
import 'approvals.dart';
import 'kit.dart';
import 'operation.dart';
import 'team.dart' show EmployeeScreen;

/// Abrir a origem de um movimento no próprio app (W16 no celular). A tela de destino aplica de novo a permissão.
/// Registro sem tela de detalhe própria abre o histórico daquele registro.
typedef OriginOpener = Future<void> Function(BuildContext context, String id);
final Map<String, OriginOpener> originOpeners = {
  'order': (c, id) => pushScreen(c, 'Pedido', OwnerOrderScreen(id: id)),
  'trip': (c, id) => pushScreen(c, 'Viagem', OwnerTripScreen(id: id)),
  'vehicle': (c, id) => pushScreen(c, 'Veículo', OwnerVehicleScreen(id: id)),
  'product': (c, id) => pushScreen(c, 'Produto', ProductScreen(id: id)),
  'employee': (c, id) => pushScreen(c, 'Funcionário', EmployeeScreen(id: id)),
  'inventory_receipt': (c, id) => pushRaw(c, ReceiptDetailScreen(id: id)),
  'tax_obligation': (c, id) => openApproval(c, {'kind': 'TAX', 'id': id}),
  'payroll_run': (c, id) => openApproval(c, {'kind': 'PAYROLL', 'id': id}),
  'expense': (c, id) => openApproval(c, {'kind': 'EXPENSE', 'id': id}),
  'vehicle_expense': (c, id) =>
      openApproval(c, {'kind': 'VEHICLE_EXPENSE', 'id': id}),
  'inventory_loss': (c, id) => openApproval(c, {'kind': 'LOSS', 'id': id}),
};

final _uuid = RegExp(
  r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
  caseSensitive: false,
);
const historyRecordTypes = {
  'order': 'Pedido',
  'customer': 'Cliente',
  'supplier': 'Fornecedor',
  'vehicle': 'Veículo',
  'trip': 'Viagem',
};

Future<void> openOrigin(BuildContext context, String? type, String? id) async {
  if (type == null || id == null || !_uuid.hasMatch(id)) return;
  final opener = originOpeners[type];
  if (opener != null) return opener(context, id);
  await pushScreen(
    context,
    'Histórico: ${labelOf(entityLabels, type)}',
    HistoryScreen(entityType: type, entityId: id),
  );
}

const _sectors = [
  ('COUNTER', 'Retirada, balcão e fornecedores', 'box', 'engenheiro-retirada'),
  (
    'DELIVERY',
    'Entregas, motoristas e veículos',
    'truck',
    'engenheiro-entregas',
  ),
  ('ACCOUNTING', 'Contabilidade e documentos', 'tax', 'engenheiro-contabil'),
];

/// P03 — central dos três setores: o que espera decisão e os últimos movimentos com autor e app, cada um abrindo a
/// origem. O dono age com a própria conta (a ação fica em nome dele, pelo app Patrão).
class OwnerCentralScreen extends StatelessWidget {
  const OwnerCentralScreen({super.key, this.enter = const {}});

  /// Atalhos "Entrar no setor" (rótulo → tela), por setor.
  final Map<String, List<(String, Widget)>> enter;
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return JsonView(
      load: (api) async {
        final ap = await api.getJson('/api/v1/approvals');
        final out = <String, dynamic>{'approvals': ap};
        if (me.can('audit.view')) {
          for (final s in _sectors) {
            out[s.$1] = await api.getJson('/api/v1/audit-events', {
              'sector': s.$1,
              'limit': '6',
              'result': 'SUCCESS',
            });
          }
        }
        return out;
      },
      builder: (context, d, reload) {
        final counts = {
          for (final s in items(d['approvals'] as J, 'sectors'))
            str(s['sector']): s['pending'] as int? ?? 0,
        };
        final pending = items(d['approvals'] as J).length;
        return [
          const Text(
            'Você acompanha e age em cada setor com a sua própria conta: o que fizer aqui fica registrado em seu nome.',
            style: TextStyle(color: EngColors.muted),
          ),
          Card(
            child: ListTile(
              leading: EngIcon(
                'shield',
                color: pending > 0 ? EngColors.warning : EngColors.green,
              ),
              title: Text(
                pending == 0
                    ? 'Nada esperando sua decisão'
                    : '$pending aprovação(ões) esperando você',
              ),
              subtitle: const Text('Todas as pendências, de todos os setores'),
              onTap: () async {
                await pushScreen(
                  context,
                  'Aprovações',
                  const ApprovalsScreen(),
                );
                reload();
              },
            ),
          ),
          for (final s in _sectors)
            Card(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        EngIcon(s.$3, color: EngColors.cyan),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            s.$2,
                            style: const TextStyle(
                              fontWeight: FontWeight.w700,
                              fontSize: 16,
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 4),
                    if ((counts[s.$1] ?? 0) > 0)
                      TextButton(
                        onPressed: () async {
                          await pushScreen(
                            context,
                            'Aprovações',
                            const ApprovalsScreen(),
                          );
                          reload();
                        },
                        child: Text('${counts[s.$1]} esperando sua decisão'),
                      )
                    else
                      const Text(
                        'Nada esperando sua decisão',
                        style: TextStyle(color: EngColors.muted),
                      ),
                    if (d[s.$1] != null) ...[
                      for (final e in items(d[s.$1] as J))
                        InkWell(
                          onTap: () => openOrigin(
                            context,
                            strOrNull(e['entity_type']),
                            strOrNull(e['entity_id']),
                          ),
                          child: Padding(
                            padding: const EdgeInsets.symmetric(vertical: 4),
                            child: Text(
                              '${brDateTime(e['occurred_at'])} · ${e['actor_name'] ?? 'Sistema'} ${labelOf(auditActionLabels, str(e['action']))}'
                              '${e['client_id'] == null ? '' : ' · ${labelOf(clientLabels, str(e['client_id']))}'}',
                            ),
                          ),
                        ),
                      if (items(d[s.$1] as J).isEmpty)
                        const Text(
                          'Sem movimentos ainda.',
                          style: TextStyle(color: EngColors.muted),
                        ),
                      TextButton(
                        onPressed: () => pushScreen(
                          context,
                          'Histórico: ${labelOf(sectorNames, s.$1)}',
                          HistoryScreen(sector: s.$1),
                        ),
                        child: const Text(
                          'Histórico do setor (filtrar pessoa)',
                        ),
                      ),
                    ] else
                      const Text(
                        'Histórico de movimentos exige a permissão de histórico.',
                        style: TextStyle(color: EngColors.muted),
                      ),
                    Wrap(
                      spacing: 8,
                      children: [
                        for (final (label, screen)
                            in enter[s.$1] ?? const <(String, Widget)>[])
                          OutlinedButton(
                            onPressed: () => pushScreen(context, label, screen),
                            child: Text(label),
                          ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
        ];
      },
    );
  }
}

/// P10 — cada movimento, com autor: filtros por período, pessoa, app, setor e registro; abre o detalhe com o que mudou.
class HistoryScreen extends StatefulWidget {
  const HistoryScreen({super.key, this.sector, this.entityType, this.entityId});
  final String? sector;
  final String? entityType;
  final String? entityId;
  @override
  State<HistoryScreen> createState() => _HistoryScreenState();
}

class _HistoryScreenState extends State<HistoryScreen> {
  String? _actor;
  String? _client;
  late String? _sector = widget.sector;
  String? _recordType;
  String? _result;
  final _from = TextEditingController();
  final _to = TextEditingController();
  Map<String, String> _people = const {};
  Map<String, String?> _q = const {};

  @override
  void initState() {
    super.initState();
    _apply();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_people.isEmpty && EngScope.of(context).me.can('users.view')) {
      _loadPeople();
    }
  }

  Future<void> _loadPeople() async {
    try {
      final p = await EngScope.of(context).api.users();
      if (mounted) {
        setState(
          () => _people = {for (final u in p.items) u.id: u.displayName},
        );
      }
    } catch (_) {}
  }

  void _apply() => setState(
    () => _q = {
      'actor_user_id': _actor,
      'client_id': _client,
      'sector': _sector,
      'entity_type': widget.entityType ?? _recordType,
      'result': _result,
      'from': _from.text.trim(),
      'to': _to.text.trim(),
      'entity_id': widget.entityId,
    },
  );

  Widget _drop(
    String label,
    String? value,
    Map<String, String> opts,
    void Function(String?) set,
  ) => DropdownButtonFormField<String?>(
    initialValue: value,
    isExpanded: true,
    decoration: InputDecoration(labelText: label),
    items: [
      const DropdownMenuItem<String?>(value: null, child: Text('Todos')),
      for (final e in opts.entries)
        DropdownMenuItem<String?>(
          value: e.key,
          child: Text(e.value, overflow: TextOverflow.ellipsis),
        ),
    ],
    onChanged: (v) {
      set(v);
      _apply();
    },
  );

  @override
  Widget build(BuildContext context) => CursorList(
    path: '/api/v1/audit-events',
    query: _q,
    empty: 'Nenhum movimento com esses filtros.',
    header: [
      if (widget.entityType == null)
        ExpansionTile(
          title: const Text('Filtros'),
          tilePadding: EdgeInsets.zero,
          children: [
            if (_people.isNotEmpty)
              _drop('Pessoa', _actor, _people, (v) => _actor = v),
            _drop('App de origem', _client, clientLabels, (v) => _client = v),
            _drop(
              'Tipo de registro',
              _recordType,
              historyRecordTypes,
              (v) => _recordType = v,
            ),
            _drop('Setor', _sector, const {
              'COUNTER': 'Retirada',
              'DELIVERY': 'Entregas',
              'ACCOUNTING': 'Contabilidade',
            }, (v) => _sector = v),
            _drop('Resultado', _result, const {
              'SUCCESS': 'Feito',
              'DENIED': 'Negado',
              'FAILURE': 'Falhou',
            }, (v) => _result = v),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _from,
                    decoration: const InputDecoration(
                      labelText: 'De (AAAA-MM-DD)',
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: TextField(
                    controller: _to,
                    decoration: const InputDecoration(
                      labelText: 'Até, exclusive',
                    ),
                  ),
                ),
              ],
            ),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(
                onPressed: _apply,
                child: const Text('Aplicar período'),
              ),
            ),
          ],
        ),
    ],
    itemBuilder: (context, e, reload) => Card(
      child: ListTile(
        title: Text(
          '${e['actor_name'] ?? (e['actor_kind'] == 'SERVICE' ? 'Integração' : 'Sistema')} — '
          '${labelOf(auditActionLabels, str(e['action']))}',
        ),
        subtitle: Text(
          [
            brDateTime(e['occurred_at']),
            if (e['client_id'] != null)
              labelOf(clientLabels, str(e['client_id'])),
            if (e['entity_type'] != null)
              labelOf(entityLabels, str(e['entity_type'])),
            if (e['result'] != 'SUCCESS')
              labelOf(const {
                'DENIED': 'NEGADO',
                'FAILURE': 'FALHOU',
              }, str(e['result'])),
          ].join(' · '),
        ),
        onTap: () => pushScreen(
          context,
          'Movimento',
          AuditEventScreen(id: str(e['id'])),
        ),
      ),
    ),
  );
}

/// Detalhe de um movimento: quem, quando, por qual app, o que mudou (campos autorizados; sensível sai "•••") e a origem.
class AuditEventScreen extends StatelessWidget {
  const AuditEventScreen({super.key, required this.id});
  final String id;
  @override
  Widget build(BuildContext context) => JsonView(
    load: (api) => api.getJson('/api/v1/audit-events/$id'),
    builder: (context, d, reload) {
      final e = d['event'] as J;
      final masked = (d['masked_fields'] as List? ?? const []).cast<String>();
      return [
        Text(
          labelOf(auditActionLabels, str(e['action'])),
          style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
        ),
        KV(
          'Quem',
          str(
            e['actor_name'] ??
                (e['actor_kind'] == 'SERVICE' ? 'Integração' : 'Sistema'),
          ),
        ),
        KV('Quando', brDateTime(e['occurred_at'])),
        if (e['client_id'] != null)
          KV('Por', labelOf(clientLabels, str(e['client_id']))),
        KV(
          'Resultado',
          labelOf(const {
            'SUCCESS': 'Feito',
            'DENIED': 'Negado',
            'FAILURE': 'Falhou',
          }, str(e['result'])),
        ),
        if (e['reason'] != null) KV('Motivo', str(e['reason'])),
        if (e['entity_type'] != null)
          KV('Registro', labelOf(entityLabels, str(e['entity_type']))),
        KV('Protocolo', str(e['request_id'])),
        const SectionTitle('O que mudou'),
        if (d['changes'] == null)
          const Text(
            'Sem detalhes registrados.',
            style: TextStyle(color: EngColors.muted),
          )
        else
          SelectableText(
            const JsonEncoder.withIndent('  ').convert(d['changes']),
            style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
          ),
        if (masked.isNotEmpty)
          Text(
            'Ocultos pela sua permissão de campos: ${masked.join(', ')}',
            style: const TextStyle(color: EngColors.muted, fontSize: 12),
          ),
        if (e['entity_type'] != null && e['entity_id'] != null) ...[
          const SizedBox(height: 12),
          FilledButton(
            onPressed: () =>
                openOrigin(context, str(e['entity_type']), str(e['entity_id'])),
            child: const Text('Abrir origem'),
          ),
          OutlinedButton(
            onPressed: () => pushScreen(
              context,
              'Histórico do registro',
              HistoryScreen(
                entityType: str(e['entity_type']),
                entityId: str(e['entity_id']),
              ),
            ),
            child: const Text('Todos os movimentos deste registro'),
          ),
        ],
      ];
    },
  );
}

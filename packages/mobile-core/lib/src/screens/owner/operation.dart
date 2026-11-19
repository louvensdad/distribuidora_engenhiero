import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:material_ui/material_ui.dart';
import '../../labels.dart';
import '../../scope.dart';
import '../delivery_screens.dart' show TripDetailScreen;
import '../inventory_screens.dart' show ReceiptsScreen;
import '../orders_screens.dart' show OrderDetailScreen;
import 'approvals.dart';
import 'central.dart';
import 'kit.dart';

const unitNames = {'UNIDADE': 'Unidade', 'DUZIA': 'Dúzia', 'FEIXE': 'Feixe', 'MACO': 'Maço', 'CAIXA': 'Caixa', 'SACO': 'Saco', 'KG': 'Kg',
  'TONELADA': 'Tonelada'};
const ownerOrderStateLabels = {'OPEN': 'Aberto', 'READY': 'Pronto', 'FULFILLED': 'Concluído', 'PARTIAL': 'Parcial', 'CANCELLED': 'Cancelado',
  'DISPATCHED': 'Em entrega'};
const ownerTripStateLabels = {'DRAFT': 'Rascunho', 'ASSIGNED': 'Atribuída', 'DEPARTED': 'Em rota', 'RETURNED': 'Retornou',
  'RECONCILED': 'Conferida', 'CANCELLED': 'Cancelada'};
const lossReasons = {'QUEBRA': 'Quebra', 'DETERIORACAO': 'Deterioração', 'AVARIA_TRANSPORTE': 'Avaria no transporte', 'FURTO': 'Furto',
  'ERRO_CONTAGEM': 'Erro de contagem', 'OUTRO': 'Outro'};

/// Trilha encadeada de um registro (pedido → reserva → entrega → recebimento → despesa) com autor e app.
List<Widget> trail(BuildContext context, List<J> events) => [
      if (events.isEmpty) const Text('Sem movimentos.', style: TextStyle(color: EngColors.muted)),
      for (final e in events)
        InkWell(
          onTap: () => openOrigin(context, strOrNull(e['entity_type']), strOrNull(e['entity_id'])),
          child: Padding(padding: const EdgeInsets.symmetric(vertical: 4), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('${labelOf(auditActionLabels, str(e['action']))} — ${e['actor_name'] ?? 'Sistema'}',
                style: const TextStyle(fontWeight: FontWeight.w600)),
            Text([brDateTime(e['occurred_at']), if (e['client_id'] != null) labelOf(clientLabels, str(e['client_id'])),
              if (e['entity_type'] != null) labelOf(entityLabels, str(e['entity_type']))].join(' · '),
                style: const TextStyle(color: EngColors.muted, fontSize: 12)),
          ])),
        ),
    ];

class OperationHubScreen extends StatelessWidget {
  const OperationHubScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    Widget item(String icon, String title, String sub, Widget screen) => Card(child: ListTile(
        leading: EngIcon(icon, color: EngColors.cyan), title: Text(title), subtitle: Text(sub),
        onTap: () => pushScreen(context, title, screen)));
    return ListView(padding: const EdgeInsets.all(16), children: [
      if (me.can('orders.pickup.view') || me.can('orders.delivery.view'))
        item('order', 'Pedidos', 'Retirada e entrega, com a trilha completa', const OwnerOrdersScreen()),
      if (me.can('customers.view')) item('users', 'Clientes', 'Cadastro, edição e arquivamento', const PartiesScreen(kind: 'customers')),
      if (me.can('suppliers.view')) item('supplier', 'Fornecedores', 'Cadastro, edição e arquivamento', const PartiesScreen(kind: 'suppliers')),
      if (me.can('catalog.view') || me.can('inventory.stock.view'))
        item('inventory', 'Produtos, preços e medidas', 'Preço por unidade, conversões e confirmação da medida', const ProductsScreen()),
      if (me.can('inventory.stock.view')) item('box', 'Estoque e perdas', 'Totais, movimentos, perdas e ajustes', const OwnerStockScreen()),
      if (me.can('inventory.receipts.view')) item('supplier', 'Recebimentos de fornecedor', 'Conferência, custo e devolução', const ReceiptsScreen()),
      if (me.can('vehicles.view')) item('car', 'Frota', 'Veículos, alertas, consumo, odômetro e gastos', const FleetScreen()),
      if (me.can('trips.view')) item('route', 'Viagens', 'Custos, rateio, conferência e trilha', const OwnerTripsScreen()),
    ]);
  }
}

class OwnerOrdersScreen extends StatefulWidget {
  const OwnerOrdersScreen({super.key});
  @override
  State<OwnerOrdersScreen> createState() => _OwnerOrdersScreenState();
}

class _OwnerOrdersScreenState extends State<OwnerOrdersScreen> {
  String? _type;
  final _q = TextEditingController();
  String _query = '';
  @override
  Widget build(BuildContext context) => CursorList(
        path: '/api/v1/orders',
        query: {'type': _type, 'q': _query},
        empty: 'Nenhum pedido.',
        header: [
          Row(children: [
            Expanded(child: TextField(controller: _q, decoration: const InputDecoration(labelText: 'Número ou cliente'),
                onSubmitted: (v) => setState(() => _query = v.trim()))),
            const SizedBox(width: 8),
            Expanded(child: DropdownButtonFormField<String?>(initialValue: _type, isExpanded: true, decoration: const InputDecoration(labelText: 'Tipo'),
                items: const [DropdownMenuItem(value: null, child: Text('Todos')), DropdownMenuItem(value: 'PICKUP', child: Text('Retirada')),
                  DropdownMenuItem(value: 'DELIVERY', child: Text('Entrega'))],
                onChanged: (v) => setState(() => _type = v))),
          ]),
        ],
        itemBuilder: (context, o, reload) => RowCard(
          title: 'Pedido #${o['number']} · ${o['customer_name']}',
          subtitle: '${o['type'] == 'DELIVERY' ? 'Entrega' : 'Retirada'} · ${labelOf(ownerOrderStateLabels, str(o['state']))} · '
              '${o['created_by_name'] ?? ''} · ${brDateTime(o['created_at'])}',
          trailing: formatMoney(strOrNull(o['total'])),
          onTap: () => pushScreen(context, 'Pedido #${o['number']}', OwnerOrderScreen(id: str(o['id']))),
        ),
      );
}

/// Pedido visto pelo dono: valores separados (reserva, cumprimento, pagamento) e a trilha encadeada com a origem de cada passo.
class OwnerOrderScreen extends StatelessWidget {
  const OwnerOrderScreen({super.key, required this.id});
  final String id;
  @override
  Widget build(BuildContext context) => JsonView(
        load: (api) async => {'o': await api.getJson('/api/v1/orders/$id'), 'h': await api.getJson('/api/v1/orders/$id/history')},
        builder: (context, d, reload) {
          final o = d['o'] as J;
          return [
            Text('Pedido #${o['number']} · ${o['customer_name']}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
            KV('Situação', labelOf(ownerOrderStateLabels, str(o['state']))),
            KV('Pagamento', labelOf(paymentStates, str(o['payment_state']))),
            KV('Total', formatMoney(strOrNull(o['total'])), strong: true),
            KV('Pago', formatMoney(strOrNull(o['paid_amount']))),
            if (o['pending_amount'] != null) KV('Aguardando conferência', formatMoney(strOrNull(o['pending_amount']))),
            KV('Em aberto', formatMoney(strOrNull(o['balance_due']))),
            KV('Cadastrado por', '${o['created_by_name']} · ${brDateTime(o['created_at'])}'),
            const SectionTitle('Itens'),
            for (final l in items(o, 'lines'))
              KV(str(l['product_name']), '${formatDecimal(strOrNull(l['quantity']))} ${unitLabel(str(l['unit']), strOrNull(l['quantity']))} · '
                  '${formatMoney(strOrNull(l['net']))} · entregue ${formatDecimal(strOrNull(l['fulfilled_quantity']))}'),
            const SizedBox(height: 8),
            OutlinedButton(onPressed: () async {
              await pushRaw(context, OrderDetailScreen(id: id));
              reload();
            }, child: const Text('Abrir pedido para agir (pagamento, retirada, cancelamento)')),
            const SectionTitle('Trilha: pedido → estoque → entrega → recebimento'),
            ...trail(context, items(d['h'] as J)),
          ];
        },
      );
}

/// Clientes ou fornecedores: lista com busca, cadastro, edição (versão) e arquivamento.
class PartiesScreen extends StatefulWidget {
  const PartiesScreen({super.key, required this.kind});
  /// 'customers' ou 'suppliers'
  final String kind;
  @override
  State<PartiesScreen> createState() => _PartiesScreenState();
}

class _PartiesScreenState extends State<PartiesScreen> {
  final _q = TextEditingController();
  String _query = '';
  Key _k = UniqueKey();
  bool get _cust => widget.kind == 'customers';

  List<FieldSpec> _fields(J? x) => [
        FieldSpec('name', 'Nome', required: true, initial: strOrNull(x?['name'])),
        FieldSpec('contact', 'Contato', initial: strOrNull(x?['contact'])),
        if (_cust) FieldSpec('address', 'Endereço', initial: strOrNull(x?['address'])),
        FieldSpec('personal_document', 'CPF/CNPJ', initial: strOrNull(x?['personal_document'])),
        if (x != null) FieldSpec('status', 'Situação', kind: FieldKind.select, options: const {'ACTIVE': 'Ativo', 'ARCHIVED': 'Arquivado'},
            initial: strOrNull(x['status'])),
      ];

  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    final res = _cust ? 'customers' : 'suppliers';
    return Scaffold(
      floatingActionButton: me.can('$res.create')
          ? FloatingActionButton.extended(
              icon: const EngIcon('plus', color: EngColors.background),
              label: Text(_cust ? 'Novo cliente' : 'Novo fornecedor'),
              onPressed: () async {
                final r = await openForm(context, title: _cust ? 'Novo cliente' : 'Novo fornecedor', fields: _fields(null),
                    submit: (api, v, key) => api.sendJson('POST', '/api/v1/${widget.kind}', v));
                if (r != null) setState(() => _k = UniqueKey());
              })
          : null,
      body: CursorList(
        key: _k,
        path: '/api/v1/${widget.kind}',
        query: {'q': _query},
        empty: 'Nenhum cadastro.',
        header: [TextField(controller: _q, decoration: const InputDecoration(labelText: 'Buscar'),
            onSubmitted: (v) => setState(() => _query = v.trim()))],
        itemBuilder: (context, x, reload) => RowCard(
          title: str(x['name']),
          subtitle: [if (x['contact'] != null) str(x['contact']), if (x['status'] != 'ACTIVE') labelOf(genericStateLabels, str(x['status'])),
            if ((x['masked_fields'] as List? ?? const []).isNotEmpty) 'campos ocultos pela permissão'].join(' · '),
          onTap: me.can('$res.edit_draft')
              ? () async {
                  final full = await runCommand(context, (api) => api.getJson('/api/v1/${widget.kind}/${x['id']}'));
                  if (full == null || !context.mounted) return;
                  final r = await openForm(context, title: 'Editar ${full['name']}', fields: _fields(full),
                      submit: (api, v, key) => api.sendJson('PUT', '/api/v1/${widget.kind}/${x['id']}', v, ifMatch: full['version']));
                  if (r != null) reload();
                }
              : null,
        ),
      ),
    );
  }
}

class ProductsScreen extends StatefulWidget {
  const ProductsScreen({super.key});
  @override
  State<ProductsScreen> createState() => _ProductsScreenState();
}

class _ProductsScreenState extends State<ProductsScreen> {
  final _q = TextEditingController();
  String _query = '';
  Key _k = UniqueKey();
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return Scaffold(
      floatingActionButton: me.can('catalog.create')
          ? FloatingActionButton.extended(
              icon: const EngIcon('plus', color: EngColors.background),
              label: const Text('Novo produto'),
              onPressed: () async {
                final r = await openForm(context, title: 'Novo produto', fields: const [
                  FieldSpec('sku', 'Código (SKU)', required: true),
                  FieldSpec('name', 'Nome', required: true),
                  FieldSpec('base_unit', 'Unidade de estoque', kind: FieldKind.select, required: true, options: unitNames),
                ], submit: (api, v, key) => api.sendJson('POST', '/api/v1/products', v));
                if (r != null) setState(() => _k = UniqueKey());
              })
          : null,
      body: CursorList(
        key: _k,
        path: '/api/v1/products',
        query: {'q': _query},
        empty: 'Nenhum produto.',
        header: [TextField(controller: _q, decoration: const InputDecoration(labelText: 'Buscar produto'),
            onSubmitted: (v) => setState(() => _query = v.trim()))],
        itemBuilder: (context, p, reload) => RowCard(
          title: '${p['name']} (${p['sku']})',
          subtitle: '${labelOf(unitNames, str(p['base_unit']))}${p['base_unit_confirmed'] == true ? '' : ' · medida NÃO confirmada'}'
              '${p['status'] == 'ACTIVE' ? '' : ' · ${p['status']}'}',
          onTap: () async {
            await pushScreen(context, str(p['name']), ProductScreen(id: str(p['id'])));
            reload();
          },
        ),
      ),
    );
  }
}

class ProductScreen extends StatefulWidget {
  const ProductScreen({super.key, required this.id});
  final String id;
  @override
  State<ProductScreen> createState() => _ProductScreenState();
}

class _ProductScreenState extends State<ProductScreen> {
  Key _k = UniqueKey();
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    final edit = me.can('catalog.edit_draft');
    return JsonView(key: _k, load: (api) => api.getJson('/api/v1/products/${widget.id}'), builder: (context, p, reload) {
      Future<void> form(String title, List<FieldSpec> f, String path) async {
        final r = await openForm(context, title: title, fields: f,
            submit: (api, v, key) => api.sendJson('POST', '/api/v1/products/${widget.id}/$path', v, ifMatch: p['version']));
        if (r != null) setState(() => _k = UniqueKey());
      }
      final unitOpts = {str(p['base_unit']): labelOf(unitNames, str(p['base_unit'])),
        for (final c in items(p, 'conversions')) str(c['unit']): labelOf(unitNames, str(c['unit']))};
      return [
        Text('${p['name']} (${p['sku']})', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
        KV('Unidade de estoque', labelOf(unitNames, str(p['base_unit']))),
        KV('Medida confirmada', p['base_unit_confirmed'] == true ? 'Sim, em ${brDateTime(p['unit_confirmed_at'])}' : 'Não'),
        if (p['base_unit_confirmed'] != true && edit)
          OutlinedButton(onPressed: () async {
            final r = await runCommand(context, (api) => api.sendJson('POST', '/api/v1/products/${widget.id}/unit-confirmation', null,
                ifMatch: p['version']), ok: 'Medida confirmada.');
            if (r != null) setState(() => _k = UniqueKey());
          }, child: const Text('Confirmar a unidade de estoque')),
        const SectionTitle('Preços de venda'),
        for (final x in items(p, 'prices'))
          KV(labelOf(unitNames, str(x['unit'])), '${formatMoney(strOrNull(x['price']))} desde ${brDateTime(x['valid_from'])}'),
        if (edit) OutlinedButton(onPressed: () => form('Novo preço', [
          FieldSpec('unit', 'Unidade', kind: FieldKind.select, options: unitOpts, initial: str(p['base_unit'])),
          const FieldSpec('price', 'Preço (R\$)', kind: FieldKind.money, required: true),
        ], 'prices'), child: const Text('Alterar preço')),
        const SectionTitle('Conversões'),
        for (final c in items(p, 'conversions'))
          KV(labelOf(unitNames, str(c['unit'])), '= ${formatDecimal(strOrNull(c['factor']))} ${unitLabel(str(p['base_unit']), strOrNull(c['factor']))}'),
        if (edit) OutlinedButton(onPressed: () => form('Nova conversão', [
          FieldSpec('unit', 'Unidade', kind: FieldKind.select, required: true, options: unitNames),
          const FieldSpec('factor', 'Equivale a (na unidade de estoque)', kind: FieldKind.decimal, required: true),
        ], 'conversions'), child: const Text('Adicionar conversão')),
        const SectionTitle('Histórico'),
        OutlinedButton(onPressed: () => pushScreen(context, 'Histórico do produto', HistoryScreen(entityType: 'product', entityId: widget.id)),
            child: const Text('Quem alterou preço e medidas')),
      ];
    });
  }
}

class OwnerStockScreen extends StatelessWidget {
  const OwnerStockScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return JsonView(load: (api) => api.getJson('/api/v1/inventory/balances/summary'), builder: (context, d, reload) => [
      for (final t in items(d, 'totals'))
        Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(labelOf(unitNames, str(t['base_unit'])), style: const TextStyle(fontWeight: FontWeight.w700)),
          KV('Físico', formatDecimal(strOrNull(t['physical']))),
          KV('Reservado', formatDecimal(strOrNull(t['reserved']))),
          KV('Disponível', formatDecimal(strOrNull(t['available'])), strong: true),
          KV('Perdas', formatDecimal(strOrNull(t['lost']))),
          KV('Produtos', str(t['products'])),
        ]))),
      RowCard(title: 'Movimentos', subtitle: 'Cada entrada e saída com autor, custo autorizado e origem',
          onTap: () => pushScreen(context, 'Movimentos de estoque', const MovementsScreen())),
      if (me.can('inventory.losses.view')) ...[
        RowCard(title: 'Perdas', subtitle: 'Registrar e decidir', onTap: () => pushScreen(context, 'Perdas', const LossesScreen())),
        RowCard(title: 'Ajustes de contagem', subtitle: 'Registrar e decidir',
            onTap: () => pushScreen(context, 'Ajustes de contagem', const AdjustmentsScreen())),
      ],
    ]);
  }
}

class MovementsScreen extends StatelessWidget {
  const MovementsScreen({super.key});
  @override
  Widget build(BuildContext context) => CursorList(
        path: '/api/v1/inventory/movements',
        empty: 'Nenhum movimento.',
        itemBuilder: (context, m, reload) => RowCard(
          title: '${m['product_name']} · ${labelOf(movementKinds, str(m['kind']))}',
          subtitle: '${brDateTime(m['occurred_at'])} · ${m['created_by_name']} · saldo ${formatDecimal(strOrNull(m['balance_after']))}'
              '${m['total_cost'] == null ? '' : ' · custo ${formatMoney(strOrNull(m['total_cost']))}'}',
          trailing: '${formatDecimal(strOrNull(m['quantity']))} ${unitLabel(str(m['base_unit']), strOrNull(m['quantity']))}',
          onTap: () => openOrigin(context, strOrNull(m['source_type']), strOrNull(m['source_id'])),
        ),
      );
}

Future<J?> _pickProductAndForm(BuildContext context, String title, List<FieldSpec> extra,
    Future<J> Function(dynamic api, Map<String, String> v, String key) submit) async {
  final api = EngScope.of(context).api;
  final page = await api.products(limit: 100);
  if (!context.mounted) return null;
  return openForm(context, title: title, fields: [
    FieldSpec('product_id', 'Produto', kind: FieldKind.select, required: true,
        options: {for (final p in page.items) p.id: '${p.name} (${p.sku})'}),
    ...extra,
  ], submit: (a, v, k) => submit(a, v, k));
}

class LossesScreen extends StatefulWidget {
  const LossesScreen({super.key});
  @override
  State<LossesScreen> createState() => _LossesScreenState();
}

class _LossesScreenState extends State<LossesScreen> {
  String? _status = 'PENDING';
  Key _k = UniqueKey();
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return Scaffold(
      floatingActionButton: me.can('inventory.losses.create')
          ? FloatingActionButton.extended(
              icon: const EngIcon('plus', color: EngColors.background),
              label: const Text('Registrar perda'),
              onPressed: () async {
                final r = await _pickProductAndForm(context, 'Registrar perda', const [
                  FieldSpec('quantity', 'Quantidade (unidade de estoque)', kind: FieldKind.decimal, required: true),
                  FieldSpec('reason', 'Motivo', kind: FieldKind.select, required: true, options: lossReasons),
                  FieldSpec('notes', 'Observação'),
                ], (api, v, key) => api.sendJson('POST', '/api/v1/inventory/losses', v, idempotencyKey: key));
                if (r != null) setState(() => _k = UniqueKey());
              })
          : null,
      body: CursorList(
        key: _k,
        path: '/api/v1/inventory/losses',
        query: {'status': _status},
        empty: 'Nenhuma perda.',
        header: [
          DropdownButtonFormField<String?>(initialValue: _status, isExpanded: true, decoration: const InputDecoration(labelText: 'Situação'),
              items: const [DropdownMenuItem(value: null, child: Text('Todas')), DropdownMenuItem(value: 'PENDING', child: Text('Pendentes')),
                DropdownMenuItem(value: 'APPROVED', child: Text('Aprovadas')), DropdownMenuItem(value: 'REJECTED', child: Text('Recusadas'))],
              onChanged: (v) => setState(() => _status = v)),
        ],
        itemBuilder: (context, l, reload) => RowCard(
          title: '${l['product_name']} · ${labelOf(lossReasons, str(l['reason']))}',
          subtitle: '${formatDecimal(strOrNull(l['quantity']))} ${unitLabel(str(l['unit']), strOrNull(l['quantity']))} · '
              '${labelOf(genericStateLabels, str(l['status']))} · ${l['created_by_name']}',
          trailing: l['total_cost'] == null ? null : formatMoney(strOrNull(l['total_cost'])),
          onTap: () async {
            await openApproval(context, {'kind': 'LOSS', 'id': l['id']});
            reload();
          },
        ),
      ),
    );
  }
}

class AdjustmentsScreen extends StatefulWidget {
  const AdjustmentsScreen({super.key});
  @override
  State<AdjustmentsScreen> createState() => _AdjustmentsScreenState();
}

class _AdjustmentsScreenState extends State<AdjustmentsScreen> {
  Key _k = UniqueKey();
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return Scaffold(
      floatingActionButton: me.can('inventory.losses.create')
          ? FloatingActionButton.extended(
              icon: const EngIcon('plus', color: EngColors.background),
              label: const Text('Registrar contagem'),
              onPressed: () async {
                final r = await _pickProductAndForm(context, 'Ajuste de contagem', const [
                  FieldSpec('counted_quantity', 'Quantidade contada', kind: FieldKind.decimal, required: true),
                  FieldSpec('unit_cost', 'Custo unitário (sobra)', kind: FieldKind.money),
                  FieldSpec('notes', 'Observação', required: true),
                ], (api, v, key) => api.sendJson('POST', '/api/v1/inventory/adjustments', v, idempotencyKey: key));
                if (r != null) setState(() => _k = UniqueKey());
              })
          : null,
      body: CursorList(
        key: _k,
        path: '/api/v1/inventory/adjustments',
        empty: 'Nenhum ajuste.',
        itemBuilder: (context, a, reload) => RowCard(
          title: '${a['product_name']} · diferença ${formatDecimal(strOrNull(a['difference']))}',
          subtitle: '${labelOf(genericStateLabels, str(a['status']))} · ${a['created_by_name']} · ${a['notes']}',
          onTap: a['status'] == 'PENDING'
              ? () async {
                  await openApproval(context, {'kind': 'ADJUSTMENT', 'id': a['id']});
                  reload();
                }
              : null,
        ),
      ),
    );
  }
}

class FleetScreen extends StatefulWidget {
  const FleetScreen({super.key});
  @override
  State<FleetScreen> createState() => _FleetScreenState();
}

class _FleetScreenState extends State<FleetScreen> {
  Key _k = UniqueKey();
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return Scaffold(
      floatingActionButton: me.can('vehicles.create')
          ? FloatingActionButton.extended(
              icon: const EngIcon('plus', color: EngColors.background),
              label: const Text('Novo veículo'),
              onPressed: () async {
                final r = await openForm(context, title: 'Novo veículo', fields: const [
                  FieldSpec('plate', 'Placa', required: true),
                  FieldSpec('model', 'Modelo', required: true),
                  FieldSpec('capacity_quantity', 'Capacidade', kind: FieldKind.decimal, required: true),
                  FieldSpec('capacity_unit', 'Unidade da capacidade', kind: FieldKind.select, required: true, options: unitNames),
                  FieldSpec('odometer_km', 'Odômetro (km)', kind: FieldKind.decimal, required: true),
                  FieldSpec('next_service_km', 'Próxima revisão (km)', kind: FieldKind.decimal),
                  FieldSpec('registration_valid_until', 'Licenciamento até', kind: FieldKind.date),
                  FieldSpec('insurance_valid_until', 'Seguro até', kind: FieldKind.date),
                ], submit: (api, v, key) => api.sendJson('POST', '/api/v1/vehicles', v, idempotencyKey: key));
                if (r != null) setState(() => _k = UniqueKey());
              })
          : null,
      body: JsonView(key: _k, load: (api) => api.getJson('/api/v1/vehicles'), builder: (context, d, reload) => [
        if (items(d).isEmpty) const EmptyState(title: 'Nenhum veículo'),
        for (final v in items(d))
          RowCard(
            title: '${v['plate']} · ${v['model']}',
            subtitle: [labelOf(genericStateLabels, str(v['status'])), '${formatDecimal(strOrNull(v['odometer_km']))} km',
              if (v['current_trip_number'] != null) 'em rota V${v['current_trip_number']} com ${v['current_driver_name']}',
              for (final a in (v['alerts'] as List? ?? const []).cast<J>()) '⚠ ${a['message']}'].join(' · '),
            onTap: () async {
              await pushScreen(context, str(v['plate']), OwnerVehicleScreen(id: str(v['id'])));
              reload();
            },
          ),
      ]),
    );
  }
}

class OwnerVehicleScreen extends StatefulWidget {
  const OwnerVehicleScreen({super.key, required this.id});
  final String id;
  @override
  State<OwnerVehicleScreen> createState() => _OwnerVehicleScreenState();
}

class _OwnerVehicleScreenState extends State<OwnerVehicleScreen> {
  Key _k = UniqueKey();
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return JsonView(key: _k, load: (api) async => {
          'v': await api.getJson('/api/v1/vehicles/${widget.id}'),
          'odo': await api.getJson('/api/v1/vehicles/${widget.id}/odometer'),
          'x': await api.getJson('/api/v1/vehicle-expenses', {'vehicle_id': widget.id}),
          'h': await api.getJson('/api/v1/vehicles/${widget.id}/history'),
        }, builder: (context, d, reload) {
      final v = d['v'] as J;
      final c = (v['consumption'] as J?) ?? const {};
      return [
        Text('${v['plate']} · ${v['model']}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
        KV('Situação', labelOf(genericStateLabels, str(v['status']))),
        KV('Odômetro', '${formatDecimal(strOrNull(v['odometer_km']))} km'),
        KV('Capacidade', '${formatDecimal(strOrNull(v['capacity_quantity']))} ${unitLabel(str(v['capacity_unit']), strOrNull(v['capacity_quantity']))}'),
        if (v['next_service_km'] != null) KV('Próxima revisão', '${formatDecimal(strOrNull(v['next_service_km']))} km'),
        if (v['registration_valid_until'] != null) KV('Licenciamento', brDate(v['registration_valid_until'])),
        if (v['insurance_valid_until'] != null) KV('Seguro', brDate(v['insurance_valid_until'])),
        KV('Consumo', c['km_per_liter'] == null ? str(c['message']) : '${formatDecimal(strOrNull(c['km_per_liter']))} km/l'),
        for (final a in (v['alerts'] as List? ?? const []).cast<J>()) Text('⚠ ${a['message']}', style: const TextStyle(color: EngColors.warning)),
        if (me.can('vehicles.edit_draft'))
          OutlinedButton(onPressed: () async {
            final r = await openForm(context, title: 'Mudar situação', fields: [
              FieldSpec('status', 'Situação', kind: FieldKind.select, required: true, initial: str(v['status']),
                  options: const {'ACTIVE': 'Ativo', 'MAINTENANCE': 'Em manutenção', 'INACTIVE': 'Inativo'}),
              const FieldSpec('reason', 'Motivo', required: true),
            ], submit: (api, x, key) => api.sendJson('POST', '/api/v1/vehicles/${widget.id}/status', x, ifMatch: v['version']));
            if (r != null) setState(() => _k = UniqueKey());
          }, child: const Text('Mudar situação')),
        const SectionTitle('Gastos'),
        if (items(d['x'] as J).isEmpty) const Text('Nenhum gasto.', style: TextStyle(color: EngColors.muted)),
        for (final x in items(d['x'] as J))
          RowCard(title: '${x['kind']} · ${brDate(x['occurred_on'])}', subtitle: '${labelOf(genericStateLabels, str(x['state']))} · ${x['created_by_name'] ?? ''}',
              trailing: formatMoney(strOrNull(x['amount'])), onTap: () async {
                await openApproval(context, {'kind': 'VEHICLE_EXPENSE', 'id': x['id']});
                setState(() => _k = UniqueKey());
              }),
        const SectionTitle('Odômetro'),
        for (final o in items(d['odo'] as J).take(10))
          KV(brDateTime(o['recorded_at']), '${formatDecimal(strOrNull(o['reading']))} km · ${o['kind']} · ${o['recorded_by_name'] ?? ''}'
              '${o['reason'] == null ? '' : ' · ${o['reason']}'}'),
        if (me.admin)
          OutlinedButton(onPressed: () async {
            final r = await openForm(context, title: 'Corrigir odômetro', fields: const [
              FieldSpec('reading', 'Leitura correta (km)', kind: FieldKind.decimal, required: true),
              FieldSpec('reason', 'Motivo', required: true),
            ], submit: (api, x, key) => api.sendJson('POST', '/api/v1/vehicles/${widget.id}/odometer-corrections', x));
            if (r != null) setState(() => _k = UniqueKey());
          }, child: const Text('Corrigir odômetro')),
        const SectionTitle('Histórico'),
        ...trail(context, items(d['h'] as J)),
      ];
    });
  }
}

class OwnerTripsScreen extends StatefulWidget {
  const OwnerTripsScreen({super.key});
  @override
  State<OwnerTripsScreen> createState() => _OwnerTripsScreenState();
}

class _OwnerTripsScreenState extends State<OwnerTripsScreen> {
  String? _state;
  @override
  Widget build(BuildContext context) => CursorList(
        path: '/api/v1/trips',
        query: {'state': _state},
        empty: 'Nenhuma viagem.',
        header: [
          DropdownButtonFormField<String?>(initialValue: _state, isExpanded: true, decoration: const InputDecoration(labelText: 'Situação'),
              items: [const DropdownMenuItem(value: null, child: Text('Todas')),
                for (final e in ownerTripStateLabels.entries) DropdownMenuItem(value: e.key, child: Text(e.value))],
              onChanged: (v) => setState(() => _state = v)),
        ],
        itemBuilder: (context, t, reload) => RowCard(
          title: 'V${t['number']} · ${t['plate'] ?? 'sem veículo'} · ${t['driver_name'] ?? 'sem motorista'}',
          subtitle: '${labelOf(ownerTripStateLabels, str(t['state']))} · ${brDate(t['planned_date'])}',
          onTap: () async {
            await pushScreen(context, 'Viagem V${t['number']}', OwnerTripScreen(id: str(t['id'])));
            reload();
          },
        ),
      );
}

/// Viagem vista pelo dono: dinheiro (esperado × declarado), custos e rateio, conferência, cancelamento e trilha.
class OwnerTripScreen extends StatefulWidget {
  const OwnerTripScreen({super.key, required this.id});
  final String id;
  @override
  State<OwnerTripScreen> createState() => _OwnerTripScreenState();
}

class _OwnerTripScreenState extends State<OwnerTripScreen> {
  Key _k = UniqueKey();
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return JsonView(key: _k, load: (api) async => {
          't': await api.getJson('/api/v1/trips/${widget.id}'),
          'h': await api.getJson('/api/v1/trips/${widget.id}/history'),
          if (me.can('expenses.view')) 'c': await api.getJson('/api/v1/trips/${widget.id}/costs'),
        }, builder: (context, d, reload) {
      final t = d['t'] as J;
      final c = d['c'] as J?;
      final state = str(t['state']);
      Future<void> act(String title, String path, bool required, String ok) async {
        final note = await askText(context, title: title, label: required ? 'Motivo' : 'Observação', required: required);
        if (note == null || !context.mounted) return;
        final r = await runCommand(context, (api) => api.sendJson('POST', path, {if (note.isNotEmpty) 'note': note}, ifMatch: t['version']), ok: ok);
        if (r != null) setState(() => _k = UniqueKey());
      }
      return [
        Text('Viagem V${t['number']} · ${labelOf(ownerTripStateLabels, state)}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
        KV('Veículo', '${t['plate'] ?? '—'} ${t['vehicle_model'] ?? ''}'),
        KV('Motorista', str(t['driver_name'] ?? '—')),
        KV('Carga', '${formatDecimal(strOrNull(t['load']))} de ${formatDecimal(strOrNull(t['capacity_quantity']))}'),
        if (t['distance_km'] != null) KV('Distância', '${formatDecimal(strOrNull(t['distance_km']))} km'),
        if (t['cash_expected'] != null) ...[
          KV('Dinheiro esperado', formatMoney(strOrNull(t['cash_expected']))),
          KV('Dinheiro entregue', formatMoney(strOrNull(t['cash_declared']))),
          KV('Diferença', formatMoney(strOrNull(t['cash_difference'])), strong: true),
        ],
        if (t['reconciled_by_name'] != null) KV('Conferida por', '${t['reconciled_by_name']} · ${brDateTime(t['reconciled_at'])}'),
        if (t['cancel_reason'] != null) KV('Cancelada', str(t['cancel_reason'])),
        const SectionTitle('Paradas'),
        for (final s in items(t, 'stops'))
          KV('#${s['seq']}', '${((s['order'] as J?) ?? const {})['customer_name'] ?? ''} · ${s['state']}'
              '${s['delivered_by_name'] == null ? '' : ' · ${s['delivered_by_name']}'}'),
        Wrap(spacing: 8, children: [
          OutlinedButton(onPressed: () async {
            await pushRaw(context, TripDetailScreen(id: widget.id));
            setState(() => _k = UniqueKey());
          }, child: const Text('Abrir viagem (operação)')),
          if (state == 'RETURNED' && me.can('trips.close'))
            FilledButton(onPressed: () => act('Conferir viagem', '/api/v1/trips/${widget.id}/reconcile', false, 'Viagem conferida.'),
                child: const Text('Conferir prestação')),
          if ((state == 'DRAFT' || state == 'ASSIGNED') && me.can('trips.assign'))
            OutlinedButton(onPressed: () => act('Cancelar viagem', '/api/v1/trips/${widget.id}/cancel', true, 'Viagem cancelada.'),
                child: const Text('Cancelar viagem')),
        ]),
        if (c != null) ...[
          const SectionTitle('Custos e rateio'),
          KV('Regra', '${c['rule_description']} (v${c['rule_version']})'),
          KV('Custo total', formatMoney(strOrNull(c['total_cost'])), strong: true),
          KV('Rateado', formatMoney(strOrNull(c['allocated_total']))),
          if (c['pending_cost'] != null) KV('Pendente de aprovação', formatMoney(strOrNull(c['pending_cost']))),
          if (c['cost_per_km'] != null) KV('Custo por km', formatMoney(strOrNull(c['cost_per_km']))),
          if (c['message'] != null) Text(str(c['message']), style: const TextStyle(color: EngColors.muted)),
          for (final l in items(c, 'lines'))
            KV('Pedido #${l['order_number']}', '${l['customer_name']} · ${formatMoney(strOrNull(l['allocated_cost']))}'),
        ],
        const SectionTitle('Trilha'),
        ...trail(context, items(d['h'] as J)),
      ];
    });
  }
}

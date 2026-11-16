import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:material_ui/material_ui.dart';
import '../labels.dart';
import '../scope.dart';

const orderStateLabels = {
  'CONFIRMED': 'Reservado', 'READY': 'Pronto', 'PARTIALLY_FULFILLED': 'Entregue em parte', 'FULFILLED': 'Concluído',
  'CANCELLED': 'Cancelado',
};
const paymentStateLabels = {
  'UNPAID': 'Não pago', 'PARTIALLY_PAID': 'Pago em parte', 'PAID': 'Pago', 'ON_ACCOUNT': 'A prazo autorizado',
  'REFUND_DUE': 'Devolver valor',
};

Widget _row(String label, Widget value) => Padding(
      padding: const EdgeInsets.only(top: 4),
      child: Row(children: [
        SizedBox(width: 120, child: Text(label, style: const TextStyle(color: EngColors.muted, fontSize: 13))),
        Expanded(child: value),
      ]),
    );

Future<bool?> _push(BuildContext context, Widget child) {
  final s = EngScope.of(context);
  return Navigator.of(context).push<bool>(MaterialPageRoute(builder: (_) => EngScope(config: s.config, session: s.session, child: child)));
}

/// R02 — atendimento de balcão: só os pedidos que a permissão alcança (próprios, para o balcão).
class OrdersScreen extends StatefulWidget {
  const OrdersScreen({super.key});
  @override
  State<OrdersScreen> createState() => _OrdersScreenState();
}

class _OrdersScreenState extends State<OrdersScreen> {
  List<OrderInfo>? _items;
  ApiException? _error;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_items == null && _error == null) _load();
  }

  Future<void> _load() async {
    setState(() {
      _items = null;
      _error = null;
    });
    try {
      final p = await EngScope.of(context).api.orders();
      if (mounted) setState(() => _items = p.items);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    Widget body;
    if (_error != null) {
      body = _error!.isForbidden
          ? const ForbiddenStateView()
          : ErrorStateView(message: _error!.message, requestId: _error!.requestId, offline: _error!.isNetwork, onRetry: _load);
    } else if (_items == null) {
      body = const LoadingState();
    } else if (_items!.isEmpty) {
      body = const EmptyState(title: 'Nenhum pedido');
    } else {
      body = RefreshIndicator(
        onRefresh: _load,
        child: ListView.separated(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 96),
          itemCount: _items!.length,
          separatorBuilder: (_, _) => const SizedBox(height: 8),
          itemBuilder: (context, i) {
            final o = _items![i];
            return Card(
              child: InkWell(
                onTap: () async {
                  await _push(context, OrderDetailScreen(id: o.id));
                  _load();
                },
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text('#${o.number} ${o.customerName}', style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 16)),
                    Text('${o.lines.isEmpty ? '' : formatQty(o.lines.first.quantity, o.lines.first.unit)} · ${formatMoney(o.total)}'),
                    _row('Situação', Text(orderStateLabels[o.state] ?? o.state)),
                    _row('Pagamento', Text(paymentStateLabels[o.paymentState] ?? o.paymentState,
                        style: TextStyle(color: o.paymentState == 'PAID' ? EngColors.green : EngColors.violet))),
                  ]),
                ),
              ),
            );
          },
        ),
      );
    }
    return Scaffold(
      floatingActionButton: me.can('orders.pickup.create')
          ? FloatingActionButton.extended(
              onPressed: () async {
                final created = await _push(context, const NewOrderScreen());
                if (created == true) _load();
              },
              icon: const EngIcon('plus', color: EngColors.background),
              label: const Text('Novo pedido'),
            )
          : null,
      body: body,
    );
  }
}

/// Novo pedido de retirada: preço e total calculados pelo servidor; reserva o estoque ao salvar.
class NewOrderScreen extends StatefulWidget {
  const NewOrderScreen({super.key});
  @override
  State<NewOrderScreen> createState() => _NewOrderScreenState();
}

class _NewOrderScreenState extends State<NewOrderScreen> {
  List<Customer>? _customers;
  List<Product>? _products;
  ApiException? _loadError;
  String? _customer;
  String? _product;
  String _unit = '';
  final _qty = TextEditingController();
  final _key = ApiClient.newIdempotencyKey();
  bool _busy = false;
  ApiException? _error;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_customers == null && _loadError == null) _load();
  }

  Future<void> _load() async {
    final api = EngScope.of(context).api;
    try {
      final c = await api.customersAll();
      final p = await api.products();
      if (mounted) {
        setState(() {
          _customers = c.items;
          _products = p.items.where((x) => x.baseUnitConfirmed).toList();
        });
      }
    } on ApiException catch (e) {
      if (mounted) setState(() => _loadError = e);
    }
  }

  String? _fe(String f) => _error?.fieldErrors.where((e) => e.field == f).map((e) => e.message).firstOrNull;

  Future<void> _save() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final o = await EngScope.of(context).api.createOrder(
          idempotencyKey: _key, customerId: _customer ?? '', items: [(productId: _product ?? '', unit: _unit, quantity: _qty.text)]);
      if (!mounted) return;
      final s = EngScope.of(context);
      await Navigator.of(context).pushReplacement(MaterialPageRoute<bool>(
          builder: (_) => EngScope(config: s.config, session: s.session, child: OrderDetailScreen(id: o.id))), result: true);
    } on ApiException catch (e) {
      if (mounted) {
        setState(() {
          _error = e;
          _busy = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = _products?.where((x) => x.id == _product).firstOrNull;
    final unit = _unit.isEmpty ? p?.baseUnit : _unit;
    final price = p?.prices[unit];
    Widget body;
    if (_loadError != null) {
      body = ErrorStateView(message: _loadError!.message, requestId: _loadError!.requestId, offline: _loadError!.isNetwork, onRetry: _load);
    } else if (_customers == null) {
      body = const LoadingState();
    } else {
      body = ListView(padding: const EdgeInsets.all(16), children: [
        DropdownButtonFormField<String>(
          initialValue: _customer,
          isExpanded: true,
          decoration: InputDecoration(labelText: 'Cliente *', errorText: _fe('customer_id')),
          items: [for (final c in _customers!) DropdownMenuItem(value: c.id, child: Text(c.name, overflow: TextOverflow.ellipsis))],
          onChanged: (v) => setState(() => _customer = v),
        ),
        const SizedBox(height: 12),
        DropdownButtonFormField<String>(
          initialValue: _product,
          isExpanded: true,
          decoration: InputDecoration(labelText: 'Produto *', errorText: _fe('items[0].product_id')),
          items: [for (final x in _products!) DropdownMenuItem(value: x.id, child: Text(x.name, overflow: TextOverflow.ellipsis))],
          onChanged: (v) => setState(() {
            _product = v;
            _unit = '';
          }),
        ),
        if (p != null)
          DropdownButtonFormField<String>(
            initialValue: _unit,
            isExpanded: true,
            decoration: const InputDecoration(labelText: 'Medida'),
            items: [
              DropdownMenuItem(value: '', child: Text(unitLabel(p.baseUnit))),
              for (final c in p.conversions) DropdownMenuItem(value: c.unit, child: Text(unitLabel(c.unit))),
            ],
            onChanged: (v) => setState(() => _unit = v ?? ''),
          ),
        if (p != null)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(price == null ? 'Sem preço nesta medida' : 'Preço vigente: ${formatMoney(price)} por ${unitLabel(unit!, '1')}',
                style: const TextStyle(color: EngColors.muted)),
          ),
        TextField(controller: _qty, keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(labelText: 'Quantidade *', errorText: _fe('items[0].quantity'))),
        if (_error != null && _error!.fieldErrors.isEmpty)
          Padding(padding: const EdgeInsets.only(top: 8),
              child: Semantics(liveRegion: true, child: Text(_error!.message, style: const TextStyle(color: EngColors.error)))),
        const SizedBox(height: 16),
        FilledButton(onPressed: _busy ? null : _save, child: Text(_busy ? 'Salvando…' : 'Salvar e reservar')),
        const SizedBox(height: 8),
        const Text('O total é calculado pelo servidor com a tabela vigente.', style: TextStyle(color: EngColors.muted, fontSize: 12)),
      ]);
    }
    return Scaffold(appBar: AppBar(title: const Text('Novo pedido')), body: body);
  }
}

/// Pedido: R04 — receber em dinheiro (troco calculado pelo servidor) ou Pix (aguarda conferência) e registrar a retirada.
class OrderDetailScreen extends StatefulWidget {
  const OrderDetailScreen({super.key, required this.id});
  final String id;
  @override
  State<OrderDetailScreen> createState() => _OrderDetailScreenState();
}

class _OrderDetailScreenState extends State<OrderDetailScreen> {
  OrderInfo? _o;
  ApiException? _error;
  String _method = 'CASH';
  final _presented = TextEditingController();
  String _payKey = ApiClient.newIdempotencyKey();
  String _fulfillKey = ApiClient.newIdempotencyKey();
  bool _busy = false;
  String? _change;
  String? _payMessage;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_o == null && _error == null) _load();
  }

  Future<void> _load() async {
    try {
      final o = await EngScope.of(context).api.order(widget.id);
      if (mounted) setState(() => _o = o);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  Future<void> _pay() async {
    final api = EngScope.of(context).api;
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _busy = true);
    try {
      final r = await api.payOrder(orderId: widget.id, idempotencyKey: _payKey, method: _method, amount: _o!.balanceDue,
          presentedCash: _method == 'CASH' ? _presented.text : null);
      setState(() {
        _o = r.order;
        _change = r.payments.first.changeGiven;
        _payMessage = r.payments.first.state == 'PENDING' ? 'Pix registrado: aguardando conferência. Ainda não está pago.' : null;
        _payKey = ApiClient.newIdempotencyKey();
      });
    } on ApiException catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(e.isNetwork ? 'Sem conexão. Nada foi registrado; tente de novo.' : e.message)));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _fulfill() async {
    final api = EngScope.of(context).api;
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _busy = true);
    try {
      final o = await api.fulfillOrder(orderId: widget.id, idempotencyKey: _fulfillKey);
      setState(() {
        _o = o;
        _fulfillKey = ApiClient.newIdempotencyKey();
      });
      messenger.showSnackBar(const SnackBar(content: Text('Retirada registrada.')));
    } on ApiException catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(e.message)));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    final o = _o;
    Widget body;
    if (_error != null) {
      body = _error!.isForbidden || _error!.status == 404
          ? const ForbiddenStateView()
          : ErrorStateView(message: _error!.message, requestId: _error!.requestId, onRetry: _load);
    } else if (o == null) {
      body = const LoadingState();
    } else {
      body = ListView(padding: const EdgeInsets.all(16), children: [
        Text(o.customerName, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
        _row('Situação', Text(orderStateLabels[o.state] ?? o.state)),
        _row('Pagamento', Text(paymentStateLabels[o.paymentState] ?? o.paymentState,
            style: TextStyle(color: o.paymentState == 'PAID' ? EngColors.green : EngColors.violet))),
        _row('Cadastrado por', Text(o.createdByName)),
        const SizedBox(height: 8),
        for (final l in o.lines)
          Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(l.productName, style: const TextStyle(fontWeight: FontWeight.w600)),
            _row('Quantidade', Text('${formatQty(l.quantity, l.unit)} × ${formatMoney(l.unitPrice)}')),
            _row('Valor', Text(formatMoney(l.net))),
            _row('Pendente', Text(formatQty(l.remaining, l.unit))),
          ]))),
        _row('Total', Text(formatMoney(o.total), style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 18))),
        _row('Pago', Text(formatMoney(o.paid))),
        if (o.pending != '0.00') _row('Aguardando', Text(formatMoney(o.pending))),
        if (_change != null && _change != '0.00')
          Semantics(
            liveRegion: true,
            child: Card(
              color: EngColors.surfaceRaised,
              child: Padding(padding: const EdgeInsets.all(16), child: Column(children: [
                const Text('Troco a entregar', style: TextStyle(color: EngColors.muted)),
                Text(formatMoney(_change), style: const TextStyle(fontSize: 32, fontWeight: FontWeight.w800, color: EngColors.green)),
              ])),
            ),
          ),
        if (_payMessage != null) Padding(padding: const EdgeInsets.only(top: 8), child: Text(_payMessage!, style: const TextStyle(color: EngColors.warning))),
        if (o.open && o.owes && (me.can('payments.confirm') || me.can('payments.create'))) ...[
          const SizedBox(height: 16),
          Text('Receber ${formatMoney(o.balanceDue)}', style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 16)),
          SegmentedButton<String>(
            segments: const [ButtonSegment(value: 'CASH', label: Text('Dinheiro')), ButtonSegment(value: 'PIX', label: Text('Pix'))],
            selected: {_method},
            onSelectionChanged: (v) => setState(() => _method = v.first),
          ),
          if (_method == 'CASH')
            TextField(controller: _presented, keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: const InputDecoration(labelText: 'Dinheiro apresentado (R\$) *', helperText: 'O troco é calculado pelo servidor')),
          const SizedBox(height: 8),
          FilledButton(onPressed: _busy ? null : _pay, child: Text(_busy ? 'Registrando…' : 'Registrar recebimento')),
        ],
        if (o.open && me.can('orders.pickup.confirm')) ...[
          const SizedBox(height: 16),
          OutlinedButton(onPressed: _busy ? null : _fulfill, child: const Text('Registrar retirada')),
          const Text('Só é entregue o que está pago (Pix aguardando não conta).', style: TextStyle(color: EngColors.muted, fontSize: 12)),
        ],
      ]);
    }
    return Scaffold(appBar: AppBar(title: Text(o == null ? 'Pedido' : 'Pedido #${o.number}')), body: body);
  }
}

/// R08 — meu caixa do turno: fundo, recebimentos próprios em dinheiro, esperado e contagem. Sem saldo da empresa.
class CashScreen extends StatefulWidget {
  const CashScreen({super.key});
  @override
  State<CashScreen> createState() => _CashScreenState();
}

class _CashScreenState extends State<CashScreen> {
  CashSessionInfo? _s;
  bool _loaded = false;
  ApiException? _error;
  final _fund = TextEditingController();
  final _counted = TextEditingController();
  final _just = TextEditingController();
  String _key = ApiClient.newIdempotencyKey();
  bool _busy = false;
  String? _closedMessage;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_loaded && _error == null) _load();
  }

  Future<void> _load() async {
    try {
      final s = await EngScope.of(context).api.currentCash();
      if (mounted) {
        setState(() {
          _s = s;
          _loaded = true;
        });
      }
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  Future<void> _run(Future<void> Function() f) async {
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _busy = true);
    try {
      await f();
      _key = ApiClient.newIdempotencyKey();
    } on ApiException catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(e.message)));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final api = EngScope.of(context).api;
    if (_error != null) {
      return _error!.isForbidden ? const ForbiddenStateView() : ErrorStateView(message: _error!.message, requestId: _error!.requestId, onRetry: _load);
    }
    if (!_loaded) return const LoadingState();
    final s = _s;
    return ListView(padding: const EdgeInsets.all(16), children: [
      if (_closedMessage != null) Card(child: Padding(padding: const EdgeInsets.all(12), child: Text(_closedMessage!))),
      if (s == null) ...[
        const Text('Nenhum caixa aberto.', style: TextStyle(fontSize: 16)),
        TextField(controller: _fund, keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(labelText: 'Fundo de troco (R\$) *')),
        const SizedBox(height: 12),
        FilledButton(
          onPressed: _busy ? null : () => _run(() async {
            final n = await api.openCash(idempotencyKey: _key, openingFloat: _fund.text);
            setState(() {
              _s = n;
              _closedMessage = null;
            });
          }),
          child: const Text('Abrir caixa'),
        ),
      ] else ...[
        _row('Fundo', Text(formatMoney(s.openingFloat))),
        _row('Recebido em dinheiro', Text(formatMoney(s.cashIn))),
        _row('Saídas (devoluções e gastos)', Text(formatMoney(s.cashOut))),
        _row('Esperado', Text(formatMoney(s.expected), style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800, color: EngColors.green))),
        const SizedBox(height: 16),
        TextField(controller: _counted, keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(labelText: 'Valor contado (R\$) *')),
        TextField(controller: _just, decoration: const InputDecoration(labelText: 'Justificativa (se houver diferença)')),
        const SizedBox(height: 12),
        FilledButton(
          onPressed: _busy ? null : () => _run(() async {
            final c = await api.closeCash(id: s.id, version: s.version, idempotencyKey: _key, counted: _counted.text, justification: _just.text);
            setState(() {
              _s = null;
              _closedMessage = 'Caixa fechado: esperado ${formatMoney(c.expected)}, contado ${formatMoney(c.counted)}, '
                  'diferença ${formatMoney(c.difference)}. Enviado para conferência.';
            });
          }),
          child: const Text('Fechar e enviar para conferência'),
        ),
      ],
    ]);
  }
}

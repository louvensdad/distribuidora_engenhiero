import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:material_ui/material_ui.dart';
import '../labels.dart';
import '../scope.dart';
import 'reports_screens.dart' show isoDate, todaySaoPaulo;

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

/// R02/R09 — pedidos que a permissão alcança (próprios, para o balcão), com busca por número ou cliente, período
/// (dia civil de São Paulo) e paginação. Situação e pagamento sempre vêm do servidor.
class OrdersScreen extends StatefulWidget {
  /// null = todos os tipos no escopo; 'DELIVERY' no app Entregas.
  const OrdersScreen({super.key, this.type});
  final String? type;
  @override
  State<OrdersScreen> createState() => _OrdersScreenState();
}

const orderPeriods = {'today': 'Hoje', 'week': '7 dias', 'month': '30 dias', 'all': 'Todos'};

class _OrdersScreenState extends State<OrdersScreen> {
  List<OrderInfo>? _items;
  String? _cursor;
  ApiException? _error;
  ApiException? _moreError;
  bool _loadingMore = false;
  String _period = 'today';
  String _query = '';
  final _search = TextEditingController();

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_items == null && _error == null) _load();
  }

  ({String? from, String? to}) get _range {
    final today = todaySaoPaulo();
    final to = isoDate(today.add(const Duration(days: 1)));
    return switch (_period) {
      'today' => (from: isoDate(today), to: to),
      'week' => (from: isoDate(today.subtract(const Duration(days: 6))), to: to),
      'month' => (from: isoDate(today.subtract(const Duration(days: 29))), to: to),
      _ => (from: null, to: null),
    };
  }

  Future<ApiPage<OrderInfo>> _fetch({String? cursor}) {
    final r = _range;
    return EngScope.of(context).api.orders(limit: 25, query: _query.isEmpty ? null : _query, from: r.from, to: r.to, cursor: cursor,
        type: widget.type);
  }

  Future<void> _load() async {
    setState(() {
      _items = null;
      _error = null;
      _moreError = null;
    });
    try {
      final p = await _fetch();
      if (mounted) {
        setState(() {
          _items = p.items;
          _cursor = p.nextCursor;
        });
      }
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  Future<void> _more() async {
    setState(() {
      _loadingMore = true;
      _moreError = null;
    });
    try {
      final p = await _fetch(cursor: _cursor);
      if (mounted) {
        setState(() {
          _items = [...?_items, ...p.items];
          _cursor = p.nextCursor;
        });
      }
    } on ApiException catch (e) {
      // O que já foi carregado continua na tela.
      if (mounted) setState(() => _moreError = e);
    } finally {
      if (mounted) setState(() => _loadingMore = false);
    }
  }

  Widget _filters() => Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          TextField(
            controller: _search,
            textInputAction: TextInputAction.search,
            decoration: InputDecoration(
              labelText: 'Buscar pelo número ou cliente',
              prefixIcon: const Padding(padding: EdgeInsets.all(12), child: EngIcon('search', size: 18)),
              suffixIcon: _query.isEmpty
                  ? null
                  : IconButton(
                      tooltip: 'Limpar busca',
                      icon: const EngIcon('close', size: 18, semanticLabel: 'Limpar busca'),
                      onPressed: () {
                        _search.clear();
                        _query = '';
                        _load();
                      },
                    ),
            ),
            onSubmitted: (v) {
              _query = v.trim();
              _load();
            },
          ),
          const SizedBox(height: 8),
          Wrap(spacing: 8, runSpacing: 4, children: [
            for (final e in orderPeriods.entries)
              ChoiceChip(
                label: Text(e.value),
                selected: _period == e.key,
                onSelected: (_) {
                  _period = e.key;
                  _load();
                },
              ),
          ]),
        ]),
      );

  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    Widget list;
    if (_error != null) {
      list = _error!.isForbidden
          ? const ForbiddenStateView()
          : ErrorStateView(message: _error!.message, requestId: _error!.requestId, offline: _error!.isNetwork, onRetry: _load);
    } else if (_items == null) {
      list = const LoadingState();
    } else if (_items!.isEmpty) {
      list = EmptyState(
          title: _query.isNotEmpty ? 'Nada encontrado para “$_query”' : (_period == 'all' ? 'Nenhum pedido' : 'Nenhum pedido neste período'));
    } else {
      final n = _items!.length;
      list = RefreshIndicator(
        onRefresh: _load,
        child: ListView.separated(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 96),
          itemCount: n + 1,
          separatorBuilder: (_, _) => const SizedBox(height: 8),
          itemBuilder: (context, i) {
            if (i == n) {
              if (_moreError != null) {
                return Column(children: [
                  Text(_moreError!.isNetwork ? 'Sem conexão. Os pedidos acima continuam valendo.' : _moreError!.message,
                      style: const TextStyle(color: EngColors.error)),
                  TextButton(onPressed: _more, child: const Text('Tentar novamente')),
                ]);
              }
              if (_cursor != null) {
                return OutlinedButton(onPressed: _loadingMore ? null : _more, child: Text(_loadingMore ? 'Carregando…' : 'Carregar mais'));
              }
              return Text('$n pedido(s) — fim da lista', textAlign: TextAlign.center, style: const TextStyle(color: EngColors.muted, fontSize: 12));
            }
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
                    Text('${o.lines.isEmpty ? '' : formatQty(o.lines.first.quantity, o.lines.first.unit)} · ${formatMoney(o.total)}'
                        '${o.createdAt == null ? '' : ' · ${formatDateTime(DateTime.parse(o.createdAt!))}'}'),
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
      floatingActionButton: me.can(widget.type == 'DELIVERY' ? 'orders.delivery.create' : 'orders.pickup.create')
          ? FloatingActionButton.extended(
              onPressed: () async {
                final created = await _push(context, NewOrderScreen(type: widget.type == 'DELIVERY' ? 'DELIVERY' : 'PICKUP'));
                if (created == true) _load();
              },
              icon: const EngIcon('plus', color: EngColors.background),
              label: const Text('Novo pedido'),
            )
          : null,
      body: Column(children: [_filters(), Expanded(child: list)]),
    );
  }
}

/// Novo pedido (retirada ou entrega): preço e total calculados pelo servidor; reserva o estoque ao salvar.
/// Cliente e produto por busca no servidor (a lista não fica presa aos primeiros cadastros).
class NewOrderScreen extends StatefulWidget {
  const NewOrderScreen({super.key, this.type = 'PICKUP'});
  final String type;
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
  final _customerSearch = TextEditingController();
  final _productSearch = TextEditingController();
  final _address = TextEditingController();
  final _reference = TextEditingController();
  final _contact = TextEditingController();
  String? _agreed;
  final Map<String, Customer> _seenCustomers = {};
  final Map<String, Product> _seenProducts = {};
  bool get _delivery => widget.type == 'DELIVERY';

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_customers == null && _loadError == null) _load();
  }

  Future<void> _searchCustomers() async {
    try {
      final c = await EngScope.of(context).api.customers(query: _customerSearch.text.trim().isEmpty ? null : _customerSearch.text.trim());
      if (mounted) {
        setState(() {
          for (final x in c.items) {
            _seenCustomers[x.id] = x;
          }
          _customers = [if (_customer != null && !c.items.any((x) => x.id == _customer)) _seenCustomers[_customer]!, ...c.items];
        });
      }
    } on ApiException catch (e) {
      if (mounted) setState(() => _loadError = e);
    }
  }

  Future<void> _searchProducts() async {
    try {
      final p = await EngScope.of(context).api.products(limit: 25, query: _productSearch.text.trim().isEmpty ? null : _productSearch.text.trim());
      final ok = p.items.where((x) => x.baseUnitConfirmed).toList();
      if (mounted) {
        setState(() {
          for (final x in ok) {
            _seenProducts[x.id] = x;
          }
          _products = [if (_product != null && !ok.any((x) => x.id == _product)) _seenProducts[_product]!, ...ok];
        });
      }
    } on ApiException catch (e) {
      if (mounted) setState(() => _loadError = e);
    }
  }

  Future<void> _load() async {
    final api = EngScope.of(context).api;
    try {
      final c = await api.customers();
      final p = await api.products(limit: 25);
      for (final x in c.items) {
        _seenCustomers[x.id] = x;
      }
      for (final x in p.items) {
        _seenProducts[x.id] = x;
      }
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
          idempotencyKey: _key, customerId: _customer ?? '', items: [(productId: _product ?? '', unit: _unit, quantity: _qty.text)],
          type: widget.type,
          delivery: _delivery
              ? (address: _address.text, reference: _reference.text, contact: _contact.text, agreedPayment: _agreed)
              : null);
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
        TextField(
          controller: _customerSearch,
          textInputAction: TextInputAction.search,
          decoration: const InputDecoration(labelText: 'Buscar cliente', prefixIcon: Padding(padding: EdgeInsets.all(12), child: EngIcon('search', size: 18))),
          onSubmitted: (_) => _searchCustomers(),
        ),
        DropdownButtonFormField<String>(
          key: ValueKey('c${_customers!.length}${_customers!.firstOrNull?.id}'),
          initialValue: _customer,
          isExpanded: true,
          decoration: InputDecoration(labelText: 'Cliente *', errorText: _fe('customer_id')),
          items: [for (final c in _customers!) DropdownMenuItem(value: c.id, child: Text(c.name, overflow: TextOverflow.ellipsis))],
          onChanged: (v) => setState(() {
            _customer = v;
            final c = v == null ? null : _seenCustomers[v];
            if (_delivery && c != null) {
              if (_address.text.isEmpty && c.address != null) _address.text = c.address!;
              if (_contact.text.isEmpty && c.contact != null) _contact.text = c.contact!;
            }
          }),
        ),
        if (_delivery) ...[
          const SizedBox(height: 12),
          if (EngScope.of(context).me.canSeeField('delivery_address')) ...[
            TextField(controller: _address, decoration: InputDecoration(labelText: 'Endereço de entrega *', errorText: _fe('delivery.address'))),
            TextField(controller: _reference, decoration: const InputDecoration(labelText: 'Referência')),
          ] else
            const Text('Endereço: sua permissão não inclui endereço de entrega.', style: TextStyle(color: EngColors.warning)),
          if (EngScope.of(context).me.canSeeField('operational_contact'))
            TextField(controller: _contact, decoration: const InputDecoration(labelText: 'Contato no local')),
          DropdownButtonFormField<String>(
            initialValue: _agreed,
            isExpanded: true,
            decoration: InputDecoration(labelText: 'Pagamento combinado *', errorText: _fe('delivery.agreed_payment')),
            items: const [
              DropdownMenuItem(value: 'CASH', child: Text('Dinheiro na entrega')),
              DropdownMenuItem(value: 'PIX', child: Text('Pix')),
              DropdownMenuItem(value: 'DEBIT', child: Text('Débito na entrega')),
              DropdownMenuItem(value: 'CREDIT', child: Text('Crédito na entrega')),
            ],
            onChanged: (v) => setState(() => _agreed = v),
          ),
        ],
        const SizedBox(height: 12),
        TextField(
          controller: _productSearch,
          textInputAction: TextInputAction.search,
          decoration: const InputDecoration(labelText: 'Buscar produto', prefixIcon: Padding(padding: EdgeInsets.all(12), child: EngIcon('search', size: 18))),
          onSubmitted: (_) => _searchProducts(),
        ),
        DropdownButtonFormField<String>(
          key: ValueKey('p${_products!.length}${_products!.firstOrNull?.id}'),
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
              child: Semantics(liveRegion: true, child: Text(
                  _error!.isNetwork
                      ? 'Sem resposta do servidor: o pedido pode ou não ter sido salvo. Toque em salvar de novo — '
                          'o mesmo pedido não é criado duas vezes.'
                      : _error!.message,
                  style: const TextStyle(color: EngColors.error)))),
        const SizedBox(height: 16),
        FilledButton(onPressed: _busy ? null : _save, child: Text(_busy ? 'Salvando…' : 'Salvar e reservar')),
        const SizedBox(height: 8),
        const Text('O total é calculado pelo servidor com a tabela vigente.', style: TextStyle(color: EngColors.muted, fontSize: 12)),
      ]);
    }
    return Scaffold(appBar: AppBar(title: Text(_delivery ? 'Novo pedido de entrega' : 'Novo pedido')), body: body);
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
  String? _uncertainMsg;

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
        _uncertainMsg = null;
        _o = r.order;
        _change = r.payments.first.changeGiven;
        _payMessage = r.payments.first.state == 'PENDING' ? 'Pix registrado: aguardando conferência. Ainda não está pago.' : null;
        _payKey = ApiClient.newIdempotencyKey();
      });
    } on ApiException catch (e) {
      if (e.isNetwork) {
        await _uncertain('recebimento');
      } else {
        messenger.showSnackBar(SnackBar(content: Text(e.message)));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Sem resposta do servidor depois de enviar: NÃO sabemos se foi registrado. Nada é dado como pago/retirado no
  /// aparelho; tentamos ler a situação real e mantemos a mesma chave — repetir nunca duplica.
  Future<void> _uncertain(String what) async {
    setState(() => _uncertainMsg = 'Sem resposta do servidor: não dá para saber se o $what foi registrado. '
        'Conferindo a situação real do pedido…');
    try {
      final o = await EngScope.of(context).api.order(widget.id);
      if (mounted) {
        setState(() {
          _o = o;
          _uncertainMsg = 'A conexão caiu durante o envio. A situação abaixo é a do servidor agora. '
              'Se ainda faltar, toque de novo: o mesmo envio não é registrado duas vezes.';
        });
      }
    } on ApiException {
      if (mounted) {
        setState(() => _uncertainMsg = 'Sem conexão. Não sabemos se o $what foi registrado — não entregue a mercadoria '
            'nem devolva troco ainda. Quando a conexão voltar, toque de novo: o mesmo envio não é duplicado.');
      }
    }
  }

  Future<void> _fulfill() async {
    final api = EngScope.of(context).api;
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _busy = true);
    try {
      final o = await api.fulfillOrder(orderId: widget.id, idempotencyKey: _fulfillKey);
      setState(() {
        _uncertainMsg = null;
        _o = o;
        _fulfillKey = ApiClient.newIdempotencyKey();
      });
      messenger.showSnackBar(const SnackBar(content: Text('Retirada registrada.')));
    } on ApiException catch (e) {
      if (e.isNetwork) {
        await _uncertain('registro da retirada');
      } else {
        messenger.showSnackBar(SnackBar(content: Text(e.message)));
      }
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
        if (_uncertainMsg != null)
          Semantics(
            liveRegion: true,
            child: Card(
              color: EngColors.surfaceRaised,
              child: Padding(padding: const EdgeInsets.all(12), child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const EngIcon('offline', size: 20, color: EngColors.warning),
                const SizedBox(width: 8),
                Expanded(child: Text(_uncertainMsg!, style: const TextStyle(color: EngColors.warning))),
              ])),
            ),
          ),
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
        if (o.paid != '0.00' || o.state == 'FULFILLED') ...[
          const SizedBox(height: 16),
          OutlinedButton.icon(
            onPressed: () => _push(context, OrderReceiptScreen(id: o.id)),
            icon: const EngIcon('document', size: 18),
            label: const Text('Comprovante para o cliente'),
          ),
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

const _methodLabels = {
  'CASH': 'Dinheiro', 'PIX': 'Pix', 'DEBIT': 'Débito', 'CREDIT': 'Crédito', 'BANK_TRANSFER': 'Transferência', 'ON_ACCOUNT': 'A prazo',
};
const _paymentStateLabels = {'PENDING': 'aguardando conferência', 'CONFIRMED': 'confirmado', 'REJECTED': 'recusado', 'REVERSED': 'estornado'};

/// Comprovante do pedido (não fiscal) para mostrar ao cliente: tudo vem do servidor — itens, total, pagamentos,
/// troco e situação da retirada. Sem permissão de ver pagamentos, mostra só o pedido.
class OrderReceiptScreen extends StatefulWidget {
  const OrderReceiptScreen({super.key, required this.id});
  final String id;
  @override
  State<OrderReceiptScreen> createState() => _OrderReceiptScreenState();
}

class _OrderReceiptScreenState extends State<OrderReceiptScreen> {
  OrderInfo? _o;
  List<PaymentInfo>? _payments;
  ApiException? _error;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_o == null && _error == null) _load();
  }

  Future<void> _load() async {
    final s = EngScope.of(context);
    setState(() => _error = null);
    try {
      final o = await s.api.order(widget.id);
      final pays = s.me.can('payments.view') ? await s.api.orderPayments(widget.id) : null;
      if (mounted) {
        setState(() {
          _o = o;
          _payments = pays;
        });
      }
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final o = _o;
    Widget body;
    if (_error != null) {
      body = _error!.isForbidden || _error!.status == 404
          ? const ForbiddenStateView()
          : ErrorStateView(message: _error!.message, requestId: _error!.requestId, offline: _error!.isNetwork, onRetry: _load);
    } else if (o == null) {
      body = const LoadingState();
    } else {
      const muted = TextStyle(color: EngColors.muted, fontSize: 12);
      body = ListView(padding: const EdgeInsets.all(16), children: [
        Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 520),
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(20),
                child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                  const EngBrand(height: 40),
                  const SizedBox(height: 8),
                  Text(engBrandLegalName, textAlign: TextAlign.center, style: const TextStyle(fontWeight: FontWeight.w700)),
                  if (o.branch != null) Text('Unidade ${o.branch}', textAlign: TextAlign.center, style: muted),
                  const Divider(height: 24),
                  Text('Pedido #${o.number}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800)),
                  _row('Cliente', Text(o.customerName)),
                  if (o.createdAt != null) _row('Data', Text(formatDateTime(DateTime.parse(o.createdAt!)))),
                  _row('Atendido por', Text(o.createdByName)),
                  const Divider(height: 24),
                  for (final l in o.lines)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 6),
                      child: Row(children: [
                        Expanded(child: Text('${l.productName}\n${formatQty(l.quantity, l.unit)} × ${formatMoney(l.unitPrice)}')),
                        Text(formatMoney(l.net)),
                      ]),
                    ),
                  const Divider(height: 24),
                  _row('Total', Text(formatMoney(o.total), style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 18))),
                  for (final p in _payments ?? const <PaymentInfo>[])
                    if (p.state != 'REJECTED')
                      _row(
                          _methodLabels[p.method] ?? p.method,
                          Text('${formatMoney(p.amount)} (${_paymentStateLabels[p.state] ?? p.state})'
                              '${p.presentedCash != null ? ' · recebido ${formatMoney(p.presentedCash)}, troco ${formatMoney(p.changeGiven)}' : ''}')),
                  _row('Pago', Text(formatMoney(o.paid))),
                  if (o.pending != '0.00')
                    _row('Aguardando', Text(formatMoney(o.pending), style: const TextStyle(color: EngColors.warning))),
                  if (o.owes) _row('Falta pagar', Text(formatMoney(o.balanceDue), style: const TextStyle(color: EngColors.warning))),
                  _row('Retirada', Text(o.state == 'FULFILLED' ? 'Concluída' : orderStateLabels[o.state] ?? o.state)),
                  const SizedBox(height: 16),
                  const Text('Comprovante de pedido — não é documento fiscal.', textAlign: TextAlign.center, style: muted),
                ]),
              ),
            ),
          ),
        ),
      ]);
    }
    return Scaffold(appBar: AppBar(title: const Text('Comprovante')), body: body);
  }
}

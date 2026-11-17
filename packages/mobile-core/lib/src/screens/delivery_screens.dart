import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:material_ui/material_ui.dart';
import '../labels.dart';
import '../scope.dart';
import 'orders_screens.dart' show paymentStateLabels;
import 'inventory_screens.dart' show attachmentPicker;

const tripStateLabels = {
  'DRAFT': 'Rascunho', 'ASSIGNED': 'Atribuída', 'DEPARTED': 'Em rota', 'RETURNED': 'Retornou', 'RECONCILED': 'Conferida',
  'CANCELLED': 'Cancelada',
};
const stopStateLabels = {
  'PENDING': 'Pendente', 'DELIVERED': 'Entregue', 'PARTIAL': 'Entregue em parte', 'FAILED': 'Não entregue', 'REMOVED': 'Retirada',
};
const incidentKindLabels = {
  'CUSTOMER_ABSENT': 'Cliente ausente', 'WRONG_ADDRESS': 'Endereço errado', 'REFUSED': 'Recusa', 'DAMAGED': 'Avaria',
  'VEHICLE': 'Problema no veículo', 'OTHER': 'Outro', 'PARTIAL': 'Entrega parcial',
};
const methodLabels = {'CASH': 'Dinheiro', 'PIX': 'Pix', 'DEBIT': 'Débito', 'CREDIT': 'Crédito', 'BANK_TRANSFER': 'Transferência', 'ON_ACCOUNT': 'A prazo'};

Widget _row(String label, Widget value) => Padding(
      padding: const EdgeInsets.only(top: 4),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(width: 120, child: Text(label, style: const TextStyle(color: EngColors.muted, fontSize: 13))),
        Expanded(child: value),
      ]),
    );

Future<bool?> _push(BuildContext context, Widget child) {
  final s = EngScope.of(context);
  return Navigator.of(context).push<bool>(MaterialPageRoute(builder: (_) => EngScope(config: s.config, session: s.session, child: child)));
}

String? _fe(ApiException? e, String field) {
  for (final f in e?.fieldErrors ?? const <FieldError>[]) {
    if (f.field == field) return f.message;
  }
  return null;
}

Widget _formError(ApiException? e) => e == null || e.fieldErrors.isNotEmpty
    ? const SizedBox.shrink()
    : Padding(
        padding: const EdgeInsets.only(top: 8),
        child: Semantics(liveRegion: true, child: Text(e.isNetwork ? 'Sem conexão. Nada foi registrado; tente de novo.' : e.message,
            style: const TextStyle(color: EngColors.error))));

const _hidden = Text('oculto', style: TextStyle(color: EngColors.muted, fontStyle: FontStyle.italic));

/// E05 — minhas viagens: o servidor só devolve as atribuídas ao motorista (escopo ASSIGNED).
class TripsScreen extends StatefulWidget {
  const TripsScreen({super.key});
  @override
  State<TripsScreen> createState() => _TripsScreenState();
}

class _TripsScreenState extends State<TripsScreen> {
  List<TripSummaryInfo>? _items;
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
      final t = await EngScope.of(context).api.trips();
      if (mounted) setState(() => _items = t);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    final dispatch = me.can('trips.assign') && me.can('trips.create');
    return Scaffold(
      floatingActionButton: dispatch
          ? FloatingActionButton.extended(
              onPressed: () async {
                final id = await Navigator.of(context).push<String>(MaterialPageRoute(builder: (_) {
                  final s = EngScope.of(context);
                  return EngScope(config: s.config, session: s.session, child: const DispatchScreen());
                }));
                if (id != null && context.mounted) {
                  await _push(context, TripDetailScreen(id: id));
                }
                _load();
              },
              icon: const EngIcon('plus', color: EngColors.background),
              label: const Text('Organizar viagem'),
            )
          : null,
      body: _body(dispatch),
    );
  }

  Widget _body(bool dispatch) {
    if (_error != null) {
      return _error!.isForbidden
          ? const ForbiddenStateView()
          : ErrorStateView(message: _error!.message, requestId: _error!.requestId, offline: _error!.isNetwork, onRetry: _load);
    }
    if (_items == null) return const LoadingState();
    if (_items!.isEmpty) return EmptyState(title: dispatch ? 'Nenhuma viagem ainda' : 'Nenhuma viagem atribuída a você');
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView.separated(
        padding: const EdgeInsets.all(16),
        itemCount: _items!.length,
        separatorBuilder: (_, _) => const SizedBox(height: 8),
        itemBuilder: (context, i) {
          final t = _items![i];
          return Card(
            child: InkWell(
              onTap: () async {
                await _push(context, TripDetailScreen(id: t.id));
                _load();
              },
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('Viagem V${t.number} · ${t.plate ?? 'sem veículo'}', style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 16)),
                  _row('Situação', Text(labelOf(tripStateLabels, t.state))),
                  _row('Entregas', Text('${t.delivered} de ${t.stops}')),
                  _row('Data', Text(t.plannedDate)),
                ]),
              ),
            ),
          );
        },
      ),
    );
  }
}

/// E05/E10 — viagem: saída com odômetro, paradas (endereço/contato da entrega, valor a cobrar) e retorno com prestação.
class TripDetailScreen extends StatefulWidget {
  const TripDetailScreen({super.key, required this.id});
  final String id;
  @override
  State<TripDetailScreen> createState() => _TripDetailScreenState();
}

class _TripDetailScreenState extends State<TripDetailScreen> {
  TripInfo? _t;
  ApiException? _loadError;
  ApiException? _error;
  bool _busy = false;
  final _odo = TextEditingController();
  final _cash = TextEditingController();
  final _just = TextEditingController();
  String _returnKey = ApiClient.newIdempotencyKey();

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_t == null && _loadError == null) _load();
  }

  Future<void> _load() async {
    try {
      final t = await EngScope.of(context).api.trip(widget.id);
      if (mounted) setState(() => _t = t);
    } on ApiException catch (e) {
      if (mounted) setState(() => _loadError = e);
    }
  }

  Future<void> _run(Future<TripInfo> Function(ApiClient api) action, String done) async {
    final api = EngScope.of(context).api;
    final messenger = ScaffoldMessenger.of(context);
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final t = await action(api);
      setState(() {
        _t = t;
        _odo.clear();
      });
      messenger.showSnackBar(SnackBar(content: Text(done)));
    } on ApiException catch (e) {
      setState(() => _error = e);
      if (e.isConflict) _load();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    final t = _t;
    Widget body;
    if (_loadError != null) {
      body = _loadError!.isForbidden || _loadError!.status == 404
          ? const ForbiddenStateView()
          : ErrorStateView(message: _loadError!.message, requestId: _loadError!.requestId, onRetry: _load);
    } else if (t == null) {
      body = const LoadingState();
    } else {
      final pending = t.stops.where((s) => s.state == 'PENDING').length;
      body = RefreshIndicator(
        onRefresh: _load,
        child: ListView(padding: const EdgeInsets.all(16), children: [
          Text('${t.vehicleModel ?? ''} · ${t.plate ?? ''}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
          _row('Situação', Text(labelOf(tripStateLabels, t.state))),
          _row('Motorista', Text(t.driverName ?? '—')),
          if (t.load != null && t.capacityUnit != null)
            _row('Carga', Text('${formatDecimal(t.load)} de ${formatQty(t.capacity ?? '0', t.capacityUnit!)}')),
          if (t.departureOdometer != null) _row('Saída', Text('${formatDecimal(t.departureOdometer)} km')),
          if (t.returnOdometer != null) _row('Retorno', Text('${formatDecimal(t.returnOdometer)} km')),
          if (t.cashExpected != null) ...[
            _row('A prestar', Text(formatMoney(t.cashExpected), style: const TextStyle(fontWeight: FontWeight.w700))),
            _row('Entregue', Text(formatMoney(t.cashDeclared))),
            _row('Diferença', Text(formatMoney(t.cashDifference))),
          ],
          if (t.state == 'ASSIGNED' && me.can('trips.confirm')) ...[
            const SizedBox(height: 16),
            const Text('Registrar saída', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 16)),
            TextField(controller: _odo, keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: InputDecoration(labelText: 'Odômetro de saída (km) *', errorText: _fe(_error, 'odometer'))),
            const SizedBox(height: 8),
            FilledButton(
                onPressed: _busy ? null : () => _run((api) => api.departTrip(id: t.id, version: t.version, odometer: _odo.text), 'Saída registrada.'),
                child: const Text('Registrar saída')),
            _formError(_error),
          ],
          const SizedBox(height: 16),
          const Text('Paradas', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 16)),
          for (final s in t.stops) _StopCard(trip: t, stop: s, onChanged: (nt) => setState(() => _t = nt)),
          if (t.state == 'DEPARTED' && me.can('trips.close')) ...[
            const SizedBox(height: 16),
            const Text('Retorno e prestação de contas', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 16)),
            if (pending > 0)
              Text('$pending parada(s) sem registro: entregue ou registre ocorrência antes de retornar.',
                  style: const TextStyle(color: EngColors.warning)),
            TextField(controller: _odo, keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: InputDecoration(labelText: 'Odômetro de chegada (km) *', errorText: _fe(_error, 'odometer'))),
            TextField(controller: _cash, keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: InputDecoration(labelText: 'Dinheiro entregue (R\$) *', errorText: _fe(_error, 'cash_declared'),
                    helperText: 'Recebido em dinheiro menos gastos pagos com ele')),
            TextField(controller: _just,
                decoration: InputDecoration(labelText: 'Justificativa (se houver diferença)', errorText: _fe(_error, 'justification'))),
            const SizedBox(height: 8),
            FilledButton(
                onPressed: _busy
                    ? null
                    : () => _run((api) async {
                          final r = await api.returnTrip(id: t.id, version: t.version, idempotencyKey: _returnKey, odometer: _odo.text,
                              cashDeclared: _cash.text, justification: _just.text);
                          _returnKey = ApiClient.newIdempotencyKey();
                          return r;
                        }, 'Prestação enviada para conferência.'),
                child: const Text('Enviar prestação')),
            _formError(_error),
          ],
        ]),
      );
    }
    return Scaffold(appBar: AppBar(title: Text(t == null ? 'Viagem' : 'Viagem V${t.number}')), body: body);
  }
}

class _StopCard extends StatelessWidget {
  const _StopCard({required this.trip, required this.stop, required this.onChanged});
  final TripInfo trip;
  final TripStopInfo stop;
  final ValueChanged<TripInfo> onChanged;

  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    final o = stop.order;
    final d = o.delivery;
    final paid = o.paymentState == 'PAID' || o.paymentState == 'ON_ACCOUNT';
    final canAct = trip.state == 'DEPARTED' && stop.state == 'PENDING' && me.can('trips.confirm');
    return Card(
      child: InkWell(
        onTap: canAct
            ? () async {
                final changed = await _push(context, StopScreen(trip: trip, stop: stop));
                if (changed == true && context.mounted) onChanged(await EngScope.of(context).api.trip(trip.id));
              }
            : null,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('${stop.seq}. #${o.number} ${o.customerName}', style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 16)),
            _row('Endereço', o.maskedFields.contains('delivery.address')
                ? _hidden
                : Text('${d?.address ?? '—'}${d?.reference != null ? ' (${d!.reference})' : ''}')),
            _row('Contato', o.maskedFields.contains('delivery.contact') ? _hidden : Text(d?.contact ?? '—')),
            _row('Levar', Text([for (final l in o.lines) '${l.productName}: ${formatQty(l.remaining != '0' ? l.remaining : l.fulfilled, l.unit)}'].join(' · '))),
            _row('Pagamento', Text(
                paid
                    ? '${labelOf(paymentStateLabels, o.paymentState)} — não cobrar novamente'
                    : 'Cobrar ${formatMoney(o.balanceDue)}${d?.agreedPayment != null ? ' (${labelOf(methodLabels, d!.agreedPayment!)})' : ''}',
                style: TextStyle(color: paid ? EngColors.green : EngColors.warning, fontWeight: FontWeight.w600))),
            _row('Situação', Text(labelOf(stopStateLabels, stop.state))),
            if (stop.recipientName != null) _row('Recebido por', Text(stop.recipientName!)),
            for (final i in stop.incidents) _row('Ocorrência', Text('${labelOf(incidentKindLabels, i.kind)} — ${i.description}')),
            if (canAct) const Padding(padding: EdgeInsets.only(top: 8), child: Text('Toque para entregar ou registrar ocorrência',
                style: TextStyle(color: EngColors.cyan, fontSize: 13))),
          ]),
        ),
      ),
    );
  }
}

/// E06/E07/E09 — confirmar entrega (com recebimento) ou registrar ocorrência. Ocorrência nunca marca como entregue.
class StopScreen extends StatefulWidget {
  const StopScreen({super.key, required this.trip, required this.stop});
  final TripInfo trip;
  final TripStopInfo stop;
  @override
  State<StopScreen> createState() => _StopScreenState();
}

class _StopScreenState extends State<StopScreen> {
  final _recipient = TextEditingController();
  late final Map<int, TextEditingController> _qty = {
    for (final l in widget.stop.order.lines) l.lineNo: TextEditingController(text: formatDecimal(l.remaining)),
  };
  final _reason = TextEditingController();
  final _presented = TextEditingController();
  final _nsu = TextEditingController();
  final _desc = TextEditingController();
  late String _method = switch (widget.stop.order.delivery?.agreedPayment) {
    'PIX' => 'PIX', 'DEBIT' => 'DEBIT', 'CREDIT' => 'CREDIT', _ => 'CASH',
  };
  String _kind = 'CUSTOMER_ABSENT';
  String _action = 'RESCHEDULE';
  final _deliverKey = ApiClient.newIdempotencyKey();
  final _incidentKey = ApiClient.newIdempotencyKey();
  bool _busy = false;
  ApiException? _error;
  ApiException? _incidentError;

  final List<String> _evidence = [];
  bool _uploading = false;

  Future<void> _photo(bool camera) async {
    final api = EngScope.of(context).api;
    final messenger = ScaffoldMessenger.of(context);
    final picked = await attachmentPicker(camera: camera);
    if (picked == null || !mounted) return;
    setState(() => _uploading = true);
    try {
      final d = await api.uploadStopEvidence(tripId: widget.trip.id, stopId: widget.stop.id, fileName: picked.name,
          bytes: picked.bytes, contentType: picked.mime);
      if (!mounted) return;
      setState(() => _evidence.add(d.fileName));
      messenger.showSnackBar(SnackBar(content: Text(d.available ? 'Foto da entrega enviada.' : 'Arquivo recusado: ${d.rejection}')));
    } on ApiException catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(e.isNetwork ? 'Sem conexão. A foto não foi enviada.' : e.message)));
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  bool get _partial => widget.stop.order.lines.any((l) => decimalText(_qty[l.lineNo]!.text) != l.remaining);

  Future<void> _deliver() async {
    final api = EngScope.of(context).api;
    final nav = Navigator.of(context);
    final o = widget.stop.order;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await api.deliverStop(
        tripId: widget.trip.id,
        stopId: widget.stop.id,
        idempotencyKey: _deliverKey,
        recipientName: _recipient.text,
        lines: _partial ? {for (final l in o.lines) if (decimalText(_qty[l.lineNo]!.text) != '0') l.lineNo: _qty[l.lineNo]!.text} : null,
        partialReason: _partial ? _reason.text : null,
        payment: o.owes
            ? (method: _method, amount: o.balanceDue, presentedCash: _method == 'CASH' ? _presented.text : null,
                reference: _method == 'DEBIT' || _method == 'CREDIT' ? _nsu.text : null)
            : null,
      );
      nav.pop(true);
    } on ApiException catch (e) {
      setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _incident() async {
    final api = EngScope.of(context).api;
    final nav = Navigator.of(context);
    setState(() {
      _busy = true;
      _incidentError = null;
    });
    try {
      await api.tripIncident(tripId: widget.trip.id, stopId: widget.stop.id, idempotencyKey: _incidentKey, kind: _kind,
          description: _desc.text, requestedAction: _action);
      nav.pop(true);
    } on ApiException catch (e) {
      setState(() => _incidentError = e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final o = widget.stop.order;
    final d = o.delivery;
    return Scaffold(
      appBar: AppBar(title: Text('Pedido #${o.number}')),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        Text(o.customerName, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
        _row('Endereço', o.maskedFields.contains('delivery.address') ? _hidden : Text(d?.address ?? '—')),
        if (!o.owes)
          Card(
            color: EngColors.surfaceRaised,
            child: Padding(padding: const EdgeInsets.all(12), child: Text(
                '${labelOf(paymentStateLabels, o.paymentState)}: não cobrar novamente.',
                style: const TextStyle(color: EngColors.green, fontWeight: FontWeight.w700))),
          ),
        const SizedBox(height: 12),
        const Text('Confirmar entrega', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 16)),
        TextField(controller: _recipient, decoration: InputDecoration(labelText: 'Quem recebeu *', errorText: _fe(_error, 'recipient_name'))),
        for (final (i, l) in o.lines.indexed)
          TextField(
            controller: _qty[l.lineNo],
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            onChanged: (_) => setState(() {}),
            decoration: InputDecoration(labelText: '${l.productName} entregue (${unitLabel(l.unit)})',
                helperText: 'Pendente: ${formatQty(l.remaining, l.unit)}', errorText: _fe(_error, 'lines[$i].quantity')),
          ),
        if (_partial)
          TextField(controller: _reason,
              decoration: InputDecoration(labelText: 'Motivo da entrega parcial *', helperText: 'Gera ocorrência; o restante volta para despacho',
                  errorText: _fe(_error, 'partial_reason'))),
        if (o.owes) ...[
          const SizedBox(height: 12),
          Text('Receber ${formatMoney(o.balanceDue)}', style: const TextStyle(fontWeight: FontWeight.w600)),
          SegmentedButton<String>(
            segments: const [
              ButtonSegment(value: 'CASH', label: Text('Dinheiro')),
              ButtonSegment(value: 'PIX', label: Text('Pix')),
              ButtonSegment(value: 'DEBIT', label: Text('Débito')),
            ],
            selected: {_method == 'CREDIT' ? 'DEBIT' : _method},
            onSelectionChanged: (v) => setState(() => _method = v.first),
          ),
          if (_method == 'CASH')
            TextField(controller: _presented, keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: InputDecoration(labelText: 'Dinheiro apresentado (R\$) *', errorText: _fe(_error, 'parts[0].presented_cash'),
                    helperText: 'Vai para o seu caixa; o troco é calculado pelo servidor')),
          if (_method == 'DEBIT' || _method == 'CREDIT')
            TextField(controller: _nsu,
                decoration: InputDecoration(labelText: 'NSU/código da transação *', errorText: _fe(_error, 'parts[0].provider_reference'))),
          if (_method == 'PIX')
            const Text('Pix fica aguardando conferência; sem conferência a entrega é recusada.', style: TextStyle(color: EngColors.warning)),
        ],
        const SizedBox(height: 12),
        const Text('Evidência da entrega', style: TextStyle(fontWeight: FontWeight.w600)),
        Wrap(spacing: 8, children: [
          OutlinedButton(onPressed: _uploading ? null : () => _photo(true), child: const Text('Fotografar canhoto')),
          OutlinedButton(onPressed: _uploading ? null : () => _photo(false), child: const Text('Da galeria')),
        ]),
        for (final f in _evidence) Text('Anexado: $f', style: const TextStyle(color: EngColors.green)),
        const SizedBox(height: 12),
        FilledButton(onPressed: _busy ? null : _deliver, child: Text(_busy ? 'Registrando…' : 'Confirmar entrega')),
        _formError(_error),
        const Divider(height: 32),
        const Text('Ocorrência', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 16)),
        DropdownButtonFormField<String>(
          initialValue: _kind,
          isExpanded: true,
          decoration: const InputDecoration(labelText: 'Motivo *'),
          items: [for (final e in incidentKindLabels.entries) if (e.key != 'PARTIAL') DropdownMenuItem(value: e.key, child: Text(e.value))],
          onChanged: (v) => setState(() => _kind = v ?? 'OTHER'),
        ),
        DropdownButtonFormField<String>(
          initialValue: _action,
          isExpanded: true,
          decoration: const InputDecoration(labelText: 'Pedido'),
          items: const [
            DropdownMenuItem(value: 'RESCHEDULE', child: Text('Reagendar')),
            DropdownMenuItem(value: 'RETURN', child: Text('Devolver')),
            DropdownMenuItem(value: 'NONE', child: Text('Sem pedido')),
          ],
          onChanged: (v) => setState(() => _action = v ?? 'NONE'),
        ),
        TextField(controller: _desc, decoration: InputDecoration(labelText: 'O que aconteceu *', errorText: _fe(_incidentError, 'description'))),
        const SizedBox(height: 8),
        OutlinedButton(onPressed: _busy ? null : _incident, child: const Text('Salvar ocorrência')),
        const Text('Não marca como entregue: o pedido segue reservado.', style: TextStyle(color: EngColors.muted, fontSize: 12)),
        _formError(_incidentError),
      ]),
    );
  }
}

/// E11/E08 — veículos visíveis (motorista: o da viagem atribuída), avisos, consumo e abastecimento por placa.
class VehiclesScreen extends StatefulWidget {
  const VehiclesScreen({super.key});
  @override
  State<VehiclesScreen> createState() => _VehiclesScreenState();
}

class _VehiclesScreenState extends State<VehiclesScreen> {
  List<VehicleInfo>? _items;
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
      final v = await EngScope.of(context).api.vehicles();
      if (mounted) setState(() => _items = v);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    if (_error != null) {
      return _error!.isForbidden
          ? const ForbiddenStateView()
          : ErrorStateView(message: _error!.message, requestId: _error!.requestId, offline: _error!.isNetwork, onRetry: _load);
    }
    if (_items == null) return const LoadingState();
    if (_items!.isEmpty) return const EmptyState(title: 'Nenhum veículo disponível para você');
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(padding: const EdgeInsets.all(16), children: [
        for (final v in _items!)
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('${v.model} · ${v.plate}', style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 16)),
                _row('Odômetro', Text('${formatDecimal(v.odometerKm)} km')),
                if (v.nextServiceKm != null) _row('Revisão', Text('${formatDecimal(v.nextServiceKm)} km')),
                _row('Consumo', Text(v.consumptionState == 'OK' ? '${formatDecimal(v.kmPerLiter)} km/l' : 'Dados insuficientes')),
                if (v.currentTripNumber != null) _row('Viagem', Text('V${v.currentTripNumber}')),
                for (final a in v.alerts) Text(a, style: const TextStyle(color: EngColors.warning)),
                if (me.can('vehicles.edit_draft') && v.status != 'INACTIVE')
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: OutlinedButton(
                      onPressed: () async {
                        final done = await _push(context, MaintenanceScreen(vehicle: v));
                        if (done == true) _load();
                      },
                      child: const Text('Registrar manutenção ou outro gasto'),
                    ),
                  ),
                if (me.can('fuel.create') && v.status != 'INACTIVE')
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: OutlinedButton(
                      onPressed: () async {
                        final done = await _push(context, FuelScreen(vehicle: v));
                        if (done == true) _load();
                      },
                      child: const Text('Registrar abastecimento'),
                    ),
                  ),
              ]),
            ),
          ),
      ]),
    );
  }
}

/// E08 — abastecimento por placa: odômetro não regressivo, litros, valor, posto e quem pagou.
class FuelScreen extends StatefulWidget {
  const FuelScreen({super.key, required this.vehicle});
  final VehicleInfo vehicle;
  @override
  State<FuelScreen> createState() => _FuelScreenState();
}

class _FuelScreenState extends State<FuelScreen> {
  final _odo = TextEditingController();
  final _liters = TextEditingController();
  final _amount = TextEditingController();
  final _station = TextEditingController();
  String _fuel = 'DIESEL';
  bool _full = true;
  String _paidWith = 'DRIVER_CASH';
  final _key = ApiClient.newIdempotencyKey();
  bool _busy = false;
  ApiException? _error;

  Future<void> _save() async {
    final api = EngScope.of(context).api;
    final nav = Navigator.of(context);
    final messenger = ScaffoldMessenger.of(context);
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await api.registerFuel(vehicleId: widget.vehicle.id, idempotencyKey: _key, odometer: _odo.text, liters: _liters.text,
          fuelType: _fuel, fullTank: _full, amount: _amount.text, station: _station.text, paidWith: _paidWith);
      messenger.showSnackBar(const SnackBar(content: Text('Abastecimento registrado — aguardando aprovação.')));
      nav.pop(true);
    } on ApiException catch (e) {
      setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final v = widget.vehicle;
    return Scaffold(
      appBar: AppBar(title: Text('Abastecer ${v.plate}')),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        TextField(controller: _odo, keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(labelText: 'Odômetro (km) *', helperText: 'Último: ${formatDecimal(v.odometerKm)} km',
                errorText: _fe(_error, 'odometer'))),
        TextField(controller: _liters, keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(labelText: 'Litros *', errorText: _fe(_error, 'liters'))),
        DropdownButtonFormField<String>(
          initialValue: _fuel,
          isExpanded: true,
          decoration: const InputDecoration(labelText: 'Combustível *'),
          items: const [
            DropdownMenuItem(value: 'DIESEL', child: Text('Diesel')),
            DropdownMenuItem(value: 'GASOLINA', child: Text('Gasolina')),
            DropdownMenuItem(value: 'ETANOL', child: Text('Etanol')),
            DropdownMenuItem(value: 'GNV', child: Text('GNV')),
          ],
          onChanged: (x) => setState(() => _fuel = x ?? 'DIESEL'),
        ),
        SwitchListTile(value: _full, onChanged: (x) => setState(() => _full = x), title: const Text('Completou o tanque'),
            subtitle: const Text('Só tanque cheio entra no cálculo de km/l')),
        TextField(controller: _amount, keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(labelText: 'Valor pago (R\$) *', errorText: _fe(_error, 'amount'))),
        TextField(controller: _station, decoration: InputDecoration(labelText: 'Posto', errorText: _fe(_error, 'station'))),
        DropdownButtonFormField<String>(
          initialValue: _paidWith,
          isExpanded: true,
          decoration: const InputDecoration(labelText: 'Pago com *'),
          items: const [
            DropdownMenuItem(value: 'DRIVER_CASH', child: Text('Dinheiro que está comigo (sai do meu caixa)')),
            DropdownMenuItem(value: 'COMPANY', child: Text('Empresa (vira conta a pagar)')),
          ],
          onChanged: (x) => setState(() => _paidWith = x ?? 'COMPANY'),
        ),
        const SizedBox(height: 16),
        FilledButton(onPressed: _busy ? null : _save, child: Text(_busy ? 'Registrando…' : 'Registrar gasto')),
        _formError(_error),
      ]),
    );
  }
}


/// E04 — despacho pelo app (coordenação): escolhe pedidos de entrega abertos, veículo e motorista. Capacidade,
/// documentos, disponibilidade e escopo são validados pelo SERVIDOR; o app só mostra o resultado. Se a atribuição
/// falhar, a viagem já criada é reaproveitada na nova tentativa (sem viagem duplicada).
class DispatchScreen extends StatefulWidget {
  const DispatchScreen({super.key});
  @override
  State<DispatchScreen> createState() => _DispatchScreenState();
}

class _DispatchScreenState extends State<DispatchScreen> {
  List<OrderInfo>? _orders;
  List<VehicleInfo>? _vehicles;
  List<({String id, String name, bool busy})>? _drivers;
  ApiException? _loadError;
  final Set<String> _selected = {};
  String? _vehicle;
  String? _driver;
  TripInfo? _trip; // criada e ainda não atribuída (reaproveitada)
  final _key = ApiClient.newIdempotencyKey();
  bool _busy = false;
  ApiException? _error;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_orders == null && _loadError == null) _load();
  }

  Future<void> _load() async {
    final api = EngScope.of(context).api;
    setState(() => _loadError = null);
    try {
      final o = await api.orders(limit: 100, type: 'DELIVERY');
      final v = await api.vehicles();
      final d = await api.eligibleDrivers();
      if (mounted) {
        setState(() {
          _orders = o.items.where((x) => x.open).toList();
          _vehicles = v.where((x) => x.status == 'ACTIVE' && x.currentTripNumber == null).toList();
          _drivers = d;
        });
      }
    } on ApiException catch (e) {
      if (mounted) setState(() => _loadError = e);
    }
  }

  String? _fe(String prefix) =>
      _error?.fieldErrors.where((e) => e.field.startsWith(prefix)).map((e) => e.message).join(' · ').ifEmptyNull();

  Future<void> _save() async {
    final api = EngScope.of(context).api;
    final nav = Navigator.of(context);
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      _trip ??= await api.createTrip(idempotencyKey: _key);
      final t = await api.assignTrip(id: _trip!.id, version: _trip!.version, vehicleId: _vehicle ?? '', driverId: _driver ?? '',
          orderIds: _selected.toList());
      nav.pop(t.id);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    Widget body;
    if (_loadError != null) {
      body = _loadError!.isForbidden
          ? const ForbiddenStateView()
          : ErrorStateView(message: _loadError!.message, requestId: _loadError!.requestId, offline: _loadError!.isNetwork, onRetry: _load);
    } else if (_orders == null) {
      body = const LoadingState();
    } else {
      body = ListView(padding: const EdgeInsets.all(16), children: [
        Text('Pedidos de entrega (${_selected.length} selecionado(s))', style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 16)),
        if (_orders!.isEmpty) const Padding(padding: EdgeInsets.all(8), child: Text('Nenhum pedido de entrega aberto.', style: TextStyle(color: EngColors.muted))),
        for (final o in _orders!)
          CheckboxListTile(
            value: _selected.contains(o.id),
            onChanged: (v) => setState(() => v == true ? _selected.add(o.id) : _selected.remove(o.id)),
            title: Text('#${o.number} ${o.customerName}'),
            subtitle: Text('${o.lines.isEmpty ? '' : formatQty(o.lines.first.quantity, o.lines.first.unit)} · ${formatMoney(o.total)} · '
                '${o.paymentState == 'PAID' ? 'Pago — não cobrar' : 'cobrar ${formatMoney(o.balanceDue)}'}'),
          ),
        if (_fe('order_ids') != null) Text(_fe('order_ids')!, style: const TextStyle(color: EngColors.error)),
        const SizedBox(height: 12),
        DropdownButtonFormField<String>(
          initialValue: _vehicle,
          isExpanded: true,
          decoration: InputDecoration(labelText: 'Veículo *', errorText: _fe('vehicle_id')),
          items: [
            for (final v in _vehicles!)
              DropdownMenuItem(value: v.id, child: Text('${v.plate} · ${v.model}'
                  '${v.capacityQuantity == null ? '' : ' · até ${formatQty(v.capacityQuantity!, v.capacityUnit ?? '')}'}', overflow: TextOverflow.ellipsis)),
          ],
          onChanged: (v) => setState(() => _vehicle = v),
        ),
        DropdownButtonFormField<String>(
          initialValue: _driver,
          isExpanded: true,
          decoration: InputDecoration(labelText: 'Motorista *', errorText: _fe('driver_id')),
          items: [
            for (final d in _drivers!)
              DropdownMenuItem(value: d.id, enabled: !d.busy, child: Text(d.busy ? '${d.name} (em viagem)' : d.name)),
          ],
          onChanged: (v) => setState(() => _driver = v),
        ),
        if (_error != null && _error!.fieldErrors.isEmpty)
          Padding(padding: const EdgeInsets.only(top: 8),
              child: Text(_error!.isNetwork ? 'Sem conexão. Toque de novo: a viagem não é criada duas vezes.' : _error!.message,
                  style: const TextStyle(color: EngColors.error))),
        if (_trip != null)
          Padding(padding: const EdgeInsets.only(top: 8),
              child: Text('Viagem V${_trip!.number} criada em rascunho; ajuste e toque de novo para atribuir.',
                  style: const TextStyle(color: EngColors.warning))),
        const SizedBox(height: 16),
        FilledButton(onPressed: _busy || _selected.isEmpty ? null : _save, child: Text(_busy ? 'Atribuindo…' : 'Criar e atribuir viagem')),
        const SizedBox(height: 8),
        const Text('Capacidade, documentos do veículo e disponibilidade do motorista são conferidos pelo servidor.',
            style: TextStyle(color: EngColors.muted, fontSize: 12)),
      ]);
    }
    return Scaffold(appBar: AppBar(title: const Text('Organizar viagem')), body: body);
  }
}

extension on String {
  String? ifEmptyNull() => isEmpty ? null : this;
}

const _expenseKinds = {
  'MAINTENANCE': 'Manutenção', 'TIRES': 'Pneus', 'INSURANCE': 'Seguro', 'DOCUMENTS': 'Documentos', 'TOLL': 'Pedágio', 'OTHER': 'Outro',
};

/// E11 — manutenção e outros gastos do veículo: entra pendente; aprovação e conta a pagar seguem no site/central.
class MaintenanceScreen extends StatefulWidget {
  const MaintenanceScreen({super.key, required this.vehicle});
  final VehicleInfo vehicle;
  @override
  State<MaintenanceScreen> createState() => _MaintenanceScreenState();
}

class _MaintenanceScreenState extends State<MaintenanceScreen> {
  String _kind = 'MAINTENANCE';
  String _type = 'PREVENTIVE';
  String _paidWith = 'COMPANY';
  final _odo = TextEditingController();
  final _amount = TextEditingController();
  final _workshop = TextEditingController();
  final _parts = TextEditingController();
  final _next = TextEditingController();
  final _desc = TextEditingController();
  final _key = ApiClient.newIdempotencyKey();
  bool _busy = false;
  ApiException? _error;

  String? _fe(String f) => _error?.fieldErrors.where((e) => e.field == f).map((e) => e.message).firstOrNull;

  Future<void> _save() async {
    final api = EngScope.of(context).api;
    final nav = Navigator.of(context);
    final messenger = ScaffoldMessenger.of(context);
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await api.registerMaintenance(vehicleId: widget.vehicle.id, idempotencyKey: _key, kind: _kind,
          maintenanceType: _kind == 'MAINTENANCE' ? _type : null, odometer: _odo.text, amount: _amount.text, workshop: _workshop.text,
          parts: _parts.text, nextServiceKm: _next.text, paidWith: _paidWith, description: _desc.text);
      messenger.showSnackBar(const SnackBar(content: Text('Gasto registrado — aguardando aprovação.')));
      nav.pop(true);
    } on ApiException catch (e) {
      setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final maint = _kind == 'MAINTENANCE';
    return Scaffold(
      appBar: AppBar(title: Text('Gasto · ${widget.vehicle.plate}')),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        DropdownButtonFormField<String>(
          initialValue: _kind,
          isExpanded: true,
          decoration: InputDecoration(labelText: 'Tipo *', errorText: _fe('kind')),
          items: [for (final e in _expenseKinds.entries) DropdownMenuItem(value: e.key, child: Text(e.value))],
          onChanged: (v) => setState(() => _kind = v ?? 'MAINTENANCE'),
        ),
        if (maint)
          DropdownButtonFormField<String>(
            initialValue: _type,
            isExpanded: true,
            decoration: InputDecoration(labelText: 'Manutenção *', errorText: _fe('maintenance_type')),
            items: const [
              DropdownMenuItem(value: 'PREVENTIVE', child: Text('Preventiva (revisão)')),
              DropdownMenuItem(value: 'CORRECTIVE', child: Text('Corretiva (conserto)')),
            ],
            onChanged: (v) => setState(() => _type = v ?? 'PREVENTIVE'),
          ),
        TextField(controller: _amount, keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(labelText: 'Valor (R\$) *', errorText: _fe('amount'))),
        TextField(controller: _odo, keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(labelText: 'Odômetro (km)', helperText: 'Atual: ${formatDecimal(widget.vehicle.odometerKm)} km', errorText: _fe('odometer'))),
        if (maint) ...[
          TextField(controller: _workshop, decoration: InputDecoration(labelText: 'Oficina', errorText: _fe('workshop'))),
          TextField(controller: _parts, decoration: InputDecoration(labelText: 'Peças/serviços', errorText: _fe('parts'))),
          TextField(controller: _next, keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: InputDecoration(labelText: 'Próxima revisão (km)', errorText: _fe('next_service_km'))),
        ],
        TextField(controller: _desc, decoration: InputDecoration(labelText: maint ? 'Observação' : 'Descrição *', errorText: _fe('description'))),
        DropdownButtonFormField<String>(
          initialValue: _paidWith,
          isExpanded: true,
          decoration: InputDecoration(labelText: 'Quem pagou *', errorText: _fe('paid_with')),
          items: const [
            DropdownMenuItem(value: 'COMPANY', child: Text('Empresa (vira conta a pagar)')),
            DropdownMenuItem(value: 'DRIVER_CASH', child: Text('Dinheiro que está comigo (sai do meu caixa)')),
          ],
          onChanged: (v) => setState(() => _paidWith = v ?? 'COMPANY'),
        ),
        if (_error != null && _error!.fieldErrors.isEmpty)
          Padding(padding: const EdgeInsets.only(top: 8),
              child: Text(_error!.isNetwork ? 'Sem conexão. Nada foi salvo; tente de novo — não duplica.' : _error!.message,
                  style: const TextStyle(color: EngColors.error))),
        const SizedBox(height: 16),
        FilledButton(onPressed: _busy ? null : _save, child: Text(_busy ? 'Salvando…' : 'Registrar gasto')),
      ]),
    );
  }
}

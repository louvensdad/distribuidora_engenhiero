import 'dart:typed_data';
import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:image_picker/image_picker.dart';
import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:material_ui/material_ui.dart';
import '../labels.dart';
import '../scope.dart';

Widget _masked() => const Row(mainAxisSize: MainAxisSize.min, children: [
      EngIcon('lock', size: 14, color: EngColors.muted),
      SizedBox(width: 4),
      Text('oculto', style: TextStyle(color: EngColors.muted, fontStyle: FontStyle.italic)),
    ]);

Widget _kv(String label, Widget value) => Padding(
      padding: const EdgeInsets.only(top: 4),
      child: Row(children: [
        SizedBox(width: 110, child: Text(label, style: const TextStyle(color: EngColors.muted, fontSize: 13))),
        Expanded(child: value),
      ]),
    );

/// Arquivo escolhido para anexar. [camera] = fotografar agora; senão galeria.
typedef AttachmentPicker = Future<({String name, Uint8List bytes, String mime})?> Function({required bool camera});

/// Seletor real (câmera/galeria do Android). Substituível em teste.
AttachmentPicker attachmentPicker = ({required bool camera}) async {
  final x = await ImagePicker().pickImage(
      source: camera ? ImageSource.camera : ImageSource.gallery, imageQuality: 80, maxWidth: 2400, maxHeight: 2400);
  if (x == null) return null;
  final bytes = await x.readAsBytes();
  // Tipo pelos bytes (o seletor pode recomprimir em JPEG mantendo o nome .png); o servidor confere de novo.
  final png = bytes.length > 4 && bytes[0] == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47;
  final name = x.name.isEmpty ? (png ? 'foto.png' : 'foto.jpg') : x.name;
  return (name: name, bytes: bytes, mime: png ? 'image/png' : 'image/jpeg');
};

/// Saldo por produto: físico, reservado, disponível e perdas. Valor e custo médio só para quem vê custo.
class StockScreen extends StatefulWidget {
  const StockScreen({super.key});
  @override
  State<StockScreen> createState() => _StockScreenState();
}

class _StockScreenState extends State<StockScreen> {
  List<StockBalance>? _items;
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
      final p = await EngScope.of(context).api.balances();
      if (mounted) setState(() => _items = p.items);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_error != null) {
      if (_error!.isForbidden) return const ForbiddenStateView();
      return ErrorStateView(message: _error!.message, requestId: _error!.requestId, offline: _error!.isNetwork, onRetry: _load);
    }
    final items = _items;
    if (items == null) return const LoadingState();
    if (items.isEmpty) return const EmptyState(title: 'Nenhum produto com estoque nesta unidade');
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView.separated(
        padding: const EdgeInsets.all(16),
        itemCount: items.length,
        separatorBuilder: (_, _) => const SizedBox(height: 8),
        itemBuilder: (context, i) {
          final b = items[i];
          return Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(b.productName, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 16)),
                const SizedBox(height: 2),
                Text('${b.sku} · ${b.branch}', style: const TextStyle(color: EngColors.muted, fontSize: 12)),
                const SizedBox(height: 8),
                Text('Disponível: ${formatQty(b.available, b.baseUnit)}',
                    style: const TextStyle(color: EngColors.green, fontWeight: FontWeight.w700, fontSize: 18)),
                _kv('Físico', Text(formatQty(b.physical, b.baseUnit))),
                _kv('Reservado', Text(formatQty(b.reserved, b.baseUnit))),
                _kv('Perdas', Text(formatQty(b.lost, b.baseUnit))),
                _kv('Valor em estoque', b.costHidden ? _masked() : Text(formatMoney(b.value))),
              ]),
            ),
          );
        },
      ),
    );
  }
}

/// R06 — fornecedores e recebimentos: conferência de quantidade; custo oculto sem permissão.
class ReceiptsScreen extends StatefulWidget {
  const ReceiptsScreen({super.key});
  @override
  State<ReceiptsScreen> createState() => _ReceiptsScreenState();
}

class _ReceiptsScreenState extends State<ReceiptsScreen> {
  List<Receipt>? _items;
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
      final p = await EngScope.of(context).api.receipts();
      if (mounted) setState(() => _items = p.items);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  Future<void> _open(Widget screen) async {
    final s = EngScope.of(context);
    final changed = await Navigator.of(context)
        .push<bool>(MaterialPageRoute(builder: (_) => EngScope(config: s.config, session: s.session, child: screen)));
    if (changed == true) _load();
  }

  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return Scaffold(
      floatingActionButton: me.can('inventory.receipts.create')
          ? FloatingActionButton.extended(
              onPressed: () => _open(const NewReceiptScreen()),
              icon: const EngIcon('box', color: EngColors.background),
              label: const Text('Receber mercadoria'),
            )
          : null,
      body: EngReadableWidth(child: _body()),
    );
  }

  Widget _body() {
    if (_error != null) {
      if (_error!.isForbidden) return const ForbiddenStateView();
      return ErrorStateView(message: _error!.message, requestId: _error!.requestId, offline: _error!.isNetwork, onRetry: _load);
    }
    final items = _items;
    if (items == null) return const LoadingState();
    if (items.isEmpty) return const EmptyState(title: 'Nenhum recebimento registrado');
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView.separated(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 96),
        itemCount: items.length,
        separatorBuilder: (_, _) => const SizedBox(height: 8),
        itemBuilder: (context, i) {
          final r = items[i];
          final first = r.lines.isEmpty ? null : r.lines.first;
          return Card(
            child: InkWell(
              onTap: () => _open(ReceiptDetailScreen(id: r.id)),
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(r.supplierName, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 16)),
                  if (first != null)
                    Text('${first.productName} · ${formatQty(first.quantityReceived, first.unit)}'
                        '${r.lines.length > 1 ? ' + ${r.lines.length - 1} item(ns)' : ''}'),
                  _kv('Situação', Text(receiptStatusLabels[r.status] ?? r.status,
                      style: TextStyle(color: r.isDraft ? EngColors.violet : EngColors.green))),
                  _kv('Total', r.costHidden ? _masked() : Text(formatMoney(r.totalCost))),
                  _kv('Registro', Text(r.createdByName)),
                ]),
              ),
            ),
          );
        },
      ),
    );
  }
}

class ReceiptDetailScreen extends StatefulWidget {
  const ReceiptDetailScreen({super.key, required this.id});
  final String id;
  @override
  State<ReceiptDetailScreen> createState() => _ReceiptDetailScreenState();
}

class _ReceiptDetailScreenState extends State<ReceiptDetailScreen> {
  Receipt? _r;
  List<DocumentInfo>? _docs;
  ApiException? _error;
  bool _busy = false;
  bool _uploading = false;
  final _costs = <int, TextEditingController>{};
  final _freight = TextEditingController();
  DateTime? _due;
  bool _changed = false;
  final _confirmKey = ApiClient.newIdempotencyKey();

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_r == null && _error == null) _load();
  }

  Future<void> _load() async {
    try {
      final api = EngScope.of(context).api;
      final r = await api.receipt(widget.id);
      final docs = await api.receiptAttachments(widget.id);
      if (mounted) {
        setState(() {
          _r = r;
          _docs = docs;
        });
      }
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  Future<void> _confirm() async {
    setState(() => _busy = true);
    try {
      final r = await EngScope.of(context).api.confirmReceipt(
          id: _r!.id, version: _r!.version, idempotencyKey: _confirmKey,
          unitCosts: {for (final e in _costs.entries) e.key: e.value.text},
          freightAmount: _freight.text,
          dueDate: _due == null ? null : '${_due!.year.toString().padLeft(4, '0')}-${_due!.month.toString().padLeft(2, '0')}-${_due!.day.toString().padLeft(2, '0')}');
      if (mounted) {
        setState(() {
          _r = r;
          _busy = false;
          _changed = true;
        });
      }
    } on ApiException catch (e) {
      if (mounted) {
        setState(() => _busy = false);
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.message)));
      }
    }
  }

  Future<void> _attach(bool camera) async {
    final api = EngScope.of(context).api;
    final messenger = ScaffoldMessenger.of(context);
    final picked = await attachmentPicker(camera: camera);
    if (picked == null || !mounted) return;
    setState(() => _uploading = true);
    try {
      final d = await api.uploadReceiptAttachment(id: widget.id, fileName: picked.name, bytes: picked.bytes, contentType: picked.mime);
      final docs = await api.receiptAttachments(widget.id);
      if (!mounted) return;
      setState(() => _docs = docs);
      messenger.showSnackBar(SnackBar(content: Text(d.available ? 'Anexo enviado.' : 'Arquivo recusado: ${d.rejection}')));
    } on ApiException catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(e.isNetwork ? 'Sem conexão. O anexo não foi enviado.' : e.message)));
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  Widget _attachments(bool canAttach) {
    final docs = _docs ?? const <DocumentInfo>[];
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Text('Anexos', style: TextStyle(fontWeight: FontWeight.w600)),
          const Text('Foto da carga, nota ou romaneio. O servidor confere o conteúdo; antivírus ainda não configurado.',
              style: TextStyle(color: EngColors.muted, fontSize: 12)),
          if (docs.isEmpty) const Padding(padding: EdgeInsets.only(top: 6), child: Text('Nenhum anexo.')),
          for (final d in docs)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Row(children: [
                EngIcon(d.available ? 'attachment' : 'close', size: 16, color: d.available ? EngColors.green : EngColors.error),
                const SizedBox(width: 6),
                Expanded(child: Text('${d.fileName} · ${(d.sizeBytes / 1024).ceil()} KB${d.available ? '' : ' · recusado'}',
                    overflow: TextOverflow.ellipsis)),
              ]),
            ),
          if (canAttach) ...[
            const SizedBox(height: 8),
            Wrap(spacing: 8, runSpacing: 8, children: [
              OutlinedButton.icon(onPressed: _uploading ? null : () => _attach(true),
                  icon: const EngIcon('attachment', size: 16), label: Text(_uploading ? 'Enviando…' : 'Fotografar')),
              OutlinedButton.icon(onPressed: _uploading ? null : () => _attach(false),
                  icon: const EngIcon('document', size: 16), label: const Text('Da galeria')),
            ]),
          ],
        ]),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    final r = _r;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) Navigator.of(context).pop(_changed);
      },
      child: Scaffold(
        appBar: AppBar(title: Text(r?.supplierName ?? 'Recebimento')),
        body: EngReadableWidth(child: _error != null
            ? (_error!.isForbidden || _error!.status == 404
                ? const ForbiddenStateView()
                : ErrorStateView(message: _error!.message, requestId: _error!.requestId, onRetry: _load))
            : r == null
                ? const LoadingState()
                : ListView(padding: const EdgeInsets.all(16), children: [
                    Text(receiptStatusLabels[r.status] ?? r.status,
                        style: TextStyle(color: r.isDraft ? EngColors.violet : EngColors.green, fontWeight: FontWeight.w600)),
                    _kv('Documento', Text(r.documentNumber ?? '—')),
                    _kv('Registrado por', Text(r.createdByName)),
                    if (r.confirmedByName != null) _kv('Conferido por', Text(r.confirmedByName!)),
                    _kv('Total', r.costHidden ? _masked() : Text(formatMoney(r.totalCost))),
                    const SizedBox(height: 12),
                    for (final l in r.lines)
                      Card(
                        child: Padding(
                          padding: const EdgeInsets.all(12),
                          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                            Text(l.productName, style: const TextStyle(fontWeight: FontWeight.w600)),
                            _kv('Prevista', Text(l.quantityExpected == null ? '—' : formatQty(l.quantityExpected!, l.unit))),
                            _kv('Recebida', Text(formatQty(l.quantityReceived, l.unit) +
                                (l.unit != l.baseUnit ? ' (= ${formatQty(l.baseQuantity, l.baseUnit)})' : ''))),
                            if (l.difference != null && l.difference != '0')
                              _kv('Diferença', Text(formatDecimal(l.difference), style: const TextStyle(color: EngColors.warning))),
                            if (l.lot != null) _kv('Lote', Text(l.lot!)),
                            _kv('Custo unitário', r.costHidden ? _masked() : Text(l.unitCost == null ? 'pendente' : 'R\$ ${formatDecimal(l.unitCost)}')),
                          ]),
                        ),
                      ),
                    const SizedBox(height: 8),
                    _attachments(me.can('inventory.receipts.create') || me.can('inventory.receipts.confirm')),
                    if (r.isDraft && me.can('inventory.receipts.confirm')) ...[
                      const SizedBox(height: 16),
                      const Text('Ao confirmar, as quantidades entram no estoque físico e nasce a conta a pagar. Sem custo informado por quem tem acesso, o servidor não confirma.',
                          style: TextStyle(color: EngColors.muted)),
                      if (me.canSeeField('purchase_cost')) ...[
                        for (final l in r.lines)
                          TextField(
                            controller: _costs.putIfAbsent(l.lineNo, () => TextEditingController(text: l.unitCost ?? '')),
                            keyboardType: const TextInputType.numberWithOptions(decimal: true),
                            decoration: InputDecoration(labelText: 'Custo unitário — ${l.productName} (por ${unitLabel(l.unit, '1')})'),
                          ),
                        TextField(controller: _freight, keyboardType: const TextInputType.numberWithOptions(decimal: true),
                            decoration: const InputDecoration(labelText: 'Frete de compra autorizado (R\$)',
                                helperText: 'Rateado pelo valor e incorporado ao custo')),
                      ],
                      const SizedBox(height: 8),
                      OutlinedButton(
                        onPressed: () async {
                          final now = DateTime.now();
                          final d = await showDatePicker(context: context, firstDate: now.subtract(const Duration(days: 30)),
                              lastDate: now.add(const Duration(days: 365)), initialDate: _due ?? now.add(const Duration(days: 30)));
                          if (d != null) setState(() => _due = d);
                        },
                        child: Text(_due == null ? 'Vencimento da conta a pagar (opcional)'
                            : 'Vencimento: ${_due!.day.toString().padLeft(2, '0')}/${_due!.month.toString().padLeft(2, '0')}/${_due!.year}'),
                      ),
                      const SizedBox(height: 8),
                      FilledButton(onPressed: _busy ? null : _confirm, child: Text(_busy ? 'Confirmando…' : 'Confirmar entrada')),
                    ],
                  ])),
      ),
    );
  }
}

class _LineForm {
  String? productId;
  String unit = '';
  final expected = TextEditingController();
  final received = TextEditingController();
  final lot = TextEditingController();
  final cost = TextEditingController();
}

/// R07 — conferir cana recebida. Quantidade prevista/recebida, lote e documento; custo só para quem vê custo.
class NewReceiptScreen extends StatefulWidget {
  const NewReceiptScreen({super.key});
  @override
  State<NewReceiptScreen> createState() => _NewReceiptScreenState();
}

class _NewReceiptScreenState extends State<NewReceiptScreen> {
  List<Supplier>? _suppliers;
  List<Product>? _products;
  ApiException? _loadError;
  String? _supplierId;
  final _doc = TextEditingController();
  final _notes = TextEditingController();
  final _freight = TextEditingController();
  final _lines = [_LineForm()];
  final _key = ApiClient.newIdempotencyKey(); // reaproveitada se o envio falhar e for repetido
  bool _busy = false;
  ApiException? _error;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_suppliers == null && _loadError == null) _load();
  }

  Future<void> _load() async {
    final api = EngScope.of(context).api;
    try {
      final s = await api.suppliers();
      final p = await api.products();
      if (mounted) {
        setState(() {
          _suppliers = s.items;
          _products = p.items.where((x) => x.baseUnitConfirmed).toList();
        });
      }
    } on ApiException catch (e) {
      if (mounted) setState(() => _loadError = e);
    }
  }

  String? _fe(String f) => _error?.fieldErrors.where((e) => e.field == f).map((e) => e.message).firstOrNull;

  Future<void> _save() async {
    final seesCost = EngScope.of(context).me.canSeeField('purchase_cost');
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final r = await EngScope.of(context).api.createReceipt(
            idempotencyKey: _key,
            supplierId: _supplierId ?? '',
            documentNumber: _doc.text,
            notes: _notes.text,
            freightAmount: seesCost ? _freight.text : null,
            lines: [
              for (final l in _lines)
                ReceiptLineInput(
                  productId: l.productId ?? '',
                  unit: l.unit,
                  quantityExpected: l.expected.text,
                  quantityReceived: l.received.text,
                  lot: l.lot.text,
                  unitCost: seesCost ? l.cost.text : null,
                ),
            ],
          );
      if (!mounted) return;
      final s = EngScope.of(context);
      await Navigator.of(context).pushReplacement(MaterialPageRoute<bool>(
          builder: (_) => EngScope(config: s.config, session: s.session, child: ReceiptDetailScreen(id: r.id))), result: true);
    } on ApiException catch (e) {
      if (mounted) {
        setState(() {
          _error = e;
          _busy = false; // dados digitados preservados
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    final seesCost = me.canSeeField('purchase_cost');
    Widget body;
    if (_loadError != null) {
      body = _loadError!.isForbidden
          ? const ForbiddenStateView()
          : ErrorStateView(message: _loadError!.message, requestId: _loadError!.requestId, offline: _loadError!.isNetwork, onRetry: _load);
    } else if (_suppliers == null) {
      body = const LoadingState();
    } else {
      body = ListView(padding: const EdgeInsets.all(16), children: [
        const Text('Registre o que realmente chegou. A diferença fica registrada; o estoque não é completado para fechar o documento.',
            style: TextStyle(color: EngColors.muted)),
        const SizedBox(height: 12),
        DropdownButtonFormField<String>(
          initialValue: _supplierId,
          isExpanded: true,
          decoration: InputDecoration(labelText: 'Fornecedor *', errorText: _fe('supplier_id')),
          items: [for (final s in _suppliers!) DropdownMenuItem(value: s.id, child: Text(s.name, overflow: TextOverflow.ellipsis))],
          onChanged: (v) => setState(() => _supplierId = v),
        ),
        const SizedBox(height: 12),
        TextField(controller: _doc, maxLength: 60,
            decoration: InputDecoration(labelText: 'Documento (nota ou romaneio)', errorText: _fe('document_number'))),
        for (var i = 0; i < _lines.length; i++) _lineCard(i, seesCost),
        TextButton.icon(
          onPressed: () => setState(() => _lines.add(_LineForm())),
          icon: const EngIcon('plus', size: 16, color: EngColors.cyan),
          label: const Text('Adicionar produto'),
        ),
        if (seesCost)
          TextField(controller: _freight, keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: InputDecoration(labelText: 'Frete de compra autorizado (R\$)', errorText: _fe('freight_amount'),
                  helperText: 'Rateado pelo valor e incorporado ao custo'))
        else
          const Card(child: Padding(padding: EdgeInsets.all(12), child: Text(
              'Custo e frete ficam com quem tem acesso a custo de compra. Você registra as quantidades; a conferência completa o custo.'))),
        const SizedBox(height: 12),
        TextField(controller: _notes, maxLength: 500,
            decoration: InputDecoration(labelText: 'Observação da conferência', errorText: _fe('notes'))),
        if (_error != null && _error!.fieldErrors.isEmpty)
          Semantics(liveRegion: true, child: Padding(padding: const EdgeInsets.only(top: 8),
              child: Text(_error!.isNetwork ? 'Sem conexão. Nada foi salvo; tente de novo — o envio não será duplicado.' : _error!.message,
                  style: const TextStyle(color: EngColors.error)))),
        const SizedBox(height: 16),
        FilledButton(onPressed: _busy ? null : _save, child: Text(_busy ? 'Salvando…' : 'Salvar rascunho')),
        const SizedBox(height: 8),
        Text('Conferente: ${me.displayName}', style: const TextStyle(color: EngColors.muted, fontSize: 12)),
      ]);
    }
    return Scaffold(appBar: AppBar(title: const Text('Conferir cana recebida')), body: EngReadableWidth(child: body));
  }

  Widget _lineCard(int i, bool seesCost) {
    final l = _lines[i];
    final f = 'lines[$i]';
    final p = _products?.where((x) => x.id == l.productId).firstOrNull;
    final num = const TextInputType.numberWithOptions(decimal: true);
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 8),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(children: [
          Row(children: [
            Expanded(child: Text('Produto ${i + 1}', style: const TextStyle(fontWeight: FontWeight.w600))),
            if (_lines.length > 1)
              IconButton(tooltip: 'Remover produto ${i + 1}', onPressed: () => setState(() => _lines.removeAt(i)),
                  icon: const EngIcon('close', size: 18)),
          ]),
          DropdownButtonFormField<String>(
            initialValue: l.productId,
            isExpanded: true,
            decoration: InputDecoration(labelText: 'Produto *', errorText: _fe('$f.product_id')),
            items: [for (final x in _products!) DropdownMenuItem(value: x.id, child: Text(x.name, overflow: TextOverflow.ellipsis))],
            onChanged: (v) => setState(() {
              l.productId = v;
              l.unit = '';
            }),
          ),
          if (p != null)
            DropdownButtonFormField<String>(
              initialValue: l.unit,
              isExpanded: true,
              decoration: InputDecoration(labelText: 'Medida', errorText: _fe('$f.unit')),
              items: [
                DropdownMenuItem(value: '', child: Text(unitLabel(p.baseUnit))),
                for (final c in p.conversions)
                  DropdownMenuItem(value: c.unit, child: Text('${unitLabel(c.unit)} (= ${formatDecimal(c.factor)} ${unitLabel(p.baseUnit, c.factor)})',
                      overflow: TextOverflow.ellipsis)),
              ],
              onChanged: (v) => setState(() => l.unit = v ?? ''),
            ),
          Row(children: [
            Expanded(child: TextField(controller: l.expected, keyboardType: num,
                decoration: InputDecoration(labelText: 'Prevista', errorText: _fe('$f.quantity_expected')))),
            const SizedBox(width: 12),
            Expanded(child: TextField(controller: l.received, keyboardType: num,
                decoration: InputDecoration(labelText: 'Aceita *', errorText: _fe('$f.quantity_received')))),
          ]),
          TextField(controller: l.lot, maxLength: 60, decoration: InputDecoration(labelText: 'Lote', errorText: _fe('$f.lot'))),
          if (seesCost)
            TextField(controller: l.cost, keyboardType: num,
                decoration: InputDecoration(labelText: 'Custo unitário (R\$)', errorText: _fe('$f.unit_cost'))),
        ]),
      ),
    );
  }
}

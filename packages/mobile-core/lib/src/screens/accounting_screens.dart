import 'dart:typed_data';
import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:file_picker/file_picker.dart';
import 'package:material_ui/material_ui.dart';
import '../labels.dart';
import '../scope.dart';
import 'reports_screens.dart' show isoDate, todaySaoPaulo;

/// Escolha de arquivo (PDF, imagem, CSV). Substituível em teste. O servidor confere o conteúdo de novo.
typedef PickedFile = ({String name, Uint8List bytes, String mime});
typedef AccountingFilePicker = Future<PickedFile?> Function({required List<String> extensions});

AccountingFilePicker accountingFilePicker = ({required List<String> extensions}) async {
  final f = await FilePicker.pickFile(type: FileType.custom, allowedExtensions: extensions);
  if (f == null) return null;
  final bytes = await f.readAsBytes();
  final ext = f.name.split('.').last.toLowerCase();
  final mime = switch (ext) {
    'pdf' => 'application/pdf',
    'png' => 'image/png',
    'jpg' || 'jpeg' => 'image/jpeg',
    'csv' => 'text/csv',
    _ => 'application/octet-stream',
  };
  return (name: f.name, bytes: bytes, mime: mime);
};

/// Salvar no aparelho (PDF/CSV) pelo seletor do sistema. Substituível em teste. Devolve false se o usuário desistiu.
typedef AccountingFileSaver = Future<bool> Function({required String fileName, required Uint8List bytes, required String mime});

AccountingFileSaver accountingFileSaver = ({required String fileName, required Uint8List bytes, required String mime}) async =>
    (await FilePicker.saveFile(fileName: fileName, bytes: bytes, mimeType: mime)) != null;

const docOriginLabels = {'tax_obligation': 'Guia', 'payroll_run': 'Folha', 'expense': 'Despesa', 'payable': 'Conta a pagar'};

const taxStepLabels = {
  'DRAFT': 'Em preparação', 'SUBMITTED': 'Aguardando aprovação do patrão', 'RETURNED': 'Devolvida — corrigir',
  'APPROVED': 'Aprovada — pagamento ainda pendente', 'PAID': 'Paga', 'CANCELLED': 'Cancelada',
};
const taxKinds = ['DAS', 'ICMS', 'ISS', 'INSS', 'FGTS', 'IRRF', 'OUTRA'];
const docKindLabels = {'GUIA': 'Guia', 'COMPROVANTE': 'Comprovante', 'FOLHA': 'Folha', 'NOTA_FISCAL': 'Nota fiscal',
  'CONTRATO': 'Contrato', 'EXTRATO': 'Extrato', 'OUTRO': 'Outro'};

String _month(String ym) => ym.split('-').reversed.join('/');
String _date(String iso) => iso.split('-').reversed.join('/');

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

String _dt(String iso) {
  final d = DateTime.tryParse(iso);
  return d == null ? iso : formatDateTime(d);
}

/// "Ver documento" (C04/C08): baixa pelo download autorizado; imagem abre na tela, PDF/CSV vai para o aparelho.
class DocumentViewButton extends StatefulWidget {
  const DocumentViewButton({super.key, required this.doc, this.label = 'Ver documento'});
  final DocumentInfo doc;
  final String label;
  @override
  State<DocumentViewButton> createState() => _DocumentViewButtonState();
}

class _DocumentViewButtonState extends State<DocumentViewButton> {
  bool _busy = false;

  Future<void> _open() async {
    final api = EngScope.of(context).api;
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _busy = true);
    try {
      final f = await api.downloadDocument(widget.doc.id);
      if (!mounted) return;
      if (f.mime.startsWith('image/')) {
        await showDialog<void>(context: context, builder: (c) => AlertDialog(
          title: Text(widget.doc.fileName),
          content: Image.memory(f.bytes, semanticLabel: widget.doc.fileName, fit: BoxFit.contain),
          actions: [TextButton(onPressed: () => Navigator.of(c).pop(), child: const Text('Fechar'))],
        ));
      } else {
        final saved = await accountingFileSaver(fileName: widget.doc.fileName, bytes: f.bytes, mime: f.mime);
        messenger.showSnackBar(SnackBar(content: Text(saved ? 'Arquivo salvo no aparelho.' : 'Nada foi salvo.')));
      }
    } on ApiException catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(e.isForbidden ? 'Sem permissão para este arquivo.' : e.message)));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) =>
      TextButton(onPressed: _busy || !widget.doc.available ? null : _open, child: Text(_busy ? 'Baixando…' : widget.label));
}

/// Histórico do registro (C04 "Histórico da decisão"): quem fez, quando, por qual app e a observação. Sem valores.
class RecordHistoryView extends StatefulWidget {
  const RecordHistoryView({super.key, required this.load});
  final Future<List<RecordHistoryItem>> Function(ApiClient api) load;
  @override
  State<RecordHistoryView> createState() => _RecordHistoryViewState();
}

class _RecordHistoryViewState extends State<RecordHistoryView> {
  Future<List<RecordHistoryItem>>? _f;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _f ??= widget.load(EngScope.of(context).api);
  }

  @override
  Widget build(BuildContext context) => FutureBuilder<List<RecordHistoryItem>>(
        future: _f,
        builder: (context, snap) {
          if (snap.hasError) {
            final e = snap.error;
            return Text(e is ApiException ? e.message : 'Histórico indisponível.', style: const TextStyle(color: EngColors.muted));
          }
          if (!snap.hasData) return const Padding(padding: EdgeInsets.all(8), child: LinearProgressIndicator());
          final items = snap.data!;
          if (items.isEmpty) return const Text('Sem histórico.', style: TextStyle(color: EngColors.muted));
          return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            for (final i in items)
              Padding(padding: const EdgeInsets.symmetric(vertical: 4), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('${labelOf(auditActionLabels, i.action)} — ${i.actorName ?? (i.actorKind == 'SERVICE' ? 'Integração' : 'Sistema')}',
                    style: const TextStyle(fontWeight: FontWeight.w600)),
                Text('${_dt(i.occurredAt)}${i.clientId == null ? '' : ' · ${labelOf(clientLabels, i.clientId!)}'}',
                    style: const TextStyle(color: EngColors.muted, fontSize: 12)),
                if (i.note != null) Text('“${i.note}”'),
              ])),
          ]);
        },
      );
}

Future<String?> _askReason(BuildContext context, {required String title, required String action}) {
  final c = TextEditingController();
  return showDialog<String>(context: context, builder: (d) => AlertDialog(
    title: Text(title),
    content: TextField(controller: c, autofocus: true, maxLength: 500, decoration: const InputDecoration(labelText: 'Motivo *')),
    actions: [
      TextButton(onPressed: () => Navigator.of(d).pop(), child: const Text('Voltar')),
      FilledButton(onPressed: () => Navigator.of(d).pop(c.text), child: Text(action)),
    ],
  ));
}

Widget _err(ApiException? e) => e == null
    ? const SizedBox.shrink()
    : Padding(padding: const EdgeInsets.only(top: 8), child: Semantics(liveRegion: true, child: Text(
        e.fieldErrors.isEmpty ? e.message : [e.message, for (final f in e.fieldErrors) '${f.field}: ${f.message}'].join('\n'),
        style: const TextStyle(color: EngColors.error))));

/// Lista que carrega da API com estados de carregando/erro/vazio e "puxar para atualizar".
class _Loader<T> extends StatefulWidget {
  const _Loader({super.key, required this.load, required this.builder, required this.empty});
  final Future<T> Function(ApiClient api) load;
  final Widget Function(BuildContext context, T data, VoidCallback reload) builder;
  final bool Function(T data) empty;
  @override
  State<_Loader<T>> createState() => _LoaderState<T>();
}

class _LoaderState<T> extends State<_Loader<T>> {
  T? _data;
  ApiException? _error;
  bool _started = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_started) {
      _started = true;
      _load();
    }
  }

  Future<void> _load() async {
    setState(() {
      _error = null;
    });
    try {
      final d = await widget.load(EngScope.of(context).api);
      if (mounted) setState(() => _data = d);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_error != null) {
      return _error!.isForbidden
          ? const ForbiddenStateView()
          : ErrorStateView(message: _error!.message, requestId: _error!.requestId, offline: _error!.isNetwork, onRetry: _load);
    }
    if (_data == null) return const LoadingState();
    return RefreshIndicator(onRefresh: _load, child: widget.builder(context, _data as T, _load));
  }
}

/// C02 — agenda do contador: o que está pendente (guias por vencimento, folha, notas, documentos pedidos).
/// Sem dashboard nem números gerais: só os registros que o servidor libera para esta conta.
class AccountantAgendaScreen extends StatelessWidget {
  const AccountantAgendaScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    final today = isoDate(todaySaoPaulo());
    return _Loader<({List<TaxInfo> taxes, List<PayrollRunInfo> runs, List<DocRequestInfo> requests, List<InvoiceInfo> invoices})>(
      load: (api) async => (
        taxes: me.can('taxes.view') ? await api.taxes() : <TaxInfo>[],
        runs: me.can('payroll.view') ? await api.payrollRuns() : <PayrollRunInfo>[],
        requests: me.can('documents.view') ? await api.documentRequests() : <DocRequestInfo>[],
        invoices: me.can('invoices.view') ? await api.invoices() : <InvoiceInfo>[],
      ),
      empty: (d) => false,
      builder: (context, d, reload) {
        final pendingTaxes = d.taxes.where((t) => t.state != 'PAID' && t.state != 'CANCELLED').toList()
          ..sort((x, y) => x.dueDate.compareTo(y.dueDate));
        final pendingRuns = d.runs.where((r) => r.state != 'PAID' && r.state != 'CANCELLED').toList();
        final openReq = d.requests.where((r) => r.state == 'OPEN').toList();
        final invoices = d.invoices.where((n) => const ['DRAFT', 'REJECTED', 'UNCERTAIN'].contains(n.state)).toList();
        if (pendingTaxes.isEmpty && pendingRuns.isEmpty && openReq.isEmpty && invoices.isEmpty) {
          return ListView(children: const [SizedBox(height: 120), EmptyState(title: 'Nada pendente na agenda')]);
        }
        final overdue = pendingTaxes.where((t) => t.overdueOn(today)).length;
        return ListView(padding: const EdgeInsets.all(16), children: [
          if (overdue > 0)
            Card(child: Padding(padding: const EdgeInsets.all(12), child: Text('$overdue guia(s) vencida(s) sem pagamento registrado.',
                style: const TextStyle(color: EngColors.error, fontWeight: FontWeight.w700)))),
          for (final t in pendingTaxes)
            Card(child: ListTile(
              title: Text('Guia ${t.kind} ${_month(t.competence)} · ${formatMoney(t.amount)}'),
              subtitle: Text('${labelOf(taxStepLabels, t.state)} · ${t.overdueOn(today) ? 'VENCIDA em' : 'vence'} ${_date(t.dueDate)}',
                  style: t.overdueOn(today) ? const TextStyle(color: EngColors.error) : null),
              onTap: () async {
                await _push(context, TaxDetailScreen(id: t.id));
                reload();
              },
            )),
          for (final r in pendingRuns)
            Card(child: ListTile(
              title: Text('${r.description} ${_month(r.competence)}'),
              subtitle: Text(labelOf(taxStepLabels, r.state), style: r.state == 'RETURNED' ? const TextStyle(color: EngColors.error) : null),
              onTap: () async {
                await _push(context, PayrollDetailScreen(id: r.id));
                reload();
              },
            )),
          for (final n in invoices)
            Card(child: ListTile(
              title: Text('Nota do pedido #${n.orderNumber} · ${n.customerName}'),
              subtitle: Text(labelOf(invoiceStateLabels, n.state),
                  style: TextStyle(color: n.state == 'REJECTED' ? EngColors.error : n.state == 'UNCERTAIN' ? EngColors.warning : null)),
            )),
          for (final q in openReq)
            Card(child: ListTile(
              title: Text('Documento solicitado: ${q.title}'),
              subtitle: Text([
                if (q.requestedFromName != null) 'Pedido a ${q.requestedFromName}',
                if (q.dueDate != null) 'até ${_date(q.dueDate!)}',
                if (q.requestedFromName == null && q.dueDate == null) 'Aberto',
              ].join(' · ')),
              onTap: me.can('documents.attach')
                  ? () async {
                      await _push(context, FulfillRequestScreen(request: q));
                      reload();
                    }
                  : null,
            )),
        ]);
      },
    );
  }
}

/// Guias (C02/C04): lista e "Preparar guia".
class TaxesScreen extends StatelessWidget {
  const TaxesScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return Scaffold(
      floatingActionButton: me.can('taxes.create')
          ? FloatingActionButton.extended(
              onPressed: () => _push(context, const TaxFormScreen()),
              icon: const EngIcon('plus', color: EngColors.background),
              label: const Text('Preparar guia'),
            )
          : null,
      body: EngReadableWidth(child: _Loader<List<TaxInfo>>(
        load: (api) => api.taxes(),
        empty: (d) => d.isEmpty,
        builder: (context, items, reload) => items.isEmpty
            ? ListView(children: const [SizedBox(height: 120), EmptyState(title: 'Nenhuma guia')])
            : ListView(padding: const EdgeInsets.fromLTRB(16, 16, 16, 96), children: [
                for (final t in items)
                  Card(child: ListTile(
                    title: Text('${t.kind} · ${_month(t.competence)} · ${formatMoney(t.amount)}'),
                    subtitle: Text('${labelOf(taxStepLabels, t.state)} · vence ${_date(t.dueDate)}'),
                    onTap: () async {
                      await _push(context, TaxDetailScreen(id: t.id));
                      reload();
                    },
                  )),
              ]),
      )),
    );
  }
}

/// C03 — preparar guia (rascunho). O arquivo da guia é anexado no detalhe antes de enviar.
class TaxFormScreen extends StatefulWidget {
  const TaxFormScreen({super.key, this.existing});
  /// Guia em rascunho ou devolvida a corrigir (C04 → C03). Sem ela, cria uma nova.
  final TaxInfo? existing;
  @override
  State<TaxFormScreen> createState() => _TaxFormScreenState();
}

class _TaxFormScreenState extends State<TaxFormScreen> {
  String _kind = 'DAS';
  final _desc = TextEditingController();
  final _comp = TextEditingController();
  final _due = TextEditingController();
  final _amount = TextEditingController();
  final _notes = TextEditingController();
  final _key = ApiClient.newIdempotencyKey();
  bool _busy = false;
  ApiException? _error;

  @override
  void initState() {
    super.initState();
    final t = widget.existing;
    if (t != null) {
      _kind = taxKinds.contains(t.kind) ? t.kind : 'OUTRA';
      _desc.text = t.description;
      _comp.text = t.competence.length >= 7 ? t.competence.substring(0, 7) : t.competence;
      _due.text = t.dueDate;
      _amount.text = t.amount;
      _notes.text = t.notes ?? '';
    }
  }

  Future<void> _save() async {
    final api = EngScope.of(context).api;
    final nav = Navigator.of(context);
    setState(() {
      _busy = true;
      _error = null;
    });
    final existing = widget.existing;
    if (existing != null) {
      try {
        await api.updateTax(id: existing.id, version: existing.version, kind: _kind, description: _desc.text,
            competence: _comp.text, dueDate: _due.text, amount: _amount.text, notes: _notes.text, payrollRunId: existing.payrollRunId);
        if (mounted) nav.pop(true);
      } on ApiException catch (e) {
        setState(() => _error = e);
      } finally {
        if (mounted) setState(() => _busy = false);
      }
      return;
    }
    try {
      final t = await api.createTax(idempotencyKey: _key, kind: _kind, description: _desc.text, competence: _comp.text,
          dueDate: _due.text, amount: _amount.text, notes: _notes.text);
      if (!mounted) return;
      nav.pop(true);
      _push(context, TaxDetailScreen(id: t.id));
    } on ApiException catch (e) {
      setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text(widget.existing == null ? 'Preparar guia' : 'Corrigir guia')),
        body: EngReadableWidth(child: ListView(padding: const EdgeInsets.all(16), children: [
          DropdownButtonFormField<String>(
            initialValue: _kind,
            isExpanded: true,
            decoration: const InputDecoration(labelText: 'Tipo *'),
            items: [for (final k in taxKinds) DropdownMenuItem(value: k, child: Text(k))],
            onChanged: (v) => setState(() => _kind = v ?? 'DAS'),
          ),
          TextField(controller: _desc, decoration: InputDecoration(labelText: 'Descrição *', errorText: _fe(_error, 'description'))),
          TextField(controller: _comp, decoration: InputDecoration(labelText: 'Competência (AAAA-MM) *', errorText: _fe(_error, 'competence'))),
          TextField(controller: _due, decoration: InputDecoration(labelText: 'Vencimento (AAAA-MM-DD) *', errorText: _fe(_error, 'due_date'))),
          TextField(controller: _amount, keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: InputDecoration(labelText: 'Valor (R\$) *', errorText: _fe(_error, 'amount'))),
          TextField(controller: _notes, decoration: const InputDecoration(labelText: 'Observação')),
          const SizedBox(height: 16),
          FilledButton(onPressed: _busy ? null : _save,
              child: Text(_busy ? 'Salvando…' : widget.existing == null ? 'Salvar rascunho' : 'Salvar correção')),
          if (_error != null && _error!.fieldErrors.isEmpty) _err(_error),
        ])),
      );
}

/// C04 — acompanhar guia: anexar o arquivo, enviar para aprovação e ver a decisão. Sem acesso à conta bancária.
class TaxDetailScreen extends StatefulWidget {
  const TaxDetailScreen({super.key, required this.id});
  final String id;
  @override
  State<TaxDetailScreen> createState() => _TaxDetailScreenState();
}

class _TaxDetailScreenState extends State<TaxDetailScreen> {
  TaxInfo? _t;
  ApiException? _error;
  bool _busy = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_t == null && _error == null) _load();
  }

  Future<void> _load() async {
    try {
      final t = await EngScope.of(context).api.tax(widget.id);
      if (mounted) setState(() => _t = t);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  Future<void> _attach() async {
    final api = EngScope.of(context).api;
    final messenger = ScaffoldMessenger.of(context);
    final f = await accountingFilePicker(extensions: const ['pdf', 'png', 'jpg', 'jpeg']);
    if (f == null) return;
    setState(() => _busy = true);
    try {
      final d = await api.uploadTaxFile(id: widget.id, fileName: f.name, bytes: f.bytes, contentType: f.mime);
      messenger.showSnackBar(SnackBar(content: Text(d.available ? 'Arquivo da guia anexado.' : 'Arquivo recusado: ${d.rejection}')));
      await _load();
    } on ApiException catch (e) {
      setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _submit() async {
    final api = EngScope.of(context).api;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final t = await api.submitTax(id: widget.id, version: _t!.version);
      setState(() => _t = t);
    } on ApiException catch (e) {
      setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    final t = _t;
    Widget body;
    if (t == null) {
      body = _error != null ? ErrorStateView(message: _error!.message, requestId: _error!.requestId, onRetry: _load) : const LoadingState();
    } else {
      final editable = t.state == 'DRAFT' || t.state == 'RETURNED';
      body = ListView(padding: const EdgeInsets.all(16), children: [
        Text('${t.kind} · competência ${_month(t.competence)}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
        Text('${formatMoney(t.amount)} · vence ${_date(t.dueDate)}'),
        Text(t.description, style: const TextStyle(color: EngColors.muted)),
        if (t.createdByName != null)
          Text('Preparada por ${t.createdByName}${t.submittedByName == null ? '' : ' · enviada por ${t.submittedByName}'}',
              style: const TextStyle(color: EngColors.muted, fontSize: 12)),
        const SizedBox(height: 8),
        Card(child: Padding(padding: const EdgeInsets.all(12), child: Text(labelOf(taxStepLabels, t.state),
            style: TextStyle(fontWeight: FontWeight.w700, color: t.state == 'RETURNED' ? EngColors.error
                : t.state == 'PAID' ? EngColors.green : EngColors.violet)))),
        if (t.decidedNote != null) Text('${t.decidedByName ?? ''}: ${t.decidedNote}', style: const TextStyle(color: EngColors.warning)),
        const SizedBox(height: 12),
        const Text('Arquivos', style: TextStyle(fontWeight: FontWeight.w600)),
        if (t.attachments.isEmpty) const Text('Nenhum arquivo. Anexe a guia (PDF ou foto) antes de enviar.', style: TextStyle(color: EngColors.muted)),
        for (final d in t.attachments)
          Row(children: [
            Expanded(child: Text('${d.fileName} — ${d.available ? 'ok' : 'recusado: ${d.rejection}'}')),
            if (d.available) DocumentViewButton(doc: d),
          ]),
        if (editable && me.can('taxes.create')) ...[
          const SizedBox(height: 8),
          OutlinedButton(onPressed: _busy ? null : _attach, child: const Text('Anexar arquivo da guia')),
          OutlinedButton(
            onPressed: _busy
                ? null
                : () async {
                    final changed = await _push(context, TaxFormScreen(existing: t));
                    if (changed == true) await _load();
                  },
            child: const Text('Corrigir dados da guia'),
          ),
        ],
        if (editable && me.can('taxes.request')) ...[
          const SizedBox(height: 8),
          FilledButton(onPressed: _busy ? null : _submit, child: const Text('Enviar para aprovação')),
        ],
        if (t.state == 'APPROVED')
          const Text('O pagamento é registrado pelo patrão; a guia continua pendente até lá.', style: TextStyle(color: EngColors.muted)),
        _err(_error),
        const Divider(height: 32),
        const Text('Histórico da decisão', style: TextStyle(fontWeight: FontWeight.w600)),
        // Anexar não muda a versão da guia: a chave inclui os anexos para o histórico recarregar.
        RecordHistoryView(key: ValueKey('h-${t.id}-${t.version}-${t.attachments.length}'), load: (api) => api.taxHistory(t.id)),
      ]);
    }
    return Scaffold(appBar: AppBar(title: const Text('Guia')), body: EngReadableWidth(child: body));
  }
}

/// C05 — folha: importar o arquivo do sistema da contabilidade e enviar ao patrão. Só totais por rubrica,
/// salvo permissão individual concedida pelo dono.
class PayrollScreen extends StatefulWidget {
  const PayrollScreen({super.key});
  @override
  State<PayrollScreen> createState() => _PayrollScreenState();
}

class _PayrollScreenState extends State<PayrollScreen> {
  Key _list = UniqueKey();
  final _comp = TextEditingController();
  final _desc = TextEditingController(text: 'Folha mensal');
  final _payDate = TextEditingController();
  bool _busy = false;
  ApiException? _error;
  String? _ok;

  Future<void> _import() async {
    final api = EngScope.of(context).api;
    final f = await accountingFilePicker(extensions: const ['csv']);
    if (f == null) return;
    setState(() {
      _busy = true;
      _error = null;
      _ok = null;
    });
    try {
      final r = await api.importPayroll(competence: _comp.text, description: _desc.text, payDate: _payDate.text,
          fileName: f.name, bytes: f.bytes);
      setState(() {
        _ok = 'Folha importada: ${r.totals['people']} pessoa(s), custo ${formatMoney(r.totals['company_cost'] as String?)}.';
        _list = UniqueKey();
      });
    } on ApiException catch (e) {
      setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return ListView(padding: const EdgeInsets.all(16), children: [
      if (me.can('payroll.create')) ...[
        const Text('Importar folha (CSV funcionario;rubrica;tipo;valor)', style: TextStyle(fontWeight: FontWeight.w600)),
        TextField(controller: _comp, decoration: InputDecoration(labelText: 'Competência (AAAA-MM) *', errorText: _fe(_error, 'competence'))),
        TextField(controller: _desc, decoration: InputDecoration(labelText: 'Descrição *', errorText: _fe(_error, 'description'))),
        TextField(controller: _payDate, decoration: InputDecoration(labelText: 'Data de pagamento (AAAA-MM-DD) *', errorText: _fe(_error, 'pay_date'))),
        const SizedBox(height: 8),
        OutlinedButton(onPressed: _busy ? null : _import, child: Text(_busy ? 'Enviando…' : 'Escolher arquivo e importar')),
        if (_ok != null) Text(_ok!, style: const TextStyle(color: EngColors.green)),
        _err(_error),
        const Divider(height: 32),
      ],
      SizedBox(
        height: 520,
        child: _Loader<List<PayrollRunInfo>>(
          key: _list,
          load: (api) => api.payrollRuns(),
          empty: (d) => d.isEmpty,
          builder: (context, runs, reload) => runs.isEmpty
              ? ListView(children: const [EmptyState(title: 'Nenhuma folha')])
              : ListView(children: [
                  for (final r in runs) _RunCard(run: r, onChanged: reload),
                ]),
        ),
      ),
    ]);
  }
}

class _RunCard extends StatelessWidget {
  const _RunCard({required this.run, required this.onChanged});
  final PayrollRunInfo run;
  final VoidCallback onChanged;
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    final t = run.totals;
    return Card(child: InkWell(
      onTap: () async {
        await _push(context, PayrollDetailScreen(id: run.id));
        onChanged();
      },
      child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text('${run.description} · ${_month(run.competence)}', style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 16)),
      Text(labelOf(taxStepLabels, run.state)),
      Text('Proventos ${formatMoney(t['earnings'] as String?)} · Benefícios ${formatMoney(t['benefits'] as String?)} · '
          'Encargos ${formatMoney(t['employer_charges'] as String?)}'),
      Text('Custo total ${formatMoney(t['company_cost'] as String?)} · ${t['people']} pessoa(s)',
          style: const TextStyle(fontWeight: FontWeight.w700)),
      if (!run.individualVisible)
        const Text('Valores por pessoa: só com permissão individual do dono.', style: TextStyle(color: EngColors.muted, fontSize: 12)),
      if (run.decidedNote != null) Text('Patrão: ${run.decidedNote}', style: const TextStyle(color: EngColors.warning)),
      if ((run.state == 'DRAFT' || run.state == 'RETURNED') && me.can('payroll.request'))
        Padding(padding: const EdgeInsets.only(top: 8), child: FilledButton(
          onPressed: () async {
            final messenger = ScaffoldMessenger.of(context);
            try {
              await EngScope.of(context).api.submitPayroll(id: run.id, version: run.version);
              messenger.showSnackBar(const SnackBar(content: Text('Folha enviada ao patrão.')));
              onChanged();
            } on ApiException catch (e) {
              messenger.showSnackBar(SnackBar(content: Text(e.message)));
            }
          },
          child: const Text('Enviar ao patrão'),
        )),
    ]))));
  }
}

const _rubricKindLabels = {'EARNING': 'Provento', 'BENEFIT': 'Benefício', 'DEDUCTION': 'Desconto', 'ADVANCE': 'Adiantamento',
  'EMPLOYER_CHARGE': 'Encargo'};

/// C05 — detalhe da folha: totais por rubrica, decisão do patrão, histórico; enviar ou desistir (devolvida/rascunho).
class PayrollDetailScreen extends StatefulWidget {
  const PayrollDetailScreen({super.key, required this.id});
  final String id;
  @override
  State<PayrollDetailScreen> createState() => _PayrollDetailScreenState();
}

class _PayrollDetailScreenState extends State<PayrollDetailScreen> {
  PayrollRunInfo? _r;
  ApiException? _error;
  bool _busy = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_r == null && _error == null) _load();
  }

  Future<void> _load() async {
    try {
      final r = await EngScope.of(context).api.payrollRun(widget.id);
      if (mounted) setState(() => _r = r);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  Future<void> _act(Future<PayrollRunInfo> Function(ApiClient api) fn, String ok) async {
    final api = EngScope.of(context).api;
    final messenger = ScaffoldMessenger.of(context);
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final r = await fn(api);
      setState(() => _r = r);
      messenger.showSnackBar(SnackBar(content: Text(ok)));
    } on ApiException catch (e) {
      setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    final r = _r;
    Widget body;
    if (r == null) {
      body = _error != null
          ? (_error!.isForbidden ? const ForbiddenStateView() : ErrorStateView(message: _error!.message, requestId: _error!.requestId, onRetry: _load))
          : const LoadingState();
    } else {
      final open = r.state == 'DRAFT' || r.state == 'RETURNED';
      final t = r.totals;
      body = ListView(padding: const EdgeInsets.all(16), children: [
        Text('${r.description} · ${_month(r.competence)}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
        if (r.payDate != null) Text('Pagamento previsto ${_date(r.payDate!)} · ${r.source == 'IMPORT' ? 'importada de arquivo' : 'lançada'}'),
        Card(child: Padding(padding: const EdgeInsets.all(12), child: Text(labelOf(taxStepLabels, r.state),
            style: TextStyle(fontWeight: FontWeight.w700, color: r.state == 'RETURNED' ? EngColors.error
                : r.state == 'PAID' ? EngColors.green : EngColors.violet)))),
        if (r.decidedNote != null) Text('${r.decidedByName ?? 'Patrão'}: ${r.decidedNote}', style: const TextStyle(color: EngColors.warning)),
        const SizedBox(height: 8),
        Text('Proventos ${formatMoney(t['earnings'] as String?)} · Benefícios ${formatMoney(t['benefits'] as String?)}'),
        Text('Encargos ${formatMoney(t['employer_charges'] as String?)} · Descontos ${formatMoney(t['deductions'] as String?)}'),
        Text('Líquido ${formatMoney(t['net_pay'] as String?)} · Custo total ${formatMoney(t['company_cost'] as String?)}',
            style: const TextStyle(fontWeight: FontWeight.w700)),
        const SizedBox(height: 12),
        const Text('Rubricas', style: TextStyle(fontWeight: FontWeight.w600)),
        for (final x in r.rubrics)
          Text('${x['rubric']} (${labelOf(_rubricKindLabels, '${x['kind']}')}) · ${formatMoney(x['amount'] as String?)} · ${x['people']} pessoa(s)'),
        if (!r.individualVisible)
          const Text('Valores por pessoa: só com permissão individual do dono.', style: TextStyle(color: EngColors.muted, fontSize: 12)),
        if (open && me.can('payroll.request'))
          Padding(padding: const EdgeInsets.only(top: 12), child: FilledButton(
            onPressed: _busy ? null : () => _act((api) => api.submitPayroll(id: r.id, version: r.version), 'Folha enviada ao patrão.'),
            child: const Text('Enviar ao patrão'),
          )),
        if (open && me.can('payroll.create'))
          OutlinedButton(
            onPressed: _busy
                ? null
                : () async {
                    final why = await _askReason(context, title: 'Desistir desta folha?', action: 'Cancelar folha');
                    if (why == null) return;
                    await _act((api) => api.cancelPayroll(id: r.id, version: r.version, note: why),
                        'Folha cancelada. A versão corrigida já pode ser importada.');
                  },
            child: Text(r.state == 'RETURNED' ? 'Cancelar e importar a versão corrigida' : 'Cancelar folha'),
          ),
        _err(_error),
        const Divider(height: 32),
        const Text('Histórico', style: TextStyle(fontWeight: FontWeight.w600)),
        RecordHistoryView(key: ValueKey('ph-${r.id}-${r.version}'), load: (api) => api.payrollHistory(r.id)),
      ]);
    }
    return Scaffold(appBar: AppBar(title: const Text('Folha')), body: EngReadableWidth(child: body));
  }
}

/// C08/C09 — documentos: enviar arquivo real (validado pelo servidor), solicitar documento e ver as solicitações.
class AccountingDocumentsScreen extends StatefulWidget {
  const AccountingDocumentsScreen({super.key});
  @override
  State<AccountingDocumentsScreen> createState() => _AccountingDocumentsScreenState();
}

class _AccountingDocumentsScreenState extends State<AccountingDocumentsScreen> {
  Key _list = UniqueKey();
  String _kind = 'OUTRO';
  final _title = TextEditingController();
  final _comp = TextEditingController();
  final _request = TextEditingController();
  bool _busy = false;
  ApiException? _error;
  String? _ok;

  Future<void> _upload() async {
    final api = EngScope.of(context).api;
    final f = await accountingFilePicker(extensions: const ['pdf', 'png', 'jpg', 'jpeg']);
    if (f == null) return;
    setState(() {
      _busy = true;
      _error = null;
      _ok = null;
    });
    try {
      await api.uploadAccountingDocument(kind: _kind, title: _title.text, competence: _comp.text, fileName: f.name,
          bytes: f.bytes, contentType: f.mime);
      setState(() {
        _ok = 'Documento enviado.';
        _title.clear();
        _list = UniqueKey();
      });
    } on ApiException catch (e) {
      setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _ask() async {
    final api = EngScope.of(context).api;
    setState(() {
      _error = null;
      _ok = null;
    });
    try {
      await api.requestDocument(title: _request.text);
      setState(() {
        _ok = 'Solicitação registrada.';
        _request.clear();
        _list = UniqueKey();
      });
    } on ApiException catch (e) {
      setState(() => _error = e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return ListView(padding: const EdgeInsets.all(16), children: [
      if (me.can('documents.attach')) ...[
        const Text('Enviar documento', style: TextStyle(fontWeight: FontWeight.w600)),
        DropdownButtonFormField<String>(
          initialValue: _kind,
          isExpanded: true,
          decoration: const InputDecoration(labelText: 'Tipo *'),
          items: [for (final e in docKindLabels.entries) DropdownMenuItem(value: e.key, child: Text(e.value))],
          onChanged: (v) => setState(() => _kind = v ?? 'OUTRO'),
        ),
        TextField(controller: _title, decoration: InputDecoration(labelText: 'Título *', errorText: _fe(_error, 'title'))),
        TextField(controller: _comp, decoration: InputDecoration(labelText: 'Competência (AAAA-MM)', errorText: _fe(_error, 'competence'))),
        const SizedBox(height: 8),
        OutlinedButton(onPressed: _busy ? null : _upload, child: Text(_busy ? 'Enviando…' : 'Escolher arquivo e enviar')),
      ],
      if (me.can('documents.create')) ...[
        const SizedBox(height: 16),
        TextField(controller: _request, decoration: const InputDecoration(labelText: 'Solicitar documento (o que precisa)')),
        OutlinedButton(onPressed: _ask, child: const Text('Solicitar')),
      ],
      if (_ok != null) Text(_ok!, style: const TextStyle(color: EngColors.green)),
      _err(_error),
      const Divider(height: 32),
      SizedBox(
        height: 520,
        child: _Loader<({List<AccDocInfo> docs, List<DocRequestInfo> requests})>(
          key: _list,
          load: (api) async => (docs: await api.accountingDocuments(), requests: await api.documentRequests()),
          empty: (d) => false,
          builder: (context, d, reload) => ListView(children: [
            for (final q in d.requests.where((r) => r.state == 'OPEN'))
              Card(child: ListTile(
                title: Text('Solicitado: ${q.title}'),
                subtitle: Text(me.can('documents.attach') ? 'Aguardando envio — toque para atender' : 'Aguardando envio'),
                onTap: me.can('documents.attach')
                    ? () async {
                        await _push(context, FulfillRequestScreen(request: q));
                        reload();
                      }
                    : null,
              )),
            if (d.docs.isEmpty) const EmptyState(title: 'Nenhum documento'),
            for (final x in d.docs)
              Card(child: ListTile(
                title: Text(x.title),
                subtitle: Text('${labelOf(docKindLabels, x.kind)}${x.competence == null ? '' : ' · ${_month(x.competence!)}'} · '
                    'versão ${x.currentVersion} · ${x.createdByName}'
                    '${x.relatedType == null ? '' : ' · origem: ${labelOf(docOriginLabels, x.relatedType!)}'}'),
                onTap: () async {
                  await _push(context, AccountingDocumentDetailScreen(id: x.id));
                  reload();
                },
              )),
          ]),
        ),
      ),
    ]);
  }
}

/// C09 — documento: versões com motivo e autor, ver cada arquivo, enviar nova versão (validada pelo servidor).
class AccountingDocumentDetailScreen extends StatefulWidget {
  const AccountingDocumentDetailScreen({super.key, required this.id});
  final String id;
  @override
  State<AccountingDocumentDetailScreen> createState() => _AccountingDocumentDetailScreenState();
}

class _AccountingDocumentDetailScreenState extends State<AccountingDocumentDetailScreen> {
  AccDocInfo? _d;
  ApiException? _error;
  bool _busy = false;
  final _reason = TextEditingController();

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_d == null && _error == null) _load();
  }

  Future<void> _load() async {
    try {
      final d = await EngScope.of(context).api.accountingDocument(widget.id);
      if (mounted) setState(() => _d = d);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  Future<void> _newVersion() async {
    final api = EngScope.of(context).api;
    final messenger = ScaffoldMessenger.of(context);
    final f = await accountingFilePicker(extensions: const ['pdf', 'png', 'jpg', 'jpeg']);
    if (f == null) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final d = await api.newDocumentVersion(id: widget.id, reason: _reason.text, fileName: f.name, bytes: f.bytes, contentType: f.mime);
      setState(() {
        _d = d;
        _reason.clear();
      });
      messenger.showSnackBar(SnackBar(content: Text('Versão ${d.currentVersion} enviada.')));
    } on ApiException catch (e) {
      setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    final d = _d;
    Widget body;
    if (d == null) {
      body = _error != null
          ? (_error!.isForbidden ? const ForbiddenStateView() : ErrorStateView(message: _error!.message, requestId: _error!.requestId, onRetry: _load))
          : const LoadingState();
    } else {
      body = ListView(padding: const EdgeInsets.all(16), children: [
        Text(d.title, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
        Text('${labelOf(docKindLabels, d.kind)}${d.competence == null ? '' : ' · ${_month(d.competence!)}'} · '
            'origem: ${d.relatedType == null ? 'envio avulso' : labelOf(docOriginLabels, d.relatedType!)}'),
        const Text('Arquivo privado: só abre para quem tem permissão; cada download fica registrado.',
            style: TextStyle(color: EngColors.muted, fontSize: 12)),
        const SizedBox(height: 12),
        const Text('Versões', style: TextStyle(fontWeight: FontWeight.w600)),
        for (final v in d.versions.reversed)
          Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Versão ${v.versionNo}${v.versionNo == d.currentVersion ? ' (atual)' : ''} · ${v.createdByName}',
                style: const TextStyle(fontWeight: FontWeight.w600)),
            if (v.createdAt.isNotEmpty) Text(_dt(v.createdAt), style: const TextStyle(color: EngColors.muted, fontSize: 12)),
            if (v.reason != null) Text('Motivo: ${v.reason}'),
            if (v.file != null)
              Row(children: [
                Expanded(child: Text('${v.file!.fileName}${v.file!.available ? '' : ' — recusado: ${v.file!.rejection}'}')),
                if (v.file!.available) DocumentViewButton(doc: v.file!),
              ]),
          ]))),
        if (me.can('documents.attach')) ...[
          const SizedBox(height: 12),
          TextField(controller: _reason, decoration: InputDecoration(labelText: 'Motivo da nova versão *', errorText: _fe(_error, 'reason'))),
          OutlinedButton(onPressed: _busy ? null : _newVersion, child: Text(_busy ? 'Enviando…' : 'Escolher arquivo e enviar nova versão')),
        ],
        _err(_error),
      ]);
    }
    return Scaffold(appBar: AppBar(title: const Text('Documento')), body: EngReadableWidth(child: body));
  }
}

/// C08 — atender uma solicitação de documento com o arquivo real (o servidor liga o envio à solicitação).
class FulfillRequestScreen extends StatefulWidget {
  const FulfillRequestScreen({super.key, required this.request});
  final DocRequestInfo request;
  @override
  State<FulfillRequestScreen> createState() => _FulfillRequestScreenState();
}

class _FulfillRequestScreenState extends State<FulfillRequestScreen> {
  late String _kind = docKindLabels.containsKey(widget.request.kind) ? widget.request.kind! : 'OUTRO';
  late final _title = TextEditingController(text: widget.request.title);
  final _comp = TextEditingController();
  bool _busy = false;
  ApiException? _error;

  Future<void> _send() async {
    final api = EngScope.of(context).api;
    final nav = Navigator.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final f = await accountingFilePicker(extensions: const ['pdf', 'png', 'jpg', 'jpeg']);
    if (f == null) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await api.uploadAccountingDocument(kind: _kind, title: _title.text, competence: _comp.text, requestId: widget.request.id,
          fileName: f.name, bytes: f.bytes, contentType: f.mime);
      messenger.showSnackBar(const SnackBar(content: Text('Solicitação atendida.')));
      nav.pop(true);
    } on ApiException catch (e) {
      setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final q = widget.request;
    return Scaffold(
      appBar: AppBar(title: const Text('Atender solicitação')),
      body: EngReadableWidth(child: ListView(padding: const EdgeInsets.all(16), children: [
        Text(q.title, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
        if (q.description != null) Text(q.description!),
        Text([
          if (q.createdByName != null) 'Pedido por ${q.createdByName}',
          if (q.dueDate != null) 'até ${_date(q.dueDate!)}',
        ].join(' · '), style: const TextStyle(color: EngColors.muted)),
        const SizedBox(height: 12),
        DropdownButtonFormField<String>(
          initialValue: _kind,
          isExpanded: true,
          decoration: const InputDecoration(labelText: 'Tipo *'),
          items: [for (final e in docKindLabels.entries) DropdownMenuItem(value: e.key, child: Text(e.value))],
          onChanged: (v) => setState(() => _kind = v ?? 'OUTRO'),
        ),
        TextField(controller: _title, decoration: InputDecoration(labelText: 'Título *', errorText: _fe(_error, 'title'))),
        TextField(controller: _comp, decoration: InputDecoration(labelText: 'Competência (AAAA-MM)', errorText: _fe(_error, 'competence'))),
        const SizedBox(height: 12),
        FilledButton(onPressed: _busy ? null : _send, child: Text(_busy ? 'Enviando…' : 'Escolher arquivo e enviar')),
        _err(_error),
      ])),
    );
  }
}

const invoiceStateLabels = {
  'DRAFT': 'Rascunho — conferir', 'READY': 'Conferida', 'TRANSMITTING': 'Enviada — aguardando a SEFAZ',
  'UNCERTAIN': 'Resultado incerto', 'AUTHORIZED': 'Autorizada', 'REJECTED': 'Rejeitada',
  'CANCEL_PENDING': 'Cancelamento pendente', 'CANCELLED': 'Cancelada', 'DISCARDED': 'Descartada',
};

/// F08 — notas fiscais para o contador: confere o rascunho (NCM dos produtos) e acompanha o resultado.
/// Transmitir é do patrão; sem provedor contratado a emissão aparece BLOQUEADA (nada é simulado).
class InvoicesScreen extends StatelessWidget {
  const InvoicesScreen({super.key});
  @override
  Widget build(BuildContext context) => _Loader<({FiscalSettingsInfo settings, List<InvoiceInfo> items})>(
        load: (api) async => (settings: await api.fiscalSettings(), items: await api.invoices()),
        empty: (d) => false,
        builder: (context, d, reload) => ListView(padding: const EdgeInsets.all(16), children: [
          if (d.settings.blocked)
            Card(child: Padding(padding: const EdgeInsets.all(12), child: Text(
              'Emissão BLOQUEADA: provedor fiscal NÃO CONFIGURADO. Rascunho e conferência funcionam; nada é transmitido.',
              style: const TextStyle(color: EngColors.warning, fontWeight: FontWeight.w600)))),
          if (d.settings.missing.isNotEmpty)
            Text('Falta definir: ${d.settings.missing.join(' · ')}', style: const TextStyle(color: EngColors.muted)),
          if (d.items.where((n) => n.state != 'DISCARDED').isEmpty)
            const EmptyState(title: 'Nenhuma nota'),
          for (final n in d.items.where((n) => n.state != 'DISCARDED'))
            _InvoiceCard(n: n, blocked: d.settings.blocked, onChanged: reload),
        ]),
      );
}

class _InvoiceCard extends StatefulWidget {
  const _InvoiceCard({required this.n, required this.onChanged, this.blocked = true});
  final InvoiceInfo n;
  final bool blocked;
  final VoidCallback onChanged;
  @override
  State<_InvoiceCard> createState() => _InvoiceCardState();
}

class _InvoiceCardState extends State<_InvoiceCard> {
  final _ncm = <String, TextEditingController>{};
  ApiException? _error;
  String _transmitKey = ApiClient.newIdempotencyKey();

  Future<void> _reasoned(String title, Future<Object?> Function(ApiClient api, String why) fn, String ok) async {
    final c = TextEditingController();
    final why = await showDialog<String>(context: context, builder: (d) => AlertDialog(
      title: Text(title),
      content: TextField(controller: c, autofocus: true, decoration: const InputDecoration(labelText: 'Justificativa *')),
      actions: [
        TextButton(onPressed: () => Navigator.of(d).pop(), child: const Text('Voltar')),
        FilledButton(onPressed: () => Navigator.of(d).pop(c.text.trim()), child: const Text('Confirmar')),
      ],
    ));
    if (why == null || why.isEmpty || !mounted) return;
    await _do((api) async {
      await fn(api, why);
    }, ok);
  }

  /// C07 "Transmitir autorizado": só com invoices.confirm e provedor configurado. O resultado mostrado é o do servidor:
  /// rejeição aparece como rejeição; em curso/incerta não é emissão.
  Future<void> _transmit() async {
    final api = EngScope.of(context).api;
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _error = null);
    try {
      final r = await api.transmitInvoice(id: widget.n.id, version: widget.n.version, idempotencyKey: _transmitKey);
      messenger.showSnackBar(SnackBar(content: Text(switch (r.state) {
        'AUTHORIZED' => 'Nota autorizada.',
        'REJECTED' => 'Nota REJEITADA: ${r.rejectionReason ?? 'sem motivo informado'}',
        _ => 'Enviada, sem resultado ainda (${labelOf(invoiceStateLabels, r.state)}). Não é emissão.',
      })));
      _transmitKey = ApiClient.newIdempotencyKey();
      widget.onChanged();
    } on ApiException catch (e) {
      setState(() => _error = e);
    }
  }

  Future<void> _do(Future<void> Function(ApiClient api) fn, String ok) async {
    final api = EngScope.of(context).api;
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _error = null);
    try {
      await fn(api);
      messenger.showSnackBar(SnackBar(content: Text(ok)));
      widget.onChanged();
    } on ApiException catch (e) {
      setState(() => _error = e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final n = widget.n;
    final me = EngScope.of(context).me;
    final missing = n.state == 'DRAFT' ? n.lines.where((l) => l.ncm == null).toList() : const <InvoiceLineInfo>[];
    return Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text('Pedido #${n.orderNumber} · ${n.customerName} · ${formatMoney(n.total)}',
          style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 16)),
      Text('${n.documentType == 'NFE' ? 'NF-e' : 'NFC-e'} · ${labelOf(invoiceStateLabels, n.state)}',
          style: TextStyle(color: n.state == 'AUTHORIZED' ? EngColors.green
              : n.state == 'REJECTED' ? EngColors.error : n.state == 'UNCERTAIN' ? EngColors.warning : EngColors.violet)),
      if (n.accessKey != null) Text('Chave ${n.accessKey} · protocolo ${n.protocol}', style: const TextStyle(fontSize: 12)),
      if (n.rejectionReason != null) Text(n.rejectionReason!, style: const TextStyle(color: EngColors.error)),
      if (n.state == 'UNCERTAIN')
        const Text('O provedor não respondeu: pode ter sido emitida. Não se reenvia; o patrão consulta.',
            style: TextStyle(color: EngColors.warning)),
      for (final i in n.issues) Text('• $i', style: const TextStyle(color: EngColors.warning)),
      if (me.can('invoices.request'))
        for (final l in missing) ...[
          TextField(
            controller: _ncm.putIfAbsent(l.productId, TextEditingController.new),
            keyboardType: TextInputType.number,
            decoration: InputDecoration(labelText: 'NCM de ${l.description}'),
          ),
          OutlinedButton(
            onPressed: () => _do((api) => api.setProductNcm(productId: l.productId, ncm: _ncm[l.productId]!.text),
                'NCM do produto salvo.'),
            child: const Text('Salvar NCM'),
          ),
        ],
      if (n.state == 'DRAFT' && me.can('invoices.request'))
        Padding(padding: const EdgeInsets.only(top: 8), child: FilledButton(
          onPressed: () => _do((api) => api.reviewInvoice(id: n.id, version: n.version), 'Nota conferida.'),
          child: const Text('Conferir nota'),
        )),
      if (n.cancelReason != null) Text('Cancelamento: ${n.cancelReason}', style: const TextStyle(color: EngColors.muted)),
      if (n.state == 'AUTHORIZED' && n.xmlDocumentId != null && me.can('invoices.export'))
        DocumentViewButton(doc: DocumentInfo(id: n.xmlDocumentId!, fileName: 'nfe-${n.accessKey ?? n.id}.xml', mime: 'application/xml',
            sizeBytes: 0, state: 'AVAILABLE', createdByName: ''), label: 'Baixar XML'),
      Wrap(spacing: 8, children: [
        if ((n.state == 'UNCERTAIN' || n.state == 'TRANSMITTING' || n.state == 'CANCEL_PENDING') && me.can('invoices.confirm'))
          OutlinedButton(
            onPressed: () => _do((api) async => api.sendJson('POST', '/api/v1/invoices/${n.id}/resolution', null),
                'Consulta feita: veja o resultado do provedor.'),
            child: const Text('Consultar resultado'),
          ),
        if (n.state == 'AUTHORIZED' && me.can('invoices.cancel'))
          OutlinedButton(onPressed: () => _reasoned('Cancelar nota', (api, why) => api.sendJson('POST', '/api/v1/invoices/${n.id}/cancellation',
              {'reason': why}, ifMatch: n.version, idempotencyKey: ApiClient.newIdempotencyKey()), 'Cancelamento enviado.'),
              child: const Text('Cancelar nota')),
        if ((n.state == 'DRAFT' || n.state == 'READY' || n.state == 'REJECTED') && me.can('invoices.create'))
          OutlinedButton(onPressed: () => _reasoned('Descartar rascunho', (api, why) => api.sendJson('POST', '/api/v1/invoices/${n.id}/discard',
              {'reason': why}, ifMatch: n.version), 'Rascunho descartado.'), child: const Text('Descartar')),
      ]),
      if (n.state == 'READY' && me.can('invoices.confirm'))
        Padding(padding: const EdgeInsets.only(top: 8), child: FilledButton(
          onPressed: widget.blocked ? null : _transmit,
          child: Text(widget.blocked ? 'Transmissão bloqueada (provedor não configurado)' : 'Transmitir nota'),
        )),
      _err(_error),
    ])));
  }
}

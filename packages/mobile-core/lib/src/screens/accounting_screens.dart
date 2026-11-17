import 'dart:typed_data';
import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:file_picker/file_picker.dart';
import 'package:material_ui/material_ui.dart';
import '../labels.dart';
import '../scope.dart';

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

/// C02 — agenda do contador: o que está pendente (guias, folha, documentos pedidos). Sem dashboard nem números gerais.
class AccountantAgendaScreen extends StatelessWidget {
  const AccountantAgendaScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return _Loader<({List<TaxInfo> taxes, List<PayrollRunInfo> runs, List<DocRequestInfo> requests})>(
      load: (api) async => (
        taxes: me.can('taxes.view') ? await api.taxes() : <TaxInfo>[],
        runs: me.can('payroll.view') ? await api.payrollRuns() : <PayrollRunInfo>[],
        requests: me.can('documents.view') ? await api.documentRequests() : <DocRequestInfo>[],
      ),
      empty: (d) => false,
      builder: (context, d, reload) {
        final pendingTaxes = d.taxes.where((t) => t.state != 'PAID' && t.state != 'CANCELLED').toList();
        final pendingRuns = d.runs.where((r) => r.state != 'PAID' && r.state != 'CANCELLED').toList();
        final openReq = d.requests.where((r) => r.state == 'OPEN').toList();
        if (pendingTaxes.isEmpty && pendingRuns.isEmpty && openReq.isEmpty) {
          return ListView(children: const [SizedBox(height: 120), EmptyState(title: 'Nada pendente na agenda')]);
        }
        return ListView(padding: const EdgeInsets.all(16), children: [
          for (final t in pendingTaxes)
            Card(child: ListTile(
              title: Text('Guia ${t.kind} ${_month(t.competence)} · ${formatMoney(t.amount)}'),
              subtitle: Text('${labelOf(taxStepLabels, t.state)} · vence ${_date(t.dueDate)}'),
              onTap: () async {
                await _push(context, TaxDetailScreen(id: t.id));
                reload();
              },
            )),
          for (final r in pendingRuns)
            Card(child: ListTile(title: Text('${r.description} ${_month(r.competence)}'), subtitle: Text(labelOf(taxStepLabels, r.state)))),
          for (final q in openReq)
            Card(child: ListTile(title: Text('Documento solicitado: ${q.title}'),
                subtitle: Text(q.requestedFromName == null ? 'Aberto' : 'Pedido a ${q.requestedFromName}'))),
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
      body: _Loader<List<TaxInfo>>(
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
      ),
    );
  }
}

/// C03 — preparar guia (rascunho). O arquivo da guia é anexado no detalhe antes de enviar.
class TaxFormScreen extends StatefulWidget {
  const TaxFormScreen({super.key});
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

  Future<void> _save() async {
    final api = EngScope.of(context).api;
    final nav = Navigator.of(context);
    setState(() {
      _busy = true;
      _error = null;
    });
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
        appBar: AppBar(title: const Text('Preparar guia')),
        body: ListView(padding: const EdgeInsets.all(16), children: [
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
          FilledButton(onPressed: _busy ? null : _save, child: Text(_busy ? 'Salvando…' : 'Salvar rascunho')),
          if (_error != null && _error!.fieldErrors.isEmpty) _err(_error),
        ]),
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
        const SizedBox(height: 8),
        Card(child: Padding(padding: const EdgeInsets.all(12), child: Text(labelOf(taxStepLabels, t.state),
            style: TextStyle(fontWeight: FontWeight.w700, color: t.state == 'RETURNED' ? EngColors.error
                : t.state == 'PAID' ? EngColors.green : EngColors.violet)))),
        if (t.decidedNote != null) Text('${t.decidedByName ?? ''}: ${t.decidedNote}', style: const TextStyle(color: EngColors.warning)),
        const SizedBox(height: 12),
        const Text('Arquivos', style: TextStyle(fontWeight: FontWeight.w600)),
        if (t.attachments.isEmpty) const Text('Nenhum arquivo. Anexe a guia (PDF ou foto) antes de enviar.', style: TextStyle(color: EngColors.muted)),
        for (final d in t.attachments)
          Text('${d.fileName} — ${d.available ? 'ok' : 'recusado: ${d.rejection}'}'),
        if (editable && me.can('taxes.create')) ...[
          const SizedBox(height: 8),
          OutlinedButton(onPressed: _busy ? null : _attach, child: const Text('Anexar arquivo da guia')),
        ],
        if (editable && me.can('taxes.request')) ...[
          const SizedBox(height: 8),
          FilledButton(onPressed: _busy ? null : _submit, child: const Text('Enviar para aprovação')),
        ],
        if (t.state == 'APPROVED')
          const Text('O pagamento é registrado pelo patrão; a guia continua pendente até lá.', style: TextStyle(color: EngColors.muted)),
        _err(_error),
      ]);
    }
    return Scaffold(appBar: AppBar(title: const Text('Guia')), body: body);
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
    return Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
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
    ])));
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
              Card(child: ListTile(title: Text('Solicitado: ${q.title}'), subtitle: const Text('Aguardando envio'))),
            if (d.docs.isEmpty) const EmptyState(title: 'Nenhum documento'),
            for (final x in d.docs)
              Card(child: ListTile(
                title: Text(x.title),
                subtitle: Text('${labelOf(docKindLabels, x.kind)}${x.competence == null ? '' : ' · ${_month(x.competence!)}'} · '
                    'versão ${x.currentVersion} · ${x.createdByName}'),
              )),
          ]),
        ),
      ),
    ]);
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
          for (final n in d.items.where((n) => n.state != 'DISCARDED')) _InvoiceCard(n: n, onChanged: reload),
        ]),
      );
}

class _InvoiceCard extends StatefulWidget {
  const _InvoiceCard({required this.n, required this.onChanged});
  final InvoiceInfo n;
  final VoidCallback onChanged;
  @override
  State<_InvoiceCard> createState() => _InvoiceCardState();
}

class _InvoiceCardState extends State<_InvoiceCard> {
  final _ncm = <String, TextEditingController>{};
  ApiException? _error;

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
      _err(_error),
    ])));
  }
}

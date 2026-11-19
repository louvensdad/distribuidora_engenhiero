import 'dart:typed_data';
import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:material_ui/material_ui.dart';
import '../../labels.dart';
import '../../scope.dart';
import '../accounting_screens.dart' show accountingFileSaver;
import '../reports_screens.dart' show isoDate, todaySaoPaulo;

/// Kit das telas do app Patrão (F14). As telas leem o JSON do contrato publicado; o servidor continua autoridade de
/// permissão, escopo, versão e valores — o app só mostra e envia o que o dono decidir.
typedef J = Map<String, dynamic>;

String str(Object? v) => v == null ? '' : '$v';
String? strOrNull(Object? v) => v == null ? null : '$v';
List<J> items(J j, [String key = 'items']) => [for (final x in (j[key] as List?) ?? const []) x as J];

String brDate(Object? iso) {
  final s = str(iso);
  if (s.length >= 10 && s[4] == '-') return '${s.substring(8, 10)}/${s.substring(5, 7)}/${s.substring(0, 4)}';
  if (s.length == 7 && s[4] == '-') return '${s.substring(5, 7)}/${s.substring(0, 4)}';
  return s;
}

String brDateTime(Object? iso) {
  final d = DateTime.tryParse(str(iso));
  return d == null ? str(iso) : formatDateTime(d);
}

String todayIso() => isoDate(todaySaoPaulo());

/// Quantidade/decimal sem zeros à direita ("200.000000" → "200"), já no formato brasileiro.
String trimDec(Object? v) {
  var s = str(v);
  if (s.contains('.')) s = s.replaceFirst(RegExp(r'\.?0+$'), '');
  return s.isEmpty ? '' : formatDecimal(s);
}

const movementKinds = {
  'RECEIPT': 'Entrada (recebimento)', 'SUPPLIER_RETURN': 'Devolução ao fornecedor', 'LOSS': 'Perda', 'LOSS_REVERSAL': 'Estorno de perda',
  'SALE_ISSUE': 'Saída (venda)', 'SALE_RETURN': 'Volta (devolução de cliente)', 'ADJUSTMENT_IN': 'Ajuste (sobra)', 'ADJUSTMENT_OUT': 'Ajuste (falta)',
};
const paymentStates = {'UNPAID': 'Não pago', 'PARTIALLY_PAID': 'Pago em parte', 'PAID': 'Pago', 'ON_ACCOUNT': 'Fiado (a receber)',
  'REFUND_DUE': 'Devolver ao cliente'};

Future<T?> pushScreen<T>(BuildContext context, String title, Widget child, {List<Widget>? actions}) {
  final s = EngScope.of(context);
  return Navigator.of(context).push<T>(MaterialPageRoute(builder: (_) => EngScope(config: s.config, session: s.session,
      child: Scaffold(appBar: AppBar(title: Text(title), actions: actions), body: EngReadableWidth(child: child)))));
}

Future<T?> pushRaw<T>(BuildContext context, Widget child) {
  final s = EngScope.of(context);
  return Navigator.of(context).push<T>(MaterialPageRoute(builder: (_) => EngScope(config: s.config, session: s.session, child: child)));
}

String errorText(ApiException e) =>
    e.fieldErrors.isEmpty ? e.message : [e.message, for (final f in e.fieldErrors) '${f.field}: ${f.message}'].join('\n');

/// Executa um comando do dono e mostra o resultado do servidor (ok ou o erro com o motivo). Devolve o JSON ou null.
Future<J?> runCommand(BuildContext context, Future<J> Function(ApiClient api) fn, {String? ok}) async {
  final api = EngScope.of(context).api;
  final messenger = ScaffoldMessenger.of(context);
  try {
    final r = await fn(api);
    if (ok != null) messenger.showSnackBar(SnackBar(content: Text(ok)));
    return r;
  } on ApiException catch (e) {
    messenger.showSnackBar(SnackBar(content: Text(e.isForbidden ? 'Sem permissão para esta ação.' : errorText(e)),
        duration: const Duration(seconds: 6)));
    return null;
  }
}

/// Pergunta um texto (motivo/observação). `required` impede enviar vazio.
Future<String?> askText(BuildContext context, {required String title, String label = 'Motivo', bool required = true,
    String action = 'Confirmar', String? message}) {
  final c = TextEditingController();
  return showDialog<String>(context: context, builder: (d) => StatefulBuilder(builder: (d, set) => AlertDialog(
    title: Text(title),
    content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
      if (message != null) Padding(padding: const EdgeInsets.only(bottom: 8), child: Text(message)),
      TextField(controller: c, autofocus: true, maxLength: 500, onChanged: (_) => set(() {}),
          decoration: InputDecoration(labelText: required ? '$label *' : label)),
    ]),
    actions: [
      TextButton(onPressed: () => Navigator.of(d).pop(), child: const Text('Voltar')),
      FilledButton(onPressed: required && c.text.trim().isEmpty ? null : () => Navigator.of(d).pop(c.text.trim()), child: Text(action)),
    ],
  )));
}

/// Salva um arquivo (CSV/PDF/XLSX) baixado do servidor pelo seletor do sistema.
Future<void> downloadAndSave(BuildContext context, String path, {Map<String, String?> query = const {}, String fallbackName = 'arquivo'}) async {
  final api = EngScope.of(context).api;
  final messenger = ScaffoldMessenger.of(context);
  try {
    final f = await api.getBytes(path, query: query);
    final saved = await accountingFileSaver(fileName: f.fileName ?? fallbackName, bytes: Uint8List.fromList(f.bytes), mime: f.mime);
    messenger.showSnackBar(SnackBar(content: Text(saved ? 'Arquivo salvo no aparelho.' : 'Nada foi salvo.')));
  } on ApiException catch (e) {
    messenger.showSnackBar(SnackBar(content: Text(e.isForbidden ? 'Sem permissão para exportar.' : errorText(e))));
  }
}

/// Carrega um JSON e monta uma lista rolável com "puxar para atualizar" e estados (carregando, erro, sem permissão).
class JsonView extends StatefulWidget {
  const JsonView({super.key, required this.load, required this.builder, this.padding = const EdgeInsets.all(16)});
  final Future<J> Function(ApiClient api) load;
  final List<Widget> Function(BuildContext context, J data, VoidCallback reload) builder;
  final EdgeInsets padding;
  @override
  State<JsonView> createState() => _JsonViewState();
}

class _JsonViewState extends State<JsonView> {
  J? _data;
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
    setState(() => _error = null);
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
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(padding: widget.padding, physics: const AlwaysScrollableScrollPhysics(),
          children: widget.builder(context, _data!, _load)),
    );
  }
}

/// Lista paginada por cursor (`items` + `next_cursor`) com "Carregar mais".
class CursorList extends StatefulWidget {
  const CursorList({super.key, required this.path, this.query = const {}, required this.itemBuilder, this.header = const [],
      this.empty = 'Nada por aqui.', this.limit = 25});
  final String path;
  final Map<String, String?> query;
  final Widget Function(BuildContext context, J item, VoidCallback reload) itemBuilder;
  final List<Widget> header;
  final String empty;
  final int limit;
  @override
  State<CursorList> createState() => _CursorListState();
}

class _CursorListState extends State<CursorList> {
  final _items = <J>[];
  String? _next;
  bool _loading = false;
  bool _loaded = false;
  ApiException? _error;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_loaded && !_loading) _reload();
  }

  @override
  void didUpdateWidget(CursorList old) {
    super.didUpdateWidget(old);
    if (old.path != widget.path || old.query.toString() != widget.query.toString()) _reload();
  }

  Future<void> _reload() async {
    _items.clear();
    _next = null;
    await _more(first: true);
  }

  Future<void> _more({bool first = false}) async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final j = await EngScope.of(context).api.getJson(widget.path,
          {...widget.query, 'limit': '${widget.limit}', if (!first && _next != null) 'cursor': _next});
      if (!mounted) return;
      setState(() {
        _items.addAll(items(j));
        _next = j['next_cursor'] as String?;
        _loaded = true;
      });
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_error != null && _items.isEmpty) {
      return _error!.isForbidden
          ? const ForbiddenStateView()
          : ErrorStateView(message: _error!.message, requestId: _error!.requestId, offline: _error!.isNetwork, onRetry: _reload);
    }
    if (!_loaded) return const LoadingState();
    return RefreshIndicator(
      onRefresh: _reload,
      child: ListView(padding: const EdgeInsets.all(16), physics: const AlwaysScrollableScrollPhysics(), children: [
        ...widget.header,
        if (_items.isEmpty) EmptyState(title: widget.empty),
        for (final i in _items) widget.itemBuilder(context, i, _reload),
        if (_next != null)
          Padding(padding: const EdgeInsets.only(top: 8), child: OutlinedButton(
              onPressed: _loading ? null : _more, child: Text(_loading ? 'Carregando…' : 'Carregar mais'))),
      ]),
    );
  }
}

/// Campo de formulário declarativo.
enum FieldKind { text, multiline, money, decimal, date, month, select, account, integer }

class FieldSpec {
  const FieldSpec(this.key, this.label, {this.kind = FieldKind.text, this.required = false, this.options = const {},
      this.initial, this.help});
  final String key;
  final String label;
  final FieldKind kind;
  final bool required;
  /// select: valor → rótulo.
  final Map<String, String> options;
  final String? initial;
  final String? help;
}

/// Formulário em tela cheia: monta o corpo a partir dos campos e chama `submit` com os valores já normalizados
/// (dinheiro/decimal em ponto, vazio → ausente) e uma chave de idempotência fixa para esta intenção.
class FormScreen extends StatefulWidget {
  const FormScreen({super.key, required this.title, required this.fields, required this.submit, this.submitLabel = 'Salvar',
      this.intro});
  final String title;
  final List<FieldSpec> fields;
  final Future<J> Function(ApiClient api, Map<String, String> values, String idempotencyKey) submit;
  final String submitLabel;
  final String? intro;
  @override
  State<FormScreen> createState() => _FormScreenState();
}

class _FormScreenState extends State<FormScreen> {
  late final _ctl = {for (final f in widget.fields) f.key: TextEditingController(text: f.initial ?? _default(f))};
  late final _sel = {for (final f in widget.fields) if (f.kind == FieldKind.select || f.kind == FieldKind.account) f.key: f.initial};
  final _key = ApiClient.newIdempotencyKey();
  Map<String, String> _accounts = const {};
  bool _busy = false;
  ApiException? _error;

  static String _default(FieldSpec f) => f.kind == FieldKind.date && f.required ? todayIso() : '';

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (widget.fields.any((f) => f.kind == FieldKind.account) && _accounts.isEmpty) _loadAccounts();
  }

  Future<void> _loadAccounts() async {
    try {
      final j = await EngScope.of(context).api.getJson('/api/v1/finance/accounts');
      if (mounted) {
        setState(() => _accounts = {
              for (final a in items(j))
                if (a['status'] != 'INACTIVE') str(a['id']): '${a['name']} · saldo ${formatMoney(strOrNull(a['balance']))}'
            });
      }
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  String? _fe(String field) {
    for (final f in _error?.fieldErrors ?? const <FieldError>[]) {
      if (f.field == field) return f.message;
    }
    return null;
  }

  Future<void> _submit() async {
    final api = EngScope.of(context).api;
    final nav = Navigator.of(context);
    final values = <String, String>{};
    for (final f in widget.fields) {
      final raw = (f.kind == FieldKind.select || f.kind == FieldKind.account) ? (_sel[f.key] ?? '') : _ctl[f.key]!.text.trim();
      if (raw.isEmpty) continue;
      values[f.key] = (f.kind == FieldKind.money || f.kind == FieldKind.decimal) ? decimalText(raw) : raw;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final r = await widget.submit(api, values, _key);
      if (mounted) nav.pop(r);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Widget _field(FieldSpec f) {
    final label = f.required ? '${f.label} *' : f.label;
    switch (f.kind) {
      case FieldKind.select:
      case FieldKind.account:
        final opts = f.kind == FieldKind.account ? _accounts : f.options;
        return DropdownButtonFormField<String>(
          initialValue: opts.containsKey(_sel[f.key]) ? _sel[f.key] : null,
          isExpanded: true,
          decoration: InputDecoration(labelText: label, errorText: _fe(f.key), helperText: f.help),
          items: [for (final e in opts.entries) DropdownMenuItem(value: e.key, child: Text(e.value, overflow: TextOverflow.ellipsis))],
          onChanged: (v) => setState(() => _sel[f.key] = v),
        );
      default:
        final hint = switch (f.kind) { FieldKind.date => 'AAAA-MM-DD', FieldKind.month => 'AAAA-MM', _ => null };
        return TextField(
          controller: _ctl[f.key],
          maxLines: f.kind == FieldKind.multiline ? 3 : 1,
          keyboardType: switch (f.kind) {
            FieldKind.money || FieldKind.decimal => const TextInputType.numberWithOptions(decimal: true),
            FieldKind.integer => TextInputType.number,
            _ => TextInputType.text,
          },
          decoration: InputDecoration(labelText: hint == null ? label : '$label ($hint)', errorText: _fe(f.key), helperText: f.help),
        );
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text(widget.title)),
        body: EngReadableWidth(child: ListView(padding: const EdgeInsets.all(16), children: [
          if (widget.intro != null) Padding(padding: const EdgeInsets.only(bottom: 8),
              child: Text(widget.intro!, style: const TextStyle(color: EngColors.muted))),
          for (final f in widget.fields) Padding(padding: const EdgeInsets.only(bottom: 4), child: _field(f)),
          const SizedBox(height: 12),
          FilledButton(onPressed: _busy ? null : _submit, child: Text(_busy ? 'Enviando…' : widget.submitLabel)),
          if (_error != null)
            Padding(padding: const EdgeInsets.only(top: 8), child: Semantics(liveRegion: true,
                child: Text(errorText(_error!), style: const TextStyle(color: EngColors.error)))),
        ])),
      );
}

Future<J?> openForm(BuildContext context, {required String title, required List<FieldSpec> fields,
        required Future<J> Function(ApiClient api, Map<String, String> v, String key) submit, String submitLabel = 'Salvar',
        String? intro}) =>
    pushRaw<J>(context, FormScreen(title: title, fields: fields, submit: submit, submitLabel: submitLabel, intro: intro));

/// Linha rótulo: valor.
class KV extends StatelessWidget {
  const KV(this.label, this.value, {super.key, this.strong = false, this.color});
  final String label;
  final String value;
  final bool strong;
  final Color? color;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 2),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          SizedBox(width: 130, child: Text(label, style: const TextStyle(color: EngColors.muted))),
          Expanded(child: Text(value, style: TextStyle(fontWeight: strong ? FontWeight.w700 : null, color: color))),
        ]),
      );
}

class SectionTitle extends StatelessWidget {
  const SectionTitle(this.text, {super.key});
  final String text;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(top: 16, bottom: 6),
        child: Text(text, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
      );
}

class StateChip extends StatelessWidget {
  const StateChip(this.state, this.labels, {super.key});
  final String state;
  final Map<String, String> labels;
  @override
  Widget build(BuildContext context) {
    final c = switch (state) {
      'PENDING' || 'SUBMITTED' || 'REQUESTED' || 'CLOSED' || 'OPEN' || 'DRAFT' || 'UNCERTAIN' => EngColors.warning,
      'REJECTED' || 'RETURNED' || 'CANCELLED' || 'REVERSED' || 'DISABLED' || 'TERMINATED' || 'ERROR' => EngColors.error,
      'APPROVED' || 'PAID' || 'REVIEWED' || 'CONFIRMED' || 'ACTIVE' || 'AUTHORIZED' || 'SETTLED' => EngColors.green,
      _ => EngColors.violet,
    };
    return Text(labelOf(labels, state), style: TextStyle(color: c, fontWeight: FontWeight.w700));
  }
}

/// Card tocável com título, subtítulo e valor à direita.
class RowCard extends StatelessWidget {
  const RowCard({super.key, required this.title, this.subtitle, this.trailing, this.onTap, this.leading});
  final String title;
  final String? subtitle;
  final String? trailing;
  final VoidCallback? onTap;
  final Widget? leading;
  @override
  Widget build(BuildContext context) => Card(child: ListTile(
        leading: leading,
        title: Text(title),
        subtitle: subtitle == null || subtitle!.isEmpty ? null : Text(subtitle!),
        trailing: trailing == null ? null : Text(trailing!, style: const TextStyle(fontWeight: FontWeight.w700)),
        onTap: onTap,
      ));
}

const entityLabels = {
  'order': 'Pedido', 'trip': 'Viagem', 'vehicle': 'Veículo', 'inventory_receipt': 'Recebimento', 'customer': 'Cliente',
  'supplier': 'Fornecedor', 'product': 'Produto', 'inventory_loss': 'Perda', 'inventory_adjustment': 'Ajuste de estoque',
  'vehicle_expense': 'Gasto de veículo', 'tax_obligation': 'Guia', 'payroll_run': 'Folha', 'expense': 'Despesa',
  'payable': 'Conta a pagar', 'receivable': 'Conta a receber', 'document': 'Documento', 'cash_session': 'Caixa',
  'goal': 'Meta', 'employee_absence': 'Ausência', 'employee': 'Funcionário', 'job_position': 'Cargo', 'membership': 'Acesso',
  'branch': 'Unidade', 'payment': 'Pagamento', 'bank_transaction': 'Lançamento bancário', 'bank_connection': 'Conexão bancária',
  'invoice': 'Nota fiscal', 'accounting_document': 'Documento', 'document_request': 'Solicitação de documento',
};

const sectorNames = {'COUNTER': 'Retirada', 'DELIVERY': 'Entregas', 'ACCOUNTING': 'Contabilidade', 'FINANCE': 'Financeiro', 'TEAM': 'Equipe'};

const genericStateLabels = {
  'PENDING': 'Pendente', 'APPROVED': 'Aprovada', 'REJECTED': 'Recusada', 'REVERSED': 'Estornada', 'CANCELLED': 'Cancelada',
  'OPEN': 'Em aberto', 'PARTIAL': 'Parcial', 'PAID': 'Paga', 'SETTLED': 'Quitada', 'CLOSED': 'Fechado', 'REVIEWED': 'Conferido',
  'REQUESTED': 'Pedida', 'CONFIRMED': 'Confirmada', 'ACTIVE': 'Ativo', 'INACTIVE': 'Inativo', 'ARCHIVED': 'Arquivado',
  'DISABLED': 'Bloqueado', 'MAINTENANCE': 'Em manutenção', 'DRAFT': 'Rascunho', 'SUBMITTED': 'Aguardando aprovação',
  'RETURNED': 'Devolvida', 'ON_LEAVE': 'Afastado', 'TERMINATED': 'Desligado',
};

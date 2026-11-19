import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:material_ui/material_ui.dart';
import '../../scope.dart';
import '../accounting_screens.dart' show AccountingDocumentsScreen;
import '../reports_screens.dart' show ReportsScreen, isoDate, todaySaoPaulo;
import 'finance.dart' show OwnerPayrollScreen, OwnerTaxesScreen;
import 'kit.dart';
import 'operation.dart' show OwnerStockScreen, OwnerVehicleScreen;
import 'reports.dart' show CategoriesScreen, GoalsAdminScreen;

/// F15 — IA da gestão no app Patrão: a mesma do site (mesmo endpoint, mesmas regras). A chave fica só no servidor;
/// sem provedor a tela diz NÃO CONFIGURADA e não oferece pergunta. Cada resposta mostra período, horário de corte,
/// consultas feitas, limitações, fontes (abrem a tela do app) e custo.
class OwnerAiScreen extends StatefulWidget {
  const OwnerAiScreen({super.key});
  @override
  State<OwnerAiScreen> createState() => _OwnerAiScreenState();
}

const aiStateLabels = {
  'RUNNING': 'Em andamento',
  'ANSWERED': 'Respondida',
  'FAILED': 'Falhou',
  'REFUSED': 'Recusada pelo provedor',
  'LIMIT_REACHED': 'Etapas demais',
};

class _OwnerAiScreenState extends State<OwnerAiScreen> {
  J? _status;
  List<J> _history = const [];
  ApiException? _error;
  J? _current;
  bool _busy = false;
  bool _started = false;
  final _question = TextEditingController();
  late DateTime _from;
  late DateTime _to; // inclusivo

  @override
  void initState() {
    super.initState();
    final t = todaySaoPaulo();
    _from = DateTime(t.year, t.month, 1);
    _to = DateTime(t.year, t.month + 1, 0);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_started) {
      _started = true;
      _load();
    }
  }

  @override
  void dispose() {
    _question.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final api = EngScope.of(context).api;
    setState(() => _error = null);
    try {
      final s = await api.getJson('/api/v1/ai/status');
      final h = await api.getJson('/api/v1/ai/questions');
      if (mounted) setState(() { _status = s; _history = items(h); });
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  Future<void> _ask() async {
    final q = _question.text.trim();
    if (q.isEmpty) return;
    setState(() => _busy = true);
    final r = await runCommand(context, (api) => api.sendJson('POST', '/api/v1/ai/questions',
        {'question': q, 'from': isoDate(_from), 'to': isoDate(_to)}));
    if (!mounted) return;
    setState(() { _busy = false; if (r != null) { _current = r; _question.clear(); } });
    if (r != null) _load();
  }

  Future<void> _pickPeriod() async {
    final r = await showDateRangePicker(context: context, firstDate: DateTime(2020), lastDate: DateTime(2100),
        initialDateRange: DateTimeRange(start: _from, end: _to));
    if (r != null) setState(() { _from = r.start; _to = r.end; });
  }

  @override
  Widget build(BuildContext context) {
    if (_error != null) {
      return _error!.isForbidden
          ? const ForbiddenStateView()
          : ErrorStateView(message: _error!.message, requestId: _error!.requestId, offline: _error!.isNetwork, onRetry: _load);
    }
    final s = _status;
    if (s == null) return const LoadingState();
    final state = str(s['state']);
    final active = state == 'CONFIGURED';
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(padding: const EdgeInsets.all(16), physics: const AlwaysScrollableScrollPhysics(), children: [
        Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(state == 'NOT_CONFIGURED' ? 'INTEGRAÇÃO NÃO CONFIGURADA' : active ? 'IA ativa' : 'IA bloqueada',
              style: TextStyle(fontWeight: FontWeight.w700, color: active ? EngColors.green : EngColors.warning)),
          const SizedBox(height: 4),
          Text(str(s['message'])),
          const SizedBox(height: 4),
          Text('${s['model'] == null ? '' : 'Modelo ${s['model']} · '}Orçamento US\$ ${s['budget_usd']} · gasto US\$ ${s['spent_usd']}'
              ' · perguntas hoje ${s['used_today']} de ${s['daily_limit']}', style: const TextStyle(color: EngColors.muted, fontSize: 12)),
        ]))),
        const Padding(padding: EdgeInsets.symmetric(vertical: 8), child: Text(
            'Só consulta, com os números do sistema e o que você pode ver. Não paga, não emite nota, não altera estoque nem acessos.',
            style: TextStyle(color: EngColors.muted))),
        if (active) ...[
          TextField(controller: _question, minLines: 2, maxLines: 5, maxLength: 2000, onChanged: (_) => setState(() {}),
              decoration: const InputDecoration(labelText: 'Pergunta', hintText: 'Ex.: Quanto gastei com combustível este mês?')),
          Row(children: [
            Expanded(child: Text('Período: ${brDate(isoDate(_from))} a ${brDate(isoDate(_to))}')),
            TextButton(onPressed: _pickPeriod, child: const Text('Mudar período')),
          ]),
          FilledButton(onPressed: _busy || _question.text.trim().isEmpty ? null : _ask,
              child: Text(_busy ? 'Consultando…' : 'Perguntar')),
        ],
        if (_current != null) AiAnswerCard(q: _current!),
        const SectionTitle('Minhas perguntas'),
        if (_history.isEmpty) const Text('Nenhuma pergunta ainda.', style: TextStyle(color: EngColors.muted)),
        for (final q in _history)
          RowCard(title: str(q['question']),
              subtitle: '${brDateTime(q['created_at'])} · ${labelOfState(q['state'])} · ${brDate(q['from'])} a ${brDate(q['to'])}',
              onTap: () => setState(() => _current = q)),
      ]),
    );
  }
}

String labelOfState(Object? s) => aiStateLabels[str(s)] ?? str(s);

/// Fonte da resposta → tela correspondente do app (o servidor só devolve fontes que o usuário pôde consultar).
Widget? aiSourceScreen(String href) {
  if (href.startsWith('/operation/fleet/')) return OwnerVehicleScreen(id: href.substring('/operation/fleet/'.length));
  return switch (href) {
    '/reports' => const ReportsScreen(),
    '/reports/categories' => const CategoriesScreen(),
    '/reports/goals' => const GoalsAdminScreen(),
    '/finance/taxes' => const OwnerTaxesScreen(),
    '/finance/payroll' => const OwnerPayrollScreen(),
    '/finance/documents' => const AccountingDocumentsScreen(),
    '/operation/stock' => const OwnerStockScreen(),
    _ => null,
  };
}

class AiAnswerCard extends StatelessWidget {
  const AiAnswerCard({super.key, required this.q});
  final J q;
  @override
  Widget build(BuildContext context) {
    final tools = items(q, 'tools');
    final lim = [for (final l in (q['limitations'] as List?) ?? const []) '$l'];
    final sources = items(q, 'sources');
    return Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(str(q['question']), style: const TextStyle(color: EngColors.muted)),
      StateChip(str(q['state']) == 'ANSWERED' ? 'CONFIRMED' : 'ERROR', {
        'CONFIRMED': labelOfState(q['state']), 'ERROR': labelOfState(q['state'])}),
      const SizedBox(height: 6),
      Text(q['answer'] == null ? 'Sem resposta${q['error_code'] == null ? '' : ' (${q['error_code']})'}. Nenhum dado foi alterado.'
          : str(q['answer'])),
      const SizedBox(height: 8),
      KV('Período', '${brDate(q['from'])} a ${brDate(q['to'])}'),
      KV('Dados até', brDateTime(q['cutoff_at'])),
      KV('Consultas', tools.isEmpty ? 'nenhuma' : [for (final t in tools)
        '${t['label']}${t['ok'] == true ? '' : t['error'] == 'SEM_PERMISSAO' ? ' (sem permissão)' : ' (${t['error']})'}'].join(' · ')),
      KV('Modelo e custo', '${q['model'] ?? '—'} · US\$ ${q['cost_usd']}'),
      if (lim.isNotEmpty) ...[
        const SectionTitle('O que faltou ou ficou de fora'),
        for (final l in lim) Text('• $l'),
      ],
      if (sources.isNotEmpty) ...[
        const SectionTitle('Fontes'),
        for (final s in sources)
          Builder(builder: (context) {
            final screen = aiSourceScreen(str(s['href']));
            return TextButton(onPressed: screen == null ? null : () => pushScreen(context, str(s['label']), screen),
                child: Text(str(s['label'])));
          }),
      ],
    ])));
  }
}

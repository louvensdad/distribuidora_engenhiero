import 'dart:math' as math;

import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:material_ui/material_ui.dart';
import '../labels.dart';
import '../scope.dart';

/// Data civil de hoje em America/Sao_Paulo (UTC−3, sem horário de verão desde 2019), nunca o fuso do aparelho.
DateTime todaySaoPaulo([DateTime? now]) {
  final u = (now ?? DateTime.now()).toUtc().subtract(const Duration(hours: 3));
  return DateTime(u.year, u.month, u.day);
}

String isoDate(DateTime d) =>
    '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

/// Relatórios do patrão (F09, paridade com o site): resultado do período com colunas 3D, tabela, quantidade,
/// comparação e metas. Os números vêm prontos do servidor (mesmo cálculo do site e dos arquivos); nada é recalculado aqui.
class ReportsScreen extends StatelessWidget {
  const ReportsScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    final goals = me.can('goals.view');
    return DefaultTabController(
      length: goals ? 2 : 1,
      child: Column(children: [
        TabBar(tabs: [const Tab(text: 'Resultado'), if (goals) const Tab(text: 'Metas')]),
        Expanded(child: TabBarView(children: [const ReportOverviewScreen(), if (goals) const GoalsScreen()])),
      ]),
    );
  }
}

class ReportOverviewScreen extends StatefulWidget {
  const ReportOverviewScreen({super.key});
  @override
  State<ReportOverviewScreen> createState() => _ReportOverviewScreenState();
}

class _ReportOverviewScreenState extends State<ReportOverviewScreen> {
  String _period = 'MONTH';
  late DateTime _date = todaySaoPaulo();
  bool _flat = false;
  Map<String, dynamic>? _r;
  ApiException? _error;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_r == null && _error == null) _load();
  }

  Future<void> _load() async {
    setState(() {
      _r = null;
      _error = null;
    });
    try {
      final r = await EngScope.of(context).api.report(period: _period, date: isoDate(_date));
      if (mounted) setState(() => _r = r);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  void _shift(int dir) {
    _date = switch (_period) {
      'DAY' => _date.add(Duration(days: dir)),
      'WEEK' => _date.add(Duration(days: 7 * dir)),
      _ => DateTime(_date.year, _date.month + dir, 1),
    };
    _load();
  }

  Widget _line(String label, String? amount, {bool strong = false}) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(children: [
          Expanded(child: Text(label, style: TextStyle(fontWeight: strong ? FontWeight.w700 : FontWeight.w400))),
          Text(formatMoney(amount), style: TextStyle(fontWeight: strong ? FontWeight.w700 : FontWeight.w400)),
        ]),
      );

  @override
  Widget build(BuildContext context) {
    final header = Column(children: [
      SegmentedButton<String>(
        segments: const [
          ButtonSegment(value: 'DAY', label: Text('Dia')),
          ButtonSegment(value: 'WEEK', label: Text('Semana')),
          ButtonSegment(value: 'MONTH', label: Text('Mês')),
        ],
        selected: {_period},
        onSelectionChanged: (s) {
          _period = s.first;
          _load();
        },
      ),
      Row(children: [
        IconButton(tooltip: 'Período anterior', onPressed: () => _shift(-1), icon: const Text('‹', style: TextStyle(fontSize: 24))),
        Expanded(child: Text(_r?['label'] as String? ?? '…', textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600))),
        IconButton(tooltip: 'Próximo período', onPressed: () => _shift(1), icon: const Text('›', style: TextStyle(fontSize: 24))),
      ]),
    ]);
    Widget body;
    if (_error != null) {
      body = _error!.isForbidden
          ? const ForbiddenStateView()
          : ErrorStateView(message: _error!.message, requestId: _error!.requestId, offline: _error!.isNetwork, onRetry: _load);
    } else if (_r == null) {
      body = const LoadingState();
    } else {
      body = _content(_r!);
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(padding: const EdgeInsets.all(16), children: [header, const SizedBox(height: 8), body]),
    );
  }

  Widget _content(Map<String, dynamic> r) {
    final x = r['result'] as Map<String, dynamic>;
    final hidden = (r['hidden'] as List).isNotEmpty;
    final chart = (r['chart'] as List).cast<Map<String, dynamic>>();
    final sold = r['sold'] as Map<String, dynamic>;
    final cmp = r['comparison'] as Map<String, dynamic>;
    final profit = x['profit'] as String?;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      if (r['source'] == 'SNAPSHOT')
        const Text('Mês fechado: valores do retrato gravado no fechamento.', style: TextStyle(color: EngColors.violet)),
      if (hidden) const Text('CMV, perdas e lucro ocultos pela sua permissão.', style: TextStyle(color: EngColors.warning)),
      Wrap(spacing: 8, runSpacing: 8, children: [
        for (final c in chart)
          SizedBox(
            width: (MediaQuery.of(context).size.width - 40) / (MediaQuery.of(context).size.width > 700 ? 3 : 2),
            child: Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(c['label'] as String, style: const TextStyle(color: EngColors.muted, fontSize: 12)),
              Text(formatMoney(c['amount'] as String?), key: ValueKey('kpi-${c['key']}'),
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800,
                      color: c['key'] == 'profit' ? ((c['amount'] as String).startsWith('-') ? EngColors.error : EngColors.green) : EngColors.text)),
            ]))),
          ),
      ]),
      Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          const Expanded(child: Text('Fechamento em colunas', style: TextStyle(fontWeight: FontWeight.w600))),
          const Text('2D', style: TextStyle(color: EngColors.muted)),
          Switch(value: _flat, onChanged: (v) => setState(() => _flat = v)),
        ]),
        Columns3DChart(columns: chart, flat: _flat || MediaQuery.of(context).disableAnimations),
        Text('Valores em R\$ · ${r['label']} · altura proporcional ao valor, escala única.',
            style: const TextStyle(color: EngColors.muted, fontSize: 12)),
      ]))),
      Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Text('Fechamento (tabela)', style: TextStyle(color: EngColors.muted)),
        _line('Vendas cumpridas (bruto)', x['gross_revenue'] as String?),
        _line('(−) Descontos', x['discounts'] as String?),
        _line('(−) Devoluções', x['returns'] as String?),
        _line('Receita líquida', x['net_revenue'] as String?, strong: true),
        if (!hidden) _line('(−) CMV', x['cogs'] as String?),
        for (final e in (r['expenses'] as List).cast<Map<String, dynamic>>()) _line('(−) ${e['label']}', e['amount'] as String?),
        _line('Despesas totais', x['expenses_total'] as String?, strong: true),
        if (!hidden) _line('(−) Perdas', x['losses'] as String?),
        if (!hidden) _line('CMV + despesas + perdas', x['costs_expenses_losses'] as String?),
        const Divider(),
        if (profit != null) _line(profit.startsWith('-') ? 'Prejuízo do período' : 'Lucro do período', profit, strong: true),
        if (x['margin_percent'] != null)
          Text('Margem: ${formatDecimal(x['margin_percent'] as String)}%', style: const TextStyle(color: EngColors.muted)),
        Text(r['formula'] as String, style: const TextStyle(color: EngColors.muted, fontSize: 12)),
      ]))),
      Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Text('Quantidade vendida', style: TextStyle(color: EngColors.muted)),
        for (final u in (sold['by_unit'] as List).cast<Map<String, dynamic>>())
          Text('${formatQty(_trim(u['sold'] as String), u['unit'] as String)} vendidas · ${formatQty(_trim(u['backlog'] as String), u['unit'] as String)} em carteira'),
        Text('${sold['orders']} pedido(s) com retirada/entrega.', style: const TextStyle(color: EngColors.muted, fontSize: 12)),
      ]))),
      Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('Comparação com ${cmp['label']}', style: const TextStyle(color: EngColors.muted)),
        for (final i in (cmp['items'] as List).cast<Map<String, dynamic>>())
          Padding(padding: const EdgeInsets.symmetric(vertical: 4), child: Row(children: [
            Expanded(child: Text(i['label'] as String)),
            Text(i['key'] == 'sold_quantity' ? formatDecimal(i['difference'] as String) : formatMoney(i['difference'] as String?)),
            const SizedBox(width: 8),
            Text(i['percent'] == null ? '—' : '${formatDecimal(i['percent'] as String)}%', style: const TextStyle(color: EngColors.muted)),
          ])),
        Text(cmp['formula'] as String, style: const TextStyle(color: EngColors.muted, fontSize: 12)),
      ]))),
      Text(r['note'] as String, style: const TextStyle(color: EngColors.muted, fontSize: 12)),
    ]);
  }
}

/// Colunas extrudadas: a altura da face frontal = valor na mesma escala; topo/lateral só dão profundidade (deslocamento
/// fixo, igual para todas). Mesma regra do gráfico do site (design/01). Valor negativo desce da linha de base.
class Columns3DChart extends StatelessWidget {
  const Columns3DChart({super.key, required this.columns, this.flat = false});
  final List<Map<String, dynamic>> columns;
  final bool flat;

  @override
  Widget build(BuildContext context) {
    final desc = columns.map((c) => '${c['label']}: ${formatMoney(c['amount'] as String?)}').join('; ');
    return Semantics(
      label: 'Gráfico de colunas. $desc',
      child: SizedBox(height: 260, width: double.infinity, child: CustomPaint(painter: _ColumnsPainter(columns, flat))),
    );
  }
}

class _ColumnsPainter extends CustomPainter {
  _ColumnsPainter(this.columns, this.flat);
  final List<Map<String, dynamic>> columns;
  final bool flat;
  static const colors = {
    'net_revenue': EngColors.cyan, 'cogs': EngColors.violet, 'expenses_total': EngColors.warning,
    'losses': EngColors.error, 'profit': EngColors.green,
  };

  @override
  void paint(Canvas canvas, Size size) {
    if (columns.isEmpty) return;
    final values = [for (final c in columns) double.parse(c['amount'] as String)];
    final maxPos = values.fold<double>(0, math.max);
    final maxNeg = values.fold<double>(0, (a, v) => math.max(a, -v));
    final range = (maxPos + maxNeg) == 0 ? 1.0 : maxPos + maxNeg;
    const top = 26.0;
    final plot = size.height - top - 66; // embaixo: valor negativo e depois o rótulo, sem sobrepor
    final zeroY = top + plot * maxPos / range;
    final slot = size.width / columns.length;
    final w = slot * 0.5;
    final dx = flat ? 0.0 : 9.0;
    final dy = flat ? 0.0 : -7.0;
    canvas.drawLine(Offset(0, zeroY), Offset(size.width, zeroY), Paint()..color = EngColors.border);
    for (var i = 0; i < columns.length; i++) {
      final v = values[i];
      final h = plot * v.abs() / range;
      final x = slot * i + (slot - w - dx) / 2;
      final y = v >= 0 ? zeroY - h : zeroY;
      final key = columns[i]['key'] as String;
      final color = key == 'profit' && v < 0 ? EngColors.error : (colors[key] ?? EngColors.cyan);
      if (!flat && h > 0) {
        canvas.drawPath(Path()..moveTo(x, y)..lineTo(x + dx, y + dy)..lineTo(x + w + dx, y + dy)..lineTo(x + w, y)..close(),
            Paint()..color = color.withValues(alpha: 0.75));
        canvas.drawPath(Path()..moveTo(x + w, y)..lineTo(x + w + dx, y + dy)..lineTo(x + w + dx, y + h + dy)..lineTo(x + w, y + h)..close(),
            Paint()..color = color.withValues(alpha: 0.45));
      }
      canvas.drawRect(Rect.fromLTWH(x, y, w, math.max(h, 1)), Paint()..color = color);
      _text(canvas, formatMoney(columns[i]['amount'] as String?), Offset(slot * i + slot / 2, v >= 0 ? y + dy - 14 : y + h + 2), slot,
          const TextStyle(color: EngColors.text, fontSize: 9, fontWeight: FontWeight.w700));
      _text(canvas, columns[i]['label'] as String, Offset(slot * i + slot / 2, size.height - 30), slot,
          const TextStyle(color: EngColors.muted, fontSize: 9));
    }
  }

  void _text(Canvas c, String s, Offset center, double maxW, TextStyle style) {
    final tp = TextPainter(text: TextSpan(text: s, style: style), textAlign: TextAlign.center, textDirection: TextDirection.ltr, maxLines: 2)
      ..layout(maxWidth: maxW);
    tp.paint(c, Offset(center.dx - tp.width / 2, center.dy));
  }

  @override
  bool shouldRepaint(_ColumnsPainter old) => old.columns != columns || old.flat != flat;
}

/// Metas do mês (REP-07): realizado vindo do relatório, faltante, % e fórmula.
class GoalsScreen extends StatefulWidget {
  const GoalsScreen({super.key});
  @override
  State<GoalsScreen> createState() => _GoalsScreenState();
}

class _GoalsScreenState extends State<GoalsScreen> {
  List<Map<String, dynamic>>? _items;
  ApiException? _error;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_items == null && _error == null) _load();
  }

  Future<void> _load() async {
    final t = todaySaoPaulo();
    final from = DateTime(t.year, t.month, 1);
    try {
      final g = await EngScope.of(context).api.goals(from: isoDate(from), to: isoDate(DateTime(t.year, t.month + 1, 1)));
      if (mounted) setState(() => _items = g);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  static const _status = {
    'NOT_STARTED': 'Não começou', 'ON_PACE': 'No ritmo', 'BEHIND_PACE': 'Abaixo do ritmo', 'ACHIEVED': 'Atingida',
    'MISSED': 'Não atingida', 'HIDDEN': 'Realizado oculto pela sua permissão',
  };

  @override
  Widget build(BuildContext context) {
    if (_error != null) {
      return _error!.isForbidden
          ? const ForbiddenStateView()
          : ErrorStateView(message: _error!.message, requestId: _error!.requestId, offline: _error!.isNetwork, onRetry: _load);
    }
    final items = _items;
    if (items == null) return const LoadingState();
    final live = items.where((g) => g['state'] != 'CANCELLED').toList();
    final cancelled = items.length - live.length;
    if (live.isEmpty) {
      return EmptyState(title: 'Nenhuma meta ativa neste mês',
          message: 'Metas são criadas no site (Relatórios › Metas).${cancelled > 0 ? ' $cancelled cancelada(s) no histórico do site.' : ''}');
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(padding: const EdgeInsets.all(16), children: [
        for (final g in live) _goal(g),
        if (cancelled > 0)
          Text('$cancelled meta(s) cancelada(s) neste mês — histórico no site.', style: const TextStyle(color: EngColors.muted)),
      ]),
    );
  }

  Widget _goal(Map<String, dynamic> g) {
    final p = g['progress'] as Map<String, dynamic>;
    final qty = g['metric'] == 'SOLD_QUANTITY';
    String f(String? v) => v == null ? '—' : qty ? formatQty(_trim(v), g['unit'] as String? ?? '') : formatMoney(v);
    final pct = p['percent'] == null ? null : double.parse(p['percent'] as String);
    final state = {'DRAFT': 'Rascunho', 'ACTIVE': 'Ativa', 'CANCELLED': 'Cancelada'}[g['state']];
    return Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(g['title'] as String, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
      Text('$state · ${g['metric_label']} · ${g['period_label']}', style: const TextStyle(color: EngColors.muted)),
      if (g['state'] != 'CANCELLED') Text(_status[p['status']] ?? '', style: const TextStyle(color: EngColors.cyan)),
      const SizedBox(height: 8),
      Text('Meta: ${f(g['target'] as String?)}'),
      Text('Realizado: ${p['realized'] == null ? 'oculto' : f(p['realized'] as String?)}'),
      Text('Falta: ${f(p['remaining'] as String?)}', style: const TextStyle(fontWeight: FontWeight.w700)),
      if (pct != null) ...[
        const SizedBox(height: 8),
        LinearProgressIndicator(value: (pct / 100).clamp(0, 1).toDouble(), minHeight: 8,
            semanticsLabel: 'Progresso da meta', semanticsValue: '${formatDecimal(p['percent'] as String)}%'),
        Text('${formatDecimal(p['percent'] as String)}% da meta', style: const TextStyle(color: EngColors.muted)),
      ],
      Text(p['formula'] as String, style: const TextStyle(color: EngColors.muted, fontSize: 12)),
    ])));
  }
}

/// Quantidade do contrato (6 casas) sem zeros à direita: "200.000000" → "200", "12.500000" → "12.5".
String _trim(String v) => v.contains('.') ? v.replaceFirst(RegExp(r'\.?0+$'), '') : v;

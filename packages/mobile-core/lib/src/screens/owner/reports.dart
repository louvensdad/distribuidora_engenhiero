import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:material_ui/material_ui.dart';
import '../../labels.dart';
import '../../scope.dart';
import '../reports_screens.dart' show ReportsScreen;
import 'central.dart';
import 'kit.dart';

/// Relatórios do dono além do resultado (F09): origem de cada total, exportação, categorias, ranking e metas editáveis.
class OwnerReportsHub extends StatelessWidget {
  const OwnerReportsHub({super.key});
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    Widget item(String icon, String title, String sub, Widget screen) => Card(child: ListTile(
        leading: EngIcon(icon, color: EngColors.cyan), title: Text(title), subtitle: Text(sub),
        onTap: () => pushScreen(context, title, screen)));
    return ListView(padding: const EdgeInsets.all(16), children: [
      item('report', 'Resultado e gráfico 3D', 'Dia, semana ou mês; comparação e quantidade', const ReportsScreen()),
      item('search', 'De onde vem cada total', 'Abrir os lançamentos e o registro de origem', const TotalsOriginScreen()),
      item('wallet', 'Despesas por categoria', 'Categorias e subcategorias do mês', const CategoriesScreen()),
      item('users', 'Ranking de clientes', 'Receita, quantidade, margem, prazo', const CustomerRankingScreen()),
      if (me.can('goals.view')) item('home', 'Metas', 'Criar, editar, ativar e cancelar', const GoalsAdminScreen()),
    ]);
  }
}

class _PeriodBar extends StatelessWidget {
  const _PeriodBar({required this.period, required this.date, required this.onChange});
  final String period;
  final String date;
  final void Function(String period, String date) onChange;
  @override
  Widget build(BuildContext context) {
    final c = TextEditingController(text: date);
    return Row(children: [
      Expanded(child: DropdownButtonFormField<String>(initialValue: period, isExpanded: true, decoration: const InputDecoration(labelText: 'Período'),
          items: const [DropdownMenuItem(value: 'DAY', child: Text('Dia')), DropdownMenuItem(value: 'WEEK', child: Text('Semana')),
            DropdownMenuItem(value: 'MONTH', child: Text('Mês'))],
          onChanged: (v) => onChange(v ?? 'MONTH', c.text))),
      const SizedBox(width: 8),
      Expanded(child: TextField(controller: c, decoration: const InputDecoration(labelText: 'Data (AAAA-MM-DD)'),
          onSubmitted: (v) => onChange(period, v.trim()))),
    ]);
  }
}

/// "Abre a origem do total": cada linha do resultado lista os lançamentos que a compõem, com autor e registro de origem.
class TotalsOriginScreen extends StatefulWidget {
  const TotalsOriginScreen({super.key});
  @override
  State<TotalsOriginScreen> createState() => _TotalsOriginScreenState();
}

class _TotalsOriginScreenState extends State<TotalsOriginScreen> {
  String _period = 'MONTH';
  String _date = todayIso();
  Key _k = UniqueKey();
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    final q = {'period': _period, 'date': _date};
    return JsonView(key: _k, load: (api) => api.getJson('/api/v1/reports/overview', q), builder: (context, r, reload) {
      final hidden = (r['hidden'] as List? ?? const []).cast<String>();
      Widget line(J x) => RowCard(
            title: str(x['label']),
            trailing: x['amount'] == null ? 'oculto' : formatMoney(strOrNull(x['amount'])),
            subtitle: (x['accounts'] as List? ?? const []).isEmpty ? null : 'Toque para ver os lançamentos',
            onTap: (x['accounts'] as List? ?? const []).isEmpty || x['amount'] == null
                ? null
                : () => pushScreen(context, str(x['label']), LedgerLinesScreen(accounts: (x['accounts'] as List).cast<String>(), query: q)),
          );
      return [
        _PeriodBar(period: _period, date: _date, onChange: (p, d) => setState(() {
          _period = p;
          _date = d;
          _k = UniqueKey();
        })),
        Text('${r['label']}${r['closed'] == true ? ' · mês fechado (retrato do fechamento)' : ''}', style: const TextStyle(color: EngColors.muted)),
        if (hidden.isNotEmpty) Text('Ocultos pela sua permissão: ${hidden.join(', ')}', style: const TextStyle(color: EngColors.warning)),
        Wrap(spacing: 8, children: [
          if (me.can('reports.print'))
            OutlinedButton(onPressed: () => downloadAndSave(context, '/api/v1/reports/overview/export', query: {...q, 'format': 'pdf'},
                fallbackName: 'relatorio.pdf'), child: const Text('PDF')),
          if (me.can('reports.export')) ...[
            OutlinedButton(onPressed: () => downloadAndSave(context, '/api/v1/reports/overview/export', query: {...q, 'format': 'xlsx'},
                fallbackName: 'relatorio.xlsx'), child: const Text('Planilha')),
            OutlinedButton(onPressed: () => downloadAndSave(context, '/api/v1/reports/overview/export', query: {...q, 'format': 'csv'},
                fallbackName: 'relatorio.csv'), child: const Text('CSV')),
          ],
        ]),
        const SectionTitle('Resultado'),
        ...resultLines(r),
        const SectionTitle('Abrir a origem'),
        for (final x in items(r, 'drill')) line(x),
        const SectionTitle('Despesas'),
        for (final x in items(r, 'expenses')) line(x),
        if (r['formula'] != null) Text('Fórmula: ${r['formula']}', style: const TextStyle(color: EngColors.muted, fontSize: 12)),
      ];
    });
  }
}

class LedgerLinesScreen extends StatelessWidget {
  const LedgerLinesScreen({super.key, required this.accounts, required this.query});
  final List<String> accounts;
  final Map<String, String?> query;
  @override
  Widget build(BuildContext context) => JsonView(
        load: (api) => api.getJson('/api/v1/reports/lines', {...query, 'accounts': accounts.join(',')}),
        builder: (context, d, reload) => [
          KV('Débitos', formatMoney(strOrNull(d['total_debit']))),
          KV('Créditos', formatMoney(strOrNull(d['total_credit']))),
          KV('Líquido', formatMoney(strOrNull(d['net'])), strong: true),
          const SectionTitle('Lançamentos'),
          if (items(d).isEmpty) const Text('Sem lançamentos.', style: TextStyle(color: EngColors.muted)),
          for (final l in items(d))
            RowCard(
              title: str(l['memo']),
              subtitle: '${brDate(l['entry_date'])} · ${l['created_by_name'] ?? ''}'
                  '${l['origin_type'] == null ? '' : ' · ${labelOf(entityLabels, str(l['origin_type']))}${l['origin_number'] == null ? '' : ' #${l['origin_number']}'}'}',
              trailing: l['debit'] != null && l['debit'] != '0.00' ? 'D ${formatMoney(strOrNull(l['debit']))}' : 'C ${formatMoney(strOrNull(l['credit']))}',
              onTap: () => openOrigin(context, strOrNull(l['origin_type'] ?? l['source_type']), strOrNull(l['origin_id'] ?? l['source_id'])),
            ),
        ],
      );
}

class CategoriesScreen extends StatelessWidget {
  const CategoriesScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final t = DateTime.parse(todayIso());
    final from = '${todayIso().substring(0, 8)}01';
    final n = DateTime(t.year, t.month + 1, 1);
    final to = '${n.year}-${n.month.toString().padLeft(2, '0')}-01';
    return JsonView(load: (api) => api.getJson('/api/v1/reports/categories', {'from': from, 'to': to}), builder: (context, d, reload) => [
      Text('${brDate(d['from'])} a ${brDate(d['to'])} (exclusive)', style: const TextStyle(color: EngColors.muted)),
      KV('Total', formatMoney(strOrNull(d['total'])), strong: true),
      const SectionTitle('Categorias'),
      for (final c in items(d, 'categories')) KV(str(c['label']), formatMoney(strOrNull(c['amount']))),
      const SectionTitle('Subcategorias'),
      for (final c in items(d, 'subcategories')) KV(str(c['label']), formatMoney(strOrNull(c['amount']))),
    ]);
  }
}

class CustomerRankingScreen extends StatefulWidget {
  const CustomerRankingScreen({super.key});
  @override
  State<CustomerRankingScreen> createState() => _CustomerRankingScreenState();
}

class _CustomerRankingScreenState extends State<CustomerRankingScreen> {
  String _sort = 'net_revenue';
  @override
  Widget build(BuildContext context) => JsonView(
        key: ValueKey(_sort),
        load: (api) => api.getJson('/api/v1/reports/customers', {'period': 'MONTH', 'date': todayIso(), 'sort': _sort}),
        builder: (context, d, reload) => [
          DropdownButtonFormField<String>(initialValue: _sort, isExpanded: true, decoration: const InputDecoration(labelText: 'Ordenar por'),
              items: [for (final m in items(d, 'measures')) DropdownMenuItem(value: str(m['key']), child: Text(str(m['label'])))],
              onChanged: (v) => setState(() => _sort = v ?? 'net_revenue')),
          Text(str(d['note']), style: const TextStyle(color: EngColors.muted, fontSize: 12)),
          for (final c in items(d))
            RowCard(
              title: '${c['position']}. ${c['customer_name']}',
              subtitle: '${c['orders']} pedido(s) · qtd ${formatDecimal(strOrNull(c['quantity']))}'
                  '${c['margin_percent'] == null ? '' : ' · margem ${formatDecimal(strOrNull(c['margin_percent']))}%'}'
                  '${c['on_time_percent'] == null ? '' : ' · em dia ${formatDecimal(strOrNull(c['on_time_percent']))}%'}',
              trailing: formatMoney(strOrNull(c['net_revenue'])),
            ),
        ],
      );
}

const goalMetrics = {'NET_REVENUE': 'Receita líquida', 'SOLD_QUANTITY': 'Quantidade vendida', 'PROFIT': 'Lucro'};

class GoalsAdminScreen extends StatefulWidget {
  const GoalsAdminScreen({super.key});
  @override
  State<GoalsAdminScreen> createState() => _GoalsAdminScreenState();
}

class _GoalsAdminScreenState extends State<GoalsAdminScreen> {
  Key _k = UniqueKey();
  List<FieldSpec> _fields(J? g) => [
        FieldSpec('title', 'Título', required: true, initial: strOrNull(g?['title'])),
        FieldSpec('metric', 'Medida', kind: FieldKind.select, required: true, options: goalMetrics, initial: strOrNull(g?['metric'])),
        FieldSpec('unit', 'Unidade (só quantidade)', kind: FieldKind.select, initial: strOrNull(g?['unit']),
            options: const {'DUZIA': 'Dúzia', 'UNIDADE': 'Unidade', 'FEIXE': 'Feixe', 'MACO': 'Maço', 'CAIXA': 'Caixa', 'SACO': 'Saco', 'KG': 'Kg'}),
        FieldSpec('period_kind', 'Período', kind: FieldKind.select, required: true, options: const {'WEEK': 'Semana', 'MONTH': 'Mês'},
            initial: strOrNull(g?['period_kind']) ?? 'MONTH'),
        FieldSpec('period_start', 'Início do período', kind: FieldKind.date, required: true, initial: strOrNull(g?['period_start'])),
        FieldSpec('target', 'Alvo', kind: FieldKind.decimal, required: true, initial: strOrNull(g?['target'])),
        FieldSpec('notes', 'Observação', initial: strOrNull(g?['notes'])),
      ];
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    final t = DateTime.parse(todayIso());
    final from = DateTime(t.year, t.month - 1, 1);
    final to = DateTime(t.year, t.month + 2, 1);
    String iso(DateTime d) => '${d.year}-${d.month.toString().padLeft(2, '0')}-01';
    return Scaffold(
      floatingActionButton: me.can('goals.create')
          ? FloatingActionButton.extended(
              icon: const EngIcon('plus', color: EngColors.background),
              label: const Text('Nova meta'),
              onPressed: () async {
                final r = await openForm(context, title: 'Nova meta', fields: _fields(null),
                    submit: (api, v, key) => api.sendJson('POST', '/api/v1/goals', v));
                if (r != null) setState(() => _k = UniqueKey());
              })
          : null,
      body: JsonView(key: _k, load: (api) => api.getJson('/api/v1/goals', {'from': iso(from), 'to': iso(to)}), builder: (context, d, reload) => [
        if (items(d).isEmpty) const EmptyState(title: 'Nenhuma meta no período'),
        for (final g in items(d))
          Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(str(g['title']), style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
            Text('${g['metric_label']} · ${g['period_label']} · ${labelOf(const {'DRAFT': 'Rascunho', 'ACTIVE': 'Ativa', 'CANCELLED': 'Cancelada'}, str(g['state']))}',
                style: const TextStyle(color: EngColors.muted)),
            Text('Alvo ${g['metric'] == 'SOLD_QUANTITY' ? formatDecimal(strOrNull(g['target'])) : formatMoney(strOrNull(g['target']))}'),
            Wrap(spacing: 8, children: [
              if (g['state'] == 'DRAFT' && me.can('goals.edit_draft'))
                OutlinedButton(onPressed: () async {
                  final r = await openForm(context, title: 'Editar meta', fields: _fields(g),
                      submit: (api, v, key) => api.sendJson('PUT', '/api/v1/goals/${g['id']}', v, ifMatch: g['version']));
                  if (r != null) reload();
                }, child: const Text('Editar')),
              if (g['state'] == 'DRAFT' && me.can('goals.create'))
                FilledButton(onPressed: () async {
                  if (await runCommand(context, (api) => api.sendJson('POST', '/api/v1/goals/${g['id']}/activation', null, ifMatch: g['version']),
                      ok: 'Meta ativada.') != null) {
                    reload();
                  }
                }, child: const Text('Ativar')),
              if (g['state'] != 'CANCELLED' && me.can('goals.create'))
                OutlinedButton(onPressed: () async {
                  final why = await askText(context, title: 'Cancelar meta', action: 'Cancelar meta');
                  if (why == null || !context.mounted) return;
                  if (await runCommand(context, (api) => api.sendJson('POST', '/api/v1/goals/${g['id']}/cancellation', {'reason': why},
                      ifMatch: g['version']), ok: 'Meta cancelada.') != null) {
                    reload();
                  }
                }, child: const Text('Cancelar')),
            ]),
          ]))),
      ]),
    );
  }
}

/// P12 — busca geral: pedidos, viagens, veículos, clientes, fornecedores e produtos que a permissão deixa ver.
class OwnerSearchScreen extends StatefulWidget {
  const OwnerSearchScreen({super.key});
  @override
  State<OwnerSearchScreen> createState() => _OwnerSearchScreenState();
}

class _OwnerSearchScreenState extends State<OwnerSearchScreen> {
  final _q = TextEditingController();
  String _query = '';
  @override
  Widget build(BuildContext context) => Column(children: [
        Padding(padding: const EdgeInsets.fromLTRB(16, 16, 16, 0), child: TextField(controller: _q, autofocus: true,
            decoration: const InputDecoration(labelText: 'Buscar número, cliente, placa, produto…'),
            onSubmitted: (v) => setState(() => _query = v.trim()))),
        Expanded(child: _query.length < 2
            ? const Center(child: Text('Digite ao menos 2 caracteres.', style: TextStyle(color: EngColors.muted)))
            : JsonView(key: ValueKey(_query), load: (api) => api.getJson('/api/v1/search', {'q': _query}), builder: (context, d, reload) => [
                Text('Procurado em: ${(d['searched'] as List? ?? const []).map((e) => labelOf(entityLabels, '$e')).join(', ')}',
                    style: const TextStyle(color: EngColors.muted, fontSize: 12)),
                if (items(d).isEmpty) const EmptyState(title: 'Nada encontrado'),
                for (final i in items(d))
                  RowCard(title: str(i['title']), subtitle: '${labelOf(entityLabels, str(i['type']))}${i['subtitle'] == null ? '' : ' · ${i['subtitle']}'}',
                      onTap: () => openOrigin(context, str(i['type']), str(i['id']))),
              ])),
      ]);
}

/// P02 — Início do dono: resultado do mês (os mesmos números do site, prontos do servidor), aprovações pendentes e
/// a meta ativa. Sem `reports.view` nada numérico aparece (nenhum número de exemplo).
class OwnerHomeScreen extends StatelessWidget {
  const OwnerHomeScreen({super.key, required this.openApprovals});
  final VoidCallback openApprovals;
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    final today = todayIso();
    return JsonView(load: (api) async => {
          'ap': await api.getJson('/api/v1/approvals'),
          if (me.can('reports.view')) 'r': await api.getJson('/api/v1/reports/overview', {'period': 'MONTH', 'date': today}),
          if (me.can('goals.view')) 'g': await api.getJson('/api/v1/goals', {'from': '${today.substring(0, 8)}01', 'to': _nextMonth(today)}),
        }, builder: (context, d, reload) {
      final pending = items(d['ap'] as J).length;
      final r = d['r'] as J?;
      final goals = d['g'] == null ? const <J>[] : items(d['g'] as J).where((g) => g['state'] == 'ACTIVE').toList();
      return [
        Text('Olá, ${me.displayName}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
        Text('${me.organizationName} · ${me.branchName}', style: const TextStyle(color: EngColors.muted)),
        const SizedBox(height: 8),
        Card(child: ListTile(
          leading: EngIcon('shield', color: pending > 0 ? EngColors.warning : EngColors.green),
          title: Text(pending == 0 ? 'Nada esperando sua decisão' : '$pending aprovação(ões) esperando você'),
          subtitle: const Text('Aprovar não paga: pagamento é outra etapa.'),
          onTap: openApprovals,
        )),
        if (r != null) ...[
          SectionTitle('Seu negócio · ${r['label']}'),
          ...resultLines(r),
          if ((r['hidden'] as List? ?? const []).isNotEmpty)
            const Text('Alguns valores ocultos pela sua permissão.', style: TextStyle(color: EngColors.warning)),
        ] else
          const Text('Indicadores financeiros dependem da permissão de relatórios. Nenhum número de exemplo é exibido.',
              style: TextStyle(color: EngColors.muted)),
        for (final g in goals)
          KV('Meta: ${g['title']}', (g['progress'] as J?)?['percent'] == null ? 'sem dado'
              : '${formatDecimal(strOrNull((g['progress'] as J)['percent']))}% de ${g['metric'] == 'SOLD_QUANTITY' ? formatDecimal(strOrNull(g['target'])) : formatMoney(strOrNull(g['target']))}'),
        if (me.can('audit.view')) ...[
          const SizedBox(height: 8),
          OutlinedButton(onPressed: () => pushScreen(context, 'Histórico completo', const HistoryScreen()), child: const Text('Últimos movimentos')),
        ],
      ];
    });
  }
}

const _resultLabels = [
  ('net_revenue', 'Vendas (receita líquida)'), ('cogs', 'CMV'), ('expenses_total', 'Despesas'), ('losses', 'Perdas'), ('profit', 'Lucro'),
];

/// Resultado do período como o servidor calculou (objeto `result`); valor oculto pela permissão aparece como "oculto".
List<Widget> resultLines(J r) {
  final res = (r['result'] as J?) ?? const {};
  final hidden = (r['hidden'] as List? ?? const []).cast<String>();
  return [
    for (final (k, label) in _resultLabels)
      if (res.containsKey(k) || hidden.contains(k))
        KV(label, res[k] == null ? 'oculto' : formatMoney(strOrNull(res[k])), strong: k == 'profit'),
    if (res['margin_percent'] != null) KV('Margem', '${formatDecimal(strOrNull(res['margin_percent']))}%'),
  ];
}

String _nextMonth(String today) {
  final t = DateTime.parse(today);
  final n = DateTime(t.year, t.month + 1, 1);
  return '${n.year}-${n.month.toString().padLeft(2, '0')}-01';
}

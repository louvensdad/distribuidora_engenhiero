import 'package:engenheiro_core/engenheiro_core.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'accounting_test.dart' show login;
import 'app_test.dart' show j;

Map<String, String> col(String key, String label, String amount) => {'key': key, 'label': label, 'amount': amount};

/// FECHAMENTO-01 do pacote, como o servidor devolve (hidden = sem custo/margem).
Map<String, dynamic> report({bool hidden = false}) => {
      'policy_version': 'RESULTADO-GERENCIAL-1', 'timezone': 'America/Sao_Paulo', 'period': 'MONTH', 'from': '2026-11-01',
      'to': '2026-12-01', 'label': 'novembro de 2026', 'branch_id': null, 'branch_name': null, 'basis': 'COMPETENCE',
      'source': 'LIVE', 'closed': null, 'hidden': hidden ? ['cogs', 'losses', 'costs_expenses_losses', 'profit', 'margin_percent'] : [],
      'result': {'gross_revenue': '40000.00', 'discounts': '0.00', 'returns': '0.00', 'net_revenue': '40000.00',
        'cogs': hidden ? null : '15000.00', 'expenses_total': '15000.00', 'losses': hidden ? null : '500.00',
        'costs_expenses_losses': hidden ? null : '30500.00', 'profit': hidden ? null : '9500.00',
        'margin_percent': hidden ? null : '23.75', 'outcome': hidden ? null : 'PROFIT'},
      'expenses': [
        {'key': 'FUNCIONARIOS', 'label': 'Funcionários', 'amount': '8000.00', 'accounts': ['DESPESAS_FUNCIONARIOS']},
        {'key': 'VEICULOS', 'label': 'Veículos', 'amount': '3000.00', 'accounts': ['DESPESAS_VEICULOS']},
        {'key': 'ESTRUTURA', 'label': 'Estrutura', 'amount': '2000.00', 'accounts': ['DESPESAS_ESTRUTURA']},
        {'key': 'IMPOSTOS_TAXAS', 'label': 'Impostos e taxas', 'amount': '2000.00', 'accounts': ['DESPESAS_IMPOSTOS']},
      ],
      'subcategories': [],
      'sold': {'quantity_total': '2000.000000', 'unit': 'DUZIA', 'orders': 12, 'note': '',
        'by_unit': [{'unit': 'DUZIA', 'sold': '2000.000000', 'backlog': '0.000000'}]},
      'chart_unit': 'BRL',
      'chart': [
        col('net_revenue', 'Vendas (receita líquida)', '40000.00'),
        if (!hidden) col('cogs', 'CMV', '15000.00'),
        col('expenses_total', 'Despesas', '15000.00'),
        if (!hidden) ...[col('losses', 'Perdas', '500.00'), col('profit', 'Lucro', '9500.00')],
      ],
      'timeline_bucket': 'DAY', 'timeline': [],
      'comparison': {'from': '2026-10-01', 'to': '2026-11-01', 'label': 'outubro de 2026', 'formula': 'Diferença = atual − anterior',
        'items': [{'key': 'net_revenue', 'label': 'Vendas (receita líquida)', 'current': '40000.00', 'previous': '32000.00',
          'difference': '8000.00', 'percent': '25.00'}]},
      'drill': [], 'cash': null, 'formula': 'Lucro = receita líquida − CMV − despesas − perdas', 'note': 'Resultado gerencial.',
      'generated_by': 'Patrão', 'generated_at': '2026-11-17T12:00:00Z',
    };

void main() {
  testWidgets('relatório no app: 40.000 − 15.000 − 15.000 − 500 = 9.500 nos indicadores, no gráfico e na tabela', (tester) async {
    final queries = <Uri>[];
    await login(tester, (_) => const ReportsScreen(), ['reports.view', 'goals.view'], (http.Request r) async {
      queries.add(r.url);
      return switch (r.url.path) {
        '/api/v1/reports/overview' => j(200, report()),
        '/api/v1/goals' => j(200, {'items': []}),
        _ => j(404, {'status': 404, 'code': 'NOT_FOUND'}),
      };
    });
    expect(find.byKey(const ValueKey('kpi-profit')), findsOneWidget);
    expect(tester.widget<Text>(find.byKey(const ValueKey('kpi-profit'))).data, 'R\$ 9.500,00');
    expect(find.text('R\$ 30.500,00'), findsOneWidget); // CMV + despesas + perdas
    expect(find.text('Lucro do período'), findsOneWidget);
    expect(find.text('Margem: 23,75%'), findsOneWidget);
    final chart = tester.widget<Columns3DChart>(find.byType(Columns3DChart));
    expect([for (final c in chart.columns) c['amount']], ['40000.00', '15000.00', '15000.00', '500.00', '9500.00']);
    expect(find.bySemanticsLabel(RegExp('Lucro: R\\\$ 9.500,00')), findsOneWidget);
    final q = queries.firstWhere((u) => u.path == '/api/v1/reports/overview').queryParameters;
    expect(q['period'], 'MONTH');
    expect(q['date'], matches(RegExp(r'^\d{4}-\d{2}-\d{2}$')));
    // Trocar para "Dia" pede o dia civil.
    await tester.tap(find.text('Dia'));
    await tester.pumpAndSettle();
    expect(queries.last.queryParameters['period'], 'DAY');
  });

  testWidgets('sem custo/margem: CMV, perdas e lucro não aparecem no app', (tester) async {
    await login(tester, (_) => const ReportsScreen(), ['reports.view'], (http.Request r) async => j(200, report(hidden: true)));
    expect(find.text('CMV, perdas e lucro ocultos pela sua permissão.'), findsOneWidget);
    expect(find.byKey(const ValueKey('kpi-profit')), findsNothing);
    expect(find.text('R\$ 9.500,00'), findsNothing);
    expect(find.text('R\$ 500,00'), findsNothing);
    expect(find.text('Metas'), findsNothing); // sem goals.view, sem aba
  });

  test('hoje em São Paulo: 01h UTC ainda é o dia anterior', () {
    expect(isoDate(todaySaoPaulo(DateTime.utc(2026, 4, 1, 2, 30))), '2026-03-31');
    expect(isoDate(todaySaoPaulo(DateTime.utc(2026, 4, 1, 3, 30))), '2026-04-01');
  });
}

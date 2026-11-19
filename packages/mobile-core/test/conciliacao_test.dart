import 'package:engenheiro_core/src/screens/owner/reports.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:http/http.dart' as http;

import 'app_test.dart' show j;
import 'patrao_test.dart' show loginOwner;

/// F17 (UAT-02) — conferência do mês com a operação no app Patrão: mesmo endpoint e mesmas verificações do site.
Map<String, dynamic> pilot() => {
      'from': '2026-11-01', 'to': '2026-12-01', 'label': 'novembro de 2026', 'timezone': 'America/Sao_Paulo',
      'status': 'DIVERGENT',
      'report': {'net_revenue': '40000.00', 'cogs': '15000.00', 'expenses_total': '2000.00', 'losses': '500.00',
        'profit': '22500.00', 'fulfillments': 1, 'payments': 2, 'cash_sessions_closed': 0},
      'checks': [
        {'code': 'SALES_NET', 'area': 'Vendas', 'label': 'Vendas líquidas × relatório', 'unit': 'BRL', 'expected': '40000.00',
          'actual': '40000.00', 'difference': '0.00', 'status': 'OK', 'explanation': 'Pedido só pago não é venda.'},
        {'code': 'SALES_STOCK_QTY', 'area': 'Estoque', 'label': 'Quantidade entregue × saída de estoque', 'unit': 'base',
          'expected': '2000', 'actual': '2000', 'difference': '0', 'status': 'OK', 'explanation': 'Uma baixa por entrega.'},
        {'code': 'CASH_PAYMENTS', 'area': 'Caixa', 'label': 'Dinheiro confirmado × entradas nos caixas', 'unit': 'BRL',
          'expected': '41000.00', 'actual': '40990.00', 'difference': '-10.00', 'status': 'DIVERGENT', 'explanation': 'O troco não é receita.'},
      ],
      'note': 'Conferência de leitura: não altera nada.', 'checked_at': '2026-11-19T12:00:00Z',
    };

void main() {
  testWidgets('conferência do mês: totais, cada verificação e divergência visível', (tester) async {
    final calls = <Uri>[];
    await loginOwner(tester, (_) => const PilotReconciliationScreen(), ['reports.view'], (http.Request r) async {
      calls.add(r.url);
      return r.url.path == '/api/v1/reports/reconciliation' ? j(200, pilot()) : j(404, {'status': 404});
    });
    expect(calls.single.queryParameters['period'], 'MONTH');
    expect(find.text('1 divergência(s)'), findsOneWidget);
    expect(find.text('novembro de 2026'), findsOneWidget);
    await tester.scrollUntilVisible(find.textContaining('Dinheiro confirmado'), 300,
        scrollable: find.descendant(of: find.byType(ListView), matching: find.byType(Scrollable)).first);
    expect(find.text('Divergente'), findsOneWidget);
    expect(find.text('Confere'), findsNWidgets(2));
    expect(find.textContaining('2000 un. base'), findsOneWidget);
  });

  testWidgets('sem custo/resultado: o servidor recusa (403) e o app mostra sem permissão, sem números', (tester) async {
    await loginOwner(tester, (_) => const PilotReconciliationScreen(), ['reports.view'], (http.Request r) async =>
        j(403, {'status': 403, 'code': 'FORBIDDEN', 'title': 'Acesso negado'}));
    expect(find.textContaining('R\$'), findsNothing);
    expect(find.textContaining('divergência'), findsNothing);
    expect(find.textContaining('permissão'), findsWidgets);
  });
}

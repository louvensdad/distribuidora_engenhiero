import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_auth/engenheiro_auth.dart';
import 'package:engenheiro_core/engenheiro_core.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'app_test.dart' show FakeAuth, j, meJson;

void main() {
  testWidgets('patrão: resultado (FECHAMENTO-01) separado do caixa e conciliação divergente', (tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    final requested = <String>[];
    final session = SessionController(
      auth: AuthSession(authenticator: FakeAuth(), store: MemorySecureStore()),
      apiFactory: (tokens) => ApiClient(baseUrl: Uri.parse('http://api.teste.invalid'), tokenProvider: tokens,
          httpClient: MockClient((r) async {
        requested.add(r.url.toString());
        switch (r.url.path) {
          case '/api/v1/me/capabilities':
            return j(200, meJson(['reports.view'], client: 'engenheiro-gestao'));
          case '/api/v1/reports/summary':
            return j(200, {'gross_revenue': '40000.00', 'discounts': '0.00', 'returns': '0.00', 'cogs': '15000.00',
              'expenses': [
                {'key': 'FUNCIONARIOS', 'label': 'Funcionários', 'amount': '8000.00', 'accounts': []},
                {'key': 'VEICULOS', 'label': 'Veículos', 'amount': '3000.00', 'accounts': []},
                {'key': 'ESTRUTURA', 'label': 'Estrutura', 'amount': '2000.00', 'accounts': []},
                {'key': 'IMPOSTOS_TAXAS', 'label': 'Impostos e taxas', 'amount': '2000.00', 'accounts': []},
              ],
              'losses': '500.00', 'profit': '9500.00', 'margin_percent': '23.75',
              'cash': {'available': '11000.00', 'card_receivables_open': '97.00', 'receivables_open': '4000.00', 'payables_open': '1510.00'}});
          case '/api/v1/finance/reconciliation':
            return j(200, {'status': 'DIVERGENT', 'checks': [
              {'code': 'INVENTORY', 'label': 'Valor do estoque × ESTOQUE', 'difference': '-1794.74', 'status': 'DIVERGENT'}]});
        }
        return j(404, {});
      })),
    );
    await tester.pumpWidget(EngenheiroApp(
        config: AppConfig(appName: 'Engenheiro Patrão', applicationId: 'br.com.engenheiro.gestao', clientId: 'engenheiro-gestao',
            audienceDescription: 'Gestão', apiBaseUrl: Uri.parse('http://api.teste.invalid'),
            issuer: 'http://idp.teste.invalid/realms/engenheiro',
            destinations: [Destination(id: 'fin', label: 'Financeiro', icon: 'wallet', anyOf: const ['reports.view'],
                builder: (_) => const FinanceSummaryScreen())]),
        session: session));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Entrar com segurança'));
    await tester.pumpAndSettle();
    expect(find.text('Lucro do período'), findsOneWidget);
    expect(find.text('R\$ 9.500,00'), findsOneWidget);
    expect(find.text('Margem: 23,75%'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('Conciliação: há divergência'), 300,
        scrollable: find.descendant(of: find.byType(FinanceSummaryScreen), matching: find.byType(Scrollable)).first);
    expect(find.text('R\$ 11.000,00'), findsOneWidget);
    expect(find.text('Saldo em caixa não é lucro.'), findsOneWidget);
    expect(find.text('Conciliação: há divergência'), findsOneWidget);
    expect(find.textContaining('diferença −R\$ 1.794,74'), findsOneWidget);
    final summaryUrl = requested.firstWhere((u) => u.contains('/reports/summary'));
    expect(summaryUrl, matches(RegExp(r'from=\d{4}-\d{2}-01&to=\d{4}-\d{2}-01')));
  });
}

import 'dart:convert';
import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_auth/engenheiro_auth.dart';
import 'package:engenheiro_core/engenheiro_core.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'app_test.dart' show FakeAuth, j, meJson;

AppConfig balcao() => AppConfig(
      appName: 'Engenheiro Retirada',
      applicationId: 'br.com.engenheiro.retirada',
      clientId: 'engenheiro-retirada',
      audienceDescription: 'Balcão',
      apiBaseUrl: Uri.parse('http://api.teste.invalid'),
      issuer: 'http://idp.teste.invalid/realms/engenheiro',
      destinations: [
        Destination(id: 'pedidos', label: 'Pedidos', icon: 'order', anyOf: const ['orders.pickup.view'], builder: (_) => const OrdersScreen()),
        Destination(id: 'caixa', label: 'Caixa', icon: 'cash', anyOf: const ['own_cash.view'], builder: (_) => const CashScreen()),
      ],
    );

Map<String, dynamic> order({String state = 'CONFIRMED', String pay = 'UNPAID', String paid = '0.00', String due = '75.00', String remaining = '10'}) => {
      'id': 'o1', 'number': 1047, 'type': 'PICKUP', 'state': state, 'payment_state': pay, 'customer_id': 'c1', 'customer_name': 'Ana Santos',
      'branch': 'Matriz', 'gross_total': '75.00', 'discount_total': '0.00', 'total': '75.00', 'effective_total': '75.00',
      'paid_amount': paid, 'pending_amount': '0.00', 'on_account': '0.00', 'balance_due': due, 'notes': null,
      'created_by_name': 'Balcão', 'created_at': '2026-11-16T12:00:00Z', 'cancel_reason': null, 'version': 3,
      'lines': [{'line_no': 1, 'product_id': 'p1', 'sku': 'CANA', 'product_name': 'Cana caiana', 'unit': 'DUZIA', 'factor': '1',
        'quantity': '10', 'base_quantity': '10', 'base_unit': 'DUZIA', 'unit_price': '7.50', 'gross': '75.00', 'discount': '0.00',
        'net': '75.00', 'fulfilled_quantity': '0', 'cancelled_quantity': '0', 'returned_quantity': '0', 'remaining_quantity': remaining}],
    };

void main() {
  testWidgets('balcão: R\$ 75 pagos com R\$ 100 mostra troco de R\$ 25 vindo do servidor e registra a retirada', (tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    http.Request? payment;
    var current = order();
    final session = SessionController(
      auth: AuthSession(authenticator: FakeAuth(), store: MemorySecureStore()),
      apiFactory: (tokens) => ApiClient(baseUrl: Uri.parse('http://api.teste.invalid'), tokenProvider: tokens, httpClient: MockClient((r) async {
        switch (r.url.path) {
          case '/api/v1/me/capabilities':
            return j(200, meJson(['orders.pickup.view', 'orders.pickup.confirm', 'payments.confirm', 'own_cash.view']));
          case '/api/v1/orders':
            return j(200, {'items': [current], 'next_cursor': null});
          case '/api/v1/orders/o1':
            return j(200, current);
          case '/api/v1/orders/o1/payments':
            payment = r;
            current = order(pay: 'PAID', paid: '75.00', due: '0.00');
            return j(200, {'payments': [{'id': 'p1', 'order_id': 'o1', 'method': 'CASH', 'state': 'CONFIRMED', 'amount': '75.00',
              'change_given': '25.00'}], 'order': current});
          case '/api/v1/orders/o1/fulfillments':
            current = order(state: 'FULFILLED', pay: 'PAID', paid: '75.00', due: '0.00', remaining: '0');
            return j(200, current);
        }
        return j(404, {});
      })),
    );
    await tester.pumpWidget(EngenheiroApp(config: balcao(), session: session));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Entrar com segurança'));
    await tester.pumpAndSettle();
    expect(find.text('#1047 Ana Santos'), findsOneWidget);
    await tester.tap(find.text('#1047 Ana Santos'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Dinheiro apresentado (R\$) *'), '100,00');
    await tester.dragUntilVisible(find.text('Registrar recebimento'), find.byType(ListView).last, const Offset(0, -300));
    await tester.tap(find.text('Registrar recebimento'));
    await tester.pumpAndSettle();
    expect(payment!.headers['Idempotency-Key'], startsWith('app-'));
    expect(jsonDecode(payment!.body), {'parts': [{'method': 'CASH', 'amount': '75.00', 'presented_cash': '100.00'}]});
    expect(find.text('Troco a entregar'), findsOneWidget);
    expect(find.text('R\$ 25,00'), findsOneWidget);
    await tester.dragUntilVisible(find.text('Registrar retirada'), find.byType(ListView).last, const Offset(0, -300));
    await tester.tap(find.text('Registrar retirada'));
    await tester.pumpAndSettle();
    expect(find.text('Concluído'), findsOneWidget);
  });
}

import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_auth/engenheiro_auth.dart';
import 'package:engenheiro_core/engenheiro_core.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'app_test.dart' show FakeAuth, j, meJson;
import 'orders_test.dart' show balcao, order;

/// F11 — app Retirada: queda de conexão sem confirmar nada no aparelho, "meus pedidos" e comprovante.
SessionController _session(List<String> caps, Future<http.Response> Function(http.Request r) api) => SessionController(
      auth: AuthSession(authenticator: FakeAuth(), store: MemorySecureStore()),
      apiFactory: (tokens) => ApiClient(
          baseUrl: Uri.parse('http://api.teste.invalid'),
          tokenProvider: tokens,
          httpClient: MockClient((r) async => r.url.path == '/api/v1/me/capabilities' ? j(200, meJson(caps)) : api(r))),
    );

Future<void> _enter(WidgetTester tester, SessionController s) async {
  tester.view.physicalSize = const Size(1080, 2400);
  tester.view.devicePixelRatio = 3;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(EngenheiroApp(config: balcao(), session: s));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Entrar com segurança'));
  await tester.pumpAndSettle();
}

const _caps = ['orders.pickup.view', 'orders.pickup.confirm', 'payments.confirm', 'payments.view', 'own_cash.view'];

void main() {
  testWidgets('resposta perdida no pagamento: nada é dado como pago no aparelho; confere no servidor e repete com a MESMA chave',
      (tester) async {
    final keys = <String>[];
    var serverReachable = true;
    final s = _session(_caps, (r) async {
      switch (r.url.path) {
        case '/api/v1/orders':
          return j(200, {'items': [order()], 'next_cursor': null});
        case '/api/v1/orders/o1':
          if (!serverReachable) throw http.ClientException('sem rede');
          return j(200, order());
        case '/api/v1/orders/o1/payments':
          keys.add(r.headers['Idempotency-Key']!);
          serverReachable = false; // caiu durante o envio: a resposta não chega e a conferência também falha
          throw http.ClientException('conexão perdida');
      }
      return j(404, {});
    });
    await _enter(tester, s);
    await tester.tap(find.text('#1047 Ana Santos'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Dinheiro apresentado (R\$) *'), '100,00');
    await tester.dragUntilVisible(find.text('Registrar recebimento'), find.byType(ListView).last, const Offset(0, -300));
    await tester.tap(find.text('Registrar recebimento'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Não sabemos se o recebimento foi registrado'), findsOneWidget);
    expect(find.textContaining('não entregue a mercadoria nem devolva troco'), findsOneWidget);
    expect(find.text('Troco a entregar'), findsNothing); // nenhum troco "inventado" no aparelho
    expect(find.text('Não pago'), findsOneWidget); // situação continua a última vinda do servidor
    // Conexão volta: nova tentativa usa a mesma chave (o servidor não duplica).
    await tester.dragUntilVisible(find.text('Registrar recebimento'), find.byType(ListView).last, const Offset(0, -300));
    await tester.tap(find.text('Registrar recebimento'));
    await tester.pumpAndSettle();
    expect(keys, hasLength(2));
    expect(keys[1], keys[0]);
  });

  testWidgets('resposta perdida, mas o servidor registrou: mostra a situação real (pago) sem pedir para pagar de novo', (tester) async {
    var paid = false;
    final s = _session(_caps, (r) async {
      switch (r.url.path) {
        case '/api/v1/orders':
          return j(200, {'items': [order()], 'next_cursor': null});
        case '/api/v1/orders/o1':
          return j(200, paid ? order(pay: 'PAID', paid: '75.00', due: '0.00') : order());
        case '/api/v1/orders/o1/payments':
          paid = true; // registrou, mas a resposta se perdeu
          throw http.ClientException('conexão perdida');
      }
      return j(404, {});
    });
    await _enter(tester, s);
    await tester.tap(find.text('#1047 Ana Santos'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Dinheiro apresentado (R\$) *'), '75,00');
    await tester.dragUntilVisible(find.text('Registrar recebimento'), find.byType(ListView).last, const Offset(0, -300));
    await tester.tap(find.text('Registrar recebimento'));
    await tester.pumpAndSettle();
    expect(find.textContaining('A situação abaixo é a do servidor agora'), findsOneWidget);
    expect(find.text('Pago'), findsWidgets);
    expect(find.text('Não pago'), findsNothing);
    expect(find.text('Registrar recebimento'), findsNothing);
  });

  testWidgets('meus pedidos: período (hoje por padrão), busca por número/cliente e "Carregar mais"', (tester) async {
    final calls = <Uri>[];
    final s = _session(_caps, (r) async {
      if (r.url.path == '/api/v1/orders') {
        calls.add(r.url);
        if (r.url.queryParameters['cursor'] == 'c2') {
          return j(200, {'items': [{...order(), 'id': 'o2', 'number': 1046, 'customer_name': 'Feira Fictícia'}], 'next_cursor': null});
        }
        return j(200, {'items': [order()], 'next_cursor': 'c2'});
      }
      return j(404, {});
    });
    await _enter(tester, s);
    final first = calls.first.queryParameters;
    final today = isoDate(todaySaoPaulo());
    expect(first['from'], today);
    expect(first['to'], isoDate(todaySaoPaulo().add(const Duration(days: 1))));
    await tester.tap(find.text('Carregar mais'));
    await tester.pumpAndSettle();
    expect(find.text('#1046 Feira Fictícia'), findsOneWidget);
    expect(find.text('2 pedido(s) — fim da lista'), findsOneWidget);
    await tester.tap(find.text('Todos'));
    await tester.pumpAndSettle();
    expect(calls.last.queryParameters.containsKey('from'), isFalse);
    await tester.enterText(find.widgetWithText(TextField, 'Buscar pelo número ou cliente'), '1047');
    await tester.testTextInput.receiveAction(TextInputAction.search);
    await tester.pumpAndSettle();
    expect(calls.last.queryParameters['q'], '1047');
  });

  testWidgets('comprovante: itens, pagamento, troco e aviso de que não é documento fiscal', (tester) async {
    final s = _session(_caps, (r) async {
      switch (r.url.path) {
        case '/api/v1/orders':
          return j(200, {'items': [order(state: 'FULFILLED', pay: 'PAID', paid: '75.00', due: '0.00', remaining: '0')], 'next_cursor': null});
        case '/api/v1/orders/o1':
          return j(200, order(state: 'FULFILLED', pay: 'PAID', paid: '75.00', due: '0.00', remaining: '0'));
        case '/api/v1/orders/o1/payments':
          return j(200, {'items': [{'id': 'p1', 'order_id': 'o1', 'method': 'CASH', 'state': 'CONFIRMED', 'amount': '75.00',
            'presented_cash': '100.00', 'change_given': '25.00', 'created_by_name': 'Balcão', 'created_at': '2026-11-16T12:01:00Z'}]});
      }
      return j(404, {});
    });
    await _enter(tester, s);
    await tester.tap(find.textContaining('#1047 Ana Santos'));
    await tester.pumpAndSettle();
    await tester.dragUntilVisible(find.text('Comprovante para o cliente'), find.byType(ListView).last, const Offset(0, -300));
    await tester.tap(find.text('Comprovante para o cliente'));
    await tester.pumpAndSettle();
    expect(find.text('Pedido #1047'), findsOneWidget);
    expect(find.text('Distribuidora de Cana Engenheiro Ltda'), findsOneWidget);
    expect(find.textContaining('recebido R\$ 100,00, troco R\$ 25,00'), findsOneWidget);
    expect(find.text('Concluída'), findsOneWidget);
    expect(find.text('Comprovante de pedido — não é documento fiscal.'), findsOneWidget);
  });
}

import 'dart:convert';
import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_auth/engenheiro_auth.dart';
import 'package:engenheiro_core/engenheiro_core.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'app_test.dart' show FakeAuth, j, meJson;

AppConfig retirada() => AppConfig(
      appName: 'Engenheiro Retirada',
      applicationId: 'br.com.engenheiro.retirada',
      clientId: 'engenheiro-retirada',
      audienceDescription: 'Balcão e recebimento de fornecedores',
      apiBaseUrl: Uri.parse('http://api.teste.invalid'),
      issuer: 'http://idp.teste.invalid/realms/engenheiro',
      destinations: [
        Destination(id: 'recebimentos', label: 'Fornecedores', icon: 'supplier', anyOf: const ['inventory.receipts.view'],
            builder: (_) => const ReceiptsScreen()),
        Destination(id: 'estoque', label: 'Estoque', icon: 'inventory', anyOf: const ['inventory.stock.view'], builder: (_) => const StockScreen()),
      ],
    );

final product = {'id': 'p1', 'sku': 'CANA-A', 'name': 'Cana caiana', 'base_unit': 'DUZIA', 'base_unit_confirmed': true,
  'conversions': [{'unit': 'FEIXE', 'factor': '3', 'valid_from': '2026-11-16T12:00:00Z'}]};
final supplier = {'id': 's1', 'name': 'Sítio Boa Colheita', 'contact': null, 'masked_fields': ['contact', 'personal_document']};
Map<String, dynamic> receipt(String status) => {
      'id': 'r1', 'status': status, 'supplier_id': 's1', 'supplier_name': 'Sítio Boa Colheita', 'document_number': 'NF-1',
      'total_cost': null, 'received_at': '2026-11-16T12:00:00Z', 'created_by_name': 'Recebimento', 'confirmed_by_name': null,
      'version': 1, 'masked_fields': ['freight_amount', 'total_cost', 'lines.unit_cost'],
      'lines': [{'line_no': 1, 'product_id': 'p1', 'product_name': 'Cana caiana', 'unit': 'FEIXE', 'base_unit': 'DUZIA',
        'quantity_expected': '10', 'quantity_received': '9', 'base_quantity': '27', 'difference': '-1', 'lot': null, 'unit_cost': null}],
    };

Future<void> login(WidgetTester tester, Future<http.Response> Function(http.Request) server) async {
  final session = SessionController(
    auth: AuthSession(authenticator: FakeAuth(), store: MemorySecureStore()),
    apiFactory: (tokens) => ApiClient(baseUrl: Uri.parse('http://api.teste.invalid'), tokenProvider: tokens, httpClient: MockClient(server)),
  );
  await tester.pumpWidget(EngenheiroApp(config: retirada(), session: session));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Entrar com segurança'));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('recebimento sem acesso a custo: registra quantidade com chave idempotente, sem campo de custo', (tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    http.Request? posted;
    await login(tester, (r) async {
      switch (r.url.path) {
        case '/api/v1/me/capabilities':
          return j(200, meJson(['inventory.receipts.view', 'inventory.receipts.create', 'suppliers.view', 'catalog.view', 'inventory.stock.view']));
        case '/api/v1/inventory/receipts' when r.method == 'GET':
          return j(200, {'items': [], 'next_cursor': null});
        case '/api/v1/inventory/receipts':
          posted = r;
          return j(201, receipt('DRAFT'));
        case '/api/v1/inventory/receipts/r1':
          return j(200, receipt('DRAFT'));
        case '/api/v1/suppliers':
          return j(200, {'items': [supplier], 'next_cursor': null});
        case '/api/v1/products':
          return j(200, {'items': [product], 'next_cursor': null});
      }
      return j(404, {'status': 404, 'code': 'NOT_FOUND'});
    });
    expect(find.text('Nenhum recebimento registrado'), findsOneWidget);
    await tester.tap(find.text('Receber mercadoria'));
    await tester.pumpAndSettle();
    expect(find.text('Conferir cana recebida'), findsOneWidget);
    expect(find.widgetWithText(TextField, 'Custo unitário (R\$)'), findsNothing);
    expect(find.widgetWithText(TextField, 'Frete de compra autorizado (R\$)'), findsNothing);
    expect(find.textContaining('Custo e frete ficam com quem tem acesso'), findsOneWidget);

    await tester.tap(find.byType(DropdownButtonFormField<String>).first);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Sítio Boa Colheita').last);
    await tester.pumpAndSettle();
    await tester.tap(find.byType(DropdownButtonFormField<String>).at(1));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Cana caiana').last);
    await tester.pumpAndSettle();
    await tester.tap(find.byType(DropdownButtonFormField<String>).at(2));
    await tester.pumpAndSettle();
    await tester.tap(find.text('feixes (= 3 dúzias)').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Prevista'), '10');
    await tester.enterText(find.widgetWithText(TextField, 'Aceita *'), '9');
    await tester.dragUntilVisible(find.text('Salvar rascunho'), find.byType(ListView).last, const Offset(0, -300));
    await tester.tap(find.text('Salvar rascunho'));
    await tester.pumpAndSettle();

    expect(posted, isNotNull);
    expect(posted!.headers['Idempotency-Key'], matches(RegExp(r'^app-[0-9a-f]{32}$')));
    final body = jsonDecode(posted!.body) as Map<String, dynamic>;
    expect(body, isNot(contains('freight_amount')));
    expect(body['lines'], [{'product_id': 'p1', 'unit': 'FEIXE', 'quantity_expected': '10', 'quantity_received': '9'}]);
    // Detalhe: diferença registrada e custo oculto.
    expect(find.text('Rascunho — aguardando conferência'), findsOneWidget);
    expect(find.text('9 feixes (= 27 dúzias)'), findsOneWidget);
    expect(find.text('−1'), findsOneWidget);
    expect(find.text('oculto'), findsWidgets);
    expect(find.text('Confirmar entrada'), findsNothing); // sem inventory.receipts.confirm
  });

  testWidgets('estoque: disponível em destaque e valor oculto sem custo', (tester) async {
    await login(tester, (r) async {
      if (r.url.path == '/api/v1/me/capabilities') return j(200, meJson(['inventory.stock.view']));
      if (r.url.path == '/api/v1/inventory/balances') {
        return j(200, {'items': [{'product_id': 'p1', 'sku': 'CANA-A', 'product_name': 'Cana caiana', 'base_unit': 'DUZIA',
          'branch_id': 'b', 'branch': 'Matriz', 'physical': '450', 'reserved': '80', 'available': '370', 'lost': '1', 'value': null,
          'average_cost': null, 'version': 1, 'updated_at': '2026-11-16T12:00:00Z', 'masked_fields': ['value', 'average_cost']}], 'next_cursor': null});
      }
      return j(404, {});
    });
    expect(find.text('Disponível: 370 dúzias'), findsOneWidget);
    expect(find.text('450 dúzias'), findsOneWidget);
    expect(find.text('80 dúzias'), findsOneWidget);
    expect(find.text('1 dúzia'), findsOneWidget);
    expect(find.text('oculto'), findsOneWidget);
  });

  test('formatação brasileira sem float', () {
    expect(formatMoney('1510.00'), 'R\$ 1.510,00');
    expect(formatQty('0.5', 'DUZIA'), '0,5 dúzias');
    expect(decimalText('1.234,5'), '1234.5');
    expect(decimalText('12.5'), '12.5');
    expect(decimalText(' 12,50 '), '12.50');
  });
}

import 'dart:convert';
import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_auth/engenheiro_auth.dart';
import 'package:engenheiro_core/engenheiro_core.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'app_test.dart' show FakeAuth, j, meJson;
import 'orders_test.dart' show order;

/// F12 — app Entregas: despacho pelo app (viagem não duplicada ao repetir), pedido pago aparece "não cobrar",
/// manutenção do veículo e acesso revogado no meio do uso.
AppConfig entregas() => AppConfig(
      appName: 'Engenheiro Entregas',
      applicationId: 'br.com.engenheiro.entregas',
      clientId: 'engenheiro-entregas',
      audienceDescription: 'Entregas',
      apiBaseUrl: Uri.parse('http://api.teste.invalid'),
      issuer: 'http://idp.teste.invalid/realms/engenheiro',
      destinations: [
        Destination(id: 'pedidos', label: 'Pedidos', icon: 'order', anyOf: const ['orders.delivery.view'],
            builder: (_) => const OrdersScreen(type: 'DELIVERY')),
        Destination(id: 'viagens', label: 'Viagens', icon: 'route', anyOf: const ['trips.view'], builder: (_) => const TripsScreen()),
        Destination(id: 'veiculos', label: 'Veículos', icon: 'car', anyOf: const ['vehicles.view'], builder: (_) => const VehiclesScreen()),
      ],
    );

Map<String, dynamic> trip({String state = 'DRAFT', int version = 1}) => {
      'id': 't1', 'number': 82, 'state': state, 'planned_date': '2026-11-17', 'notes': null, 'vehicle_id': null, 'plate': null,
      'vehicle_model': null, 'capacity_quantity': null, 'capacity_unit': null, 'load': null, 'driver_id': null, 'driver_name': null,
      'departed_at': null, 'departure_odometer': null, 'returned_at': null, 'return_odometer': null, 'distance_km': null,
      'returned_by_name': null, 'cash_collected': null, 'cash_expenses': null, 'cash_expected': null, 'cash_declared': null,
      'cash_difference': null, 'return_note': null, 'reconciled_at': null, 'reconciled_by_name': null, 'reconcile_note': null,
      'cancel_reason': null, 'created_by_name': 'Marcos', 'created_at': '2026-11-17T12:00:00Z', 'version': version,
      'stops': [], 'incidents': [],
    };

Map<String, dynamic> vehicle() => {
      'id': 'v1', 'plate': 'ABC1D23', 'model': 'Fiorino', 'status': 'ACTIVE', 'odometer_km': '45200.0', 'next_service_km': null,
      'alerts': [], 'consumption': {'state': 'INSUFFICIENT_DATA'}, 'current_trip_number': null, 'capacity_quantity': '100.000000',
      'capacity_unit': 'DUZIA',
    };

SessionController _session(List<String> caps, Future<http.Response> Function(http.Request r) api) => SessionController(
      auth: AuthSession(authenticator: FakeAuth(), store: MemorySecureStore()),
      apiFactory: (tokens) => ApiClient(
          baseUrl: Uri.parse('http://api.teste.invalid'),
          tokenProvider: tokens,
          httpClient: MockClient((r) async => r.url.path == '/api/v1/me/capabilities' ? j(200, meJson(caps, client: 'engenheiro-entregas')) : api(r))),
    );

Future<void> _enter(WidgetTester tester, SessionController s) async {
  tester.view.physicalSize = const Size(1080, 2400);
  tester.view.devicePixelRatio = 3;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(EngenheiroApp(config: entregas(), session: s));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Entrar com segurança'));
  await tester.pumpAndSettle();
}

const coord = ['orders.delivery.view', 'orders.delivery.create', 'trips.view', 'trips.create', 'trips.assign', 'vehicles.view',
  'vehicles.edit_draft'];

void main() {
  testWidgets('despacho: capacidade recusada pelo servidor e nova tentativa usa a MESMA viagem (sem duplicar)', (tester) async {
    var creates = 0;
    var assigns = 0;
    final pago = {...order(pay: 'PAID', paid: '75.00', due: '0.00'), 'id': 'o2', 'number': 1048, 'type': 'DELIVERY',
      'customer_name': 'Mercado Fictício'};
    final s = _session(coord, (r) async {
      switch (r.url.path) {
        case '/api/v1/orders':
          return j(200, {'items': [{...order(), 'type': 'DELIVERY'}, pago], 'next_cursor': null});
        case '/api/v1/vehicles':
          return j(200, {'items': [vehicle()]});
        case '/api/v1/trips/eligible-drivers':
          return j(200, {'items': [{'id': 'd1', 'name': 'Carlos Motorista', 'busy': false}, {'id': 'd2', 'name': 'Roberto', 'busy': true}]});
        case '/api/v1/trips':
          if (r.method == 'POST') {
            creates++;
            return j(201, trip());
          }
          return j(200, {'items': [], 'next_cursor': null});
        case '/api/v1/trips/t1/assignments':
          assigns++;
          expect(r.headers['If-Match'], '"1"');
          if (assigns == 1) {
            return j(422, {'status': 422, 'code': 'VALIDATION_ERROR', 'detail': 'Corrija os campos indicados.',
              'errors': [{'field': 'vehicle_id', 'message': 'Carga 120 dúzias acima da capacidade (100)'}]});
          }
          return j(200, trip(state: 'ASSIGNED', version: 2));
        case '/api/v1/trips/t1':
          return j(200, trip(state: 'ASSIGNED', version: 2));
      }
      return j(404, {});
    });
    await _enter(tester, s);
    await tester.tap(find.text('Viagens').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Organizar viagem'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Pago — não cobrar'), findsOneWidget); // pedido pago aparece como tal
    await tester.tap(find.text('#1047 Ana Santos'));
    await tester.tap(find.text('#1048 Mercado Fictício'));
    await tester.tap(find.widgetWithText(DropdownButtonFormField<String>, 'Veículo *'));
    await tester.pumpAndSettle();
    await tester.tap(find.textContaining('ABC1D23').last);
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(DropdownButtonFormField<String>, 'Motorista *'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Carlos Motorista').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Criar e atribuir viagem'));
    await tester.pumpAndSettle();
    expect(find.text('Carga 120 dúzias acima da capacidade (100)'), findsOneWidget);
    expect(find.textContaining('Viagem V82 criada em rascunho'), findsOneWidget);
    await tester.tap(find.text('Criar e atribuir viagem'));
    await tester.pumpAndSettle();
    expect(creates, 1); // nenhuma viagem duplicada
    expect(assigns, 2);
  });

  testWidgets('manutenção: tipo, valor, oficina e quem pagou; entra pendente', (tester) async {
    Map<String, dynamic>? body;
    final s = _session(coord, (r) async {
      switch (r.url.path) {
        case '/api/v1/orders':
          return j(200, {'items': [], 'next_cursor': null});
        case '/api/v1/vehicles':
          return j(200, {'items': [vehicle()]});
        case '/api/v1/vehicles/v1/maintenance':
          body = jsonDecode(r.body) as Map<String, dynamic>;
          expect(r.headers['Idempotency-Key'], startsWith('app-'));
          return j(201, {'id': 'x1', 'kind': 'MAINTENANCE', 'amount': '350.00', 'state': 'PENDING', 'paid_with': 'COMPANY',
            'occurred_on': '2026-11-17'});
      }
      return j(404, {});
    });
    await _enter(tester, s);
    await tester.tap(find.text('Veículos').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Registrar manutenção ou outro gasto'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Valor (R\$) *'), '350,00');
    await tester.enterText(find.widgetWithText(TextField, 'Odômetro (km)'), '45.500');
    await tester.enterText(find.widgetWithText(TextField, 'Oficina'), 'Oficina Fictícia');
    await tester.enterText(find.widgetWithText(TextField, 'Próxima revisão (km)'), '55.500');
    await tester.tap(find.text('Registrar gasto'));
    await tester.pumpAndSettle();
    expect(body, containsPair('kind', 'MAINTENANCE'));
    expect(body, containsPair('maintenance_type', 'PREVENTIVE'));
    expect(body, containsPair('amount', '350.00'));
    expect(body, containsPair('odometer', '45500'));
    expect(body, containsPair('next_service_km', '55500'));
    expect(body, containsPair('paid_with', 'COMPANY'));
  });

  testWidgets('pedido de entrega: endereço vem do cliente e pagamento combinado é enviado', (tester) async {
    Map<String, dynamic>? body;
    // Coordenação com endereço e contato liberados.
    final session = SessionController(
      auth: AuthSession(authenticator: FakeAuth(), store: MemorySecureStore()),
      apiFactory: (tokens) => ApiClient(baseUrl: Uri.parse('http://api.teste.invalid'), tokenProvider: tokens,
          httpClient: MockClient((r) async {
            if (r.url.path == '/api/v1/me/capabilities') {
              return j(200, meJson([...coord, 'customers.view'], fields: {'delivery_address': true, 'operational_contact': true},
                  client: 'engenheiro-entregas'));
            }
            switch (r.url.path) {
              case '/api/v1/orders':
                if (r.method == 'POST') {
                  body = jsonDecode(r.body) as Map<String, dynamic>;
                  return j(201, {...order(), 'type': 'DELIVERY'});
                }
                return j(200, {'items': [], 'next_cursor': null});
              case '/api/v1/customers':
                return j(200, {'items': [{'id': 'c1', 'name': 'Mercado Fictício', 'branch': 'Matriz', 'contact': '(11) 9',
                  'address': 'Rua Exemplo, 100', 'personal_document': null, 'status': 'ACTIVE', 'version': 1,
                  'created_at': '2026-11-17T12:00:00Z', 'masked_fields': ['personal_document']}], 'next_cursor': null});
              case '/api/v1/products':
                return j(200, {'items': [{'id': 'p1', 'sku': 'CANA', 'name': 'Cana caiana', 'base_unit': 'DUZIA',
                  'base_unit_confirmed': true, 'status': 'ACTIVE', 'version': 1, 'conversions': [],
                  'prices': [{'unit': 'DUZIA', 'price': '7.50'}], 'created_at': '2026-11-17T12:00:00Z'}], 'next_cursor': null});
              case '/api/v1/orders/o1':
                return j(200, {...order(), 'type': 'DELIVERY'});
            }
            return j(404, {});
          })),
    );
    await _enter(tester, session);
    await tester.tap(find.text('Novo pedido'));
    await tester.pumpAndSettle();
    expect(find.text('Novo pedido de entrega'), findsOneWidget);
    await tester.tap(find.widgetWithText(DropdownButtonFormField<String>, 'Cliente *'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Mercado Fictício').last);
    await tester.pumpAndSettle();
    expect(find.widgetWithText(TextField, 'Rua Exemplo, 100'), findsOneWidget); // endereço sugerido do cadastro
    await tester.tap(find.widgetWithText(DropdownButtonFormField<String>, 'Pagamento combinado *'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Pix').last);
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(DropdownButtonFormField<String>, 'Produto *'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Cana caiana').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Quantidade *'), '10');
    await tester.dragUntilVisible(find.text('Salvar e reservar'), find.byType(ListView).last, const Offset(0, -300));
    await tester.tap(find.text('Salvar e reservar'));
    await tester.pumpAndSettle();
    expect(body!['type'], 'DELIVERY');
    expect(body!['delivery'], {'address': 'Rua Exemplo, 100', 'contact': '(11) 9', 'agreed_payment': 'PIX'});
  });
}

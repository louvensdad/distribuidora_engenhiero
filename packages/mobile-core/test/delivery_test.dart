import 'dart:convert';
import 'dart:typed_data';
import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_auth/engenheiro_auth.dart';
import 'package:engenheiro_core/engenheiro_core.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'app_test.dart' show FakeAuth, j, meJson;

AppConfig entregas() => AppConfig(
      appName: 'Engenheiro Entregas',
      applicationId: 'br.com.engenheiro.entregas',
      clientId: 'engenheiro-entregas',
      audienceDescription: 'Entregas, motoristas e veículos',
      apiBaseUrl: Uri.parse('http://api.teste.invalid'),
      issuer: 'http://idp.teste.invalid/realms/engenheiro',
      destinations: [
        Destination(id: 'viagens', label: 'Viagens', icon: 'route', anyOf: const ['trips.view'], builder: (_) => const TripsScreen()),
        Destination(id: 'veiculos', label: 'Veículos', icon: 'car', anyOf: const ['vehicles.view'], builder: (_) => const VehiclesScreen()),
      ],
    );

Map<String, dynamic> line(String remaining) => {'line_no': 1, 'product_id': 'p1', 'sku': 'CANA', 'product_name': 'Cana caiana',
  'unit': 'DUZIA', 'factor': '1', 'quantity': '24', 'base_quantity': '24', 'base_unit': 'DUZIA', 'unit_price': '7.50',
  'gross': '180.00', 'discount': '0.00', 'net': '180.00', 'fulfilled_quantity': '0', 'cancelled_quantity': '0',
  'returned_quantity': '0', 'remaining_quantity': remaining};

Map<String, dynamic> stop(String id, int seq, int number, String payment, String balance, {List<String> masked = const []}) => {
      'id': id, 'seq': seq, 'state': 'PENDING', 'recipient_name': null, 'delivered_at': null, 'delivered_by_name': null, 'load': '24',
      'incidents': [],
      'order': {'id': 'o$number', 'number': number, 'customer_name': 'Cliente $number', 'state': 'CONFIRMED', 'payment_state': payment,
        'effective_total': '180.00', 'paid_amount': payment == 'PAID' ? '180.00' : '0.00', 'pending_amount': '0.00', 'on_account': '0.00',
        'balance_due': balance, 'lines': [line('24')], 'masked_fields': masked,
        'delivery': {'address': masked.isEmpty ? 'Rua Exemplo, 100' : null, 'reference': null, 'contact': masked.isEmpty ? '(11) 9' : null,
          'window_start': null, 'window_end': null, 'agreed_payment': payment == 'PAID' ? 'PIX' : 'CASH'}},
    };

Map<String, dynamic> trip(String state) => {
      'id': 't1', 'number': 82, 'state': state, 'planned_date': '2026-11-16', 'vehicle_id': 'v1', 'plate': 'ABC1D23',
      'vehicle_model': 'Fiorino', 'driver_name': 'Carlos', 'departure_odometer': state == 'ASSIGNED' ? null : '45200.0',
      'capacity_unit': 'DUZIA', 'capacity_quantity': '100', 'load': '48', 'version': 3,
      'stops': [stop('s1', 1, 1048, 'PAID', '0.00'), stop('s2', 2, 1046, 'UNPAID', '180.00', masked: ['delivery.address', 'delivery.reference', 'delivery.contact'])],
    };

Future<void> login(WidgetTester tester, List<String> caps, Future<http.Response> Function(http.Request) server) async {
  tester.view.physicalSize = const Size(1080, 2400);
  tester.view.devicePixelRatio = 3;
  addTearDown(tester.view.reset);
  final session = SessionController(
    auth: AuthSession(authenticator: FakeAuth(), store: MemorySecureStore()),
    apiFactory: (tokens) => ApiClient(baseUrl: Uri.parse('http://api.teste.invalid'), tokenProvider: tokens,
        httpClient: MockClient((r) async => r.url.path == '/api/v1/me/capabilities'
            ? j(200, meJson(caps, fields: {'delivery_address': true, 'operational_contact': true}))
            : server(r))),
  );
  await tester.pumpWidget(EngenheiroApp(config: entregas(), session: session));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Entrar com segurança'));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('motorista: saída com odômetro; parada paga não é cobrada; endereço oculto aparece como oculto', (tester) async {
    http.Request? depart;
    var state = 'ASSIGNED';
    await login(tester, ['trips.view', 'trips.confirm'], (r) async {
      switch (r.url.path) {
        case '/api/v1/trips':
          return j(200, {'items': [{'id': 't1', 'number': 82, 'state': state, 'planned_date': '2026-11-16', 'plate': 'ABC1D23',
            'driver_name': 'Carlos', 'stops': 2, 'delivered': 0, 'created_at': '2026-11-16T12:00:00Z', 'version': 3}], 'next_cursor': null});
        case '/api/v1/trips/t1':
          return j(200, trip(state));
        case '/api/v1/trips/t1/depart':
          depart = r;
          state = 'DEPARTED';
          return j(200, trip('DEPARTED'));
      }
      return j(404, {'status': 404, 'code': 'NOT_FOUND'});
    });
    expect(find.text('Viagem V82 · ABC1D23'), findsOneWidget);
    await tester.tap(find.text('Viagem V82 · ABC1D23'));
    await tester.pumpAndSettle();
    expect(find.text('Pago — não cobrar novamente'), findsOneWidget);
    expect(find.text('Cobrar R\$ 180,00 (Dinheiro)'), findsOneWidget);
    expect(find.text('oculto'), findsNWidgets(2));
    await tester.enterText(find.widgetWithText(TextField, 'Odômetro de saída (km) *'), '45.210');
    await tester.tap(find.text('Registrar saída').last);
    await tester.pumpAndSettle();
    expect(depart!.headers['If-Match'], '"3"');
    expect(jsonDecode(depart!.body), {'odometer': '45210'});
    expect(find.text('Em rota'), findsOneWidget);
    expect(find.text('Toque para entregar ou registrar ocorrência'), findsNWidgets(2));
  });

  testWidgets('entrega com dinheiro vai com chave idempotente; parcial exige motivo; ocorrência não entrega', (tester) async {
    final posts = <http.Request>[];
    http.BaseRequest? upload;
    attachmentPicker = ({required bool camera}) async =>
        (name: 'canhoto.jpg', bytes: Uint8List.fromList([0xFF, 0xD8, 0xFF, 0xD9]), mime: 'image/jpeg');
    await login(tester, ['trips.view', 'trips.confirm', 'payments.confirm'], (r) async {
      if (r.method == 'POST' && r.url.path.endsWith('/attachments')) {
        upload = r;
        return j(201, {'id': 'd1', 'file_name': 'canhoto.jpg', 'mime': 'image/jpeg', 'size_bytes': 2048, 'sha256': '0' * 64,
          'state': 'AVAILABLE', 'scan_state': 'x', 'rejection': null, 'created_by_name': 'Carlos', 'created_at': '2026-11-16T12:00:00Z'});
      }
      if (r.method == 'POST') {
        posts.add(r);
        if (r.url.path.endsWith('/deliveries') && (jsonDecode(r.body) as Map)['partial_reason'] == null && (jsonDecode(r.body) as Map)['lines'] != null) {
          return j(422, {'status': 422, 'code': 'VALIDATION_ERROR', 'detail': 'Entrega parcial exige o motivo (gera ocorrência).',
            'errors': [{'field': 'partial_reason', 'message': 'Informe o motivo da entrega parcial'}]});
        }
        return j(200, trip('DEPARTED'));
      }
      if (r.url.path == '/api/v1/trips') {
        return j(200, {'items': [{'id': 't1', 'number': 82, 'state': 'DEPARTED', 'planned_date': '2026-11-16', 'plate': 'ABC1D23',
          'driver_name': 'Carlos', 'stops': 2, 'delivered': 0, 'created_at': '2026-11-16T12:00:00Z', 'version': 3}], 'next_cursor': null});
      }
      return j(200, trip('DEPARTED'));
    });
    await tester.tap(find.text('Viagem V82 · ABC1D23'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('2. #1046 Cliente 1046'));
    await tester.pumpAndSettle();
    expect(find.text('Receber R\$ 180,00'), findsOneWidget);
    // Evidência: foto do canhoto enviada em multipart para a parada.
    await tester.scrollUntilVisible(find.text('Fotografar canhoto'), 200, scrollable: find.descendant(of: find.byType(StopScreen), matching: find.byType(Scrollable)).first);
    await tester.tap(find.text('Fotografar canhoto'));
    await tester.pumpAndSettle();
    expect(upload!.url.path, '/api/v1/trips/t1/stops/s2/attachments');
    expect(latin1.decode((upload! as http.Request).bodyBytes), contains('filename="canhoto.jpg"'));
    expect(find.text('Anexado: canhoto.jpg'), findsOneWidget);
    await tester.scrollUntilVisible(find.widgetWithText(TextField, 'Quem recebeu *'), -300, scrollable: find.descendant(of: find.byType(StopScreen), matching: find.byType(Scrollable)).first);
    await tester.enterText(find.widgetWithText(TextField, 'Quem recebeu *'), 'João');
    // Parcial: muda a quantidade → aparece o motivo; sem motivo o servidor recusa e o erro aparece no campo.
    await tester.enterText(find.widgetWithText(TextField, 'Cana caiana entregue (dúzias)'), '12');
    await tester.pumpAndSettle();
    expect(find.widgetWithText(TextField, 'Motivo da entrega parcial *'), findsOneWidget);
    await tester.enterText(find.widgetWithText(TextField, 'Dinheiro apresentado (R\$) *'), '200,00');
    await tester.tap(find.text('Confirmar entrega').last);
    await tester.pumpAndSettle();
    expect(find.text('Informe o motivo da entrega parcial'), findsOneWidget);
    final first = jsonDecode(posts.last.body) as Map<String, dynamic>;
    expect(first['lines'], [{'line_no': 1, 'quantity': '12'}]);
    expect(first['payment'], {'parts': [{'method': 'CASH', 'amount': '180.00', 'presented_cash': '200.00'}]});
    final key = posts.last.headers['Idempotency-Key'];
    expect(key, matches(RegExp(r'^app-[0-9a-f]{32}$')));
    await tester.enterText(find.widgetWithText(TextField, 'Motivo da entrega parcial *'), 'Sem espaço no depósito');
    await tester.tap(find.text('Confirmar entrega').last);
    await tester.pumpAndSettle();
    expect(posts.last.headers['Idempotency-Key'], key); // mesma intenção, mesma chave
    expect((jsonDecode(posts.last.body) as Map)['partial_reason'], 'Sem espaço no depósito');
    // Volta para a viagem (espera o aviso da foto sumir); ocorrência na parada 2 de novo.
    await tester.pump(const Duration(seconds: 5));
    await tester.pumpAndSettle();
    await tester.tap(find.text('2. #1046 Cliente 1046'));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.text('Salvar ocorrência'), 300, scrollable: find.descendant(of: find.byType(StopScreen), matching: find.byType(Scrollable)).first);
    await tester.enterText(find.widgetWithText(TextField, 'O que aconteceu *'), 'Portão fechado');
    await tester.tap(find.text('Salvar ocorrência'));
    await tester.pumpAndSettle();
    final inc = posts.last;
    expect(inc.url.path, '/api/v1/trips/t1/incidents');
    expect(jsonDecode(inc.body), {'stop_id': 's2', 'kind': 'CUSTOMER_ABSENT', 'description': 'Portão fechado', 'requested_action': 'RESCHEDULE'});
  });

  testWidgets('abastecimento: dinheiro do motorista, tanque cheio e odômetro em pt-BR', (tester) async {
    http.Request? fuel;
    await login(tester, ['vehicles.view', 'fuel.create'], (r) async {
      if (r.url.path == '/api/v1/vehicles') {
        return j(200, {'items': [{'id': 'v1', 'plate': 'ABC1D23', 'model': 'Fiorino', 'status': 'ACTIVE', 'odometer_km': '45200.0',
          'next_service_km': null, 'alerts': [], 'current_trip_number': 82,
          'consumption': {'state': 'INSUFFICIENT_DATA', 'km_per_liter': null, 'message': 'Consumo exige ao menos dois abastecimentos de tanque cheio.'}}]});
      }
      if (r.url.path == '/api/v1/vehicles/v1/fuel') {
        fuel = r;
        return j(201, {'id': 'e1', 'kind': 'FUEL', 'amount': '180.00', 'state': 'PENDING', 'paid_with': 'DRIVER_CASH', 'occurred_on': '2026-11-16'});
      }
      return j(404, {});
    });
    await tester.tap(find.text('Veículos').last);
    await tester.pumpAndSettle();
    expect(find.text('Dados insuficientes'), findsOneWidget);
    await tester.tap(find.text('Registrar abastecimento'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Odômetro (km) *'), '45.260');
    await tester.enterText(find.widgetWithText(TextField, 'Litros *'), '30,5');
    await tester.enterText(find.widgetWithText(TextField, 'Valor pago (R\$) *'), '180,00');
    await tester.tap(find.text('Registrar gasto'));
    await tester.pumpAndSettle();
    expect(jsonDecode(fuel!.body), {'odometer': '45260', 'liters': '30.5', 'fuel_type': 'DIESEL', 'full_tank': true, 'amount': '180.00',
      'paid_with': 'DRIVER_CASH'});
    expect(fuel!.headers['Idempotency-Key'], matches(RegExp(r'^app-')));
    expect(find.text('Abastecimento registrado — aguardando aprovação.'), findsOneWidget);
  });
}

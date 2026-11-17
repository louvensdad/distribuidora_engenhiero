import 'dart:convert';
import 'dart:io';
import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_auth/engenheiro_auth.dart';
import 'package:engenheiro_core/engenheiro_core.dart';
import 'package:entregas/main.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:material_ui/material_ui.dart';

/// F12 (APP-E-01/02): coordenação e motorista no MESMO app, com funções separadas pelo que o servidor libera;
/// acesso revogado no meio do uso leva à tela de sem acesso; sem seletor de perfil.
class _Auth implements Authenticator {
  @override
  Future<TokenSet> login() async =>
      TokenSet(accessToken: 't', expiresAt: DateTime.now().add(const Duration(hours: 1)), refreshToken: 'r', idToken: 'i');
  @override
  Future<TokenSet> refresh(String refreshToken) async => throw RefreshRejected();
  @override
  Future<void> endSession(String? idToken) async {}
}

http.Response _j(Object body, [int status = 200]) =>
    http.Response.bytes(utf8.encode(jsonEncode(body)), status, headers: {'content-type': 'application/json'});

Map<String, dynamic> _me(String template, List<String> caps) => {
      'user': {'id': 'u1', 'display_name': '$template Fictício'},
      'organization': {'id': 'o', 'name': 'Distribuidora de Cana Engenheiro Ltda'},
      'branch': {'id': 'b', 'name': 'Matriz'},
      'client_id': 'engenheiro-entregas', 'admin': false, 'role_template': template, 'sector': 'ENTREGAS', 'policy_version': 1,
      'capabilities': [
        for (final c in caps)
          {'capability': c, 'resource': c.substring(0, c.lastIndexOf('.')), 'action': c.substring(c.lastIndexOf('.') + 1),
            'scopes': ['ASSIGNED'], 'max_amount': null}
      ],
      'fields': <String, bool>{},
    };

Future<void> _open(WidgetTester tester, Future<http.Response> Function(http.Request) api) async {
  tester.view.physicalSize = const Size(400, 860);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final session = SessionController(
    auth: AuthSession(authenticator: _Auth(), store: MemorySecureStore()),
    apiFactory: (tokens) => ApiClient(baseUrl: Uri.parse('http://api.teste.invalid'), tokenProvider: tokens, httpClient: MockClient(api)),
  );
  await tester.pumpWidget(EngenheiroApp(config: config, session: session));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Entrar com segurança'));
  await tester.pumpAndSettle();
}

const coordenacao = ['orders.delivery.view', 'orders.delivery.create', 'trips.view', 'trips.create', 'trips.assign', 'vehicles.view',
  'customers.view'];
const motorista = ['trips.view', 'trips.confirm', 'vehicles.view', 'fuel.create', 'own_cash.view'];

void main() {
  testWidgets('coordenação: pedidos, viagens (com "Organizar viagem"), veículos e clientes; sem caixa', (tester) async {
    await _open(tester, (r) async => switch (r.url.path) {
          '/api/v1/me/capabilities' => _j(_me('COORDENADOR_ENTREGAS', coordenacao)),
          _ => _j({'items': [], 'next_cursor': null}),
        });
    final nav = find.byType(NavigationBar);
    for (final t in ['Pedidos', 'Viagens', 'Veículos', 'Clientes']) {
      expect(find.descendant(of: nav, matching: find.text(t)), findsOneWidget);
    }
    expect(find.descendant(of: nav, matching: find.text('Caixa')), findsNothing);
    await tester.tap(find.descendant(of: nav, matching: find.text('Viagens')));
    await tester.pumpAndSettle();
    expect(find.text('Organizar viagem'), findsOneWidget);
  });

  testWidgets('motorista: viagens, veículos e caixa — sem pedidos, clientes nem despacho', (tester) async {
    await _open(tester, (r) async => switch (r.url.path) {
          '/api/v1/me/capabilities' => _j(_me('MOTORISTA', motorista)),
          '/api/v1/cash-sessions/current' => _j({'session': null}),
          _ => _j({'items': [], 'next_cursor': null}),
        });
    final nav = find.byType(NavigationBar);
    for (final t in ['Viagens', 'Veículos', 'Caixa']) {
      expect(find.descendant(of: nav, matching: find.text(t)), findsOneWidget);
    }
    for (final t in ['Pedidos', 'Clientes']) {
      expect(find.descendant(of: nav, matching: find.text(t)), findsNothing);
    }
    expect(find.text('Organizar viagem'), findsNothing);
    expect(find.text('Nenhuma viagem atribuída a você'), findsOneWidget);
  });

  testWidgets('acesso revogado no meio do uso: a próxima carga mostra "sem permissão", sem dados antigos', (tester) async {
    var revoked = false;
    await _open(tester, (r) async {
      if (r.url.path == '/api/v1/me/capabilities') return _j(_me('MOTORISTA', motorista));
      if (revoked) return _j({'status': 403, 'code': 'FORBIDDEN'}, 403);
      if (r.url.path == '/api/v1/trips') {
        return _j({'items': [{'id': 't1', 'number': 82, 'state': 'DEPARTED', 'planned_date': '2026-11-17', 'plate': 'ABC1D23',
          'driver_name': 'Carlos', 'stops': 2, 'delivered': 0}], 'next_cursor': null});
      }
      return _j({'items': [], 'next_cursor': null});
    });
    expect(find.text('Viagem V82 · ABC1D23'), findsOneWidget);
    revoked = true;
    await tester.fling(find.text('Viagem V82 · ABC1D23'), const Offset(0, 400), 1000);
    await tester.pumpAndSettle();
    expect(find.text('Viagem V82 · ABC1D23'), findsNothing);
    expect(find.textContaining('permiss'), findsWidgets);
  });

  test('sem seletor de perfil; destinos só por capacidade; no máximo cinco', () {
    final src = File('lib/main.dart').readAsStringSync().toLowerCase();
    for (final proibido in ['escolher perfil', 'entrar como', 'modo patrão', 'modo motorista', 'role_template ==']) {
      expect(src, isNot(contains(proibido)));
    }
    expect(config.destinations.every((d) => d.anyOf.isNotEmpty), isTrue);
    expect(config.destinations.length, lessThanOrEqualTo(5));
    expect(config.destinations.any((d) => d.pendingPhase != null), isFalse); // despacho não é mais "em implementação"
  });
}

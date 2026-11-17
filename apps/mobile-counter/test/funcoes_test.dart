import 'dart:convert';
import 'dart:io';
import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_auth/engenheiro_auth.dart';
import 'package:engenheiro_core/engenheiro_core.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:material_ui/material_ui.dart';
import 'package:retirada/main.dart';

/// F11 (APP-R-02/03): balconista e recebedor no MESMO app, com funções separadas pelo que o servidor libera;
/// tablet com trilho lateral; nenhum seletor de perfil/patrão/contador no código do app.
class _Auth implements Authenticator {
  @override
  Future<TokenSet> login() async =>
      TokenSet(accessToken: 't', expiresAt: DateTime.now().add(const Duration(hours: 1)), refreshToken: 'r', idToken: 'i');
  @override
  Future<TokenSet> refresh(String refreshToken) async => throw RefreshRejected();
  @override
  Future<void> endSession(String? idToken) async {}
}

http.Response _j(Object body) => http.Response.bytes(utf8.encode(jsonEncode(body)), 200, headers: {'content-type': 'application/json'});

Map<String, dynamic> _me(String template, List<String> caps) => {
      'user': {'id': 'u1', 'display_name': '$template Fictício'},
      'organization': {'id': 'o', 'name': 'Distribuidora de Cana Engenheiro Ltda'},
      'branch': {'id': 'b', 'name': 'Matriz'},
      'client_id': 'engenheiro-retirada', 'admin': false, 'role_template': template, 'sector': 'RETIRADA', 'policy_version': 1,
      'capabilities': [
        for (final c in caps)
          {'capability': c, 'resource': c.substring(0, c.lastIndexOf('.')), 'action': c.substring(c.lastIndexOf('.') + 1),
            'scopes': ['BRANCH'], 'max_amount': null}
      ],
      'fields': <String, bool>{},
    };

Future<void> _open(WidgetTester tester, Map<String, dynamic> me, Size size) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final session = SessionController(
    auth: AuthSession(authenticator: _Auth(), store: MemorySecureStore()),
    apiFactory: (tokens) => ApiClient(baseUrl: Uri.parse('http://api.teste.invalid'), tokenProvider: tokens,
        httpClient: MockClient((r) async => switch (r.url.path) {
              '/api/v1/me/capabilities' => _j(me),
              '/api/v1/cash-sessions/current' => _j({'session': null}),
              _ => _j({'items': [], 'next_cursor': null}),
            })),
  );
  await tester.pumpWidget(EngenheiroApp(config: config, session: session));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Entrar com segurança'));
  await tester.pumpAndSettle();
}

const balconista = ['customers.view', 'orders.pickup.view', 'orders.pickup.create', 'inventory.stock.view', 'own_cash.view'];
const recebedor = ['suppliers.view', 'inventory.receipts.view', 'inventory.receipts.create', 'inventory.stock.view'];

void main() {
  testWidgets('balconista: atendimento, pedidos, estoque e caixa — sem fornecedores', (tester) async {
    await _open(tester, _me('BALCAO', balconista), const Size(400, 860));
    final nav = find.byType(NavigationBar);
    for (final t in ['Atendimento', 'Pedidos', 'Estoque', 'Caixa']) {
      expect(find.descendant(of: nav, matching: find.text(t)), findsOneWidget);
    }
    expect(find.descendant(of: nav, matching: find.text('Fornecedores')), findsNothing);
  });

  testWidgets('recebedor no mesmo app: fornecedores e estoque — sem pedidos, caixa ou atendimento', (tester) async {
    await _open(tester, _me('RECEBIMENTO', recebedor), const Size(400, 860));
    final nav = find.byType(NavigationBar);
    expect(find.descendant(of: nav, matching: find.text('Fornecedores')), findsOneWidget);
    expect(find.descendant(of: nav, matching: find.text('Estoque')), findsOneWidget);
    for (final t in ['Pedidos', 'Caixa', 'Atendimento']) {
      expect(find.descendant(of: nav, matching: find.text(t)), findsNothing);
    }
  });

  testWidgets('tablet (1280×800): trilho lateral com as funções do balcão', (tester) async {
    await _open(tester, _me('BALCAO', balconista), const Size(1280, 800));
    expect(find.byType(NavigationRail), findsOneWidget);
    expect(find.byType(NavigationBar), findsNothing);
    expect(find.descendant(of: find.byType(NavigationRail), matching: find.text('Caixa')), findsOneWidget);
  });

  test('sem seletor de perfil, de patrão ou de contador no app (a função vem só do servidor)', () {
    final src = [
      File('lib/main.dart').readAsStringSync(),
      for (final f in Directory('../../packages/mobile-core/lib').listSync(recursive: true).whereType<File>())
        if (f.path.endsWith('.dart')) f.readAsStringSync(),
    ].join('\n').toLowerCase();
    for (final proibido in ['escolher perfil', 'trocar perfil', 'entrar como', 'selecionar perfil', 'modo patrão', 'modo contador',
      'role_template ==', "roletemplate == 'admin'"]) {
      expect(src, isNot(contains(proibido)), reason: proibido);
    }
    // Os destinos dependem só de capacidades (nenhum depende do nome do modelo de perfil).
    expect(config.destinations.every((d) => d.anyOf.isNotEmpty), isTrue);
  });
}

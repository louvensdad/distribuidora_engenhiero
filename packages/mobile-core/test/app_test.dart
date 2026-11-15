import 'dart:convert';
import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_auth/engenheiro_auth.dart';
import 'package:engenheiro_core/engenheiro_core.dart';
import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

class FakeAuth implements Authenticator {
  int logins = 0;
  bool cancel = false;
  @override
  Future<TokenSet> login() async {
    if (cancel) throw LoginCancelled();
    logins++;
    return TokenSet(accessToken: 'tok', expiresAt: DateTime.now().add(const Duration(hours: 1)), refreshToken: 'r', idToken: 'i');
  }

  @override
  Future<TokenSet> refresh(String refreshToken) async => throw RefreshRejected();
  @override
  Future<void> endSession(String? idToken) async {}
}

http.Response j(int status, Object body) =>
    http.Response.bytes(utf8.encode(jsonEncode(body)), status, headers: {'content-type': 'application/json'});

Map<String, dynamic> meJson(List<String> caps, {Map<String, bool> fields = const {}, String client = 'engenheiro-retirada'}) => {
      'user': {'id': 'u1', 'display_name': 'Balcão Teste'},
      'organization': {'id': 'o', 'name': 'Distribuidora de Cana Engenheiro Ltda'},
      'branch': {'id': 'b', 'name': 'Matriz'},
      'client_id': client, 'admin': false, 'role_template': 'BALCAO', 'sector': 'RETIRADA', 'policy_version': 1,
      'capabilities': [
        for (final c in caps)
          {'capability': c, 'resource': c.substring(0, c.lastIndexOf('.')), 'action': c.substring(c.lastIndexOf('.') + 1), 'scopes': ['BRANCH'], 'max_amount': null}
      ],
      'fields': fields,
    };

AppConfig config() => AppConfig(
      appName: 'Engenheiro Retirada',
      applicationId: 'br.com.engenheiro.retirada',
      clientId: 'engenheiro-retirada',
      audienceDescription: 'Balcão e recebimento de fornecedores',
      apiBaseUrl: Uri.parse('http://api.teste.invalid'),
      issuer: 'http://idp.teste.invalid/realms/engenheiro',
      destinations: [
        Destination(id: 'atendimento', label: 'Atendimento', icon: 'users', anyOf: const ['customers.view'], builder: (_) => const CustomersScreen()),
        Destination(id: 'pedidos', label: 'Pedidos', icon: 'order', anyOf: const ['orders.pickup.view'], pendingPhase: 'F04',
            builder: (_) => const PendingModuleView(title: 'Pedidos de retirada', phase: 'F04')),
        Destination(id: 'caixa', label: 'Caixa', icon: 'cash', anyOf: const ['own_cash.view'], pendingPhase: 'F04',
            builder: (_) => const PendingModuleView(title: 'Caixa', phase: 'F04')),
      ],
    );

Future<(FakeAuth, SessionController)> pump(WidgetTester tester, Future<http.Response> Function(http.Request) server) async {
  final auth = FakeAuth();
  final session = SessionController(
    auth: AuthSession(authenticator: auth, store: MemorySecureStore()),
    apiFactory: (tokens) => ApiClient(baseUrl: Uri.parse('http://api.teste.invalid'), tokenProvider: tokens, httpClient: MockClient(server)),
  );
  await tester.pumpWidget(EngenheiroApp(config: config(), session: session));
  await tester.pumpAndSettle();
  return (auth, session);
}

void main() {
  testWidgets('sem sessão salva mostra a entrada com o nome do app e da empresa', (tester) async {
    await pump(tester, (_) async => j(500, {}));
    expect(find.text('Engenheiro Retirada'), findsOneWidget);
    expect(find.text(engBrandLegalName), findsOneWidget);
    expect(find.text('Entrar com segurança'), findsOneWidget);
  });

  testWidgets('login abre o shell só com destinos permitidos e rotula módulo pendente', (tester) async {
    final (auth, _) = await pump(tester, (r) async {
      if (r.url.path == '/api/v1/me/capabilities') return j(200, meJson(['customers.view', 'orders.pickup.view'], fields: {'operational_contact': true}));
      if (r.url.path == '/api/v1/customers') {
        return j(200, {
          'items': [{'id': 'c1', 'name': 'Mercado Exemplo', 'branch': 'Matriz', 'contact': '(11) 9', 'address': null, 'personal_document': null,
              'status': 'ACTIVE', 'version': 1, 'created_at': '2026-11-14T12:00:00Z', 'masked_fields': ['address', 'personal_document']}],
          'next_cursor': null,
        });
      }
      return j(404, {'status': 404, 'code': 'NOT_FOUND'});
    });
    await tester.tap(find.text('Entrar com segurança'));
    await tester.pumpAndSettle();
    expect(auth.logins, 1);

    final nav = find.byType(NavigationBar);
    expect(find.descendant(of: nav, matching: find.text('Atendimento')), findsOneWidget);
    expect(find.descendant(of: nav, matching: find.text('Pedidos')), findsOneWidget);
    expect(find.descendant(of: nav, matching: find.text('Caixa')), findsNothing); // sem own_cash.view

    expect(find.text('Mercado Exemplo'), findsOneWidget);
    expect(find.text('oculto'), findsNWidgets(2));
    expect(find.text('Novo cliente'), findsNothing); // sem customers.create

    await tester.tap(find.descendant(of: nav, matching: find.text('Pedidos')));
    await tester.pumpAndSettle();
    expect(find.text('Pedidos de retirada: em implementação'), findsOneWidget);
  });

  testWidgets('conta não liberada para este app (403) mostra sem acesso e permite sair', (tester) async {
    await pump(tester, (r) async => j(403, {'status': 403, 'code': 'FORBIDDEN', 'request_id': 'rid-1'}));
    await tester.tap(find.text('Entrar com segurança'));
    await tester.pumpAndSettle();
    expect(find.textContaining('não tem acesso ao Engenheiro Retirada'), findsOneWidget);
    expect(find.textContaining('rid-1'), findsOneWidget);
    await tester.tap(find.text('Sair desta conta'));
    await tester.pumpAndSettle();
    expect(find.text('Entrar com segurança'), findsOneWidget);
  });

  testWidgets('login cancelado volta à entrada sem erro', (tester) async {
    final (auth, _) = await pump(tester, (_) async => j(500, {}));
    auth.cancel = true;
    await tester.tap(find.text('Entrar com segurança'));
    await tester.pumpAndSettle();
    expect(find.text('Entrar com segurança'), findsOneWidget);
    expect(find.textContaining('Não foi possível'), findsNothing);
  });

  testWidgets('sem nenhuma função liberada não mostra navegação', (tester) async {
    await pump(tester, (r) async => j(200, meJson(const [])));
    await tester.tap(find.text('Entrar com segurança'));
    await tester.pumpAndSettle();
    expect(find.byType(NavigationBar), findsNothing);
    expect(find.textContaining('ainda não tem nenhuma função liberada'), findsOneWidget);
  });

  testWidgets('tablet usa trilho lateral', (tester) async {
    tester.view.physicalSize = const Size(1280, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await pump(tester, (r) async => r.url.path == '/api/v1/me/capabilities'
        ? j(200, meJson(['customers.view', 'orders.pickup.view']))
        : j(200, {'items': [], 'next_cursor': null}));
    await tester.tap(find.text('Entrar com segurança'));
    await tester.pumpAndSettle();
    expect(find.byType(NavigationRail), findsOneWidget);
    expect(find.byType(NavigationBar), findsNothing);
  });

  test('no máximo cinco destinos', () {
    Destination d(int i) => Destination(id: '$i', label: '$i', icon: 'home', builder: (_) => const SizedBox());
    expect(
        () => AppConfig(appName: 'x', applicationId: 'x', clientId: 'x', audienceDescription: 'x', destinations: [for (var i = 0; i < 6; i++) d(i)]),
        throwsA(isA<AssertionError>()));
  });
}

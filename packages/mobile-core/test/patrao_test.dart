import 'dart:convert';

import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_auth/engenheiro_auth.dart';
import 'package:engenheiro_core/engenheiro_core.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'app_test.dart' show FakeAuth, j, meJson;

/// F14 — app Patrão: decisões com versão e idempotência, mapa de acessos, movimento com o que mudou, origem e histórico.
Future<void> loginOwner(
  WidgetTester tester,
  Widget Function(BuildContext) screen,
  List<String> caps,
  Future<http.Response> Function(http.Request) server, {
  bool admin = true,
}) async {
  tester.view.physicalSize = const Size(1080, 2400);
  tester.view.devicePixelRatio = 3;
  addTearDown(tester.view.reset);
  final session = SessionController(
    auth: AuthSession(authenticator: FakeAuth(), store: MemorySecureStore()),
    apiFactory: (tokens) => ApiClient(
      baseUrl: Uri.parse('http://api.teste.invalid'),
      tokenProvider: tokens,
      httpClient: MockClient(
        (r) async => r.url.path == '/api/v1/me/capabilities'
            ? j(200, {
                ...meJson(caps, client: 'engenheiro-gestao'),
                'admin': admin,
                'user': {'id': 'dono', 'display_name': 'Patrão'},
              })
            : server(r),
      ),
    ),
  );
  await tester.pumpWidget(
    EngenheiroApp(
      config: AppConfig(
        appName: 'Engenheiro Patrão',
        applicationId: 'br.com.engenheiro.gestao',
        clientId: 'engenheiro-gestao',
        audienceDescription: 'Patrão',
        apiBaseUrl: Uri.parse('http://api.teste.invalid'),
        issuer: 'http://idp.teste.invalid/realms/engenheiro',
        destinations: [
          Destination(id: 'x', label: 'Tela', icon: 'home', builder: screen),
        ],
      ),
      session: session,
    ),
  );
  await tester.pumpAndSettle();
  await tester.tap(find.text('Entrar com segurança'));
  await tester.pumpAndSettle();
}

Future<void> scrollTo(
  WidgetTester tester,
  Finder f, {
  double delta = 300,
}) async {
  // A lista da tela (campos de texto também têm Scrollable interno).
  await tester.scrollUntilVisible(
    f,
    delta,
    scrollable: find
        .descendant(
          of: find.byType(ListView).last,
          matching: find.byType(Scrollable),
        )
        .first,
  );
  await tester.pumpAndSettle();
}

Map<String, dynamic> taxJson(String state, int version) => {
  'id': 'g1',
  'kind': 'DAS',
  'description': 'DAS do mês',
  'competence': '2026-11',
  'due_date': '2026-11-20',
  'amount': '850.00',
  'state': state,
  'payable_state': state == 'APPROVED' ? 'OPEN' : null,
  'created_by_name': 'Fernanda',
  'submitted_by_name': 'Fernanda',
  'decided_by_name': null,
  'decided_note': null,
  'attachments': [],
  'version': version,
};

void main() {
  testWidgets(
    'aprovações: guia aprovada com observação, versão e chave de idempotência; devolver exige motivo',
    (tester) async {
      final posts = <http.Request>[];
      var tax = taxJson('SUBMITTED', 4);
      await loginOwner(
        tester,
        (_) => const ApprovalsScreen(),
        ['taxes.view', 'taxes.approve', 'taxes.confirm'],
        (r) async {
          if (r.method == 'POST') posts.add(r);
          switch ('${r.method} ${r.url.path}') {
            case 'GET /api/v1/approvals':
              return j(200, {
                'items': tax['state'] == 'SUBMITTED'
                    ? [
                        {
                          'kind': 'TAX',
                          'sector': 'ACCOUNTING',
                          'id': 'g1',
                          'title': 'DAS 11/2026',
                          'detail': 'Simples',
                          'amount': '850.00',
                          'requested_by': 'Fernanda',
                          'requested_at': '2026-11-17T12:00:00Z',
                          'path': '/finance/taxes',
                          'note': 'Aprovar não paga',
                        },
                      ]
                    : [],
                'sectors': [
                  {
                    'sector': 'ACCOUNTING',
                    'pending': tax['state'] == 'SUBMITTED' ? 1 : 0,
                  },
                ],
              });
            case 'GET /api/v1/tax-obligations/g1':
              return j(200, tax);
            case 'GET /api/v1/tax-obligations/g1/history':
              return j(200, {'items': []});
            case 'POST /api/v1/tax-obligations/g1/approval':
              tax = {
                ...taxJson('APPROVED', 5),
                'decided_by_name': 'Patrão',
                'decided_note': 'ok',
              };
              return j(200, tax);
          }
          return j(404, {});
        },
      );
      expect(find.text('Contabilidade · 1'), findsOneWidget);
      expect(find.textContaining('R\$ 850,00'), findsOneWidget);
      await tester.tap(find.text('DAS 11/2026'));
      await tester.pumpAndSettle();
      expect(find.text('Aguardando aprovação do patrão'), findsOneWidget);
      // Devolver sem motivo: o botão de confirmar fica desabilitado.
      await tester.tap(find.text('Devolver com motivo'));
      await tester.pumpAndSettle();
      final devolver = tester.widget<FilledButton>(
        find.widgetWithText(FilledButton, 'Devolver'),
      );
      expect(devolver.onPressed, isNull);
      await tester.tap(find.text('Voltar'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Aprovar guia'));
      await tester.pumpAndSettle();
      expect(
        find.text('Aprovar não paga: o pagamento é registrado depois.'),
        findsOneWidget,
      );
      await tester.enterText(
        find.widgetWithText(TextField, 'Observação'),
        'ok',
      );
      await tester.tap(find.widgetWithText(FilledButton, 'Aprovar'));
      await tester.pumpAndSettle();
      final ap = posts.single;
      expect(ap.headers['If-Match'], '"4"');
      expect(ap.headers['Idempotency-Key'], matches(RegExp(r'^app-')));
      expect(jsonDecode(ap.body), {'note': 'ok'});
      expect(find.text('Aprovada — pagamento ainda pendente'), findsOneWidget);
      expect(
        find.text('Registrar pagamento'),
        findsOneWidget,
      ); // pagar é outra etapa
    },
  );

  testWidgets(
    'pagar guia aprovada: escolhe a conta de saída e a data; envia com chave própria',
    (tester) async {
      http.Request? pay;
      await loginOwner(
        tester,
        (_) => const DecisionView(kind: 'TAX', id: 'g1', item: {}),
        ['taxes.view', 'taxes.confirm'],
        (r) async {
          switch ('${r.method} ${r.url.path}') {
            case 'GET /api/v1/tax-obligations/g1':
              return j(
                200,
                pay == null ? taxJson('APPROVED', 5) : taxJson('PAID', 6),
              );
            case 'GET /api/v1/tax-obligations/g1/history':
              return j(200, {'items': []});
            case 'GET /api/v1/finance/accounts':
              return j(200, {
                'items': [
                  {
                    'id': 'b1',
                    'name': 'Banco Fictício',
                    'kind': 'BANK',
                    'status': 'ACTIVE',
                    'balance': '1000.00',
                  },
                ],
              });
            case 'POST /api/v1/tax-obligations/g1/payments':
              pay = r;
              return j(200, taxJson('PAID', 6));
          }
          return j(404, {});
        },
      );
      await tester.tap(find.text('Registrar pagamento'));
      await tester.pumpAndSettle();
      expect(
        find.textContaining('Aprovar e pagar são etapas separadas'),
        findsOneWidget,
      );
      await tester.tap(find.text('Conta de saída *'));
      await tester.pumpAndSettle();
      await tester.tap(find.textContaining('Banco Fictício').last);
      await tester.pumpAndSettle();
      await tester.tap(
        find.widgetWithText(FilledButton, 'Registrar pagamento'),
      );
      await tester.pumpAndSettle();
      final body = jsonDecode(pay!.body) as Map;
      expect(body['fin_account_id'], 'b1');
      expect(body['paid_on'], matches(RegExp(r'^\d{4}-\d{2}-\d{2}$')));
      expect(pay!.headers['Idempotency-Key'], matches(RegExp(r'^app-')));
      expect(find.text('Paga'), findsOneWidget);
    },
  );

  testWidgets(
    'mapa de acessos: resumo antes de salvar, motivo obrigatório, versão; conflito 412 recarrega',
    (tester) async {
      final posts = <http.Request>[];
      var status = 200;
      await loginOwner(
        tester,
        (_) => const AccessEditorScreen(userId: 'u2'),
        [
          'permissions.view',
          'permissions.edit_draft',
          'orders.pickup.view',
          'orders.pickup.create',
          'users.view',
        ],
        (r) async {
          if (r.method == 'POST') posts.add(r);
          if (r.url.path == '/api/v1/users/u2/access-policy') {
            if (r.method == 'POST') {
              return status == 412
                  ? j(412, {
                      'code': 'VERSION_CONFLICT',
                      'detail': 'Versão desatualizada',
                    })
                  : j(200, {});
            }
            return j(200, {
              'user': {
                'id': 'u2',
                'display_name': 'Ana Balcão',
                'status': 'ACTIVE',
                'sector': 'RETIRADA',
                'role_template': 'BALCAO',
                'admin': false,
                'branch': 'Matriz',
                'version': 7,
              },
              'clients': ['engenheiro-retirada'],
              'grants': [
                {
                  'resource': 'orders.pickup',
                  'action': 'view',
                  'scope': 'BRANCH',
                  'effect': 'ALLOW',
                  'max_amount': null,
                  'expires_at': null,
                },
              ],
              'fields': [],
            });
          }
          return j(404, {});
        },
      );
      expect(find.text('Ana Balcão'), findsOneWidget);
      await scrollTo(tester, find.text('Pedidos de retirada'));
      await tester.tap(find.text('Pedidos de retirada'));
      await tester.pumpAndSettle();
      // "Cadastrar" (create) começa sem acesso; passa a Permitir, escopo próprio.
      final efeito = find.widgetWithText(
        DropdownButtonFormField<String>,
        'Sem acesso',
      );
      await tester.tap(efeito.first);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Permitir').last);
      await tester.pumpAndSettle();
      await scrollTo(tester, find.text('Resumo antes de salvar'));
      expect(
        find.textContaining(
          'Pedidos de retirada · Cadastrar: sem acesso → permitido (Próprios registros)',
        ),
        findsOneWidget,
      );
      await scrollTo(tester, find.text('Salvar acessos'));
      expect(
        tester
            .widget<FilledButton>(
              find.widgetWithText(FilledButton, 'Salvar acessos'),
            )
            .onPressed,
        isNull,
      ); // sem motivo
      await tester.enterText(
        find.widgetWithText(TextField, 'Motivo *'),
        'Cobrir o balcão',
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Salvar acessos'));
      await tester.pumpAndSettle();
      final p = posts.single;
      expect(p.headers['If-Match'], '"7"');
      final body = jsonDecode(p.body) as Map;
      expect(body['reason'], 'Cobrir o balcão');
      expect(
        (body['grants'] as List).map(
          (g) => '${g['resource']}.${g['action']}:${g['scope']}:${g['effect']}',
        ),
        containsAll([
          'orders.pickup.view:BRANCH:ALLOW',
          'orders.pickup.create:OWN:ALLOW',
        ]),
      );
      expect(body['clients'], ['engenheiro-retirada']);
      expect(find.textContaining('Permissões salvas'), findsOneWidget);
      await tester.pump(const Duration(seconds: 5)); // o aviso sai da tela
      await tester.pumpAndSettle();
      // Conflito: outra pessoa salvou antes — o app avisa e recarrega a versão atual, sem sobrescrever.
      status = 412;
      await scrollTo(tester, find.text('Pedidos de retirada'), delta: -300);
      // Depois de salvar, a tela recarregou do servidor: "Cadastrar" voltou a "Sem acesso" (nada de valor antigo na tela).
      if (find
          .widgetWithText(DropdownButtonFormField<String>, 'Sem acesso')
          .evaluate()
          .isEmpty) {
        await tester.tap(find.text('Pedidos de retirada'));
        await tester.pumpAndSettle();
      }
      expect(
        find.widgetWithText(DropdownButtonFormField<String>, 'Permitir'),
        findsOneWidget,
      ); // só "Ver"
      await tester.tap(
        find
            .widgetWithText(DropdownButtonFormField<String>, 'Sem acesso')
            .first,
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Bloquear').last);
      await tester.pumpAndSettle();
      await scrollTo(tester, find.widgetWithText(TextField, 'Motivo *'));
      await tester.enterText(
        find.widgetWithText(TextField, 'Motivo *'),
        'Outro ajuste',
      );
      await tester.pumpAndSettle();
      await scrollTo(tester, find.text('Salvar acessos'));
      await tester.tap(find.text('Salvar acessos'));
      await tester.pumpAndSettle();
      expect(
        find.textContaining('Outra pessoa alterou estes acessos'),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'movimento: quem, app, o que mudou com campo mascarado; abrir origem leva à trilha do pedido',
    (tester) async {
      await loginOwner(
        tester,
        (_) => const AuditEventScreen(id: 'e1'),
        ['audit.view', 'orders.pickup.view'],
        (r) async {
          switch (r.url.path) {
            case '/api/v1/audit-events/e1':
              return j(200, {
                'event': {
                  'id': 'e1',
                  'occurred_at': '2026-11-17T13:00:00Z',
                  'actor_user_id': 'u3',
                  'actor_name': 'Ana Balcão',
                  'actor_kind': 'USER',
                  'client_id': 'engenheiro-retirada',
                  'action': 'orders.create',
                  'entity_type': 'order',
                  'entity_id': '0190aaaa-0000-7000-8000-000000000001',
                  'result': 'SUCCESS',
                  'reason': null,
                  'request_id': 'req-1',
                },
                'changes': {
                  'total': '30.00',
                  'lines': [
                    {'unit_cost': '•••'},
                  ],
                },
                'masked_fields': ['unit_cost'],
              });
            case '/api/v1/orders/0190aaaa-0000-7000-8000-000000000001':
              return j(200, {
                'id': 'o1',
                'number': 77,
                'customer_name': 'Mercado',
                'state': 'FULFILLED',
                'payment_state': 'PAID',
                'total': '30.00',
                'paid_amount': '30.00',
                'balance_due': '0.00',
                'created_by_name': 'Ana Balcão',
                'created_at': '2026-11-17T13:00:00Z',
                'lines': [],
              });
            case '/api/v1/orders/0190aaaa-0000-7000-8000-000000000001/history':
              return j(200, {
                'items': [
                  {
                    'occurred_at': '2026-11-17T13:00:00Z',
                    'action': 'orders.create',
                    'actor_name': 'Ana Balcão',
                    'client_id': 'engenheiro-retirada',
                    'entity_type': 'order',
                    'entity_id': 'o1',
                  },
                  {
                    'occurred_at': '2026-11-17T13:05:00Z',
                    'action': 'inventory.sale_issue',
                    'actor_name': 'Ana Balcão',
                    'client_id': null,
                    'entity_type': 'product',
                    'entity_id': 'p1',
                  },
                  {
                    'occurred_at': '2026-11-17T14:00:00Z',
                    'action': 'trips.deliver',
                    'actor_name': 'Carlos Motorista',
                    'client_id': 'engenheiro-entregas',
                    'entity_type': 'trip',
                    'entity_id': 't1',
                  },
                ],
              });
          }
          return j(404, {});
        },
      );
      expect(find.text('Cadastrou pedido'), findsOneWidget);
      expect(find.text('Ana Balcão'), findsOneWidget);
      expect(find.text('App Engenheiro Retirada'), findsOneWidget);
      expect(find.textContaining('•••'), findsOneWidget);
      expect(
        find.text('Ocultos pela sua permissão de campos: unit_cost'),
        findsOneWidget,
      );
      await scrollTo(tester, find.text('Abrir origem'));
      await tester.tap(find.text('Abrir origem'));
      await tester.pumpAndSettle();
      expect(find.text('Pedido #77 · Mercado'), findsOneWidget);
      await scrollTo(tester, find.textContaining('Confirmou entrega'));
      expect(find.text('Baixou estoque (venda) — Ana Balcão'), findsOneWidget);
      expect(find.textContaining('App Engenheiro Entregas'), findsOneWidget);
    },
  );

  testWidgets(
    'histórico: filtro por app de origem vai para o servidor; "Carregar mais" usa o cursor',
    (tester) async {
      final queries = <Map<String, String>>[];
      await loginOwner(tester, (_) => const HistoryScreen(), ['audit.view'], (
        r,
      ) async {
        if (r.url.path == '/api/v1/audit-events') {
          queries.add(r.url.queryParameters);
          final more = r.url.queryParameters['cursor'] == null;
          return j(200, {
            'items': [
              {
                'id': 'e${queries.length}',
                'occurred_at': '2026-11-17T13:00:00Z',
                'actor_name': 'Fernanda',
                'actor_kind': 'USER',
                'client_id': 'engenheiro-contabil',
                'action': 'taxes.submit',
                'entity_type': 'tax_obligation',
                'entity_id': 'g1',
                'result': 'SUCCESS',
              },
            ],
            'next_cursor': more ? 'c2' : null,
          });
        }
        return j(404, {});
      });
      expect(
        find.textContaining('Fernanda — Enviou guia para aprovação'),
        findsOneWidget,
      );
      await tester.tap(find.text('Carregar mais'));
      await tester.pumpAndSettle();
      expect(queries.last['cursor'], 'c2');
      expect(find.textContaining('Fernanda — Enviou guia'), findsNWidgets(2));
      await tester.tap(find.text('Filtros'));
      await tester.pumpAndSettle();
      await tester.tap(
        find.widgetWithText(DropdownButtonFormField<String?>, 'Todos').at(0),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('App Engenheiro Contábil').last);
      await tester.pumpAndSettle();
      expect(queries.last['client_id'], 'engenheiro-contabil');
      expect(queries.last.containsKey('cursor'), isFalse);
    },
  );

  testWidgets('histórico: tipo de registro filtra pedidos pelo servidor', (
    tester,
  ) async {
    final queries = <Map<String, String>>[];
    await loginOwner(tester, (_) => const HistoryScreen(), ['audit.view'], (
      r,
    ) async {
      if (r.url.path == '/api/v1/audit-events') {
        queries.add(r.url.queryParameters);
        return j(200, {'items': [], 'next_cursor': null});
      }
      return j(404, {});
    });
    await tester.tap(find.text('Filtros'));
    await tester.pumpAndSettle();
    await tester.tap(
      find.widgetWithText(DropdownButtonFormField<String?>, 'Todos').at(1),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Pedido').last);
    await tester.pumpAndSettle();
    expect(queries.last['entity_type'], 'order');
  });

  testWidgets(
    'busca geral envia placa ao servidor e mostra a origem autorizada',
    (tester) async {
      Map<String, String>? query;
      await loginOwner(
        tester,
        (_) => const OwnerSearchScreen(),
        ['audit.view'],
        (r) async {
          if (r.url.path == '/api/v1/search') {
            query = r.url.queryParameters;
            return j(200, {
              'searched': ['vehicle'],
              'items': [
                {
                  'type': 'vehicle',
                  'id': '0190aaaa-0000-7000-8000-000000000001',
                  'title': 'FIC1A23 · Fiorino',
                  'subtitle': 'Em rota',
                },
              ],
            });
          }
          return j(404, {});
        },
      );
      final field = find.byType(TextField).first;
      await tester.enterText(field, 'FIC1A23');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();
      expect(query?['q'], 'FIC1A23');
      expect(find.text('FIC1A23 · Fiorino'), findsOneWidget);
    },
  );

  testWidgets(
    'Início sem permissão de relatórios: nenhum número; aprovações visíveis',
    (tester) async {
      final paths = <String>[];
      await loginOwner(
        tester,
        (c) => OwnerHomeScreen(openApprovals: () {}),
        ['audit.view'],
        (r) async {
          paths.add(r.url.path);
          if (r.url.path == '/api/v1/approvals') {
            return j(200, {'items': [], 'sectors': []});
          }
          return j(404, {});
        },
        admin: false,
      );
      expect(find.text('Nada esperando sua decisão'), findsOneWidget);
      expect(find.textContaining('Nenhum número de exemplo'), findsOneWidget);
      expect(find.textContaining('R\$'), findsNothing);
      expect(paths, isNot(contains('/api/v1/reports/overview')));
    },
  );

  testWidgets(
    'Início com relatórios: números do servidor (objeto result), lucro oculto quando a permissão esconde',
    (tester) async {
      await loginOwner(
        tester,
        (c) => OwnerHomeScreen(openApprovals: () {}),
        ['reports.view', 'goals.view'],
        (r) async {
          switch (r.url.path) {
            case '/api/v1/approvals':
              return j(200, {
                'items': [
                  {'kind': 'EXPENSE', 'sector': 'FINANCE', 'id': 'x'},
                ],
                'sectors': [],
              });
            case '/api/v1/reports/overview':
              return j(200, {
                'label': 'novembro de 2026',
                'hidden': ['cogs', 'profit'],
                'result': {
                  'net_revenue': '40000.00',
                  'cogs': null,
                  'expenses_total': '15000.00',
                  'losses': '500.00',
                  'profit': null,
                },
                'drill': [],
                'expenses': [],
              });
            case '/api/v1/goals':
              return j(200, {
                'items': [
                  {
                    'id': 'm1',
                    'title': 'Vender mais',
                    'state': 'ACTIVE',
                    'metric': 'NET_REVENUE',
                    'target': '50000.00',
                    'progress': {'percent': '80.00'},
                  },
                ],
              });
          }
          return j(404, {});
        },
        admin: false,
      );
      expect(find.text('1 aprovação(ões) esperando você'), findsOneWidget);
      expect(find.text('R\$ 40.000,00'), findsOneWidget);
      expect(find.text('R\$ 15.000,00'), findsOneWidget);
      expect(find.text('R\$ 500,00'), findsOneWidget);
      expect(find.text('oculto'), findsNWidgets(2));
      expect(find.textContaining('80,00% de R\$ 50.000,00'), findsOneWidget);
    },
  );

  testWidgets(
    'despesa lançada pelo app: "1.500,00" vira 1500.00; erro de campo do servidor aparece no campo',
    (tester) async {
      final posts = <http.Request>[];
      await loginOwner(
        tester,
        (_) => const ExpensesScreen(),
        ['expenses.view', 'expenses.create'],
        (r) async {
          if (r.method == 'POST') {
            posts.add(r);
            if (posts.length == 1) {
              return j(422, {
                'code': 'VALIDATION_ERROR',
                'detail': 'Dados inválidos',
                'errors': [
                  {'field': 'subcategory', 'message': 'Obrigatório'},
                ],
              });
            }
            return j(201, {'id': 'x1'});
          }
          if (r.url.path == '/api/v1/expenses') {
            return j(200, {'items': [], 'next_cursor': null});
          }
          return j(404, {});
        },
      );
      await tester.tap(find.text('Lançar despesa'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Categoria *'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Estrutura').last);
      await tester.pumpAndSettle();
      await tester.enterText(
        find.widgetWithText(TextField, 'Descrição *'),
        'Aluguel',
      );
      await tester.enterText(
        find.widgetWithText(TextField, 'Valor (R\$) *'),
        '1.500,00',
      );
      await tester.tap(find.widgetWithText(FilledButton, 'Lançar'));
      await tester.pumpAndSettle();
      expect(jsonDecode(posts.first.body), containsPair('amount', '1500.00'));
      expect(find.text('Obrigatório'), findsOneWidget);
      final key1 = posts.first.headers['Idempotency-Key'];
      await tester.enterText(
        find.widgetWithText(TextField, 'Subcategoria *'),
        'Aluguel',
      );
      await tester.tap(find.widgetWithText(FilledButton, 'Lançar'));
      await tester.pumpAndSettle();
      expect(
        posts[1].headers['Idempotency-Key'],
        key1,
      ); // mesma intenção, mesma chave
      expect(find.text('Lançar despesa'), findsOneWidget); // voltou à lista
    },
  );
}

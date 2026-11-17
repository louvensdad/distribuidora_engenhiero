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

final pdf = Uint8List.fromList(latin1.encode('%PDF-1.4\n1 0 obj<<>>endobj\n%%EOF'));
Map<String, dynamic> tax(String state, {List<Map<String, dynamic>> files = const [], int version = 1}) => {
      'id': 'g1', 'kind': 'DAS', 'description': 'Simples Nacional', 'competence': '2026-11', 'due_date': '2026-12-20',
      'amount': '850.00', 'state': state, 'payable_state': null, 'decided_by_name': null, 'decided_note': null,
      'attachments': files, 'version': version,
    };
final file = {'id': 'f1', 'file_name': 'guia.pdf', 'mime': 'application/pdf', 'size_bytes': 30, 'sha256': '0' * 64,
  'state': 'AVAILABLE', 'scan_state': 'x', 'rejection': null, 'created_by_name': 'Contador', 'created_at': '2026-11-16T12:00:00Z'};

Future<void> login(WidgetTester tester, Widget Function(BuildContext) screen, List<String> caps,
    Future<http.Response> Function(http.Request) server) async {
  tester.view.physicalSize = const Size(1080, 2400);
  tester.view.devicePixelRatio = 3;
  addTearDown(tester.view.reset);
  final session = SessionController(
    auth: AuthSession(authenticator: FakeAuth(), store: MemorySecureStore()),
    apiFactory: (tokens) => ApiClient(baseUrl: Uri.parse('http://api.teste.invalid'), tokenProvider: tokens,
        httpClient: MockClient((r) async => r.url.path == '/api/v1/me/capabilities'
            ? j(200, meJson(caps, client: 'engenheiro-contabil'))
            : server(r))),
  );
  await tester.pumpWidget(EngenheiroApp(
      config: AppConfig(appName: 'Engenheiro Contábil', applicationId: 'br.com.engenheiro.contabil', clientId: 'engenheiro-contabil',
          audienceDescription: 'Contador', apiBaseUrl: Uri.parse('http://api.teste.invalid'), issuer: 'http://idp.teste.invalid/realms/engenheiro',
          destinations: [Destination(id: 'x', label: 'Tela', icon: 'tax', anyOf: caps, builder: screen)]),
      session: session));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Entrar com segurança'));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('contador: prepara guia, anexa o PDF real e envia para aprovação; não vê pagar', (tester) async {
    accountingFilePicker = ({required List<String> extensions}) async => (name: 'guia.pdf', bytes: pdf, mime: 'application/pdf');
    final posts = <http.BaseRequest>[];
    var current = tax('DRAFT');
    await login(tester, (_) => const TaxesScreen(), ['taxes.view', 'taxes.create', 'taxes.request'], (r) async {
      if (r.method == 'POST') posts.add(r);
      switch ('${r.method} ${r.url.path}') {
        case 'GET /api/v1/tax-obligations':
          return j(200, {'items': []});
        case 'POST /api/v1/tax-obligations':
          return j(201, current);
        case 'GET /api/v1/tax-obligations/g1':
          return j(200, current);
        case 'POST /api/v1/tax-obligations/g1/attachments':
          current = tax('DRAFT', files: [file], version: 1);
          return j(201, file);
        case 'POST /api/v1/tax-obligations/g1/submit':
          current = tax('SUBMITTED', files: [file], version: 2);
          return j(200, current);
      }
      return j(404, {});
    });
    expect(find.text('Nenhuma guia'), findsOneWidget);
    await tester.tap(find.text('Preparar guia'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Descrição *'), 'Simples Nacional');
    await tester.enterText(find.widgetWithText(TextField, 'Competência (AAAA-MM) *'), '2026-11');
    await tester.enterText(find.widgetWithText(TextField, 'Vencimento (AAAA-MM-DD) *'), '2026-12-20');
    await tester.enterText(find.widgetWithText(TextField, 'Valor (R\$) *'), '850,00');
    await tester.tap(find.text('Salvar rascunho'));
    await tester.pumpAndSettle();
    final create = posts.first as http.Request;
    expect(jsonDecode(create.body), {'kind': 'DAS', 'description': 'Simples Nacional', 'competence': '2026-11',
      'due_date': '2026-12-20', 'amount': '850.00'});
    expect(create.headers['Idempotency-Key'], matches(RegExp(r'^app-')));
    expect(find.text('Em preparação'), findsOneWidget);
    expect(find.textContaining('Nenhum arquivo'), findsOneWidget);
    await tester.tap(find.text('Anexar arquivo da guia'));
    await tester.pumpAndSettle();
    final up = posts[1] as http.Request;
    expect(up.headers['content-type'], startsWith('multipart/form-data'));
    expect(latin1.decode(up.bodyBytes), contains('%PDF-1.4'));
    expect(find.text('guia.pdf — ok'), findsOneWidget);
    await tester.tap(find.text('Enviar para aprovação'));
    await tester.pumpAndSettle();
    expect((posts[2] as http.Request).headers['If-Match'], '"1"');
    expect(find.text('Aguardando aprovação do patrão'), findsOneWidget);
    expect(find.textContaining('Registrar pagamento'), findsNothing);
  });

  testWidgets('contador importa a folha (CSV) e só vê totais por rubrica', (tester) async {
    accountingFilePicker = ({required List<String> extensions}) async =>
        (name: 'folha.csv', bytes: Uint8List.fromList(utf8.encode('funcionario;rubrica;tipo;valor\nAna;Salário;SALARIO;3.000,00\n')), mime: 'text/csv');
    http.Request? imp;
    final run = {'id': 'r1', 'competence': '2026-11', 'description': 'Folha mensal', 'state': 'DRAFT', 'source': 'IMPORT',
      'totals': {'earnings': '3000.00', 'benefits': '0.00', 'employer_charges': '0.00', 'company_cost': '3000.00', 'people': 1},
      'rubrics': [], 'people': null, 'decided_note': null, 'version': 1};
    var runs = <Map<String, dynamic>>[];
    await login(tester, (_) => const PayrollScreen(), ['payroll.view', 'payroll.create', 'payroll.request'], (r) async {
      if (r.url.path == '/api/v1/payroll-runs/imports') {
        imp = r;
        runs = [run];
        return j(201, run);
      }
      if (r.url.path == '/api/v1/payroll-runs') return j(200, {'items': runs});
      return j(404, {});
    });
    await tester.enterText(find.widgetWithText(TextField, 'Competência (AAAA-MM) *'), '2026-11');
    await tester.enterText(find.widgetWithText(TextField, 'Data de pagamento (AAAA-MM-DD) *'), '2026-12-05');
    await tester.tap(find.text('Escolher arquivo e importar'));
    await tester.pumpAndSettle();
    final body = latin1.decode(imp!.bodyBytes);
    expect(body, contains('name="competence"'));
    expect(body, contains('2026-11'));
    expect(body, contains('filename="folha.csv"'));
    expect(find.textContaining('Folha importada: 1 pessoa(s)'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('Enviar ao patrão'), 300,
        scrollable: find.descendant(of: find.byType(PayrollScreen), matching: find.byType(Scrollable)).first);
    expect(find.textContaining('Valores por pessoa: só com permissão individual'), findsOneWidget);
  });
}

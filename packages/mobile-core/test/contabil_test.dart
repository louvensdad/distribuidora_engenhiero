import 'dart:convert';
import 'dart:typed_data';
import 'package:engenheiro_core/engenheiro_core.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'accounting_test.dart' show file, login, pdf, tax;
import 'app_test.dart' show j;

/// F13 — app Contábil: decisão do patrão volta ao contador com histórico, guia devolvida é corrigida, folha devolvida é
/// cancelada para reimportar, solicitação atendida com arquivo real, rejeição fiscal aparece como rejeição.
Map<String, dynamic> hist(String action, String actor, String client, [String? note]) => {
      'occurred_at': '2026-11-17T13:00:00Z', 'action': action, 'actor_name': actor, 'actor_kind': 'USER', 'client_id': client,
      'note': note,
    };

Future<void> scrollTo(WidgetTester tester, Finder f) async {
  await tester.scrollUntilVisible(f, 300, scrollable: find.byType(Scrollable).last);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('guia devolvida: vê motivo e histórico, abre o PDF, corrige os dados e reenvia', (tester) async {
    final posts = <http.Request>[];
    Uint8List? saved;
    accountingFileSaver = ({required String fileName, required Uint8List bytes, required String mime}) async {
      saved = bytes;
      return true;
    };
    var current = {...tax('RETURNED', files: [file], version: 3), 'decided_by_name': 'Patrão', 'decided_note': 'valor errado',
      'created_by_name': 'Fernanda', 'submitted_by_name': 'Fernanda', 'notes': null, 'competence': '2026-11-01'};
    await login(tester, (_) => const TaxDetailScreen(id: 'g1'), ['taxes.view', 'taxes.create', 'taxes.request'], (r) async {
      if (r.method == 'POST') posts.add(r);
      switch ('${r.method} ${r.url.path}') {
        case 'GET /api/v1/tax-obligations/g1':
          return j(200, current);
        case 'GET /api/v1/tax-obligations/g1/history':
          return j(200, {'items': [
            hist('taxes.create', 'Fernanda', 'engenheiro-contabil'),
            hist('taxes.submit', 'Fernanda', 'engenheiro-contabil'),
            hist('taxes.return', 'Patrão', 'engenheiro-web', 'valor errado'),
          ]});
        case 'GET /api/v1/documents/f1/download':
          return http.Response.bytes(pdf, 200, headers: {'content-type': 'application/pdf'});
        case 'POST /api/v1/tax-obligations/g1/draft':
          current = {...current, 'amount': '870.00', 'version': 4};
          return j(200, current);
        case 'POST /api/v1/tax-obligations/g1/submit':
          current = {...current, 'state': 'SUBMITTED', 'version': 5};
          return j(200, current);
      }
      return j(404, {});
    });
    expect(find.text('Devolvida — corrigir'), findsOneWidget);
    expect(find.text('Patrão: valor errado'), findsOneWidget);
    expect(find.text('Preparada por Fernanda · enviada por Fernanda'), findsOneWidget);
    await scrollTo(tester, find.text('Devolveu guia — Patrão'));
    expect(find.textContaining('Site do patrão'), findsOneWidget);
    expect(find.textContaining('App Engenheiro Contábil'), findsNWidgets(2));
    expect(find.text('“valor errado”'), findsOneWidget);

    await scrollTo(tester, find.text('Ver documento'));
    await tester.tap(find.text('Ver documento'));
    await tester.pumpAndSettle();
    expect(saved, pdf);
    expect(find.text('Arquivo salvo no aparelho.'), findsOneWidget);

    await scrollTo(tester, find.text('Corrigir dados da guia'));
    await tester.tap(find.text('Corrigir dados da guia'));
    await tester.pumpAndSettle();
    expect(find.text('Corrigir guia'), findsOneWidget);
    expect(find.widgetWithText(TextField, '2026-11'), findsOneWidget); // competência AAAA-MM preenchida
    await tester.enterText(find.widgetWithText(TextField, 'Valor (R\$) *'), '870,00');
    await tester.tap(find.text('Salvar correção'));
    await tester.pumpAndSettle();
    final upd = posts.first;
    expect(upd.url.path, '/api/v1/tax-obligations/g1/draft');
    expect(upd.headers['If-Match'], '"3"');
    expect(jsonDecode(upd.body), containsPair('amount', '870.00'));
    expect(find.textContaining('R\$ 870,00'), findsOneWidget);
    await scrollTo(tester, find.text('Enviar para aprovação'));
    await tester.tap(find.text('Enviar para aprovação'));
    await tester.pumpAndSettle();
    expect(posts[1].headers['If-Match'], '"4"');
    expect(find.text('Aguardando aprovação do patrão'), findsOneWidget);
  });

  testWidgets('agenda: vencida em destaque e ordenada, nota rejeitada como rejeitada; sem chamar o que não pode ver',
      (tester) async {
    final paths = <String>[];
    await login(tester, (_) => const AccountantAgendaScreen(), ['taxes.view', 'invoices.view'], (r) async {
      paths.add(r.url.path);
      switch (r.url.path) {
        case '/api/v1/tax-obligations':
          return j(200, {'items': [
            {...tax('APPROVED'), 'id': 'g2', 'kind': 'ICMS', 'due_date': '2099-01-10', 'payable_state': 'OPEN'},
            {...tax('SUBMITTED'), 'due_date': '2020-01-20'},
            {...tax('PAID'), 'id': 'g3', 'kind': 'ISS'},
          ]});
        case '/api/v1/invoices':
          return j(200, {'items': [
            {'id': 'n1', 'order_id': 'o1', 'order_number': 41, 'customer_name': 'Mercado Exemplo', 'document_type': 'NFCE',
              'state': 'REJECTED', 'total': '75.00', 'rejection_reason': 'NCM inválido', 'lines': [], 'issues': [], 'version': 3},
          ]});
      }
      return j(404, {});
    });
    expect(paths, isNot(contains('/api/v1/payroll-runs')));
    expect(paths, isNot(contains('/api/v1/document-requests')));
    expect(find.text('1 guia(s) vencida(s) sem pagamento registrado.'), findsOneWidget);
    expect(find.textContaining('VENCIDA em 20/01/2020'), findsOneWidget);
    expect(find.textContaining('ISS'), findsNothing); // paga sai da agenda
    final das = tester.getTopLeft(find.textContaining('Guia DAS')).dy;
    final icms = tester.getTopLeft(find.textContaining('Guia ICMS')).dy;
    expect(das, lessThan(icms)); // por vencimento
    expect(find.text('Rejeitada'), findsOneWidget);
    expect(find.textContaining('Autorizada'), findsNothing);
    // Nada de saldo, lucro ou vendas totais na agenda.
    expect(find.textContaining('Saldo'), findsNothing);
    expect(find.textContaining('Lucro'), findsNothing);
  });

  testWidgets('folha devolvida: detalhe por rubrica, histórico e cancelamento com motivo para reimportar', (tester) async {
    final posts = <http.Request>[];
    var run = {'id': 'r1', 'competence': '2026-11-01', 'description': 'Folha mensal', 'pay_date': '2026-12-05', 'state': 'RETURNED',
      'source': 'IMPORT', 'totals': {'earnings': '3000.00', 'benefits': '0.00', 'deductions': '270.00', 'employer_charges': '240.00',
        'net_pay': '2730.00', 'company_cost': '3240.00', 'people': 1},
      'rubrics': [{'kind': 'EARNING', 'rubric': 'Salário base', 'amount': '3000.00', 'people': 1}],
      'people': null, 'decided_by_name': 'Patrão', 'decided_note': 'Faltou o vale', 'version': 3};
    await login(tester, (_) => const PayrollDetailScreen(id: 'r1'), ['payroll.view', 'payroll.create', 'payroll.request'], (r) async {
      if (r.method == 'POST') posts.add(r);
      switch ('${r.method} ${r.url.path}') {
        case 'GET /api/v1/payroll-runs/r1':
          return j(200, run);
        case 'GET /api/v1/payroll-runs/r1/history':
          return j(200, {'items': [hist('payroll.create', 'Fernanda', 'engenheiro-contabil'),
            hist('payroll.return', 'Patrão', 'engenheiro-web', 'Faltou o vale')]});
        case 'POST /api/v1/payroll-runs/r1/cancel':
          run = {...run, 'state': 'CANCELLED', 'version': 4};
          return j(200, run);
      }
      return j(404, {});
    });
    expect(find.text('Devolvida — corrigir'), findsOneWidget);
    expect(find.text('Patrão: Faltou o vale'), findsOneWidget);
    expect(find.textContaining('Salário base (Provento)'), findsOneWidget);
    expect(find.textContaining('Valores por pessoa: só com permissão individual'), findsOneWidget);
    await scrollTo(tester, find.text('Cancelar e importar a versão corrigida'));
    await tester.tap(find.text('Cancelar e importar a versão corrigida'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Motivo *'), 'Refazer com o vale');
    await tester.tap(find.text('Cancelar folha'));
    await tester.pumpAndSettle();
    expect(posts.single.headers['If-Match'], '"3"');
    expect(jsonDecode(posts.single.body), {'note': 'Refazer com o vale'});
    expect(find.text('Cancelada'), findsOneWidget);
    expect(find.text('Enviar ao patrão'), findsNothing);
  });

  testWidgets('solicitação atendida com arquivo real ligado à solicitação; documento com versões e nova versão', (tester) async {
    accountingFilePicker = ({required List<String> extensions}) async => (name: 'contrato.pdf', bytes: pdf, mime: 'application/pdf');
    final posts = <http.Request>[];
    final doc = {'id': 'd1', 'kind': 'CONTRATO', 'title': 'Contrato social', 'competence': null, 'related_type': null, 'related_id': null,
      'current_version': 1, 'state': 'ACTIVE', 'created_by_name': 'Fernanda', 'created_at': '2026-11-17T12:00:00Z',
      'versions': [{'version_no': 1, 'reason': null, 'created_by_name': 'Fernanda', 'created_at': '2026-11-17T12:00:00Z', 'file': file}],
      'version': 1};
    await login(tester, (_) => const AccountingDocumentsScreen(), ['documents.view', 'documents.attach'], (r) async {
      if (r.method == 'POST') posts.add(r);
      switch ('${r.method} ${r.url.path}') {
        case 'GET /api/v1/documents':
          return j(200, {'items': [doc]});
        case 'GET /api/v1/document-requests':
          return j(200, {'items': [{'id': 'q1', 'title': 'Contrato social atualizado', 'state': 'OPEN', 'description': 'Para o banco',
            'created_by_name': 'Patrão', 'due_date': '2026-11-30', 'kind': 'CONTRATO'}]});
        case 'POST /api/v1/documents/uploads':
          return j(201, doc);
        case 'GET /api/v1/documents/d1':
          return j(200, doc);
        case 'POST /api/v1/documents/d1/versions':
          return j(200, {...doc, 'current_version': 2, 'versions': [
            ...(doc['versions'] as List),
            {'version_no': 2, 'reason': 'Alteração contratual', 'created_by_name': 'Fernanda', 'created_at': '2026-11-17T13:00:00Z',
              'file': {...file, 'id': 'f2', 'file_name': 'contrato.pdf'}},
          ]});
      }
      return j(404, {});
    });
    await scrollTo(tester, find.text('Solicitado: Contrato social atualizado'));
    await tester.tap(find.text('Solicitado: Contrato social atualizado'));
    await tester.pumpAndSettle();
    expect(find.text('Pedido por Patrão · até 30/11/2026'), findsOneWidget);
    expect(find.text('Contrato'), findsOneWidget); // tipo pedido já selecionado
    await tester.tap(find.text('Escolher arquivo e enviar'));
    await tester.pumpAndSettle();
    final up = latin1.decode(posts.first.bodyBytes);
    expect(up, contains('name="request_id"'));
    expect(up, contains('q1'));
    expect(up, contains('CONTRATO'));
    expect(up, contains('%PDF-1.4'));

    await scrollTo(tester, find.text('Contrato social'));
    await tester.tap(find.text('Contrato social'));
    await tester.pumpAndSettle();
    expect(find.textContaining('origem: envio avulso'), findsOneWidget);
    expect(find.text('Versão 1 (atual) · Fernanda'), findsOneWidget);
    await tester.tap(find.text('Escolher arquivo e enviar nova versão'));
    await tester.pumpAndSettle();
    final ver = latin1.decode(posts[1].bodyBytes);
    expect(posts[1].url.path, '/api/v1/documents/d1/versions');
    expect(ver, contains('name="reason"'));
    expect(find.text('Versão 2 (atual) · Fernanda'), findsOneWidget);
    expect(find.text('Motivo: Alteração contratual'), findsOneWidget);
  });

  testWidgets('transmitir com permissão: rejeição fiscal aparece como REJEITADA, nunca como emitida', (tester) async {
    Map<String, dynamic> nota(String state, {String? reason}) => {'id': 'n1', 'order_id': 'o1', 'order_number': 42,
      'customer_name': 'Mercado Exemplo', 'document_type': 'NFCE', 'state': state, 'total': '75.00', 'rejection_reason': reason,
      'access_key': null, 'protocol': null, 'lines': [], 'issues': [], 'version': 3};
    var current = nota('READY');
    http.Request? sent;
    await login(tester, (_) => const InvoicesScreen(), ['invoices.view', 'invoices.request', 'invoices.confirm'], (r) async {
      switch ('${r.method} ${r.url.path}') {
        case 'GET /api/v1/fiscal-settings':
          return j(200, {'document_type': 'NFCE', 'provider_state': 'CONFIGURED', 'provider': 'homologacao', 'missing': []});
        case 'GET /api/v1/invoices':
          return j(200, {'items': [current]});
        case 'POST /api/v1/invoices/n1/transmission':
          sent = r;
          current = nota('REJECTED', reason: '778 — NCM inexistente');
          return j(200, current);
      }
      return j(404, {});
    });
    await tester.tap(find.text('Transmitir nota'));
    await tester.pumpAndSettle();
    expect(sent!.headers['If-Match'], '"3"');
    expect(sent!.headers['Idempotency-Key'], matches(RegExp(r'^app-')));
    expect(find.text('Nota REJEITADA: 778 — NCM inexistente'), findsOneWidget);
    expect(find.text('NFC-e · Rejeitada'), findsOneWidget);
    expect(find.text('778 — NCM inexistente'), findsOneWidget);
    expect(find.textContaining('autorizada'), findsNothing);
    expect(find.text('Transmitir nota'), findsNothing);
  });

  testWidgets('transmitir sem provedor: botão bloqueado e nada é enviado', (tester) async {
    var calls = 0;
    await login(tester, (_) => const InvoicesScreen(), ['invoices.view', 'invoices.confirm'], (r) async {
      if (r.method == 'POST') calls++;
      if (r.url.path == '/api/v1/fiscal-settings') {
        return j(200, {'document_type': null, 'provider_state': 'NOT_CONFIGURED', 'provider': null, 'missing': []});
      }
      if (r.url.path == '/api/v1/invoices') {
        return j(200, {'items': [{'id': 'n1', 'order_number': 42, 'customer_name': 'Mercado', 'document_type': 'NFCE',
          'state': 'READY', 'total': '75.00', 'lines': [], 'issues': [], 'version': 1}]});
      }
      return j(404, {});
    });
    await tester.tap(find.text('Transmissão bloqueada (provedor não configurado)'));
    await tester.pumpAndSettle();
    expect(calls, 0);
  });
}

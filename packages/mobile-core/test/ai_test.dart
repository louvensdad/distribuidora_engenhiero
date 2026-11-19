import 'dart:convert';

import 'package:engenheiro_core/engenheiro_core.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:http/http.dart' as http;

import 'app_test.dart' show j;
import 'patrao_test.dart' show loginOwner, scrollTo;

/// F15 — IA no app Patrão: mesma API do site; NÃO CONFIGURADA sem provedor; resposta com período, consultas,
/// limitações, fontes e custo; a chave nunca está no app (só fala com /api/v1/ai).
Map<String, dynamic> status(String state, String message) => {
  'state': state, 'message': message, 'provider': state == 'NOT_CONFIGURED' ? null : 'anthropic',
  'model': state == 'NOT_CONFIGURED' ? null : 'claude-opus-5-5', 'budget_usd': '20.00', 'spent_usd': '0.10',
  'reserved_usd': '0.00', 'remaining_usd': '19.90', 'daily_limit': 30, 'used_today': 1, 'tools': ['consultar_resumo'],
};

final answered = {
  'id': 'q1', 'question': 'Quanto gastei de combustível?', 'from': '2026-11-01', 'to': '2026-11-30', 'state': 'ANSWERED',
  'answer': 'Combustível do FIC1A23: R\$ 120,00.', 'limitations': ['Sem permissão para folha.'],
  'tools': [
    {'name': 'detalhar_veiculo', 'label': 'veículo', 'input': {'placa': 'FIC1A23'}, 'ok': true, 'error': null},
    {'name': 'consultar_pendencias_contador', 'label': 'pendências do contador', 'input': {}, 'ok': false, 'error': 'SEM_PERMISSAO'},
  ],
  'sources': [{'tool': 'detalhar_veiculo', 'label': 'Veículo FIC1A23', 'href': '/operation/fleet/v1'}],
  'provider': 'anthropic', 'model': 'claude-opus-5-5', 'input_tokens': 2000, 'output_tokens': 200, 'cache_read_tokens': 0,
  'cost_usd': '0.012000', 'latency_ms': 1500, 'error_code': null, 'cutoff_at': '2026-11-19T18:00:00Z',
  'created_at': '2026-11-19T18:00:01Z', 'client_id': 'engenheiro-gestao',
};

void main() {
  testWidgets('sem provedor: NÃO CONFIGURADA, sem campo de pergunta e nenhum envio', (tester) async {
    final sent = <http.Request>[];
    await loginOwner(tester, (_) => const OwnerAiScreen(), ['ai.view'], (r) async {
      sent.add(r);
      if (r.url.path == '/api/v1/ai/status') return j(200, status('NOT_CONFIGURED', 'INTEGRAÇÃO NÃO CONFIGURADA: nenhuma chave.'));
      if (r.url.path == '/api/v1/ai/questions') return j(200, {'items': []});
      return j(404, {'status': 404, 'code': 'NOT_FOUND'});
    });
    expect(find.text('INTEGRAÇÃO NÃO CONFIGURADA'), findsOneWidget);
    expect(find.text('Perguntar'), findsNothing);
    expect(sent.where((r) => r.method == 'POST'), isEmpty);
    expect(sent.every((r) => r.url.path.startsWith('/api/v1/ai/')), isTrue);
  });

  testWidgets('pergunta com período e mostra resposta, consultas, limitação, fonte e custo', (tester) async {
    Map<String, dynamic>? body;
    await loginOwner(tester, (_) => const OwnerAiScreen(), ['ai.view'], (r) async {
      if (r.url.path == '/api/v1/ai/status') return j(200, status('CONFIGURED', 'IA ativa.'));
      if (r.url.path == '/api/v1/ai/questions' && r.method == 'GET') return j(200, {'items': body == null ? [] : [answered]});
      if (r.url.path == '/api/v1/ai/questions' && r.method == 'POST') {
        body = jsonDecode(r.body) as Map<String, dynamic>;
        return j(201, answered);
      }
      return j(404, {'status': 404, 'code': 'NOT_FOUND'});
    });
    expect(find.text('IA ativa'), findsOneWidget);
    await tester.enterText(find.widgetWithText(TextField, 'Pergunta'), 'Quanto gastei de combustível?');
    await tester.pump();
    await tester.tap(find.text('Perguntar'));
    await tester.pumpAndSettle();
    expect(body!['question'], 'Quanto gastei de combustível?');
    expect(body!['from'], matches(RegExp(r'^\d{4}-\d{2}-01$')));
    expect(find.text('Combustível do FIC1A23: R\$ 120,00.'), findsOneWidget);
    await scrollTo(tester, find.text('Veículo FIC1A23'));
    expect(find.textContaining('pendências do contador (sem permissão)'), findsOneWidget);
    expect(find.text('• Sem permissão para folha.'), findsOneWidget);
    expect(find.textContaining('US\$ 0.012000'), findsOneWidget);
    expect(find.text('01/11/2026 a 30/11/2026'), findsWidgets);
  });

  testWidgets('sem ai.view o servidor nega e a tela mostra sem permissão', (tester) async {
    await loginOwner(tester, (_) => const OwnerAiScreen(), ['reports.view'], admin: false, (r) async =>
        j(403, {'status': 403, 'code': 'FORBIDDEN', 'detail': 'Sem permissão'}));
    expect(find.text('Perguntar'), findsNothing);
    expect(find.textContaining('permissão'), findsWidgets);
  });

  test('fontes abrem telas do app, nunca uma URL externa', () {
    expect(aiSourceScreen('/operation/fleet/v1'), isA<OwnerVehicleScreen>());
    expect(aiSourceScreen('/reports'), isNotNull);
    expect(aiSourceScreen('https://exemplo.invalid/'), isNull);
  });
}

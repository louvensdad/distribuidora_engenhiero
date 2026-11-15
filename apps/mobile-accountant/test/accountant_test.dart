import 'package:flutter_test/flutter_test.dart';
import 'package:contabil/main.dart';

void main() {
  test('Contábil não oferece dashboard, relatórios, caixa geral nem aprovação', () {
    final caps = config.destinations.expand((d) => d.anyOf);
    for (final c in caps) {
      expect(c, isNot(anyOf(startsWith('dashboard'), startsWith('reports'), startsWith('company_cash'), endsWith('.approve'))));
    }
  });
}

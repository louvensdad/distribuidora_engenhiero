import 'package:flutter_test/flutter_test.dart';
import 'package:gestao/main.dart';

void main() {
  test('Patrão: Início, Central, Relatórios, IA e Mais (cerebro/01)', () {
    expect(config.destinations.map((d) => d.label), ['Início', 'Central', 'Relatórios', 'IA', 'Mais']);
  });
}

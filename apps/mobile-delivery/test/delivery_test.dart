import 'package:flutter_test/flutter_test.dart';
import 'package:entregas/main.dart';

void main() {
  test('Despacho exige capacidade específica de atribuir viagem', () {
    expect(config.destinations.firstWhere((d) => d.id == 'despacho').anyOf, ['trips.assign']);
  });
}

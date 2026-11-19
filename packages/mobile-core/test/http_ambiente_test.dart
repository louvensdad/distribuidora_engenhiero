import 'dart:convert';
import 'dart:io';

import 'package:engenheiro_core/src/http_ambiente.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('sem CA de homologação (produção): cliente padrão, só CAs do sistema', () {
    expect(clienteHttpDoAmbiente(caB64: ''), isNull);
  });

  test('CA de homologação inválida falha no início, nunca vira "aceitar qualquer certificado"', () {
    expect(() => clienteHttpDoAmbiente(caB64: base64Encode(utf8.encode('não é um certificado'))),
        throwsA(isA<TlsException>()));
  });
}

import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:http/io_client.dart';

/// Cliente HTTP do Dart para a API (F16, ADR-030).
///
/// O `dart:io` não usa a configuração de rede do Android nem as CAs de usuário: confia só nas CAs do sistema. Na
/// HOMOLOGAÇÃO a borda usa uma CA privada, entregue apenas ao artefato de homologação por
/// `--dart-define=ENGENHEIRO_CA_HOMOLOG_B64=<PEM em base64>` (ver `infra/operacao/construir_apps.sh`). Nesse caso o
/// cliente confia nas CAs do sistema MAIS essa CA — nunca aceita certificado inválido. Produção não recebe a variável
/// e devolve `null` (cliente padrão, só CAs do sistema).
http.Client? clienteHttpDoAmbiente({String caB64 = const String.fromEnvironment('ENGENHEIRO_CA_HOMOLOG_B64')}) {
  if (caB64.isEmpty) return null;
  final contexto = SecurityContext(withTrustedRoots: true)..setTrustedCertificatesBytes(base64Decode(caB64));
  return IOClient(HttpClient(context: contexto));
}

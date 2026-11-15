import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';

/// Um destino da navegação principal. Aparece só se o usuário tiver alguma das capacidades.
class Destination {
  const Destination({
    required this.id,
    required this.label,
    required this.icon,
    required this.builder,
    this.anyOf = const [],
    this.pendingPhase,
  });

  final String id;
  final String label;
  final String icon;
  /// Vazio = qualquer pessoa autenticada no app.
  final List<String> anyOf;
  final WidgetBuilder builder;
  /// Fase do roteiro que entrega a função; o builder deve mostrar o estado pendente.
  final String? pendingPhase;

  bool allowedFor(bool Function(String) can) => anyOf.isEmpty || anyOf.any(can);
}

/// Identidade e servidores de UM app. Endereços vêm de --dart-define; nenhum segredo aqui.
class AppConfig {
  AppConfig({
    required this.appName,
    required this.applicationId,
    required this.clientId,
    required this.audienceDescription,
    required this.destinations,
    Uri? apiBaseUrl,
    String? issuer,
  })  : apiBaseUrl = apiBaseUrl ?? Uri.parse(const String.fromEnvironment('ENGENHEIRO_API', defaultValue: 'http://localhost:8081')),
        issuer = issuer ?? const String.fromEnvironment('ENGENHEIRO_ISSUER', defaultValue: 'http://localhost:8180/realms/engenheiro') {
    assert(destinations.length <= 5, 'No máximo cinco destinos principais (cerebro/01)');
  }

  final String appName;
  final String applicationId;
  final String clientId;
  /// Ex.: "Balcão e recebimento de fornecedores".
  final String audienceDescription;
  final List<Destination> destinations;
  final Uri apiBaseUrl;
  final String issuer;

  bool get _http => apiBaseUrl.scheme == 'http' || issuer.startsWith('http://');

  /// HTTP só é aceito em build de desenvolvimento. Release exige HTTPS.
  bool get allowInsecure => kDebugMode && _http;
  String? get configurationError =>
      (!kDebugMode && _http) ? 'Build de produção configurado com HTTP. Use HTTPS em ENGENHEIRO_API e ENGENHEIRO_ISSUER.' : null;
}

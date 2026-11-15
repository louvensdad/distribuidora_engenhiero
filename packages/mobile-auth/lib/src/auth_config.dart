/// Configuração OIDC de UM app. O client é público (sem segredo); o redirect usa o próprio applicationId.
class AuthConfig {
  const AuthConfig({
    required this.issuer,
    required this.clientId,
    required this.redirectScheme,
    this.scopes = const ['openid', 'profile'],
    this.allowInsecureConnections = false,
  });

  final String issuer;
  final String clientId;
  /// Igual ao applicationId/bundle id (ex.: br.com.engenheiro.retirada).
  final String redirectScheme;
  final List<String> scopes;
  /// Só para desenvolvimento local em http://localhost. Produção exige HTTPS.
  final bool allowInsecureConnections;

  String get redirectUrl => '$redirectScheme:/oauth2redirect';
  String get postLogoutRedirectUrl => '$redirectScheme:/logout';
}

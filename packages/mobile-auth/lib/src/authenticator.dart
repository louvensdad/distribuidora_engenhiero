import 'package:flutter/services.dart';
import 'package:flutter_appauth/flutter_appauth.dart';
import 'auth_config.dart';

class TokenSet {
  const TokenSet({required this.accessToken, required this.expiresAt, this.refreshToken, this.idToken});
  final String accessToken;
  final DateTime expiresAt;
  final String? refreshToken;
  final String? idToken;

  bool expiresWithin(Duration margin, DateTime now) => !expiresAt.isAfter(now.add(margin));
}

/// Login cancelado pelo usuário (fechou o navegador) — não é erro.
class LoginCancelled implements Exception {}

/// Provedor recusou o refresh (conta bloqueada, sessão revogada no IdP...).
class RefreshRejected implements Exception {}

abstract interface class Authenticator {
  Future<TokenSet> login();
  Future<TokenSet> refresh(String refreshToken);
  Future<void> endSession(String? idToken);
}

/// Implementação AppAuth: Custom Tabs no Android, ASWebAuthenticationSession no iOS. PKCE S256 automático.
class AppAuthAuthenticator implements Authenticator {
  AppAuthAuthenticator(this.config, {FlutterAppAuth? appAuth}) : _appAuth = appAuth ?? const FlutterAppAuth();
  final AuthConfig config;
  final FlutterAppAuth _appAuth;

  @override
  Future<TokenSet> login() async {
    try {
      final r = await _appAuth.authorizeAndExchangeCode(AuthorizationTokenRequest(
        config.clientId,
        config.redirectUrl,
        issuer: config.issuer,
        scopes: config.scopes,
        allowInsecureConnections: config.allowInsecureConnections,
        promptValues: const ['login'],
      ));
      return _toSet(r.accessToken, r.accessTokenExpirationDateTime, r.refreshToken, r.idToken);
    } on PlatformException catch (e) {
      if (e.code.contains('authorize_and_exchange_code_failed') && (e.message ?? '').contains('User cancelled')) {
        throw LoginCancelled();
      }
      if ((e.details?.toString() ?? '').contains('user_did_cancel') || (e.message ?? '').toLowerCase().contains('cancel')) {
        throw LoginCancelled();
      }
      rethrow;
    }
  }

  @override
  Future<TokenSet> refresh(String refreshToken) async {
    try {
      final r = await _appAuth.token(TokenRequest(
        config.clientId,
        config.redirectUrl,
        issuer: config.issuer,
        refreshToken: refreshToken,
        scopes: config.scopes,
        allowInsecureConnections: config.allowInsecureConnections,
      ));
      return _toSet(r.accessToken, r.accessTokenExpirationDateTime, r.refreshToken ?? refreshToken, r.idToken);
    } on PlatformException {
      throw RefreshRejected();
    }
  }

  @override
  Future<void> endSession(String? idToken) async {
    await _appAuth.endSession(EndSessionRequest(
      idTokenHint: idToken,
      postLogoutRedirectUrl: config.postLogoutRedirectUrl,
      issuer: config.issuer,
      allowInsecureConnections: config.allowInsecureConnections,
    ));
  }

  TokenSet _toSet(String? access, DateTime? exp, String? refresh, String? id) {
    if (access == null) throw StateError('Provedor não devolveu access token');
    return TokenSet(accessToken: access, expiresAt: exp ?? DateTime.now().add(const Duration(minutes: 5)),
        refreshToken: refresh, idToken: id);
  }
}

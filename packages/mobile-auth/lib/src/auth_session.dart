import 'authenticator.dart';
import 'secure_store.dart';

/// Estado de autenticação de UM app. Access token só em memória; refresh/id token no armazenamento seguro.
class AuthSession {
  AuthSession({required this.authenticator, required this.store, DateTime Function()? clock})
      : _clock = clock ?? DateTime.now;

  static const _refreshKey = 'eng_refresh_token';
  static const _idKey = 'eng_id_token';
  static const _margin = Duration(seconds: 30);

  final Authenticator authenticator;
  final SecureStore store;
  final DateTime Function() _clock;
  TokenSet? _tokens;
  Future<String?>? _refreshing;

  bool get isSignedIn => _tokens != null;

  /// Retoma sessão salva (renova com o refresh token). Falha → sessão limpa, sem dado sensível em cache.
  Future<bool> restore() async {
    final refresh = await store.read(_refreshKey);
    if (refresh == null) return false;
    try {
      await _apply(await authenticator.refresh(refresh));
      return true;
    } on RefreshRejected {
      await _clear();
      return false;
    }
  }

  Future<void> login() async => _apply(await authenticator.login());

  /// Token para a próxima chamada. Renova antes de expirar ou quando o servidor respondeu 401.
  Future<String?> accessToken({bool forceRefresh = false}) async {
    final t = _tokens;
    if (t == null) return null;
    if (!forceRefresh && !t.expiresWithin(_margin, _clock())) return t.accessToken;
    return _refreshing ??= _doRefresh().whenComplete(() => _refreshing = null);
  }

  Future<String?> _doRefresh() async {
    final refresh = _tokens?.refreshToken ?? await store.read(_refreshKey);
    if (refresh == null) {
      await _clear();
      return null;
    }
    try {
      final t = await authenticator.refresh(refresh);
      await _apply(t);
      return t.accessToken;
    } on RefreshRejected {
      await _clear();
      return null;
    }
  }

  /// Sai no app e no provedor. Mesmo se o provedor falhar, o app esquece tudo localmente.
  Future<void> logout() async {
    final id = _tokens?.idToken ?? await store.read(_idKey);
    await _clear();
    try {
      await authenticator.endSession(id);
    } catch (_) {
      // Sem rede: tokens locais já foram apagados; a sessão do IdP expira sozinha.
    }
  }

  /// Usado quando o servidor diz que a conta não tem acesso a este app: limpa sem abrir navegador.
  Future<void> forget() => _clear();

  Future<void> _apply(TokenSet t) async {
    _tokens = t;
    if (t.refreshToken != null) await store.write(_refreshKey, t.refreshToken!);
    if (t.idToken != null) await store.write(_idKey, t.idToken!);
  }

  Future<void> _clear() async {
    _tokens = null;
    await store.delete(_refreshKey);
    await store.delete(_idKey);
  }
}

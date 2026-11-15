import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_auth/engenheiro_auth.dart';
import 'package:flutter/foundation.dart';

sealed class SessionState {
  const SessionState();
}

class SessionStarting extends SessionState {
  const SessionStarting();
}

class SessionSignedOut extends SessionState {
  const SessionSignedOut({this.message});
  final String? message;
}

class SessionSigningIn extends SessionState {
  const SessionSigningIn();
}

/// Token válido, mas a conta não tem vínculo ativo ou não está liberada para ESTE app.
class SessionNoAccess extends SessionState {
  const SessionNoAccess({this.requestId});
  final String? requestId;
}

class SessionFailed extends SessionState {
  const SessionFailed(this.error);
  final ApiException error;
}

class SessionReady extends SessionState {
  const SessionReady(this.me);
  final Me me;
}

/// Orquestra login, carga de capacidades e saída. O servidor continua autorizando cada chamada.
class SessionController extends ChangeNotifier {
  SessionController({required this.auth, required ApiClient Function(TokenProvider) apiFactory}) {
    api = apiFactory(_token);
  }

  final AuthSession auth;
  late final ApiClient api;
  SessionState _state = const SessionStarting();
  SessionState get state => _state;
  Me? get me => switch (_state) { SessionReady(:final me) => me, _ => null };

  Future<String?> _token({bool forceRefresh = false}) async {
    final t = await auth.accessToken(forceRefresh: forceRefresh);
    if (t == null && _state is SessionReady) _set(const SessionSignedOut(message: 'Sua sessão terminou. Entre novamente.'));
    return t;
  }

  void _set(SessionState s) {
    _state = s;
    notifyListeners();
  }

  Future<void> start() async {
    try {
      if (await auth.restore()) {
        await _loadMe();
      } else {
        _set(const SessionSignedOut());
      }
    } catch (_) {
      _set(const SessionSignedOut());
    }
  }

  Future<void> signIn() async {
    _set(const SessionSigningIn());
    try {
      await auth.login();
    } on LoginCancelled {
      _set(const SessionSignedOut());
      return;
    } catch (_) {
      _set(const SessionSignedOut(message: 'Não foi possível entrar. Verifique a conexão e tente de novo.'));
      return;
    }
    await _loadMe();
  }

  Future<void> reload() => _loadMe();

  Future<void> _loadMe() async {
    try {
      _set(SessionReady(await api.me()));
    } on ApiException catch (e) {
      if (e.isForbidden) {
        _set(SessionNoAccess(requestId: e.requestId));
      } else if (e.isUnauthenticated) {
        await auth.forget();
        _set(const SessionSignedOut(message: 'Sua sessão terminou. Entre novamente.'));
      } else {
        _set(SessionFailed(e));
      }
    }
  }

  Future<void> signOut() async {
    await auth.logout();
    _set(const SessionSignedOut());
  }
}

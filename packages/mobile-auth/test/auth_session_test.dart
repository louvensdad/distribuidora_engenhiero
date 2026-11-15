import 'package:engenheiro_auth/engenheiro_auth.dart';
import 'package:flutter_test/flutter_test.dart';

class FakeAuth implements Authenticator {
  int refreshes = 0;
  bool rejectRefresh = false;
  String? endedWith;
  DateTime now = DateTime(2026, 11, 14, 12);

  @override
  Future<TokenSet> login() async => TokenSet(accessToken: 'a0', expiresAt: now.add(const Duration(minutes: 5)), refreshToken: 'r0', idToken: 'id0');

  @override
  Future<TokenSet> refresh(String refreshToken) async {
    if (rejectRefresh) throw RefreshRejected();
    refreshes++;
    await Future<void>.delayed(const Duration(milliseconds: 5));
    return TokenSet(accessToken: 'a$refreshes', expiresAt: now.add(const Duration(minutes: 5)), refreshToken: 'r$refreshes', idToken: 'id0');
  }

  @override
  Future<void> endSession(String? idToken) async => endedWith = idToken;
}

void main() {
  late FakeAuth auth;
  late MemorySecureStore store;
  late AuthSession session;

  setUp(() {
    auth = FakeAuth();
    store = MemorySecureStore();
    session = AuthSession(authenticator: auth, store: store, clock: () => auth.now);
  });

  test('access token fica em memória; só refresh e id token vão para o armazenamento seguro', () async {
    await session.login();
    expect(await session.accessToken(), 'a0');
    expect(store.values.keys.toSet(), {'eng_refresh_token', 'eng_id_token'});
    expect(store.values.values, isNot(contains('a0')));
  });

  test('renova antes de expirar e uma única vez para chamadas simultâneas', () async {
    await session.login();
    auth.now = auth.now.add(const Duration(minutes: 4, seconds: 45)); // dentro da margem de 30 s
    final tokens = await Future.wait([session.accessToken(), session.accessToken(), session.accessToken()]);
    expect(tokens, ['a1', 'a1', 'a1']);
    expect(auth.refreshes, 1);
    expect(store.values['eng_refresh_token'], 'r1');
  });

  test('refresh recusado (conta revogada) limpa tudo e devolve null', () async {
    await session.login();
    auth.rejectRefresh = true;
    expect(await session.accessToken(forceRefresh: true), isNull);
    expect(session.isSignedIn, isFalse);
    expect(store.values, isEmpty);
  });

  test('restore usa o refresh salvo; sem refresh não há sessão', () async {
    expect(await session.restore(), isFalse);
    store.values['eng_refresh_token'] = 'salvo';
    expect(await session.restore(), isTrue);
    expect(await session.accessToken(), 'a1');
  });

  test('logout apaga localmente e encerra no provedor com id_token_hint', () async {
    await session.login();
    await session.logout();
    expect(session.isSignedIn, isFalse);
    expect(store.values, isEmpty);
    expect(auth.endedWith, 'id0');
  });

  test('config usa o applicationId como esquema de redirect', () {
    const c = AuthConfig(issuer: 'https://idp.invalid/realms/x', clientId: 'engenheiro-retirada', redirectScheme: 'br.com.engenheiro.retirada');
    expect(c.redirectUrl, 'br.com.engenheiro.retirada:/oauth2redirect');
    expect(c.scopes, contains('openid'));
  });
}

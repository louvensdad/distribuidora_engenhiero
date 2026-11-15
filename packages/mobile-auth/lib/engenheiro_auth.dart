/// Autenticação dos apps: OIDC Authorization Code + PKCE no navegador do sistema (AppAuth),
/// refresh token no armazenamento seguro do SO, access token só em memória. Nenhum segredo no app.
library;

export 'src/auth_config.dart';
export 'src/auth_session.dart';
export 'src/authenticator.dart';
export 'src/secure_store.dart';

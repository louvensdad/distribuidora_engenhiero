import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:retirada/main.dart';

/// O app é uma distribuição independente: identidade nativa e configuração OIDC precisam coincidir.
void main() {
  test('applicationId, client OIDC, redirect e nome coincidem com o projeto nativo', () {
    expect(config.applicationId, 'br.com.engenheiro.retirada');
    expect(config.clientId, 'engenheiro-retirada');
    final gradle = File('android/app/build.gradle.kts').readAsStringSync();
    expect(gradle, contains('applicationId = "${config.applicationId}"'));
    expect(gradle, contains('manifestPlaceholders["appAuthRedirectScheme"] = "${config.applicationId}"'));
    final manifest = File('android/app/src/main/AndroidManifest.xml').readAsStringSync();
    expect(manifest, contains('android:label="${config.appName}"'));
    expect(manifest, contains('android:allowBackup="false"'));
    expect(manifest, isNot(contains('cleartextTrafficPermitted')));
    expect(manifest, isNot(contains('networkSecurityConfig'))); // HTTP só no build debug
    expect(File('ios/Runner/Info.plist').readAsStringSync(), contains('<string>${config.applicationId}</string>'));
  });

  test('no máximo cinco destinos, ids únicos, nenhum segredo na configuração', () {
    expect(config.destinations.length, lessThanOrEqualTo(5));
    expect(config.destinations.map((d) => d.id).toSet().length, config.destinations.length);
    final src = File('lib/main.dart').readAsStringSync().toLowerCase();
    for (final proibido in ['client_secret', 'clientsecret', 'password', 'senha =', 'api_key']) {
      expect(src, isNot(contains(proibido)));
    }
  });
}

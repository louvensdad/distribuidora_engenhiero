import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Armazenamento de chave/valor protegido pelo SO (Keystore/Keychain).
abstract interface class SecureStore {
  Future<String?> read(String key);
  Future<void> write(String key, String value);
  Future<void> delete(String key);
}

class OsSecureStore implements SecureStore {
  OsSecureStore() : _s = const FlutterSecureStorage(aOptions: AndroidOptions(resetOnError: true));
  final FlutterSecureStorage _s;

  @override
  Future<String?> read(String key) => _s.read(key: key);
  @override
  Future<void> write(String key, String value) => _s.write(key: key, value: value);
  @override
  Future<void> delete(String key) => _s.delete(key: key);
}

/// Para testes e para quando o armazenamento seguro não existir: nada persiste.
class MemorySecureStore implements SecureStore {
  final Map<String, String> values = {};
  @override
  Future<String?> read(String key) async => values[key];
  @override
  Future<void> write(String key, String value) async => values[key] = value;
  @override
  Future<void> delete(String key) async => values.remove(key);
}

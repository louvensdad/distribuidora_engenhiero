import 'dart:convert';
import 'dart:math';
import 'package:http/http.dart' as http;
import 'api_exception.dart';
import 'models.dart';

/// Fornece o access token atual (em memória). [forceRefresh] pede renovação após um 401.
typedef TokenProvider = Future<String?> Function({bool forceRefresh});

/// Cliente da API. Identidade vem do token; o corpo nunca envia organização, unidade ou autor.
class ApiClient {
  ApiClient({required this.baseUrl, required this.tokenProvider, http.Client? httpClient, this.timeout = const Duration(seconds: 20)})
      : _http = httpClient ?? http.Client();

  final Uri baseUrl;
  final TokenProvider tokenProvider;
  final Duration timeout;
  final http.Client _http;

  Future<Me> me() async => Me.fromJson(await _get('/api/v1/me/capabilities'));

  Future<ApiPage<Customer>> customers({String? query, String? cursor, int limit = 25}) async => ApiPage.fromJson(
      await _get('/api/v1/customers', {'limit': '$limit', 'q': ?query, 'cursor': ?cursor}), Customer.fromJson);

  Future<Customer> createCustomer({required String name, String? contact, String? address, String? personalDocument}) async =>
      Customer.fromJson(await _post('/api/v1/customers', {
        'name': name,
        if (contact != null && contact.trim().isNotEmpty) 'contact': contact.trim(),
        if (address != null && address.trim().isNotEmpty) 'address': address.trim(),
        if (personalDocument != null && personalDocument.trim().isNotEmpty) 'personal_document': personalDocument.trim(),
      }));

  Future<ApiPage<UserSummary>> users() async => ApiPage.fromJson(await _get('/api/v1/users'), UserSummary.fromJson);

  Future<ApiPage<AuditEvent>> auditEvents({String? cursor, String? actorUserId, String? clientId, int limit = 25}) async =>
      ApiPage.fromJson(
      await _get('/api/v1/audit-events',
          {'limit': '$limit', 'cursor': ?cursor, 'actor_user_id': ?actorUserId, 'client_id': ?clientId}),
      AuditEvent.fromJson);

  // ---- F03: estoque ----
  Future<ApiPage<Product>> products({int limit = 100}) async =>
      ApiPage.fromJson(await _get('/api/v1/products', {'limit': '$limit'}), Product.fromJson);

  Future<ApiPage<Supplier>> suppliers({int limit = 100}) async =>
      ApiPage.fromJson(await _get('/api/v1/suppliers', {'limit': '$limit'}), Supplier.fromJson);

  Future<ApiPage<StockBalance>> balances({String? query, int limit = 100}) async =>
      ApiPage.fromJson(await _get('/api/v1/inventory/balances', {'limit': '$limit', 'q': ?query}), StockBalance.fromJson);

  Future<ApiPage<Receipt>> receipts({String? status, int limit = 50}) async =>
      ApiPage.fromJson(await _get('/api/v1/inventory/receipts', {'limit': '$limit', 'status': ?status}), Receipt.fromJson);

  Future<Receipt> receipt(String id) async => Receipt.fromJson(await _get('/api/v1/inventory/receipts/$id'));

  /// [idempotencyKey]: a mesma chave em nova tentativa nunca cria um segundo recebimento.
  Future<Receipt> createReceipt({required String idempotencyKey, required String supplierId, String? documentNumber,
          String? notes, String? freightAmount, required List<ReceiptLineInput> lines}) async =>
      Receipt.fromJson(await _post('/api/v1/inventory/receipts', {
        'supplier_id': supplierId,
        if (documentNumber != null && documentNumber.trim().isNotEmpty) 'document_number': documentNumber.trim(),
        if (notes != null && notes.trim().isNotEmpty) 'notes': notes.trim(),
        if (freightAmount != null && freightAmount.trim().isNotEmpty) 'freight_amount': decimalText(freightAmount),
        'lines': [for (final l in lines) l.toJson()],
      }, idempotencyKey: idempotencyKey));

  Future<Receipt> confirmReceipt({required String id, required int version, required String idempotencyKey}) async =>
      Receipt.fromJson(await _post('/api/v1/inventory/receipts/$id/confirm', null, ifMatch: '"$version"', idempotencyKey: idempotencyKey));

  /// Chave aleatória para um comando (uma por intenção do usuário).
  static String newIdempotencyKey() {
    final r = Random.secure();
    return 'app-${List.generate(16, (_) => r.nextInt(256).toRadixString(16).padLeft(2, '0')).join()}';
  }

  Future<Map<String, dynamic>> _get(String path, [Map<String, String?> query = const {}]) {
    final q = {for (final e in query.entries) if (e.value != null && e.value!.isNotEmpty) e.key: e.value!};
    return _send(() => http.Request('GET', baseUrl.replace(path: path, queryParameters: q.isEmpty ? null : q)));
  }

  Future<Map<String, dynamic>> _post(String path, Object? body, {String? ifMatch, String? idempotencyKey}) => _send(() {
        final r = http.Request('POST', baseUrl.replace(path: path));
        if (body != null) {
          r
            ..headers['Content-Type'] = 'application/json'
            ..body = jsonEncode(body);
        }
        if (ifMatch != null) r.headers['If-Match'] = ifMatch;
        if (idempotencyKey != null) r.headers['Idempotency-Key'] = idempotencyKey;
        return r;
      });

  /// Um 401 provoca uma única renovação de token e nova tentativa. Mutações só são repetidas após 401
  /// (servidor recusou antes de processar), nunca após timeout.
  Future<Map<String, dynamic>> _send(http.Request Function() build, {bool retried = false}) async {
    final token = await tokenProvider(forceRefresh: retried);
    if (token == null) throw ApiException(status: 401, code: 'UNAUTHENTICATED', detail: 'Sessão encerrada. Entre novamente.');
    final req = build()
      ..headers['Authorization'] = 'Bearer $token'
      ..headers['Accept'] = 'application/json';
    http.Response res;
    try {
      res = await http.Response.fromStream(await _http.send(req).timeout(timeout));
    } on ApiException {
      rethrow;
    } catch (_) {
      throw ApiException(status: 0, code: 'NETWORK_ERROR', detail: 'Sem conexão com o servidor.');
    }
    if (res.statusCode == 401 && !retried) return _send(build, retried: true);
    final body = res.bodyBytes.isEmpty ? null : _decode(utf8.decode(res.bodyBytes, allowMalformed: true));
    if (res.statusCode >= 400) throw ApiException.fromProblem(res.statusCode, body);
    return body ?? const {};
  }

  static Map<String, dynamic>? _decode(String s) {
    try {
      final v = jsonDecode(s);
      return v is Map<String, dynamic> ? v : null;
    } catch (_) {
      return null;
    }
  }
}

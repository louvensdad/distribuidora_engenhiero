import 'dart:convert';
import 'dart:math';
import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart' show MediaType;
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

  /// [unitCosts]: linha → custo unitário digitado (só quem vê custo); [dueDate] AAAA-MM-DD da conta a pagar.
  Future<Receipt> confirmReceipt({required String id, required int version, required String idempotencyKey,
      Map<int, String> unitCosts = const {}, String? freightAmount, String? dueDate}) async {
    final costs = {for (final e in unitCosts.entries) if (e.value.trim().isNotEmpty) e.key: decimalText(e.value)};
    final body = <String, dynamic>{
      if (costs.isNotEmpty) 'lines': [for (final e in costs.entries) {'line_no': e.key, 'unit_cost': e.value}],
      if (freightAmount != null && freightAmount.trim().isNotEmpty) 'freight_amount': decimalText(freightAmount),
      if (dueDate != null && dueDate.isNotEmpty) 'payment_due_date': dueDate,
    };
    return Receipt.fromJson(await _post('/api/v1/inventory/receipts/$id/confirm', body.isEmpty ? null : body,
        ifMatch: '"$version"', idempotencyKey: idempotencyKey));
  }

  Future<List<DocumentInfo>> receiptAttachments(String id) async {
    final j = await _get('/api/v1/inventory/receipts/$id/attachments');
    return [for (final d in j['items'] as List) DocumentInfo.fromJson(d as Map<String, dynamic>)];
  }

  /// Envia foto/arquivo (JPEG, PNG ou PDF até 10 MB). O servidor valida o conteúdo real e pode recusar.
  Future<DocumentInfo> uploadReceiptAttachment({required String id, required String fileName, required List<int> bytes,
          required String contentType}) async =>
      DocumentInfo.fromJson(await _send(() {
        final r = http.MultipartRequest('POST', baseUrl.replace(path: '/api/v1/inventory/receipts/$id/attachments'))
          ..files.add(http.MultipartFile.fromBytes('file', bytes, filename: fileName, contentType: MediaType.parse(contentType)));
        return r;
      }));

  // ---- F04 ----
  Future<ApiPage<Customer>> customersAll() async => ApiPage.fromJson(await _get('/api/v1/customers', {'limit': '100'}), Customer.fromJson);

  Future<ApiPage<OrderInfo>> orders({int limit = 50}) async =>
      ApiPage.fromJson(await _get('/api/v1/orders', {'limit': '$limit'}), OrderInfo.fromJson);

  Future<OrderInfo> order(String id) async => OrderInfo.fromJson(await _get('/api/v1/orders/$id'));

  /// Preço e total vêm do servidor; a chave evita pedido duplicado em nova tentativa.
  Future<OrderInfo> createOrder({required String idempotencyKey, required String customerId,
          required List<({String productId, String? unit, String quantity})> items}) async =>
      OrderInfo.fromJson(await _post('/api/v1/orders', {
        'type': 'PICKUP',
        'customer_id': customerId,
        'items': [
          for (final i in items)
            {'product_id': i.productId, if (i.unit != null && i.unit!.isNotEmpty) 'unit': i.unit, 'quantity': decimalText(i.quantity)}
        ],
      }, idempotencyKey: idempotencyKey));

  /// Um meio por vez no app de balcão: dinheiro (com valor apresentado) ou Pix (fica aguardando conferência).
  Future<({List<PaymentInfo> payments, OrderInfo order})> payOrder({required String orderId, required String idempotencyKey,
      required String method, required String amount, String? presentedCash}) async {
    final j = await _post('/api/v1/orders/$orderId/payments', {
      'parts': [
        {'method': method, 'amount': amount, if (presentedCash != null) 'presented_cash': decimalText(presentedCash)}
      ],
    }, idempotencyKey: idempotencyKey);
    return (
      payments: [for (final p in j['payments'] as List) PaymentInfo.fromJson(p as Map<String, dynamic>)],
      order: OrderInfo.fromJson(j['order'] as Map<String, dynamic>),
    );
  }

  Future<OrderInfo> fulfillOrder({required String orderId, required String idempotencyKey}) async =>
      OrderInfo.fromJson(await _post('/api/v1/orders/$orderId/fulfillments', null, idempotencyKey: idempotencyKey));

  Future<CashSessionInfo?> currentCash() async {
    final j = await _get('/api/v1/cash-sessions/current');
    final s = j['session'];
    return s == null ? null : CashSessionInfo.fromJson(s as Map<String, dynamic>);
  }

  Future<CashSessionInfo> openCash({required String idempotencyKey, required String openingFloat}) async =>
      CashSessionInfo.fromJson(await _post('/api/v1/cash-sessions', {'opening_float': decimalText(openingFloat)},
          idempotencyKey: idempotencyKey));

  Future<CashSessionInfo> closeCash({required String id, required int version, required String idempotencyKey,
          required String counted, String? justification}) async =>
      CashSessionInfo.fromJson(await _post('/api/v1/cash-sessions/$id/close', {
        'counted': decimalText(counted),
        if (justification != null && justification.trim().isNotEmpty) 'justification': justification.trim(),
      }, ifMatch: '"$version"', idempotencyKey: idempotencyKey));

  // ---- F05: viagens e frota (motorista: só o atribuído; o servidor filtra) ----
  Future<List<TripSummaryInfo>> trips({int limit = 50}) async {
    final j = await _get('/api/v1/trips', {'limit': '$limit'});
    return [for (final t in j['items'] as List) TripSummaryInfo.fromJson(t as Map<String, dynamic>)];
  }

  Future<TripInfo> trip(String id) async => TripInfo.fromJson(await _get('/api/v1/trips/$id'));

  Future<TripInfo> departTrip({required String id, required int version, required String odometer}) async =>
      TripInfo.fromJson(await _post('/api/v1/trips/$id/depart', {'odometer': decimalText(odometer)}, ifMatch: '"$version"'));

  /// Entrega total (sem [lines]) ou parcial (com motivo). [payment]: um meio, cobrado junto, no caixa do motorista.
  Future<TripInfo> deliverStop({required String tripId, required String stopId, required String idempotencyKey,
      required String recipientName, Map<int, String>? lines, String? partialReason,
      ({String method, String amount, String? presentedCash, String? reference})? payment}) async =>
      TripInfo.fromJson(await _post('/api/v1/trips/$tripId/deliveries', {
        'stop_id': stopId,
        'recipient_name': recipientName.trim(),
        if (lines != null) 'lines': [for (final e in lines.entries) {'line_no': e.key, 'quantity': decimalText(e.value)}],
        if (partialReason != null && partialReason.trim().isNotEmpty) 'partial_reason': partialReason.trim(),
        if (payment != null)
          'payment': {
            'parts': [
              {
                'method': payment.method,
                'amount': payment.amount,
                if (payment.presentedCash != null) 'presented_cash': decimalText(payment.presentedCash!),
                if (payment.reference != null && payment.reference!.trim().isNotEmpty) 'provider_reference': payment.reference!.trim(),
              }
            ]
          },
      }, idempotencyKey: idempotencyKey));

  Future<TripInfo> tripIncident({required String tripId, String? stopId, required String idempotencyKey, required String kind,
          required String description, String requestedAction = 'NONE'}) async =>
      TripInfo.fromJson(await _post('/api/v1/trips/$tripId/incidents', {
        'stop_id': ?stopId,
        'kind': kind,
        'description': description.trim(),
        'requested_action': requestedAction,
      }, idempotencyKey: idempotencyKey));

  Future<TripInfo> returnTrip({required String id, required int version, required String idempotencyKey,
          required String odometer, required String cashDeclared, String? justification}) async =>
      TripInfo.fromJson(await _post('/api/v1/trips/$id/return', {
        'odometer': decimalText(odometer),
        'cash_declared': decimalText(cashDeclared),
        if (justification != null && justification.trim().isNotEmpty) 'justification': justification.trim(),
      }, ifMatch: '"$version"', idempotencyKey: idempotencyKey));

  /// Evidência da entrega (foto do canhoto/carga) na parada; autorizada pela viagem atribuída.
  Future<DocumentInfo> uploadStopEvidence({required String tripId, required String stopId, required String fileName,
          required List<int> bytes, required String contentType}) async =>
      DocumentInfo.fromJson(await _send(() => http.MultipartRequest(
          'POST', baseUrl.replace(path: '/api/v1/trips/$tripId/stops/$stopId/attachments'))
        ..files.add(http.MultipartFile.fromBytes('file', bytes, filename: fileName, contentType: MediaType.parse(contentType)))));

  Future<List<VehicleInfo>> vehicles() async {
    final j = await _get('/api/v1/vehicles');
    return [for (final v in j['items'] as List) VehicleInfo.fromJson(v as Map<String, dynamic>)];
  }

  Future<List<VehicleExpenseInfo>> vehicleExpenses({String? vehicleId}) async {
    final j = await _get('/api/v1/vehicle-expenses', {'vehicle_id': vehicleId});
    return [for (final e in j['items'] as List) VehicleExpenseInfo.fromJson(e as Map<String, dynamic>)];
  }

  Future<VehicleExpenseInfo> registerFuel({required String vehicleId, required String idempotencyKey, required String odometer,
          required String liters, required String fuelType, required bool fullTank, required String amount, String? station,
          required String paidWith}) async =>
      VehicleExpenseInfo.fromJson(await _post('/api/v1/vehicles/$vehicleId/fuel', {
        'odometer': decimalText(odometer),
        'liters': decimalText(liters),
        'fuel_type': fuelType,
        'full_tank': fullTank,
        'amount': decimalText(amount),
        if (station != null && station.trim().isNotEmpty) 'station': station.trim(),
        'paid_with': paidWith,
      }, idempotencyKey: idempotencyKey));

  // ---- F06: resultado gerencial, caixa e conciliação (os mesmos números do site) ----
  Future<Map<String, dynamic>> financeSummary({required String from, required String to}) =>
      _get('/api/v1/reports/summary', {'from': from, 'to': to});

  Future<Map<String, dynamic>> reconciliation() => _get('/api/v1/finance/reconciliation');

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
  Future<Map<String, dynamic>> _send(http.BaseRequest Function() build, {bool retried = false}) async {
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

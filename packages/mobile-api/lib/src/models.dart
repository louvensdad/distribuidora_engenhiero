// Modelos espelhando packages/contracts/openapi.json (endpoints implementados).

class CapabilityView {
  const CapabilityView({required this.capability, required this.resource, required this.action, required this.scopes, this.maxAmount});
  final String capability;
  final String resource;
  final String action;
  final List<String> scopes;
  /// Decimal em texto (BRL). Nunca converter para double.
  final String? maxAmount;

  factory CapabilityView.fromJson(Map<String, dynamic> j) => CapabilityView(
        capability: j['capability'] as String,
        resource: j['resource'] as String,
        action: j['action'] as String,
        scopes: [for (final s in j['scopes'] as List) s as String],
        maxAmount: j['max_amount'] as String?,
      );
}

class Me {
  const Me({
    required this.userId,
    required this.displayName,
    required this.organizationName,
    required this.branchName,
    required this.clientId,
    required this.admin,
    required this.roleTemplate,
    required this.sector,
    required this.capabilities,
    required this.fields,
  });

  final String userId;
  final String displayName;
  final String organizationName;
  final String branchName;
  final String clientId;
  final bool admin;
  final String roleTemplate;
  final String sector;
  final List<CapabilityView> capabilities;
  final Map<String, bool> fields;

  bool can(String capability) => capabilities.any((c) => c.capability == capability);
  bool canSeeField(String field) => fields[field] == true;

  factory Me.fromJson(Map<String, dynamic> j) => Me(
        userId: (j['user'] as Map)['id'] as String,
        displayName: (j['user'] as Map)['display_name'] as String,
        organizationName: (j['organization'] as Map)['name'] as String,
        branchName: (j['branch'] as Map)['name'] as String,
        clientId: j['client_id'] as String,
        admin: j['admin'] as bool,
        roleTemplate: j['role_template'] as String,
        sector: j['sector'] as String,
        capabilities: [for (final c in j['capabilities'] as List) CapabilityView.fromJson(c as Map<String, dynamic>)],
        fields: {for (final e in (j['fields'] as Map).entries) e.key as String: e.value as bool},
      );
}

class ApiPage<T> {
  const ApiPage(this.items, this.nextCursor);
  final List<T> items;
  final String? nextCursor;

  static ApiPage<T> fromJson<T>(Map<String, dynamic> j, T Function(Map<String, dynamic>) item) =>
      ApiPage([for (final i in j['items'] as List) item(i as Map<String, dynamic>)], j['next_cursor'] as String?);
}

class Customer {
  const Customer({required this.id, required this.name, required this.branch, this.contact, this.address,
      this.personalDocument, required this.maskedFields});
  final String id;
  final String name;
  final String branch;
  final String? contact;
  final String? address;
  final String? personalDocument;
  final List<String> maskedFields;

  bool isMasked(String field) => maskedFields.contains(field);

  factory Customer.fromJson(Map<String, dynamic> j) => Customer(
        id: j['id'] as String,
        name: j['name'] as String,
        branch: j['branch'] as String,
        contact: j['contact'] as String?,
        address: j['address'] as String?,
        personalDocument: j['personal_document'] as String?,
        maskedFields: [for (final f in (j['masked_fields'] as List?) ?? const []) f as String],
      );
}

class UserSummary {
  const UserSummary({required this.id, required this.displayName, required this.status, required this.sector,
      required this.roleTemplate, required this.admin, required this.branch});
  final String id;
  final String displayName;
  final String status;
  final String sector;
  final String roleTemplate;
  final bool admin;
  final String branch;

  factory UserSummary.fromJson(Map<String, dynamic> j) => UserSummary(
        id: j['id'] as String,
        displayName: j['display_name'] as String,
        status: j['status'] as String,
        sector: j['sector'] as String,
        roleTemplate: j['role_template'] as String,
        admin: j['admin'] as bool,
        branch: j['branch'] as String,
      );
}

class AuditEvent {
  const AuditEvent({required this.id, required this.occurredAt, this.actorName, required this.actorKind, this.clientId,
      required this.action, this.entityType, required this.result, this.reason});
  final String id;
  final DateTime occurredAt;
  final String? actorName;
  final String actorKind;
  final String? clientId;
  final String action;
  final String? entityType;
  final String result;
  final String? reason;

  factory AuditEvent.fromJson(Map<String, dynamic> j) => AuditEvent(
        id: j['id'] as String,
        occurredAt: DateTime.parse(j['occurred_at'] as String),
        actorName: j['actor_name'] as String?,
        actorKind: j['actor_kind'] as String,
        clientId: j['client_id'] as String?,
        action: j['action'] as String,
        entityType: j['entity_type'] as String?,
        result: j['result'] as String,
        reason: j['reason'] as String?,
      );
}

// ---- F03: catálogo, fornecedores e estoque. Quantidades e dinheiro em texto decimal (nunca double). ----

List<String> _masked(Map<String, dynamic> j) => [for (final f in (j['masked_fields'] as List?) ?? const []) f as String];

class ProductConversion {
  const ProductConversion({required this.unit, required this.factor});
  final String unit;
  final String factor;
  factory ProductConversion.fromJson(Map<String, dynamic> j) =>
      ProductConversion(unit: j['unit'] as String, factor: j['factor'] as String);
}

class Product {
  const Product({required this.id, required this.sku, required this.name, required this.baseUnit,
      required this.baseUnitConfirmed, required this.conversions, this.prices = const {}});
  /// Preço vigente por medida (texto decimal do servidor).
  final Map<String, String> prices;
  final String id;
  final String sku;
  final String name;
  final String baseUnit;
  final bool baseUnitConfirmed;
  final List<ProductConversion> conversions;

  factory Product.fromJson(Map<String, dynamic> j) => Product(
        id: j['id'] as String,
        sku: j['sku'] as String,
        name: j['name'] as String,
        baseUnit: j['base_unit'] as String,
        baseUnitConfirmed: j['base_unit_confirmed'] as bool,
        conversions: [for (final c in (j['conversions'] as List?) ?? const []) ProductConversion.fromJson(c as Map<String, dynamic>)],
        prices: {for (final p in (j['prices'] as List?) ?? const []) (p as Map)['unit'] as String: p['price'] as String},
      );
}

class Supplier {
  const Supplier({required this.id, required this.name, this.contact, required this.maskedFields});
  final String id;
  final String name;
  final String? contact;
  final List<String> maskedFields;
  factory Supplier.fromJson(Map<String, dynamic> j) =>
      Supplier(id: j['id'] as String, name: j['name'] as String, contact: j['contact'] as String?, maskedFields: _masked(j));
}

class StockBalance {
  const StockBalance({required this.productId, required this.productName, required this.sku, required this.baseUnit,
      required this.branch, required this.physical, required this.reserved, required this.available, required this.lost,
      this.value, this.averageCost, required this.maskedFields});
  final String productId;
  final String productName;
  final String sku;
  final String baseUnit;
  final String branch;
  final String physical;
  final String reserved;
  final String available;
  final String lost;
  final String? value;
  final String? averageCost;
  final List<String> maskedFields;
  bool get costHidden => maskedFields.contains('value');

  factory StockBalance.fromJson(Map<String, dynamic> j) => StockBalance(
        productId: j['product_id'] as String,
        productName: j['product_name'] as String,
        sku: j['sku'] as String,
        baseUnit: j['base_unit'] as String,
        branch: j['branch'] as String,
        physical: j['physical'] as String,
        reserved: j['reserved'] as String,
        available: j['available'] as String,
        lost: (j['lost'] as String?) ?? '0',
        value: j['value'] as String?,
        averageCost: j['average_cost'] as String?,
        maskedFields: _masked(j),
      );
}

class ReceiptLine {
  const ReceiptLine({required this.lineNo, required this.productName, required this.unit, required this.baseUnit,
      this.quantityExpected, required this.quantityReceived, required this.baseQuantity, this.difference, this.lot, this.unitCost});
  final int lineNo;
  final String productName;
  final String unit;
  final String baseUnit;
  final String? quantityExpected;
  final String quantityReceived;
  final String baseQuantity;
  final String? difference;
  final String? lot;
  final String? unitCost;

  factory ReceiptLine.fromJson(Map<String, dynamic> j) => ReceiptLine(
        lineNo: j['line_no'] as int,
        productName: j['product_name'] as String,
        unit: j['unit'] as String,
        baseUnit: j['base_unit'] as String,
        quantityExpected: j['quantity_expected'] as String?,
        quantityReceived: j['quantity_received'] as String,
        baseQuantity: j['base_quantity'] as String,
        difference: j['difference'] as String?,
        lot: j['lot'] as String?,
        unitCost: j['unit_cost'] as String?,
      );
}

class Receipt {
  const Receipt({required this.id, required this.status, required this.supplierName, this.documentNumber, this.totalCost,
      required this.receivedAt, required this.createdByName, this.confirmedByName, required this.version, required this.lines,
      required this.maskedFields});
  final String id;
  final String status;
  final String supplierName;
  final String? documentNumber;
  final String? totalCost;
  final DateTime receivedAt;
  final String createdByName;
  final String? confirmedByName;
  final int version;
  final List<ReceiptLine> lines;
  final List<String> maskedFields;
  bool get isDraft => status == 'DRAFT';
  bool get costHidden => maskedFields.contains('total_cost');

  factory Receipt.fromJson(Map<String, dynamic> j) => Receipt(
        id: j['id'] as String,
        status: j['status'] as String,
        supplierName: j['supplier_name'] as String,
        documentNumber: j['document_number'] as String?,
        totalCost: j['total_cost'] as String?,
        receivedAt: DateTime.parse(j['received_at'] as String),
        createdByName: j['created_by_name'] as String,
        confirmedByName: j['confirmed_by_name'] as String?,
        version: j['version'] as int,
        lines: [for (final l in j['lines'] as List) ReceiptLine.fromJson(l as Map<String, dynamic>)],
        maskedFields: _masked(j),
      );
}

/// Linha digitada no app. Custo só é enviado por quem vê custo de compra; o servidor recusa o resto.
class ReceiptLineInput {
  const ReceiptLineInput({required this.productId, this.unit, this.quantityExpected, required this.quantityReceived, this.lot, this.unitCost});
  final String productId;
  final String? unit;
  final String? quantityExpected;
  final String quantityReceived;
  final String? lot;
  final String? unitCost;

  Map<String, dynamic> toJson() => {
        'product_id': productId,
        if (unit != null && unit!.isNotEmpty) 'unit': unit,
        if (quantityExpected != null && quantityExpected!.trim().isNotEmpty) 'quantity_expected': decimalText(quantityExpected!),
        'quantity_received': decimalText(quantityReceived),
        if (lot != null && lot!.trim().isNotEmpty) 'lot': lot!.trim(),
        if (unitCost != null && unitCost!.trim().isNotEmpty) 'unit_cost': decimalText(unitCost!),
      };
}

/// Texto digitado → decimal do contrato, sem conversão numérica: "1.234,5" → "1234.5"; "12,5" → "12.5".
/// Sem vírgula, ponto seguido de grupos de 3 dígitos é milhar ("45.200" → "45200"); senão é decimal ("12.5").
String decimalText(String typed) {
  final t = typed.trim().replaceAll(' ', '');
  if (t.contains(',')) return t.replaceAll('.', '').replaceAll(',', '.');
  return RegExp(r'^\d{1,3}(\.\d{3})+$').hasMatch(t) ? t.replaceAll('.', '') : t;
}

/// Anexo privado (metadados). Bytes só pelo download autorizado.
class DocumentInfo {
  const DocumentInfo({required this.id, required this.fileName, required this.mime, required this.sizeBytes, required this.state,
      this.rejection, required this.createdByName});
  final String id;
  final String fileName;
  final String mime;
  final int sizeBytes;
  final String state;
  final String? rejection;
  final String createdByName;
  bool get available => state == 'AVAILABLE';

  factory DocumentInfo.fromJson(Map<String, dynamic> j) => DocumentInfo(
        id: j['id'] as String,
        fileName: j['file_name'] as String,
        mime: j['mime'] as String,
        sizeBytes: j['size_bytes'] as int,
        state: j['state'] as String,
        rejection: j['rejection'] as String?,
        createdByName: j['created_by_name'] as String,
      );
}

// ---- F04: pedidos, pagamentos e caixa ----
class OrderLineInfo {
  const OrderLineInfo({required this.lineNo, required this.productName, required this.unit, required this.quantity,
      required this.unitPrice, required this.net, required this.fulfilled, required this.remaining});
  final int lineNo;
  final String productName;
  final String unit;
  final String quantity;
  final String unitPrice;
  final String net;
  final String fulfilled;
  final String remaining;
  factory OrderLineInfo.fromJson(Map<String, dynamic> j) => OrderLineInfo(
        lineNo: j['line_no'] as int,
        productName: j['product_name'] as String,
        unit: j['unit'] as String,
        quantity: j['quantity'] as String,
        unitPrice: j['unit_price'] as String,
        net: j['net'] as String,
        fulfilled: j['fulfilled_quantity'] as String,
        remaining: j['remaining_quantity'] as String,
      );
}

class OrderInfo {
  const OrderInfo({required this.id, required this.number, required this.type, required this.state, required this.paymentState,
      required this.customerName, required this.total, required this.paid, required this.pending, required this.balanceDue,
      required this.createdByName, required this.version, required this.lines});
  final String id;
  final int number;
  final String type;
  final String state;
  final String paymentState;
  final String customerName;
  final String total;
  final String paid;
  final String pending;
  final String balanceDue;
  final String createdByName;
  final int version;
  final List<OrderLineInfo> lines;
  bool get open => state == 'CONFIRMED' || state == 'READY' || state == 'PARTIALLY_FULFILLED';
  bool get owes => !balanceDue.startsWith('-') && balanceDue != '0.00';

  factory OrderInfo.fromJson(Map<String, dynamic> j) => OrderInfo(
        id: j['id'] as String,
        number: j['number'] as int,
        type: j['type'] as String,
        state: j['state'] as String,
        paymentState: j['payment_state'] as String,
        customerName: j['customer_name'] as String,
        total: j['effective_total'] as String,
        paid: j['paid_amount'] as String,
        pending: j['pending_amount'] as String,
        balanceDue: j['balance_due'] as String,
        createdByName: j['created_by_name'] as String,
        version: j['version'] as int,
        lines: [for (final l in j['lines'] as List) OrderLineInfo.fromJson(l as Map<String, dynamic>)],
      );
}

class PaymentInfo {
  const PaymentInfo({required this.id, required this.method, required this.state, required this.amount, this.changeGiven});
  final String id;
  final String method;
  final String state;
  final String amount;
  final String? changeGiven;
  factory PaymentInfo.fromJson(Map<String, dynamic> j) => PaymentInfo(
        id: j['id'] as String,
        method: j['method'] as String,
        state: j['state'] as String,
        amount: j['amount'] as String,
        changeGiven: j['change_given'] as String?,
      );
}

class CashSessionInfo {
  const CashSessionInfo({required this.id, required this.state, required this.openingFloat, required this.cashIn,
      required this.cashOut, required this.expected, this.counted, this.difference, required this.version});
  final String id;
  final String state;
  final String openingFloat;
  final String cashIn;
  final String cashOut;
  final String expected;
  final String? counted;
  final String? difference;
  final int version;
  factory CashSessionInfo.fromJson(Map<String, dynamic> j) => CashSessionInfo(
        id: j['id'] as String,
        state: j['state'] as String,
        openingFloat: j['opening_float'] as String,
        cashIn: j['cash_in'] as String,
        cashOut: j['cash_out'] as String,
        expected: j['expected'] as String,
        counted: j['counted'] as String?,
        difference: j['difference'] as String?,
        version: j['version'] as int,
      );
}

// ---- F05: viagens, entregas e frota ----
class DeliveryInfo {
  const DeliveryInfo({this.address, this.reference, this.contact, this.windowStart, this.windowEnd, this.agreedPayment});
  final String? address;
  final String? reference;
  final String? contact;
  final String? windowStart;
  final String? windowEnd;
  final String? agreedPayment;
  factory DeliveryInfo.fromJson(Map<String, dynamic> j) => DeliveryInfo(
        address: j['address'] as String?,
        reference: j['reference'] as String?,
        contact: j['contact'] as String?,
        windowStart: j['window_start'] as String?,
        windowEnd: j['window_end'] as String?,
        agreedPayment: j['agreed_payment'] as String?,
      );
}

class StopOrderInfo {
  const StopOrderInfo({required this.id, required this.number, required this.customerName, required this.state,
      required this.paymentState, required this.total, required this.paid, required this.pending, required this.balanceDue,
      required this.lines, this.delivery, required this.maskedFields});
  final String id;
  final int number;
  final String customerName;
  final String state;
  final String paymentState;
  final String total;
  final String paid;
  final String pending;
  final String balanceDue;
  final List<OrderLineInfo> lines;
  final DeliveryInfo? delivery;
  final List<String> maskedFields;
  bool get owes => !balanceDue.startsWith('-') && balanceDue != '0.00';
  factory StopOrderInfo.fromJson(Map<String, dynamic> j) => StopOrderInfo(
        id: j['id'] as String,
        number: j['number'] as int,
        customerName: j['customer_name'] as String,
        state: j['state'] as String,
        paymentState: j['payment_state'] as String,
        total: j['effective_total'] as String,
        paid: j['paid_amount'] as String,
        pending: j['pending_amount'] as String,
        balanceDue: j['balance_due'] as String,
        lines: [for (final l in j['lines'] as List) OrderLineInfo.fromJson(l as Map<String, dynamic>)],
        delivery: j['delivery'] == null ? null : DeliveryInfo.fromJson(j['delivery'] as Map<String, dynamic>),
        maskedFields: [for (final f in (j['masked_fields'] as List?) ?? const []) f as String],
      );
}

class TripIncidentInfo {
  const TripIncidentInfo({required this.kind, required this.description, required this.createdByName, this.stopId});
  final String kind;
  final String description;
  final String createdByName;
  final String? stopId;
  factory TripIncidentInfo.fromJson(Map<String, dynamic> j) => TripIncidentInfo(
        kind: j['kind'] as String,
        description: j['description'] as String,
        createdByName: j['created_by_name'] as String,
        stopId: j['stop_id'] as String?,
      );
}

class TripStopInfo {
  const TripStopInfo({required this.id, required this.seq, required this.state, this.recipientName, required this.order,
      required this.incidents});
  final String id;
  final int seq;
  final String state;
  final String? recipientName;
  final StopOrderInfo order;
  final List<TripIncidentInfo> incidents;
  factory TripStopInfo.fromJson(Map<String, dynamic> j) => TripStopInfo(
        id: j['id'] as String,
        seq: j['seq'] as int,
        state: j['state'] as String,
        recipientName: j['recipient_name'] as String?,
        order: StopOrderInfo.fromJson(j['order'] as Map<String, dynamic>),
        incidents: [for (final i in (j['incidents'] as List?) ?? const []) TripIncidentInfo.fromJson(i as Map<String, dynamic>)],
      );
}

class TripInfo {
  const TripInfo({required this.id, required this.number, required this.state, required this.plannedDate, this.vehicleId,
      this.plate, this.vehicleModel, this.driverName, this.departureOdometer, this.returnOdometer, this.cashExpected,
      this.cashDeclared, this.cashDifference, this.capacityUnit, this.load, this.capacity, required this.version,
      required this.stops});
  final String id;
  final int number;
  final String state;
  final String plannedDate;
  final String? vehicleId;
  final String? plate;
  final String? vehicleModel;
  final String? driverName;
  final String? departureOdometer;
  final String? returnOdometer;
  final String? cashExpected;
  final String? cashDeclared;
  final String? cashDifference;
  final String? capacityUnit;
  final String? load;
  final String? capacity;
  final int version;
  final List<TripStopInfo> stops;
  factory TripInfo.fromJson(Map<String, dynamic> j) => TripInfo(
        id: j['id'] as String,
        number: j['number'] as int,
        state: j['state'] as String,
        plannedDate: j['planned_date'] as String,
        vehicleId: j['vehicle_id'] as String?,
        plate: j['plate'] as String?,
        vehicleModel: j['vehicle_model'] as String?,
        driverName: j['driver_name'] as String?,
        departureOdometer: j['departure_odometer'] as String?,
        returnOdometer: j['return_odometer'] as String?,
        cashExpected: j['cash_expected'] as String?,
        cashDeclared: j['cash_declared'] as String?,
        cashDifference: j['cash_difference'] as String?,
        capacityUnit: j['capacity_unit'] as String?,
        load: j['load'] as String?,
        capacity: j['capacity_quantity'] as String?,
        version: j['version'] as int,
        stops: [for (final s in (j['stops'] as List?) ?? const []) TripStopInfo.fromJson(s as Map<String, dynamic>)],
      );
}

class TripSummaryInfo {
  const TripSummaryInfo({required this.id, required this.number, required this.state, required this.plannedDate, this.plate,
      this.driverName, required this.stops, required this.delivered});
  final String id;
  final int number;
  final String state;
  final String plannedDate;
  final String? plate;
  final String? driverName;
  final int stops;
  final int delivered;
  factory TripSummaryInfo.fromJson(Map<String, dynamic> j) => TripSummaryInfo(
        id: j['id'] as String,
        number: j['number'] as int,
        state: j['state'] as String,
        plannedDate: j['planned_date'] as String,
        plate: j['plate'] as String?,
        driverName: j['driver_name'] as String?,
        stops: j['stops'] as int,
        delivered: j['delivered'] as int,
      );
}

class VehicleInfo {
  const VehicleInfo({required this.id, required this.plate, required this.model, required this.status, required this.odometerKm,
      this.nextServiceKm, required this.alerts, required this.consumptionState, this.kmPerLiter, this.consumptionMessage,
      this.currentTripNumber});
  final String id;
  final String plate;
  final String model;
  final String status;
  final String odometerKm;
  final String? nextServiceKm;
  final List<String> alerts;
  final String consumptionState;
  final String? kmPerLiter;
  final String? consumptionMessage;
  final int? currentTripNumber;
  factory VehicleInfo.fromJson(Map<String, dynamic> j) {
    final c = (j['consumption'] as Map<String, dynamic>?) ?? const {};
    return VehicleInfo(
      id: j['id'] as String,
      plate: j['plate'] as String,
      model: j['model'] as String,
      status: j['status'] as String,
      odometerKm: j['odometer_km'] as String,
      nextServiceKm: j['next_service_km'] as String?,
      alerts: [for (final a in (j['alerts'] as List?) ?? const []) (a as Map)['message'] as String],
      consumptionState: (c['state'] as String?) ?? 'INSUFFICIENT_DATA',
      kmPerLiter: c['km_per_liter'] as String?,
      consumptionMessage: c['message'] as String?,
      currentTripNumber: j['current_trip_number'] as int?,
    );
  }
}

class VehicleExpenseInfo {
  const VehicleExpenseInfo({required this.id, required this.kind, required this.amount, required this.state, required this.paidWith,
      this.liters, this.odometer, required this.occurredOn});
  final String id;
  final String kind;
  final String amount;
  final String state;
  final String paidWith;
  final String? liters;
  final String? odometer;
  final String occurredOn;
  factory VehicleExpenseInfo.fromJson(Map<String, dynamic> j) => VehicleExpenseInfo(
        id: j['id'] as String,
        kind: j['kind'] as String,
        amount: j['amount'] as String,
        state: j['state'] as String,
        paidWith: j['paid_with'] as String,
        liters: j['liters'] as String?,
        odometer: j['odometer'] as String?,
        occurredOn: j['occurred_on'] as String,
      );
}

// ---- F07: guias, folha e documentos (contador) ----
class TaxInfo {
  const TaxInfo({required this.id, required this.kind, required this.description, required this.competence, required this.dueDate,
      required this.amount, required this.state, this.payableState, this.decidedByName, this.decidedNote, required this.attachments,
      required this.version});
  final String id;
  final String kind;
  final String description;
  final String competence;
  final String dueDate;
  final String amount;
  final String state;
  final String? payableState;
  final String? decidedByName;
  final String? decidedNote;
  final List<DocumentInfo> attachments;
  final int version;
  factory TaxInfo.fromJson(Map<String, dynamic> j) => TaxInfo(
        id: j['id'] as String,
        kind: j['kind'] as String,
        description: j['description'] as String,
        competence: j['competence'] as String,
        dueDate: j['due_date'] as String,
        amount: j['amount'] as String,
        state: j['state'] as String,
        payableState: j['payable_state'] as String?,
        decidedByName: j['decided_by_name'] as String?,
        decidedNote: j['decided_note'] as String?,
        attachments: [for (final d in (j['attachments'] as List?) ?? const []) DocumentInfo.fromJson(d as Map<String, dynamic>)],
        version: j['version'] as int,
      );
}

class PayrollRunInfo {
  const PayrollRunInfo({required this.id, required this.competence, required this.description, required this.state,
      required this.source, required this.totals, required this.rubrics, required this.individualVisible, this.decidedNote,
      required this.version});
  final String id;
  final String competence;
  final String description;
  final String state;
  final String source;
  final Map<String, dynamic> totals;
  final List<Map<String, dynamic>> rubrics;
  final bool individualVisible;
  final String? decidedNote;
  final int version;
  factory PayrollRunInfo.fromJson(Map<String, dynamic> j) => PayrollRunInfo(
        id: j['id'] as String,
        competence: j['competence'] as String,
        description: j['description'] as String,
        state: j['state'] as String,
        source: j['source'] as String,
        totals: j['totals'] as Map<String, dynamic>,
        rubrics: [for (final r in (j['rubrics'] as List?) ?? const []) r as Map<String, dynamic>],
        individualVisible: j['people'] != null,
        decidedNote: j['decided_note'] as String?,
        version: j['version'] as int,
      );
}

class AccDocInfo {
  const AccDocInfo({required this.id, required this.kind, required this.title, this.competence, required this.currentVersion,
      required this.createdByName});
  final String id;
  final String kind;
  final String title;
  final String? competence;
  final int currentVersion;
  final String createdByName;
  factory AccDocInfo.fromJson(Map<String, dynamic> j) => AccDocInfo(
        id: j['id'] as String,
        kind: j['kind'] as String,
        title: j['title'] as String,
        competence: j['competence'] as String?,
        currentVersion: j['current_version'] as int,
        createdByName: j['created_by_name'] as String,
      );
}

class DocRequestInfo {
  const DocRequestInfo({required this.id, required this.title, required this.state, this.requestedFromName, this.fulfilledByName});
  final String id;
  final String title;
  final String state;
  final String? requestedFromName;
  final String? fulfilledByName;
  factory DocRequestInfo.fromJson(Map<String, dynamic> j) => DocRequestInfo(
        id: j['id'] as String,
        title: j['title'] as String,
        state: j['state'] as String,
        requestedFromName: j['requested_from_name'] as String?,
        fulfilledByName: j['fulfilled_by_name'] as String?,
      );
}

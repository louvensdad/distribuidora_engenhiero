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

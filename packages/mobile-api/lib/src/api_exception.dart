/// Erro no formato de `contratos/erro.schema.json`. Nunca carrega token nem dado sensível.
class ApiException implements Exception {
  ApiException({required this.status, required this.code, this.detail, this.requestId, this.fieldErrors = const []});

  final int status;
  final String code;
  final String? detail;
  final String? requestId;
  final List<FieldError> fieldErrors;

  bool get isNetwork => code == 'NETWORK_ERROR';
  bool get isForbidden => status == 403;
  bool get isUnauthenticated => status == 401;
  bool get isConflict => status == 412 || code == 'VERSION_CONFLICT';

  String get message => detail ?? (isNetwork ? 'Sem conexão com o servidor.' : 'Erro inesperado ($status).');

  factory ApiException.fromProblem(int status, Map<String, dynamic>? json) => ApiException(
        status: status,
        code: (json?['code'] as String?) ?? (status == 403 ? 'FORBIDDEN' : 'HTTP_$status'),
        detail: json?['detail'] as String?,
        requestId: json?['request_id'] as String?,
        fieldErrors: [
          for (final e in (json?['errors'] as List?) ?? const [])
            FieldError(field: (e as Map)['field'] as String? ?? '', message: e['message'] as String? ?? ''),
        ],
      );

  @override
  String toString() => 'ApiException($status $code)';
}

class FieldError {
  const FieldError({required this.field, required this.message});
  final String field;
  final String message;
}

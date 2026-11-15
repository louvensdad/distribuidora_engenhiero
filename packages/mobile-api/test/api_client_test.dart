import 'dart:convert';
import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:test/test.dart';

http.Response json(int status, Object body) => http.Response.bytes(utf8.encode(jsonEncode(body)), status,
    headers: {'content-type': 'application/json'});

void main() {
  final base = Uri.parse('http://api.teste.invalid');

  test('envia bearer, nunca organização/autor no corpo, e decodifica UTF-8', () async {
    late http.Request seen;
    final api = ApiClient(
      baseUrl: base,
      tokenProvider: ({bool forceRefresh = false}) async => 'tok-1',
      httpClient: MockClient((r) async {
        seen = r;
        return json(201, {'id': 'c1', 'name': 'Feira São João', 'branch': 'Matriz', 'masked_fields': ['address']});
      }),
    );
    final c = await api.createCustomer(name: 'Feira São João', contact: ' 11 9 ', address: '');
    expect(seen.headers['Authorization'], 'Bearer tok-1');
    expect(jsonDecode(seen.body), {'name': 'Feira São João', 'contact': '11 9'});
    expect(c.name, 'Feira São João');
    expect(c.isMasked('address'), isTrue);
  });

  test('401 renova o token uma vez e repete; segundo 401 vira erro', () async {
    final asked = <bool>[];
    var calls = 0;
    final api = ApiClient(
      baseUrl: base,
      tokenProvider: ({bool forceRefresh = false}) async {
        asked.add(forceRefresh);
        return forceRefresh ? 'novo' : 'velho';
      },
      httpClient: MockClient((r) async {
        calls++;
        return r.headers['Authorization'] == 'Bearer novo'
            ? json(200, {'items': [], 'next_cursor': null})
            : json(401, {'status': 401, 'code': 'UNAUTHENTICATED'});
      }),
    );
    final page = await api.users();
    expect(page.items, isEmpty);
    expect(asked, [false, true]);
    expect(calls, 2);

    final sempre401 = ApiClient(
        baseUrl: base,
        tokenProvider: ({bool forceRefresh = false}) async => 'x',
        httpClient: MockClient((_) async => json(401, {'status': 401, 'code': 'UNAUTHENTICATED'})));
    await expectLater(sempre401.me(), throwsA(isA<ApiException>().having((e) => e.isUnauthenticated, '401', true)));
  });

  test('erro padronizado preserva código, request_id e erros de campo', () async {
    final api = ApiClient(
        baseUrl: base,
        tokenProvider: ({bool forceRefresh = false}) async => 't',
        httpClient: MockClient((_) async => json(422, {
              'status': 422, 'code': 'VALIDATION_ERROR', 'detail': 'Corrija', 'request_id': 'rid-7',
              'errors': [{'field': 'name', 'message': 'obrigatório'}]
            })));
    late ApiException e;
    try {
      await api.createCustomer(name: '');
      fail('deveria falhar');
    } on ApiException catch (err) {
      e = err;
    }
    expect(e.code, 'VALIDATION_ERROR');
    expect(e.requestId, 'rid-7');
    expect(e.fieldErrors.single.field, 'name');
  });

  test('sem token não chama a rede; falha de rede vira NETWORK_ERROR', () async {
    var called = false;
    final semToken = ApiClient(
        baseUrl: base,
        tokenProvider: ({bool forceRefresh = false}) async => null,
        httpClient: MockClient((_) async {
          called = true;
          return json(200, {});
        }));
    await expectLater(semToken.me(), throwsA(isA<ApiException>()));
    expect(called, isFalse);

    final semRede = ApiClient(
        baseUrl: base,
        tokenProvider: ({bool forceRefresh = false}) async => 't',
        httpClient: MockClient((_) async => throw http.ClientException('sem rede')));
    await expectLater(semRede.me(), throwsA(isA<ApiException>().having((e) => e.isNetwork, 'rede', true)));
  });

  test('capacidades e campos do /me', () {
    final me = Me.fromJson({
      'user': {'id': 'u', 'display_name': 'Balcão'},
      'organization': {'id': 'o', 'name': 'Distribuidora de Cana Engenheiro Ltda'},
      'branch': {'id': 'b', 'name': 'Matriz'},
      'client_id': 'engenheiro-retirada', 'admin': false, 'role_template': 'BALCAO', 'sector': 'RETIRADA',
      'policy_version': 1,
      'capabilities': [{'capability': 'customers.view', 'resource': 'customers', 'action': 'view', 'scopes': ['BRANCH'], 'max_amount': '150.00'}],
      'fields': {'operational_contact': true, 'personal_document': false},
    });
    expect(me.can('customers.view'), isTrue);
    expect(me.can('dashboard.view'), isFalse);
    expect(me.capabilities.single.maxAmount, '150.00');
    expect(me.canSeeField('personal_document'), isFalse);
  });
}

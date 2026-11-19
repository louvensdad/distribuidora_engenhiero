import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:material_ui/material_ui.dart';
import '../../labels.dart';
import '../../scope.dart';
import '../accounting_screens.dart' show DocumentViewButton, RecordHistoryView, taxStepLabels;
import '../orders_screens.dart' show OrderDetailScreen;
import 'kit.dart';

const approvalKindLabels = {
  'TAX': 'Guia', 'PAYROLL': 'Folha', 'LOSS': 'Perda', 'ADJUSTMENT': 'Ajuste de estoque', 'EXPENSE': 'Despesa',
  'VEHICLE_EXPENSE': 'Gasto de veículo', 'CASH_SESSION': 'Caixa', 'PAYMENT': 'Pagamento', 'PERIOD_REOPEN': 'Reabertura de mês',
  'ABSENCE': 'Folga/férias',
};

/// P07 — aprovações pendentes de QUEM ESTÁ LOGADO, por setor. Aprovar não paga: pagamento é outra etapa.
class ApprovalsScreen extends StatelessWidget {
  const ApprovalsScreen({super.key});
  @override
  Widget build(BuildContext context) => JsonView(
        load: (api) => api.getJson('/api/v1/approvals'),
        builder: (context, d, reload) {
          final all = items(d);
          final sectors = [for (final s in items(d, 'sectors')) if ((s['pending'] as int? ?? 0) > 0) s];
          if (all.isEmpty) return const [SizedBox(height: 80), EmptyState(title: 'Nada esperando a sua decisão')];
          return [
            const Text('Só aparece o que a sua permissão deixa decidir. Aprovar não paga: pagamento é sempre outra etapa.',
                style: TextStyle(color: EngColors.muted)),
            for (final s in sectors) ...[
              SectionTitle('${labelOf(sectorNames, str(s['sector']))} · ${s['pending']}'),
              for (final i in all.where((i) => i['sector'] == s['sector']))
                Card(child: ListTile(
                  title: Text(str(i['title'])),
                  subtitle: Text([
                    labelOf(approvalKindLabels, str(i['kind'])),
                    if (i['detail'] != null) str(i['detail']),
                    if (i['requested_by'] != null) 'Pedido por ${i['requested_by']}',
                    if (i['requested_at'] != null) brDateTime(i['requested_at']),
                    if (i['note'] != null) str(i['note']),
                  ].join(' · ')),
                  trailing: i['amount'] == null ? null : Text(formatMoney(str(i['amount'])), style: const TextStyle(fontWeight: FontWeight.w700)),
                  onTap: () async {
                    await openApproval(context, i);
                    reload();
                  },
                )),
            ],
          ];
        },
      );
}

/// Abre a tela de decisão certa para o item da central.
Future<void> openApproval(BuildContext context, J item) async {
  final kind = str(item['kind']);
  final id = strOrNull(item['id']);
  if (kind == 'PAYMENT') {
    final oid = str(item['path']).split('/').last;
    await pushRaw(context, OrderDetailScreen(id: oid));
    return;
  }
  await pushScreen(context, labelOf(approvalKindLabels, kind), DecisionView(kind: kind, id: id, item: item));
}

/// Detalhe + decisão de uma pendência. Sempre recarrega do servidor (versão atual) antes de decidir.
class DecisionView extends StatefulWidget {
  const DecisionView({super.key, required this.kind, required this.id, required this.item});
  final String kind;
  final String? id;
  final J item;
  @override
  State<DecisionView> createState() => _DecisionViewState();
}

class _DecisionViewState extends State<DecisionView> {
  Key _k = UniqueKey();
  String get id => widget.id ?? '';

  Future<J> _load(ApiClient api) async {
    switch (widget.kind) {
      case 'TAX':
        return api.getJson('/api/v1/tax-obligations/$id');
      case 'PAYROLL':
        return api.getJson('/api/v1/payroll-runs/$id');
      case 'EXPENSE':
        return api.getJson('/api/v1/expenses/$id');
      case 'VEHICLE_EXPENSE':
        return api.getJson('/api/v1/vehicle-expenses/$id');
      case 'LOSS':
        return api.getJson('/api/v1/inventory/losses/$id');
      case 'ADJUSTMENT':
        return _find(await api.getJson('/api/v1/inventory/adjustments', {'status': 'PENDING', 'limit': '100'}));
      case 'CASH_SESSION':
        return _find(await api.getJson('/api/v1/cash-sessions'));
      case 'PERIOD_REOPEN':
        final p = items(await api.getJson('/api/v1/periods')).where((p) => p['reopen_pending'] == true).toList();
        final month = str(widget.item['title']).split(' ').last;
        return p.firstWhere((x) => str(x['month']) == month, orElse: () => p.isEmpty ? <String, dynamic>{} : p.first);
      case 'ABSENCE':
        final dates = RegExp(r'\d{4}-\d{2}-\d{2}').allMatches(str(widget.item['detail'])).map((m) => m.group(0)!).toList();
        final to = dates.length > 1 ? DateTime.parse(dates[1]).add(const Duration(days: 1)) : null;
        final cal = await api.getJson('/api/v1/team/calendar', {
          if (dates.isNotEmpty) 'from': dates.first,
          if (to != null) 'to': '${to.year}-${to.month.toString().padLeft(2, '0')}-${to.day.toString().padLeft(2, '0')}',
        });
        return _find({'items': cal['absences']});
    }
    return widget.item;
  }

  J _find(J list) => items(list).firstWhere((x) => x['id'] == widget.id,
      orElse: () => throw ApiException(status: 404, code: 'NOT_FOUND', detail: 'Pendência não encontrada: talvez já decidida.'));

  void _reload() => setState(() => _k = UniqueKey());

  Future<void> _cmd(Future<J> Function(ApiClient api) fn, String ok) async {
    final r = await runCommand(context, fn, ok: ok);
    if (r != null) _reload();
  }

  Future<void> _withText(String title, String label, bool required, Future<J> Function(ApiClient api, String text) fn, String ok,
      {String action = 'Confirmar', String? message}) async {
    final t = await askText(context, title: title, label: label, required: required, action: action, message: message);
    if (t == null || !mounted) return;
    await _cmd((api) => fn(api, t), ok);
  }

  Future<void> _pay(String title, Future<J> Function(ApiClient api, Map<String, String> v, String key) submit) async {
    final r = await openForm(context, title: title, submitLabel: 'Registrar pagamento',
        intro: 'O valor sai da conta escolhida na data informada. Aprovar e pagar são etapas separadas.',
        fields: const [
          FieldSpec('fin_account_id', 'Conta de saída', kind: FieldKind.account, required: true),
          FieldSpec('paid_on', 'Data do pagamento', kind: FieldKind.date, required: true),
          FieldSpec('note', 'Observação'),
        ],
        submit: submit);
    if (r != null) _reload();
  }

  @override
  Widget build(BuildContext context) => JsonView(key: _k, load: _load, builder: (context, d, reload) => _body(context, d));

  List<Widget> _body(BuildContext context, J d) {
    final me = EngScope.of(context).me;
    final v = d['version'];
    final state = str(d['state'] ?? d['status']);
    String idem() => ApiClient.newIdempotencyKey();
    switch (widget.kind) {
      case 'TAX':
        return [
          Text('${d['kind']} · competência ${brDate(d['competence'])}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
          KV('Valor', formatMoney(strOrNull(d['amount'])), strong: true),
          KV('Vencimento', brDate(d['due_date'])),
          KV('Descrição', str(d['description'])),
          KV('Preparada por', str(d['created_by_name'])),
          if (d['submitted_by_name'] != null) KV('Enviada por', str(d['submitted_by_name'])),
          KV('Situação', labelOf(taxStepLabels, state)),
          if (d['payable_state'] != null) KV('Pagamento', labelOf(genericStateLabels, str(d['payable_state']))),
          if (d['decided_note'] != null) KV('Nota da decisão', '${d['decided_by_name'] ?? ''}: ${d['decided_note']}'),
          const SectionTitle('Documento privado vinculado'),
          for (final f in items(d, 'attachments'))
            Row(children: [Expanded(child: Text(str(f['file_name']))), DocumentViewButton(doc: DocumentInfo.fromJson(f))]),
          if (state == 'SUBMITTED' && me.can('taxes.approve')) ...[
            const SizedBox(height: 12),
            FilledButton(onPressed: () => _withText('Aprovar guia', 'Observação', false,
                (api, t) => api.sendJson('POST', '/api/v1/tax-obligations/$id/approval', {if (t.isNotEmpty) 'note': t},
                    ifMatch: v, idempotencyKey: idem()), 'Guia aprovada. O pagamento continua pendente.',
                action: 'Aprovar', message: 'Aprovar não paga: o pagamento é registrado depois.'), child: const Text('Aprovar guia')),
            OutlinedButton(onPressed: () => _withText('Devolver guia', 'Motivo', true,
                (api, t) => api.sendJson('POST', '/api/v1/tax-obligations/$id/return', {'note': t}, ifMatch: v),
                'Guia devolvida ao contador.', action: 'Devolver'), child: const Text('Devolver com motivo')),
          ],
          if (state == 'APPROVED' && me.can('taxes.confirm'))
            FilledButton(onPressed: () => _pay('Pagar guia', (api, val, key) => api.sendJson('POST',
                '/api/v1/tax-obligations/$id/payments', val, idempotencyKey: key)), child: const Text('Registrar pagamento')),
          const SectionTitle('Histórico'),
          RecordHistoryView(key: ValueKey('t$v'), load: (api) => api.taxHistory(id)),
        ];
      case 'PAYROLL':
        final t = (d['totals'] as J?) ?? const {};
        return [
          Text('${d['description']} · ${brDate(d['competence'])}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
          KV('Situação', labelOf(taxStepLabels, state)),
          KV('Pagamento previsto', brDate(d['pay_date'])),
          KV('Proventos', formatMoney(strOrNull(t['earnings']))),
          KV('Benefícios', formatMoney(strOrNull(t['benefits']))),
          KV('Encargos', formatMoney(strOrNull(t['employer_charges']))),
          KV('Descontos', formatMoney(strOrNull(t['deductions']))),
          KV('Líquido', formatMoney(strOrNull(t['net_pay']))),
          KV('Custo total', formatMoney(strOrNull(t['company_cost'])), strong: true),
          KV('Pessoas', str(t['people'])),
          if (d['decided_note'] != null) KV('Nota da decisão', '${d['decided_by_name'] ?? ''}: ${d['decided_note']}'),
          const SectionTitle('Rubricas'),
          for (final r in items(d, 'rubrics')) KV(str(r['rubric']), '${formatMoney(strOrNull(r['amount']))} · ${r['people']} pessoa(s)'),
          if (d['people'] != null) ...[
            const SectionTitle('Por pessoa'),
            for (final p in (d['people'] as List).cast<J>()) KV(str(p['person']), 'líquido ${formatMoney(strOrNull(p['net']))}'),
          ],
          if (state == 'SUBMITTED' && me.can('payroll.approve')) ...[
            const SizedBox(height: 12),
            FilledButton(onPressed: () => _withText('Aprovar folha', 'Observação', false,
                (api, x) => api.sendJson('POST', '/api/v1/payroll-runs/$id/approval', {if (x.isNotEmpty) 'note': x}, ifMatch: v),
                'Folha aprovada. Os pagamentos continuam pendentes.', action: 'Aprovar'), child: const Text('Aprovar folha')),
            OutlinedButton(onPressed: () => _withText('Devolver folha', 'Motivo', true,
                (api, x) => api.sendJson('POST', '/api/v1/payroll-runs/$id/return', {'note': x}, ifMatch: v),
                'Folha devolvida ao contador.', action: 'Devolver'), child: const Text('Devolver com motivo')),
          ],
          if (state == 'APPROVED' && me.can('payroll.confirm'))
            FilledButton(onPressed: () => _pay('Pagar salários', (api, val, key) =>
                api.sendJson('POST', '/api/v1/payroll-runs/$id/payments', val)), child: const Text('Registrar pagamento dos salários')),
          if (me.can('payroll.export'))
            OutlinedButton(onPressed: () => downloadAndSave(context, '/api/v1/payroll-runs/$id/export', fallbackName: 'folha.csv'),
                child: const Text('Exportar CSV')),
          const SectionTitle('Histórico'),
          RecordHistoryView(key: ValueKey('p$v'), load: (api) => api.payrollHistory(id)),
        ];
      case 'EXPENSE':
        return [
          Text(str(d['description']), style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
          KV('Valor', formatMoney(strOrNull(d['amount'])), strong: true),
          KV('Categoria', '${labelOf(expenseCategoryLabels, str(d['category']))} › ${d['subcategory']}'),
          KV('Competência', brDate(d['competence'])),
          if (d['due_date'] != null) KV('Vencimento', brDate(d['due_date'])),
          if (d['payee'] != null || d['supplier_name'] != null) KV('Favorecido', str(d['supplier_name'] ?? d['payee'])),
          KV('Lançada por', str(d['created_by_name'])),
          KV('Situação', labelOf(genericStateLabels, state)),
          if (state == 'PENDING' && me.can('expenses.approve')) ...[
            const SizedBox(height: 12),
            FilledButton(onPressed: () => _withText('Aprovar despesa', 'Observação', false,
                (api, x) => api.sendJson('POST', '/api/v1/expenses/$id/approve', {if (x.isNotEmpty) 'note': x}, ifMatch: v,
                    idempotencyKey: idem()), 'Despesa aprovada. Entra em contas a pagar.', action: 'Aprovar'),
                child: const Text('Aprovar despesa')),
            OutlinedButton(onPressed: () => _withText('Recusar despesa', 'Motivo', true,
                (api, x) => api.sendJson('POST', '/api/v1/expenses/$id/reject', {'note': x}, ifMatch: v), 'Despesa recusada.',
                action: 'Recusar'), child: const Text('Recusar com motivo')),
          ],
        ];
      case 'VEHICLE_EXPENSE':
        return [
          Text('${d['plate']} · ${d['kind']}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
          KV('Valor', formatMoney(strOrNull(d['amount'])), strong: true),
          KV('Data', brDate(d['occurred_on'])),
          if (d['trip_number'] != null) KV('Viagem', 'V${d['trip_number']}'),
          if (d['liters'] != null) KV('Litros', formatDecimal(strOrNull(d['liters']))),
          if (d['odometer'] != null) KV('Odômetro', '${formatDecimal(strOrNull(d['odometer']))} km'),
          if (d['station'] != null) KV('Posto', str(d['station'])),
          if (d['workshop'] != null) KV('Oficina', str(d['workshop'])),
          KV('Pago com', str(d['paid_with'])),
          KV('Lançado por', str(d['created_by_name'])),
          KV('Situação', labelOf(genericStateLabels, state)),
          if (state == 'PENDING' && (me.can('fuel.approve') || me.can('expenses.approve'))) ...[
            const SizedBox(height: 12),
            FilledButton(onPressed: () => _withText('Aprovar gasto', 'Observação', false,
                (api, x) => api.sendJson('POST', '/api/v1/vehicle-expenses/$id/approve', {if (x.isNotEmpty) 'note': x},
                    ifMatch: v, idempotencyKey: idem()), 'Gasto aprovado.', action: 'Aprovar'), child: const Text('Aprovar gasto')),
            OutlinedButton(onPressed: () => _withText('Recusar gasto', 'Motivo', true,
                (api, x) => api.sendJson('POST', '/api/v1/vehicle-expenses/$id/reject', {'note': x}, ifMatch: v),
                'Gasto recusado.', action: 'Recusar'), child: const Text('Recusar com motivo')),
          ],
        ];
      case 'LOSS':
        return [
          Text('Perda: ${d['product_name']}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
          KV('Quantidade', '${formatDecimal(strOrNull(d['quantity']))} ${unitLabel(str(d['unit']), strOrNull(d['quantity']))}'),
          KV('Motivo', str(d['reason'])),
          if (d['notes'] != null) KV('Observação', str(d['notes'])),
          if (d['total_cost'] != null) KV('Custo', formatMoney(strOrNull(d['total_cost'])), strong: true),
          KV('Unidade', str(d['branch'])),
          KV('Registrada por', str(d['created_by_name'])),
          KV('Situação', labelOf(genericStateLabels, state)),
          if (state == 'PENDING' && me.can('inventory.losses.approve')) ...[
            const SizedBox(height: 12),
            FilledButton(onPressed: () => _cmd((api) => api.sendJson('POST', '/api/v1/inventory/losses/$id/approve', null,
                ifMatch: v, idempotencyKey: idem()), 'Perda aprovada: estoque baixado.'), child: const Text('Aprovar perda')),
            OutlinedButton(onPressed: () => _withText('Recusar perda', 'Motivo', true,
                (api, x) => api.sendJson('POST', '/api/v1/inventory/losses/$id/reject', {'reason': x}, ifMatch: v,
                    idempotencyKey: idem()), 'Perda recusada.', action: 'Recusar'), child: const Text('Recusar com motivo')),
          ],
        ];
      case 'ADJUSTMENT':
        return [
          Text('Ajuste: ${d['product_name']}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
          KV('Sistema', formatDecimal(strOrNull(d['system_quantity']))),
          KV('Contado', formatDecimal(strOrNull(d['counted_quantity']))),
          KV('Diferença', '${formatDecimal(strOrNull(d['difference']))} ${unitLabel(str(d['base_unit']))}', strong: true),
          if (d['total_cost'] != null) KV('Custo', formatMoney(strOrNull(d['total_cost']))),
          KV('Observação', str(d['notes'])),
          KV('Pedido por', str(d['created_by_name'])),
          if (me.can('inventory.losses.approve')) ...[
            const SizedBox(height: 12),
            FilledButton(onPressed: () => _cmd((api) => api.sendJson('POST', '/api/v1/inventory/adjustments/$id/approve', null,
                ifMatch: v, idempotencyKey: idem()), 'Ajuste aprovado.'), child: const Text('Aprovar ajuste')),
            OutlinedButton(onPressed: () => _withText('Recusar ajuste', 'Motivo', true,
                (api, x) => api.sendJson('POST', '/api/v1/inventory/adjustments/$id/reject', {'reason': x}, ifMatch: v,
                    idempotencyKey: idem()), 'Ajuste recusado.', action: 'Recusar'), child: const Text('Recusar com motivo')),
          ],
        ];
      case 'CASH_SESSION':
        return [
          Text('Caixa de ${d['operator_name']}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
          KV('Fundo', formatMoney(strOrNull(d['opening_float']))),
          KV('Entradas', formatMoney(strOrNull(d['cash_in']))),
          KV('Saídas', formatMoney(strOrNull(d['cash_out']))),
          KV('Esperado', formatMoney(strOrNull(d['expected']))),
          KV('Contado', formatMoney(strOrNull(d['counted']))),
          KV('Diferença', formatMoney(strOrNull(d['difference'])), strong: true),
          if (d['justification'] != null) KV('Justificativa', str(d['justification'])),
          KV('Fechado em', brDateTime(d['closed_at'])),
          KV('Situação', labelOf(genericStateLabels, state)),
          if (state == 'CLOSED' && me.admin)
            FilledButton(onPressed: () => _withText('Conferir caixa', 'Observação', false,
                (api, x) => api.sendJson('POST', '/api/v1/cash-sessions/$id/review', {if (x.isNotEmpty) 'note': x}, ifMatch: v),
                'Caixa conferido.', action: 'Conferir'), child: const Text('Conferir caixa')),
        ];
      case 'PERIOD_REOPEN':
        if (d.isEmpty) return const [EmptyState(title: 'Pedido de reabertura não encontrado: talvez já decidido.')];
        final pid = str(d['id']);
        return [
          Text('Reabrir o mês ${d['month']}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
          KV('Pedido por', str(d['reopen_requested_by'])),
          KV('Fechado por', str(d['closed_by_name'])),
          if (d['profit_at_close'] != null) KV('Lucro no fechamento', formatMoney(strOrNull(d['profit_at_close']))),
          if (me.can('reports.reopen_period')) ...[
            const SizedBox(height: 12),
            FilledButton(onPressed: () => _withText('Reabrir mês', 'Motivo', true,
                (api, x) => api.sendJson('POST', '/api/v1/periods/$pid/reopen', {'reason': x}), 'Mês reaberto.', action: 'Reabrir'),
                child: const Text('Reabrir mês')),
            OutlinedButton(onPressed: () => _withText('Negar reabertura', 'Motivo', true,
                (api, x) => api.sendJson('POST', '/api/v1/periods/$pid/reopen-rejections', {'reason': x}), 'Reabertura negada.',
                action: 'Negar'), child: const Text('Negar com motivo')),
          ],
        ];
      case 'ABSENCE':
        return [
          Text('${d['person']} · ${labelOf(absenceKindLabels, str(d['kind']))}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
          KV('Período', '${brDate(d['start_date'])} a ${brDate(d['end_date'])} (${d['days']} dia(s))'),
          KV('Setor', labelOf(sectorLabels, str(d['sector']))),
          if (d['notes'] != null) KV('Observação', str(d['notes'])),
          KV('Pedido por', str(d['created_by_name'])),
          for (final w in (d['warnings'] as List? ?? const [])) Text('Atenção: $w', style: const TextStyle(color: EngColors.warning)),
          if (state == 'REQUESTED' && me.can('users.edit_draft')) ...[
            const SizedBox(height: 12),
            FilledButton(onPressed: () => _withText('Aprovar ausência', 'Observação', false,
                (api, x) => api.sendJson('POST', '/api/v1/team/absences/$id/approve', {if (x.isNotEmpty) 'note': x}, ifMatch: v),
                'Ausência aprovada.', action: 'Aprovar'), child: const Text('Aprovar')),
            OutlinedButton(onPressed: () => _withText('Recusar ausência', 'Motivo', true,
                (api, x) => api.sendJson('POST', '/api/v1/team/absences/$id/reject', {'note': x}, ifMatch: v), 'Ausência recusada.',
                action: 'Recusar'), child: const Text('Recusar com motivo')),
          ],
        ];
    }
    return [Text(str(widget.item['title']))];
  }
}

const expenseCategoryLabels = {
  'FUNCIONARIOS': 'Funcionários', 'VEICULOS': 'Veículos', 'ESTRUTURA': 'Estrutura', 'IMPOSTOS_TAXAS': 'Impostos e taxas',
  'OUTRAS': 'Outras', 'RETIRADA_SOCIO': 'Retirada do sócio',
};
const absenceKindLabels = {'VACATION': 'Férias', 'DAY_OFF': 'Folga', 'LEAVE': 'Afastamento'};

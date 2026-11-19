import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:material_ui/material_ui.dart';
import '../../labels.dart';
import '../../scope.dart';
import '../accounting_screens.dart' show taxStepLabels;
import '../finance_screens.dart' show FinanceSummaryScreen;
import 'approvals.dart';
import 'central.dart';
import 'kit.dart';

const expenseCategories = {
  'FUNCIONARIOS': 'Funcionários', 'VEICULOS': 'Veículos', 'ESTRUTURA': 'Estrutura', 'IMPOSTOS_TAXAS': 'Impostos e taxas',
  'OUTRAS': 'Outras', 'RETIRADA_SOCIO': 'Retirada do sócio',
};
const accountKinds = {'CASH_DRAWERS': 'Caixas de turno', 'COMPANY_CASH': 'Cofre da empresa', 'BANK': 'Banco', 'CARD_ACQUIRER': 'Adquirente'};

String _monthStart() => '${todayIso().substring(0, 8)}01';
String _nextMonthStart() {
  final t = DateTime.parse(_monthStart());
  final n = DateTime(t.year, t.month + 1, 1);
  return '${n.year}-${n.month.toString().padLeft(2, '0')}-01';
}

/// Financeiro do dono no celular (paridade com Financeiro do site).
class FinanceHubScreen extends StatelessWidget {
  const FinanceHubScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    Widget item(String icon, String title, String sub, Widget screen) => Card(child: ListTile(
          leading: EngIcon(icon, color: EngColors.cyan), title: Text(title), subtitle: Text(sub),
          onTap: () => pushScreen(context, title, screen)));
    return ListView(padding: const EdgeInsets.all(16), children: [
      if (me.can('reports.view')) item('wallet', 'Resultado do mês', 'Lucro, caixa disponível e conciliação', const FinanceSummaryScreen()),
      if (me.can('company_cash.view')) item('cash', 'Contas e saldos', 'Caixas, cofre, bancos e extrato', const AccountsScreen()),
      if (me.can('company_cash.view')) item('route', 'Transferências', 'Entre contas da empresa, com estorno', const TransfersScreen()),
      if (me.can('expenses.view')) item('report', 'Despesas por categoria', 'Lançar, aprovar e recusar', const ExpensesScreen()),
      if (me.can('expenses.view')) item('invoice', 'Contas a pagar', 'Pagar total ou parcial, com a conta de saída', const PayablesScreen()),
      if (me.can('payments.view') || me.can('expenses.view'))
        item('cash', 'Contas a receber', 'Cartão, fiado e devoluções de fornecedor', const ReceivablesScreen()),
      if (me.can('taxes.view')) item('tax', 'Guias e impostos', 'Aprovar, devolver e pagar', const OwnerTaxesScreen()),
      if (me.can('payroll.view')) item('users', 'Folha de pagamento', 'Aprovar, devolver, pagar e exportar', const OwnerPayrollScreen()),
      if (me.can('reports.view')) item('lock', 'Fechamento do mês', 'Fechar, pedir e decidir reabertura', const ClosingScreen()),
      if (me.can('company_cash.view')) item('shield', 'Open Finance', 'Extrato do banco e conciliação', const OpenFinanceScreen()),
      if (me.can('invoices.view') || me.can('payments.confirm') || me.admin)
        item('settings', 'Integrações', 'Fiscal, pagamentos e bancos: estado honesto', const IntegrationsScreen()),
    ]);
  }
}

class AccountsScreen extends StatefulWidget {
  const AccountsScreen({super.key});
  @override
  State<AccountsScreen> createState() => _AccountsScreenState();
}

class _AccountsScreenState extends State<AccountsScreen> {
  Key _k = UniqueKey();
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return Scaffold(
      floatingActionButton: me.admin
          ? FloatingActionButton.extended(
              icon: const EngIcon('plus', color: EngColors.background),
              label: const Text('Nova conta'),
              onPressed: () async {
                final r = await openForm(context, title: 'Nova conta', submitLabel: 'Criar conta', fields: const [
                  FieldSpec('name', 'Nome', required: true),
                  FieldSpec('kind', 'Tipo', kind: FieldKind.select, required: true, options: {'BANK': 'Banco', 'COMPANY_CASH': 'Cofre'}),
                  FieldSpec('opening_balance', 'Saldo inicial (R\$)', kind: FieldKind.money),
                ], submit: (api, v, key) => api.sendJson('POST', '/api/v1/finance/accounts', v, idempotencyKey: key));
                if (r != null) setState(() => _k = UniqueKey());
              })
          : null,
      body: JsonView(key: _k, load: (api) => api.getJson('/api/v1/finance/accounts'), builder: (context, d, reload) => [
        for (final a in items(d))
          RowCard(title: str(a['name']), subtitle: '${labelOf(accountKinds, str(a['kind']))}${a['status'] == 'ACTIVE' ? '' : ' · ${a['status']}'}',
              trailing: formatMoney(strOrNull(a['balance'])),
              onTap: () => pushScreen(context, 'Extrato: ${a['name']}', StatementScreen(accountId: str(a['id'])))),
      ]),
    );
  }
}

/// Extrato da conta no mês: cada linha com o autor e a origem (abre o registro que gerou o lançamento).
class StatementScreen extends StatefulWidget {
  const StatementScreen({super.key, required this.accountId});
  final String accountId;
  @override
  State<StatementScreen> createState() => _StatementScreenState();
}

class _StatementScreenState extends State<StatementScreen> {
  late final _from = TextEditingController(text: _monthStart());
  late final _to = TextEditingController(text: _nextMonthStart());
  Key _k = UniqueKey();
  @override
  Widget build(BuildContext context) => JsonView(
        key: _k,
        load: (api) => api.getJson('/api/v1/finance/accounts/${widget.accountId}/statement', {'from': _from.text, 'to': _to.text}),
        builder: (context, d, reload) => [
          Row(children: [
            Expanded(child: TextField(controller: _from, decoration: const InputDecoration(labelText: 'De'))),
            const SizedBox(width: 8),
            Expanded(child: TextField(controller: _to, decoration: const InputDecoration(labelText: 'Até (exclusive)'))),
            IconButton(onPressed: () => setState(() => _k = UniqueKey()), icon: const EngIcon('refresh'), tooltip: 'Aplicar'),
          ]),
          KV('Saldo inicial', formatMoney(strOrNull(d['opening_balance']))),
          KV('Entradas', formatMoney(strOrNull(d['inflows'])), color: EngColors.green),
          KV('Saídas', formatMoney(strOrNull(d['outflows'])), color: EngColors.error),
          KV('Saldo final', formatMoney(strOrNull(d['closing_balance'])), strong: true),
          const SectionTitle('Lançamentos'),
          if (items(d, 'lines').isEmpty) const Text('Sem lançamentos no período.', style: TextStyle(color: EngColors.muted)),
          for (final l in items(d, 'lines'))
            RowCard(
              title: str(l['memo']),
              subtitle: '${brDate(l['entry_date'])} · ${l['created_by_name'] ?? ''}${l['reversal'] == true ? ' · estorno' : ''}',
              trailing: l['debit'] != null && l['debit'] != '0.00' ? '+${formatMoney(strOrNull(l['debit']))}' : '−${formatMoney(strOrNull(l['credit']))}',
              onTap: () => openOrigin(context, strOrNull(l['source_type']), strOrNull(l['source_id'])),
            ),
        ],
      );
}

class TransfersScreen extends StatefulWidget {
  const TransfersScreen({super.key});
  @override
  State<TransfersScreen> createState() => _TransfersScreenState();
}

class _TransfersScreenState extends State<TransfersScreen> {
  Key _k = UniqueKey();
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return Scaffold(
      floatingActionButton: me.admin
          ? FloatingActionButton.extended(
              icon: const EngIcon('plus', color: EngColors.background),
              label: const Text('Transferir'),
              onPressed: () async {
                final r = await openForm(context, title: 'Transferência entre contas', submitLabel: 'Transferir', fields: const [
                  FieldSpec('from_account_id', 'De', kind: FieldKind.account, required: true),
                  FieldSpec('to_account_id', 'Para', kind: FieldKind.account, required: true),
                  FieldSpec('amount', 'Valor (R\$)', kind: FieldKind.money, required: true),
                  FieldSpec('note', 'Observação'),
                ], submit: (api, v, key) => api.sendJson('POST', '/api/v1/finance/transfers', v, idempotencyKey: key));
                if (r != null) setState(() => _k = UniqueKey());
              })
          : null,
      body: JsonView(key: _k, load: (api) => api.getJson('/api/v1/finance/transfers'), builder: (context, d, reload) => [
        if (items(d).isEmpty) const EmptyState(title: 'Nenhuma transferência'),
        for (final t in items(d))
          RowCard(
            title: '${t['from_name']} → ${t['to_name']}',
            subtitle: '${brDate(t['transfer_date'])} · ${t['created_by_name']} · ${labelOf(genericStateLabels, str(t['state']))}'
                '${t['reverse_reason'] == null ? '' : ' · ${t['reverse_reason']}'}',
            trailing: formatMoney(strOrNull(t['amount'])),
            onTap: t['state'] == 'CONFIRMED' && me.admin
                ? () async {
                    final why = await askText(context, title: 'Estornar transferência', action: 'Estornar');
                    if (why == null || !context.mounted) return;
                    final r = await runCommand(context, (api) => api.sendJson('POST', '/api/v1/finance/transfers/${t['id']}/reverse',
                        {'reason': why}), ok: 'Transferência estornada.');
                    if (r != null) reload();
                  }
                : null,
          ),
      ]),
    );
  }
}

class ExpensesScreen extends StatefulWidget {
  const ExpensesScreen({super.key});
  @override
  State<ExpensesScreen> createState() => _ExpensesScreenState();
}

class _ExpensesScreenState extends State<ExpensesScreen> {
  String? _state;
  String? _category;
  Key _k = UniqueKey();
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return Scaffold(
      floatingActionButton: me.can('expenses.create')
          ? FloatingActionButton.extended(
              icon: const EngIcon('plus', color: EngColors.background),
              label: const Text('Lançar despesa'),
              onPressed: () async {
                final r = await openForm(context, title: 'Lançar despesa', submitLabel: 'Lançar', fields: const [
                  FieldSpec('category', 'Categoria', kind: FieldKind.select, required: true, options: expenseCategories),
                  FieldSpec('subcategory', 'Subcategoria', required: true),
                  FieldSpec('description', 'Descrição', required: true),
                  FieldSpec('payee', 'Favorecido'),
                  FieldSpec('amount', 'Valor (R\$)', kind: FieldKind.money, required: true),
                  FieldSpec('competence', 'Competência', kind: FieldKind.month),
                  FieldSpec('due_date', 'Vencimento', kind: FieldKind.date),
                ], submit: (api, v, key) => api.sendJson('POST', '/api/v1/expenses', v, idempotencyKey: key));
                if (r != null) setState(() => _k = UniqueKey());
              })
          : null,
      body: CursorList(
        key: _k,
        path: '/api/v1/expenses',
        query: {'state': _state, 'category': _category},
        empty: 'Nenhuma despesa com esses filtros.',
        header: [
          Row(children: [
            Expanded(child: DropdownButtonFormField<String?>(initialValue: _state, isExpanded: true,
                decoration: const InputDecoration(labelText: 'Situação'),
                items: const [DropdownMenuItem(value: null, child: Text('Todas')), DropdownMenuItem(value: 'PENDING', child: Text('Pendentes')),
                  DropdownMenuItem(value: 'APPROVED', child: Text('Aprovadas')), DropdownMenuItem(value: 'REJECTED', child: Text('Recusadas'))],
                onChanged: (v) => setState(() => _state = v))),
            const SizedBox(width: 8),
            Expanded(child: DropdownButtonFormField<String?>(initialValue: _category, isExpanded: true,
                decoration: const InputDecoration(labelText: 'Categoria'),
                items: [const DropdownMenuItem(value: null, child: Text('Todas')),
                  for (final e in expenseCategories.entries) DropdownMenuItem(value: e.key, child: Text(e.value))],
                onChanged: (v) => setState(() => _category = v))),
          ]),
        ],
        itemBuilder: (context, x, reload) => RowCard(
          title: str(x['description']),
          subtitle: '${labelOf(expenseCategories, str(x['category']))} › ${x['subcategory']} · ${brDate(x['competence'])} · '
              '${labelOf(genericStateLabels, str(x['state']))} · ${x['created_by_name']}',
          trailing: formatMoney(strOrNull(x['amount'])),
          onTap: () async {
            await openApproval(context, {'kind': 'EXPENSE', 'id': x['id']});
            reload();
          },
        ),
      ),
    );
  }
}

class PayablesScreen extends StatefulWidget {
  const PayablesScreen({super.key});
  @override
  State<PayablesScreen> createState() => _PayablesScreenState();
}

class _PayablesScreenState extends State<PayablesScreen> {
  String? _state = 'OPEN';
  @override
  Widget build(BuildContext context) => CursorList(
        path: '/api/v1/payables',
        query: {'state': _state},
        empty: 'Nenhuma conta a pagar.',
        header: [
          DropdownButtonFormField<String?>(initialValue: _state, isExpanded: true, decoration: const InputDecoration(labelText: 'Situação'),
              items: const [DropdownMenuItem(value: null, child: Text('Todas')), DropdownMenuItem(value: 'OPEN', child: Text('Em aberto')),
                DropdownMenuItem(value: 'PARTIAL', child: Text('Parciais')), DropdownMenuItem(value: 'PAID', child: Text('Pagas'))],
              onChanged: (v) => setState(() => _state = v)),
        ],
        itemBuilder: (context, p, reload) => RowCard(
          title: str(p['description']),
          subtitle: '${p['supplier_name'] ?? ''}${p['due_date'] == null ? '' : ' · vence ${brDate(p['due_date'])}'} · '
              '${labelOf(genericStateLabels, str(p['state']))} · saldo ${formatMoney(strOrNull(p['balance']))}',
          trailing: formatMoney(strOrNull(p['amount'])),
          onTap: () async {
            await pushScreen(context, 'Conta a pagar', PayableScreen(id: str(p['id'])));
            reload();
          },
        ),
      );
}

class PayableScreen extends StatefulWidget {
  const PayableScreen({super.key, required this.id});
  final String id;
  @override
  State<PayableScreen> createState() => _PayableScreenState();
}

class _PayableScreenState extends State<PayableScreen> {
  Key _k = UniqueKey();
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return JsonView(key: _k, load: (api) => api.getJson('/api/v1/payables/${widget.id}'), builder: (context, d, reload) {
      final p = d['payable'] as J;
      final open = p['state'] == 'OPEN' || p['state'] == 'PARTIAL';
      return [
        Text(str(p['description']), style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
        KV('Valor', formatMoney(strOrNull(p['amount']))),
        KV('Saldo', formatMoney(strOrNull(p['balance'])), strong: true),
        if (p['due_date'] != null) KV('Vencimento', brDate(p['due_date'])),
        if (p['supplier_name'] != null) KV('Fornecedor', str(p['supplier_name'])),
        KV('Situação', labelOf(genericStateLabels, str(p['state']))),
        TextButton(onPressed: () => openOrigin(context, strOrNull(p['source_type']), strOrNull(p['source_id'])),
            child: Text('Origem: ${labelOf(entityLabels, str(p['source_type']))}')),
        if (open && me.can('expenses.confirm'))
          FilledButton(onPressed: () async {
            final r = await openForm(context, title: 'Pagar', submitLabel: 'Registrar pagamento', fields: [
              FieldSpec('amount', 'Valor (R\$)', kind: FieldKind.money, required: true, initial: str(p['balance'])),
              const FieldSpec('fin_account_id', 'Conta de saída', kind: FieldKind.account, required: true),
              const FieldSpec('paid_on', 'Data', kind: FieldKind.date, required: true),
              const FieldSpec('note', 'Observação'),
            ], submit: (api, v, key) => api.sendJson('POST', '/api/v1/payables/${widget.id}/payments', v, idempotencyKey: key));
            if (r != null) setState(() => _k = UniqueKey());
          }, child: const Text('Pagar (total ou parcial)')),
        const SectionTitle('Pagamentos'),
        if (items(d, 'settlements').isEmpty) const Text('Nenhum pagamento ainda.', style: TextStyle(color: EngColors.muted)),
        for (final s in items(d, 'settlements'))
          RowCard(title: '${brDate(s['paid_on'])} · ${s['account_name']}', subtitle: '${s['created_by_name']} · ${labelOf(genericStateLabels, str(s['state']))}',
              trailing: formatMoney(strOrNull(s['amount']))),
      ];
    });
  }
}

const receivableKinds = {'CARD': 'Cartão', 'CREDIT_SALE': 'Fiado', 'SUPPLIER_RETURN': 'Devolução de fornecedor'};

class ReceivablesScreen extends StatefulWidget {
  const ReceivablesScreen({super.key});
  @override
  State<ReceivablesScreen> createState() => _ReceivablesScreenState();
}

class _ReceivablesScreenState extends State<ReceivablesScreen> {
  Key _k = UniqueKey();
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return JsonView(key: _k, load: (api) => api.getJson('/api/v1/receivables'), builder: (context, d, reload) => [
      if (items(d).isEmpty) const EmptyState(title: 'Nada a receber'),
      for (final r in items(d))
        RowCard(
          title: '${labelOf(receivableKinds, str(r['kind']))} · ${r['customer_name'] ?? r['supplier_name'] ?? r['description'] ?? ''}',
          subtitle: '${r['order_number'] == null ? '' : 'Pedido #${r['order_number']} · '}${r['due_date'] == null ? '' : 'vence ${brDate(r['due_date'])} · '}'
              '${labelOf(genericStateLabels, str(r['state']))} · saldo ${formatMoney(strOrNull(r['balance']))}'
              '${r['fee'] == null ? '' : ' · taxa ${formatMoney(strOrNull(r['fee']))}'}',
          trailing: formatMoney(strOrNull(r['amount'])),
          onTap: (r['state'] == 'OPEN' || r['state'] == 'PARTIAL') && (me.can('payments.confirm') || me.can('expenses.confirm'))
              ? () async {
                  final x = await openForm(context, title: 'Baixar recebimento', submitLabel: 'Registrar recebimento', fields: [
                    FieldSpec('amount', 'Valor baixado (R\$)', kind: FieldKind.money, required: true, initial: str(r['balance'])),
                    const FieldSpec('received', 'Valor que entrou (R\$)', kind: FieldKind.money,
                        help: 'Se o banco depositou outro valor, a diferença vira ajuste'),
                    const FieldSpec('fin_account_id', 'Conta de entrada', kind: FieldKind.account, required: true),
                    const FieldSpec('received_on', 'Data', kind: FieldKind.date, required: true),
                    const FieldSpec('note', 'Observação'),
                  ], submit: (api, v, key) => api.sendJson('POST', '/api/v1/receivables/${r['id']}/settlements', v, idempotencyKey: key));
                  if (x != null) setState(() => _k = UniqueKey());
                }
              : null,
        ),
    ]);
  }
}

class OwnerTaxesScreen extends StatelessWidget {
  const OwnerTaxesScreen({super.key});
  @override
  Widget build(BuildContext context) => JsonView(load: (api) => api.getJson('/api/v1/tax-obligations'), builder: (context, d, reload) => [
        if (items(d).isEmpty) const EmptyState(title: 'Nenhuma guia'),
        for (final t in items(d))
          RowCard(
            title: '${t['kind']} · ${brDate(t['competence'])}',
            subtitle: '${labelOf(taxStepLabels, str(t['state']))} · vence ${brDate(t['due_date'])} · ${t['created_by_name']}',
            trailing: formatMoney(strOrNull(t['amount'])),
            onTap: () async {
              await openApproval(context, {'kind': 'TAX', 'id': t['id']});
              reload();
            },
          ),
      ]);
}

class OwnerPayrollScreen extends StatelessWidget {
  const OwnerPayrollScreen({super.key});
  @override
  Widget build(BuildContext context) => JsonView(load: (api) => api.getJson('/api/v1/payroll-runs'), builder: (context, d, reload) => [
        if (items(d).isEmpty) const EmptyState(title: 'Nenhuma folha'),
        for (final r in items(d))
          RowCard(
            title: '${r['description']} · ${brDate(r['competence'])}',
            subtitle: labelOf(taxStepLabels, str(r['state'])),
            trailing: formatMoney(strOrNull((r['totals'] as J?)?['company_cost'])),
            onTap: () async {
              await openApproval(context, {'kind': 'PAYROLL', 'id': r['id']});
              reload();
            },
          ),
      ]);
}

/// Fechamento do mês: fechar trava lançamentos na competência; reabrir exige pedido e decisão de quem pode.
class ClosingScreen extends StatelessWidget {
  const ClosingScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return JsonView(load: (api) => api.getJson('/api/v1/periods'), builder: (context, d, reload) {
      Future<void> act(String title, String path, String ok) async {
        final why = await askText(context, title: title, action: 'Confirmar');
        if (why == null || !context.mounted) return;
        if (await runCommand(context, (api) => api.sendJson('POST', path, {'reason': why}), ok: ok) != null) reload();
      }
      return [
        if (items(d).isEmpty) const EmptyState(title: 'Nenhum mês registrado'),
        for (final p in items(d))
          Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Mês ${p['month']}', style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
            StateChip(str(p['state']), const {'OPEN': 'Aberto', 'CLOSED': 'Fechado'}),
            if (p['closed_by_name'] != null) Text('Fechado por ${p['closed_by_name']} em ${brDateTime(p['closed_at'])}'),
            if (p['profit_at_close'] != null) Text('Lucro no fechamento ${formatMoney(strOrNull(p['profit_at_close']))}'),
            if (p['reopen_pending'] == true) Text('Reabertura pedida por ${p['reopen_requested_by']}', style: const TextStyle(color: EngColors.warning)),
            Wrap(spacing: 8, children: [
              if (p['state'] == 'OPEN' && me.can('reports.close_period'))
                OutlinedButton(onPressed: () => act('Fechar o mês ${p['month']}', '/api/v1/periods/${p['id']}/close', 'Mês fechado.'),
                    child: const Text('Fechar mês')),
              if (p['state'] == 'CLOSED' && p['reopen_pending'] != true)
                OutlinedButton(onPressed: () => act('Pedir reabertura', '/api/v1/periods/${p['id']}/reopen-requests', 'Reabertura pedida.'),
                    child: const Text('Pedir reabertura')),
              if (p['reopen_pending'] == true && me.can('reports.reopen_period')) ...[
                FilledButton(onPressed: () => act('Reabrir mês', '/api/v1/periods/${p['id']}/reopen', 'Mês reaberto.'), child: const Text('Reabrir')),
                OutlinedButton(onPressed: () => act('Negar reabertura', '/api/v1/periods/${p['id']}/reopen-rejections', 'Reabertura negada.'),
                    child: const Text('Negar')),
              ],
            ]),
            for (final e in (p['events'] as List? ?? const []).cast<J>())
              Text('${brDateTime(e['occurred_at'])} · ${e['actor_name']} ${e['action']}${e['reason'] == null ? '' : ': ${e['reason']}'}',
                  style: const TextStyle(color: EngColors.muted, fontSize: 12)),
          ]))),
      ];
    });
  }
}

class OpenFinanceScreen extends StatefulWidget {
  const OpenFinanceScreen({super.key});
  @override
  State<OpenFinanceScreen> createState() => _OpenFinanceScreenState();
}

class _OpenFinanceScreenState extends State<OpenFinanceScreen> {
  Key _k = UniqueKey();
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return JsonView(key: _k, load: (api) async => {
          'status': await api.getJson('/api/v1/finance/open-finance'),
          'tx': await api.getJson('/api/v1/finance/open-finance/transactions'),
        }, builder: (context, d, reload) {
      final st = d['status'] as J;
      final configured = st['state'] == 'CONFIGURADO';
      return [
        Card(child: Padding(padding: const EdgeInsets.all(12), child: Text(
            configured ? 'Agregador: ${st['provider']}' : 'Open Finance NÃO CONFIGURADO: ${st['message']}',
            style: TextStyle(color: configured ? EngColors.green : EngColors.warning, fontWeight: FontWeight.w600)))),
        const SectionTitle('Conexões'),
        if (items(st, 'connections').isEmpty) const Text('Nenhuma conexão.', style: TextStyle(color: EngColors.muted)),
        for (final c in items(st, 'connections'))
          Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('${c['institution']} · ${c['account_name']}', style: const TextStyle(fontWeight: FontWeight.w700)),
            Text('${c['state']}${c['last_sync_at'] == null ? '' : ' · última leitura ${brDateTime(c['last_sync_at'])}'}'),
            if (c['provider_balance'] != null)
              Text('Banco ${formatMoney(strOrNull(c['provider_balance']))} × livro ${formatMoney(strOrNull(c['ledger_balance']))}'),
            if (c['last_error'] != null) Text(str(c['last_error']), style: const TextStyle(color: EngColors.error)),
            if (me.admin) Wrap(spacing: 8, children: [
              OutlinedButton(onPressed: () async {
                final r = await runCommand(context, (api) => api.sendJson('POST', '/api/v1/finance/open-finance/connections/${c['id']}/sync', null));
                if (r != null && context.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(
                      'Lidos ${r['imported']} · conciliados ${r['matched']} · sugeridos ${r['suggested']} · sem par ${r['unmatched']}')));
                  reload();
                }
              }, child: const Text('Ler extrato')),
              OutlinedButton(onPressed: () async {
                final why = await askText(context, title: 'Revogar conexão', required: false, action: 'Revogar');
                if (why == null || !context.mounted) return;
                final r = await runCommand(context, (api) => api.sendJson('POST',
                    '/api/v1/finance/open-finance/connections/${c['id']}/revoke', {'reason': why}), ok: 'Conexão revogada.');
                if (r != null) reload();
              }, child: const Text('Revogar')),
            ]),
          ]))),
        if (me.admin)
          OutlinedButton(onPressed: () async {
            final r = await openForm(context, title: 'Conectar banco', submitLabel: 'Conectar', fields: const [
              FieldSpec('fin_account_id', 'Conta da empresa', kind: FieldKind.account, required: true),
              FieldSpec('institution', 'Banco', required: true),
              FieldSpec('company_document', 'CNPJ da empresa', required: true),
            ], submit: (api, v, key) => api.sendJson('POST', '/api/v1/finance/open-finance/connections', v));
            if (r != null) setState(() => _k = UniqueKey());
          }, child: const Text('Conectar banco')),
        const SectionTitle('Lançamentos do banco'),
        if (items(d['tx'] as J).isEmpty) const Text('Nenhum lançamento lido.', style: TextStyle(color: EngColors.muted)),
        for (final t in items(d['tx'] as J))
          RowCard(
            title: str(t['description']),
            subtitle: '${brDate(t['booked_on'])} · ${t['account_name']} · ${t['state']}${t['suggested_category'] == null ? '' : ' · sugerido ${t['suggested_category']}'}',
            trailing: '${t['direction'] == 'IN' ? '+' : '−'}${formatMoney(strOrNull(t['amount']))}',
            onTap: t['state'] == 'UNMATCHED' && me.can('expenses.confirm')
                ? () async {
                    final why = await askText(context, title: 'Ignorar lançamento', required: false, action: 'Ignorar');
                    if (why == null || !context.mounted) return;
                    final r = await runCommand(context, (api) => api.sendJson('POST',
                        '/api/v1/finance/open-finance/transactions/${t['id']}/ignore', {'reason': why}), ok: 'Lançamento ignorado.');
                    if (r != null) reload();
                  }
                : null,
          ),
      ];
    });
  }
}

class IntegrationsScreen extends StatelessWidget {
  const IntegrationsScreen({super.key});
  @override
  Widget build(BuildContext context) => JsonView(load: (api) => api.getJson('/api/v1/integrations'), builder: (context, d, reload) => [
        for (final i in items(d))
          Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(str(i['label']), style: const TextStyle(fontWeight: FontWeight.w700)),
            StateChip(str(i['state']), const {'CONFIGURED': 'Configurado', 'NOT_CONFIGURED': 'NÃO CONFIGURADO'}),
            Text(str(i['message'])),
            if ((i['pending'] as int? ?? 0) > 0) Text('${i['pending']} ${i['pending_label']}', style: const TextStyle(color: EngColors.warning)),
          ]))),
        const SectionTitle('Tentativas recentes'),
        if (items(d, 'recent').isEmpty) const Text('Nenhuma tentativa.', style: TextStyle(color: EngColors.muted)),
        for (final a in items(d, 'recent'))
          RowCard(title: '${a['integration']} · ${a['operation']} · ${a['outcome']}',
              subtitle: '${brDateTime(a['started_at'])} · ${a['actor_name'] ?? 'serviço'}${a['detail'] == null ? '' : ' · ${a['detail']}'}',
              onTap: () => openOrigin(context, strOrNull(a['entity_type']), strOrNull(a['entity_id']))),
      ]);
}

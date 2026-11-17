import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:material_ui/material_ui.dart';
import '../labels.dart';
import '../scope.dart';

const _months = ['janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho', 'julho', 'agosto', 'setembro', 'outubro',
  'novembro', 'dezembro'];
String _iso(DateTime d) => '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-01';

/// Financeiro do patrão (paridade com o site): resultado por competência, caixa disponível à parte e conciliação.
/// Só leitura; os números vêm do mesmo cálculo do servidor usado no site.
class FinanceSummaryScreen extends StatefulWidget {
  const FinanceSummaryScreen({super.key});
  @override
  State<FinanceSummaryScreen> createState() => _FinanceSummaryScreenState();
}

class _FinanceSummaryScreenState extends State<FinanceSummaryScreen> {
  int _offset = 0;
  Map<String, dynamic>? _s;
  Map<String, dynamic>? _rec;
  ApiException? _error;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_s == null && _error == null) _load();
  }

  DateTime get _month {
    final n = DateTime.now();
    return DateTime(n.year, n.month + _offset, 1);
  }

  Future<void> _load() async {
    setState(() {
      _s = null;
      _error = null;
    });
    final api = EngScope.of(context).api;
    final m = _month;
    try {
      final s = await api.financeSummary(from: _iso(m), to: _iso(DateTime(m.year, m.month + 1, 1)));
      final r = await api.reconciliation();
      if (mounted) {
        setState(() {
          _s = s;
          _rec = r;
        });
      }
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  Widget _line(String label, String? amount, {bool strong = false}) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(children: [
          Expanded(child: Text(label, style: TextStyle(fontWeight: strong ? FontWeight.w700 : FontWeight.w400))),
          Text(formatMoney(amount), style: TextStyle(fontWeight: strong ? FontWeight.w700 : FontWeight.w400)),
        ]),
      );

  @override
  Widget build(BuildContext context) {
    if (_error != null) {
      return _error!.isForbidden
          ? const ForbiddenStateView()
          : ErrorStateView(message: _error!.message, requestId: _error!.requestId, offline: _error!.isNetwork, onRetry: _load);
    }
    final s = _s;
    if (s == null) return const LoadingState();
    final cash = s['cash'] as Map<String, dynamic>;
    final profit = s['profit'] as String?; // null = oculto pela política de campo (custo/margem)
    final recOk = _rec?['status'] == 'OK';
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(padding: const EdgeInsets.all(16), children: [
        Row(children: [
          IconButton(tooltip: 'Mês anterior', onPressed: () { _offset--; _load(); }, icon: const Text('‹', style: TextStyle(fontSize: 24))),
          Expanded(child: Text('${_months[_month.month - 1]} de ${_month.year}', textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600))),
          IconButton(tooltip: 'Próximo mês', onPressed: _offset >= 0 ? null : () { _offset++; _load(); },
              icon: const Text('›', style: TextStyle(fontSize: 24))),
        ]),
        Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Text('Resultado gerencial (competência)', style: TextStyle(color: EngColors.muted)),
          _line('Vendas cumpridas', s['gross_revenue'] as String?),
          _line('(−) Descontos e devoluções', _sum(s['discounts'] as String?, s['returns'] as String?)),
          if (s['cogs'] != null) _line('(−) CMV', s['cogs'] as String?),
          for (final e in (s['expenses'] as List).cast<Map<String, dynamic>>()) _line('(−) ${e['label']}', e['amount'] as String?),
          if (s['losses'] != null) _line('(−) Perdas de estoque', s['losses'] as String?),
          const Divider(),
          if (profit == null)
            const Text('CMV, perdas e lucro ocultos pela sua permissão.', style: TextStyle(color: EngColors.muted))
          else
            _line(profit.startsWith('-') ? 'Prejuízo do período' : 'Lucro do período', profit, strong: true),
          if (s['margin_percent'] != null)
            Text('Margem: ${(s['margin_percent'] as String).replaceAll('-', '−').replaceAll('.', ',')}%', style: const TextStyle(color: EngColors.muted)),
        ]))),
        Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Text('Caixa disponível (caixas + bancos)', style: TextStyle(color: EngColors.muted)),
          Text(formatMoney(cash['available'] as String?), style: const TextStyle(fontSize: 28, fontWeight: FontWeight.w800, color: EngColors.green)),
          _line('Cartões a receber', cash['card_receivables_open'] as String?),
          _line('A receber de clientes', cash['receivables_open'] as String?),
          _line('A pagar', cash['payables_open'] as String?),
          const Text('Saldo em caixa não é lucro.', style: TextStyle(color: EngColors.muted, fontSize: 12)),
        ]))),
        if (_rec != null)
          Card(child: ListTile(
            title: Text(recOk ? 'Conciliação: tudo confere' : 'Conciliação: há divergência',
                style: TextStyle(color: recOk ? EngColors.green : EngColors.error, fontWeight: FontWeight.w600)),
            subtitle: Text([
              for (final c in (_rec!['checks'] as List).cast<Map<String, dynamic>>())
                if (c['status'] == 'DIVERGENT') '${c['label']}: diferença ${formatMoney(c['difference'] as String?)}'
            ].join('\n').ifEmpty('Livro, contas a pagar, cartões, estoque e caixas de turno batem.')),
          )),
      ]),
    );
  }

  static String _sum(String? a, String? b) {
    final ca = int.parse((a ?? '0.00').replaceAll('.', ''));
    final cb = int.parse((b ?? '0.00').replaceAll('.', ''));
    final t = ca + cb; // centavos inteiros, sem ponto flutuante
    final neg = t < 0;
    final abs = t.abs();
    return '${neg ? '-' : ''}${abs ~/ 100}.${(abs % 100).toString().padLeft(2, '0')}';
  }
}

extension on String {
  String ifEmpty(String other) => isEmpty ? other : this;
}

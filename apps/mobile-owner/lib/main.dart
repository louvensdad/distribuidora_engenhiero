import 'package:engenheiro_core/engenheiro_core.dart';
import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:material_ui/material_ui.dart';

/// Engenheiro Patrão (br.com.engenheiro.gestao): paridade com o site + Central dos outros três setores.
final config = AppConfig(
  appName: 'Engenheiro Patrão',
  applicationId: 'br.com.engenheiro.gestao',
  clientId: 'engenheiro-gestao',
  audienceDescription: 'Gestão completa e central dos setores',
  destinations: [
    Destination(id: 'inicio', label: 'Início', icon: 'home', builder: (_) => const HomeSummaryScreen()),
    Destination(id: 'central', label: 'Central', icon: 'dashboard', anyOf: const ['audit.view'], builder: (_) => const CentralScreen()),
    Destination(id: 'relatorios', label: 'Relatórios', icon: 'report', anyOf: const ['reports.view'], pendingPhase: 'F09',
        builder: (_) => const PendingModuleView(title: 'Relatórios e gráficos 3D', phase: 'F09')),
    Destination(id: 'ia', label: 'IA', icon: 'ai', anyOf: const ['ai.view'], pendingPhase: 'F15',
        builder: (_) => const PendingModuleView(title: 'IA da gestão', phase: 'F15')),
    Destination(id: 'mais', label: 'Mais', icon: 'settings',
        anyOf: const ['users.view', 'customers.view', 'audit.view', 'inventory.stock.view', 'inventory.receipts.view',
          'orders.pickup.view', 'reports.view'],
        builder: (_) => const MoreScreen()),
  ],
);

void main() => runApp(EngenheiroApp(config: config));

/// Central: Retirada, Entregas e Contabilidade com cada movimento e o ator real (filtro por app de origem).
class CentralScreen extends StatelessWidget {
  const CentralScreen({super.key});
  @override
  Widget build(BuildContext context) => DefaultTabController(
        length: 3,
        child: Column(children: [
          const TabBar(tabs: [Tab(text: 'Retirada'), Tab(text: 'Entregas'), Tab(text: 'Contabilidade')]),
          Expanded(
            child: TabBarView(children: [
              ActivityScreen(clientId: 'engenheiro-retirada', emptyMessage: 'Nenhuma atividade registrada pelo app de Retirada.'),
              ActivityScreen(clientId: 'engenheiro-entregas', emptyMessage: 'Nenhuma atividade registrada pelo app de Entregas.'),
              ActivityScreen(clientId: 'engenheiro-contabil', emptyMessage: 'Nenhuma atividade registrada pelo app Contábil.'),
            ]),
          ),
        ]),
      );
}

class MoreScreen extends StatelessWidget {
  const MoreScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final s = EngScope.of(context);
    void open(String title, Widget child) => Navigator.of(context).push(MaterialPageRoute<void>(
        builder: (_) => EngScope(config: s.config, session: s.session, child: Scaffold(appBar: AppBar(title: Text(title)), body: child))));
    return ListView(padding: const EdgeInsets.all(16), children: [
      if (s.me.can('reports.view'))
        _Item('wallet', 'Financeiro', 'Resultado do mês, caixa disponível e conciliação', () => open('Financeiro', const FinanceSummaryScreen())),
      if (s.me.can('customers.view'))
        _Item('users', 'Clientes', 'Cadastro e busca', () => open('Clientes', const CustomersScreen())),
      if (s.me.can('orders.pickup.view') || s.me.can('orders.delivery.view'))
        _Item('order', 'Pedidos', 'Reserva, pagamento e retirada com autor', () => open('Pedidos', const OrdersScreen())),
      if (s.me.can('own_cash.view'))
        _Item('cash', 'Meu caixa', 'Fundo, recebimentos em dinheiro e fechamento', () => open('Meu caixa', const CashScreen())),
      if (s.me.can('inventory.stock.view'))
        _Item('inventory', 'Estoque', 'Físico, reservado, disponível e perdas', () => open('Estoque', const StockScreen())),
      if (s.me.can('inventory.receipts.view'))
        _Item('supplier', 'Recebimentos de fornecedor', 'Conferência, custo e entrada no estoque', () => open('Recebimentos', const ReceiptsScreen())),
      if (s.me.can('users.view')) _Item('shield', 'Pessoas', 'Equipe e situação das contas', () => open('Pessoas', const PeopleScreen())),
      if (s.me.can('audit.view'))
        _Item('history', 'Histórico completo', 'Todos os movimentos com ator real', () => open('Histórico', const ActivityScreen())),
      if (s.me.can('permissions.view'))
        _Item('lock', 'Mapa de acessos', 'Edição no app chega na F14 (paridade). Hoje: pelo site.',
            () => open('Mapa de acessos', const PendingModuleView(title: 'Mapa de acessos no app', phase: 'F14'))),
    ]);
  }
}

class _Item extends StatelessWidget {
  const _Item(this.icon, this.title, this.subtitle, this.onTap);
  final String icon;
  final String title;
  final String subtitle;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Card(
          child: ListTile(
            minVerticalPadding: 14,
            leading: EngIcon(icon, color: EngColors.cyan),
            title: Text(title),
            subtitle: Text(subtitle),
            trailing: const EngIcon('arrow', size: 18, color: EngColors.muted),
            onTap: onTap,
          ),
        ),
      );
}

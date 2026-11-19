import 'package:engenheiro_core/engenheiro_core.dart';
import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:material_ui/material_ui.dart';

/// Engenheiro Patrão (br.com.engenheiro.gestao): paridade com o site + Central dos outros três setores (F14).
/// Tudo o que o dono faz aqui vai com a conta e o client dele (engenheiro-gestao); o servidor decide o que pode.
final config = AppConfig(
  appName: 'Engenheiro Patrão',
  applicationId: 'br.com.engenheiro.gestao',
  clientId: 'engenheiro-gestao',
  audienceDescription: 'Gestão completa e central dos setores',
  // design/01_DIRECAO_VISUAL: "Patrão: Início, Central, Relatórios, IA e Mais. Aprovações ficam visíveis na Central e no Início."
  destinations: [
    Destination(id: 'inicio', label: 'Início', icon: 'home', builder: (c) => OwnerHomeScreen(
        openApprovals: () => pushScreen(c, 'Aprovações', const ApprovalsScreen()))),
    Destination(id: 'central', label: 'Central', icon: 'dashboard', builder: (_) => const OwnerCentralScreen(enter: sectorShortcuts)),
    Destination(id: 'relatorios', label: 'Relatórios', icon: 'report', anyOf: const ['reports.view'],
        builder: (_) => const OwnerReportsHub()),
    Destination(id: 'ia', label: 'IA', icon: 'ai', anyOf: const ['ai.view'], pendingPhase: 'F15',
        builder: (_) => const PendingModuleView(title: 'IA da gestão', phase: 'F15')),
    Destination(id: 'mais', label: 'Mais', icon: 'settings', builder: (_) => const ModulesScreen()),
  ],
);

/// "Entrar no setor" a partir da central (P03), com as mesmas telas do resto do app.
const sectorShortcuts = <String, List<(String, Widget)>>{
  'COUNTER': [('Pedidos', OwnerOrdersScreen()), ('Estoque', OwnerStockScreen()), ('Recebimentos', ReceiptsScreen())],
  'DELIVERY': [('Viagens', OwnerTripsScreen()), ('Frota', FleetScreen())],
  'ACCOUNTING': [('Guias', OwnerTaxesScreen()), ('Folha', OwnerPayrollScreen()), ('Notas', InvoicesScreen()),
    ('Documentos', AccountingDocumentsScreen())],
};

void main() => runApp(EngenheiroApp(config: config));

/// P12 — todos os módulos do site no celular. Cada item aparece só com a permissão correspondente.
class ModulesScreen extends StatelessWidget {
  const ModulesScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    Widget item(String icon, String title, String sub, Widget screen) => Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: Card(child: ListTile(
            minVerticalPadding: 14,
            leading: EngIcon(icon, color: EngColors.cyan),
            title: Text(title),
            subtitle: Text(sub),
            trailing: const EngIcon('arrow', size: 18, color: EngColors.muted),
            onTap: () => pushScreen(context, title, screen),
          )),
        );
    return ListView(padding: const EdgeInsets.all(16), children: [
      item('search', 'Buscar registro', 'Pedido, viagem, placa, cliente, fornecedor ou produto', const OwnerSearchScreen()),
      item('order', 'Vendas e operação', 'Pedidos, clientes, fornecedores, produtos, estoque, frota e viagens', const OperationHubScreen()),
      item('wallet', 'Financeiro', 'Contas, despesas, pagar e receber, fechamento, Open Finance', const FinanceHubScreen()),
      if (me.can('taxes.view') || me.can('payroll.view') || me.can('invoices.view') || me.can('documents.view'))
        item('tax', 'Contabilidade e documentos', 'Guias, folha, notas fiscais e documentos privados', const AccountingHubScreen()),
      item('users', 'Equipe e acessos', 'Pessoas, permissões, funcionários, cargos, folgas e unidades', const TeamHubScreen()),
      if (me.can('audit.view'))
        item('history', 'Histórico completo', 'Cada movimento com autor, app e o que mudou', const HistoryScreen()),
      if (me.can('own_cash.view')) item('cash', 'Meu caixa', 'Fundo, recebimentos em dinheiro e fechamento', const CashScreen()),
      item('shield', 'Aprovações', 'Tudo que espera a sua decisão', const ApprovalsScreen()),
    ]);
  }
}

class AccountingHubScreen extends StatelessWidget {
  const AccountingHubScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    Widget item(String title, String sub, Widget screen) =>
        Card(child: ListTile(title: Text(title), subtitle: Text(sub), onTap: () => pushScreen(context, title, screen)));
    return ListView(padding: const EdgeInsets.all(16), children: [
      if (me.can('taxes.view')) item('Guias e impostos', 'Aprovar, devolver e pagar', const OwnerTaxesScreen()),
      if (me.can('payroll.view')) item('Folha de pagamento', 'Aprovar, devolver, pagar e exportar', const OwnerPayrollScreen()),
      if (me.can('invoices.view')) item('Notas fiscais', 'Conferir, transmitir, consultar, cancelar', const InvoicesScreen()),
      if (me.can('documents.view')) item('Documentos', 'Arquivos privados e solicitações', const AccountingDocumentsScreen()),
    ]);
  }
}

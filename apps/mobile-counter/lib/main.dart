import 'package:engenheiro_core/engenheiro_core.dart';
import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:material_ui/material_ui.dart';

/// Engenheiro Retirada (br.com.engenheiro.retirada): balcão e recebimento interno de fornecedores.
final config = AppConfig(
  appName: 'Engenheiro Retirada',
  applicationId: 'br.com.engenheiro.retirada',
  clientId: 'engenheiro-retirada',
  audienceDescription: 'Balcão, retirada e recebimento de fornecedores',
  destinations: [
    Destination(id: 'atendimento', label: 'Atendimento', icon: 'users', anyOf: const ['customers.view'], builder: (_) => const CustomersScreen()),
    Destination(id: 'pedidos', label: 'Pedidos', icon: 'order', anyOf: const ['orders.pickup.view'], pendingPhase: 'F04',
        builder: (_) => const PendingModuleView(title: 'Pedidos de retirada', phase: 'F04')),
    Destination(id: 'recebimentos', label: 'Fornecedores', icon: 'supplier', anyOf: const ['inventory.receipts.view'],
        builder: (_) => const ReceiptsScreen()),
    Destination(id: 'estoque', label: 'Estoque', icon: 'inventory', anyOf: const ['inventory.stock.view'], builder: (_) => const StockScreen()),
    Destination(id: 'caixa', label: 'Caixa', icon: 'cash', anyOf: const ['own_cash.view'], pendingPhase: 'F04',
        builder: (_) => const PendingModuleView(title: 'Caixa próprio', phase: 'F04')),
  ],
);

void main() => runApp(EngenheiroApp(config: config));

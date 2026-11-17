import 'package:engenheiro_core/engenheiro_core.dart';
import 'package:material_ui/material_ui.dart';

/// Engenheiro Entregas (br.com.engenheiro.entregas): coordenação, despacho, motoristas e veículos.
/// O motorista usa ESTE app (não há quinto app) e só vê o que foi atribuído a ele.
final config = AppConfig(
  appName: 'Engenheiro Entregas',
  applicationId: 'br.com.engenheiro.entregas',
  clientId: 'engenheiro-entregas',
  audienceDescription: 'Entregas, motoristas e veículos',
  destinations: [
    // Coordenação/atendimento: pedidos de entrega com endereço e pagamento combinado (E02/E03).
    Destination(id: 'pedidos', label: 'Pedidos', icon: 'order', anyOf: const ['orders.delivery.view'],
        builder: (_) => const OrdersScreen(type: 'DELIVERY')),
    // Viagens: o motorista vê só as atribuídas; a coordenação organiza (E04 despacho) pelo botão "Organizar viagem".
    Destination(id: 'viagens', label: 'Viagens', icon: 'route', anyOf: const ['trips.view'], builder: (_) => const TripsScreen()),
    Destination(id: 'veiculos', label: 'Veículos', icon: 'car', anyOf: const ['vehicles.view', 'fuel.create'],
        builder: (_) => const VehiclesScreen()),
    Destination(id: 'caixa', label: 'Caixa', icon: 'cash', anyOf: const ['own_cash.view'], builder: (_) => const CashScreen()),
    Destination(id: 'clientes', label: 'Clientes', icon: 'users', anyOf: const ['customers.view'], builder: (_) => const CustomersScreen()),
  ],
);

void main() => runApp(EngenheiroApp(config: config));

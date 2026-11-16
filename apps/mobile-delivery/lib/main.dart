import 'package:engenheiro_core/engenheiro_core.dart';
import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:material_ui/material_ui.dart';

/// Engenheiro Entregas (br.com.engenheiro.entregas): coordenação, despacho, motoristas e veículos.
/// O motorista usa ESTE app (não há quinto app) e só vê o que foi atribuído a ele.
final config = AppConfig(
  appName: 'Engenheiro Entregas',
  applicationId: 'br.com.engenheiro.entregas',
  clientId: 'engenheiro-entregas',
  audienceDescription: 'Entregas, motoristas e veículos',
  destinations: [
    Destination(id: 'viagens', label: 'Viagens', icon: 'route', anyOf: const ['trips.view'], builder: (_) => const TripsScreen()),
    Destination(id: 'veiculos', label: 'Veículos', icon: 'car', anyOf: const ['vehicles.view', 'fuel.create'],
        builder: (_) => const VehiclesScreen()),
    Destination(id: 'caixa', label: 'Caixa', icon: 'cash', anyOf: const ['own_cash.view'], builder: (_) => const CashScreen()),
    // Despacho pelo app (E04) é da F12; na F05 o despacho é feito no site (Operação > Viagens).
    Destination(id: 'despacho', label: 'Despacho', icon: 'truck', anyOf: const ['trips.assign'], pendingPhase: 'F12',
        builder: (_) => const PendingModuleView(title: 'Despacho pelo app (use o site: Operação > Viagens)', phase: 'F12')),
    Destination(id: 'clientes', label: 'Clientes', icon: 'users', anyOf: const ['customers.view'], builder: (_) => const CustomersScreen()),
  ],
);

void main() => runApp(EngenheiroApp(config: config));

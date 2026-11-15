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
    Destination(id: 'viagens', label: 'Viagens', icon: 'route', anyOf: const ['trips.view'], pendingPhase: 'F05',
        builder: (_) => const PendingModuleView(title: 'Viagens e entregas', phase: 'F05')),
    Destination(id: 'despacho', label: 'Despacho', icon: 'truck', anyOf: const ['trips.assign'], pendingPhase: 'F05',
        builder: (_) => const PendingModuleView(title: 'Despacho', phase: 'F05')),
    Destination(id: 'veiculos', label: 'Veículos', icon: 'car', anyOf: const ['vehicles.view', 'fuel.create'], pendingPhase: 'F05',
        builder: (_) => const PendingModuleView(title: 'Veículos, abastecimento e manutenção', phase: 'F05')),
    Destination(id: 'clientes', label: 'Clientes', icon: 'users', anyOf: const ['customers.view'], builder: (_) => const CustomersScreen()),
  ],
);

void main() => runApp(EngenheiroApp(config: config));

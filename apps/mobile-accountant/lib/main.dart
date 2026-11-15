import 'package:engenheiro_core/engenheiro_core.dart';
import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:material_ui/material_ui.dart';

/// Engenheiro Contábil (br.com.engenheiro.contabil): guias, folha, notas e documentos.
/// Sem dashboard, vendas totais, lucro ou saldo geral por padrão.
final config = AppConfig(
  appName: 'Engenheiro Contábil',
  applicationId: 'br.com.engenheiro.contabil',
  clientId: 'engenheiro-contabil',
  audienceDescription: 'Contador e auxiliares autorizados',
  destinations: [
    Destination(id: 'agenda', label: 'Agenda', icon: 'clock', anyOf: const ['taxes.view', 'payroll.view'], pendingPhase: 'F07',
        builder: (_) => const PendingModuleView(title: 'Agenda de obrigações', phase: 'F07')),
    Destination(id: 'guias', label: 'Guias', icon: 'tax', anyOf: const ['taxes.view'], pendingPhase: 'F07',
        builder: (_) => const PendingModuleView(title: 'Guias e impostos', phase: 'F07')),
    Destination(id: 'folha', label: 'Folha', icon: 'wallet', anyOf: const ['payroll.view'], pendingPhase: 'F07',
        builder: (_) => const PendingModuleView(title: 'Folha de pagamento', phase: 'F07')),
    Destination(id: 'notas', label: 'Notas', icon: 'invoice', anyOf: const ['invoices.view'], pendingPhase: 'F08',
        builder: (_) => const PendingModuleView(title: 'Notas fiscais', phase: 'F08')),
    Destination(id: 'documentos', label: 'Documentos', icon: 'document', anyOf: const ['documents.view'], pendingPhase: 'F07',
        builder: (_) => const PendingModuleView(title: 'Documentos privados', phase: 'F07')),
  ],
);

void main() => runApp(EngenheiroApp(config: config));

import 'package:engenheiro_core/engenheiro_core.dart';
import 'package:material_ui/material_ui.dart';

/// Engenheiro Contábil (br.com.engenheiro.contabil): guias, folha, notas e documentos.
/// Sem dashboard, vendas totais, lucro ou saldo geral por padrão.
final config = AppConfig(
  appName: 'Engenheiro Contábil',
  applicationId: 'br.com.engenheiro.contabil',
  clientId: 'engenheiro-contabil',
  audienceDescription: 'Contador e auxiliares autorizados',
  destinations: [
    Destination(id: 'agenda', label: 'Agenda', icon: 'clock', anyOf: const ['taxes.view', 'payroll.view', 'documents.view'],
        builder: (_) => const AccountantAgendaScreen()),
    Destination(id: 'guias', label: 'Guias', icon: 'tax', anyOf: const ['taxes.view'], builder: (_) => const TaxesScreen()),
    Destination(id: 'folha', label: 'Folha', icon: 'wallet', anyOf: const ['payroll.view'], builder: (_) => const PayrollScreen()),
    Destination(id: 'notas', label: 'Notas', icon: 'invoice', anyOf: const ['invoices.view'],
        builder: (_) => const InvoicesScreen()),
    Destination(id: 'documentos', label: 'Documentos', icon: 'document', anyOf: const ['documents.view'],
        builder: (_) => const AccountingDocumentsScreen()),
  ],
);

void main() => runApp(EngenheiroApp(config: config));

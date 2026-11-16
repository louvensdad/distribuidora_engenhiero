import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:material_ui/material_ui.dart';
import '../scope.dart';
import 'activity_screen.dart';

/// Início do patrão: identificação e últimos movimentos reais. Resultado e caixa do mês: Mais › Financeiro.
class HomeSummaryScreen extends StatelessWidget {
  const HomeSummaryScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    final t = Theme.of(context).textTheme;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
        child: Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('Olá, ${me.displayName}', style: t.titleMedium),
              Text('${me.organizationName} · ${me.branchName}', style: const TextStyle(color: EngColors.muted)),
              const SizedBox(height: 12),
              Text(me.can('reports.view')
                  ? 'Resultado do mês, caixa disponível e conciliação: Mais › Financeiro (os mesmos números do site).'
                  : 'Indicadores financeiros dependem da permissão de relatórios. Nenhum número de exemplo é exibido.',
                  style: const TextStyle(color: EngColors.muted, fontSize: 13)),
            ]),
          ),
        ),
      ),
      const Padding(padding: EdgeInsets.fromLTRB(16, 16, 16, 0), child: Text('Últimos movimentos', style: TextStyle(fontWeight: FontWeight.w600))),
      if (me.can('audit.view')) const Expanded(child: ActivityScreen()) else const Spacer(),
    ]);
  }
}

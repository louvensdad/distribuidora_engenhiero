import 'package:flutter/widgets.dart';

/// Largura de leitura (tablet, celular deitado): em telas largas o conteúdo fica centralizado com no máximo
/// [maxWidth] em vez de esticar cartões, tabelas e formulários até a borda. Em celular em pé não muda nada.
class EngReadableWidth extends StatelessWidget {
  const EngReadableWidth({super.key, required this.child, this.maxWidth = 960});

  /// Largura máxima do conteúdo (dp). 960 cabe duas colunas de dados confortáveis num tablet de 10" deitado.
  final double maxWidth;
  final Widget child;

  @override
  Widget build(BuildContext context) => Align(
        alignment: Alignment.topCenter,
        child: ConstrainedBox(constraints: BoxConstraints(maxWidth: maxWidth), child: child),
      );
}

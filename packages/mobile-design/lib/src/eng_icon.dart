import 'package:flutter/widgets.dart';
import 'package:flutter_svg/flutter_svg.dart';

/// Ícones vetoriais do pacote (viewBox 24, traço 1.7, currentColor → cor do tema).
class EngIcon extends StatelessWidget {
  const EngIcon(this.name, {super.key, this.size = 24, this.color, this.semanticLabel});

  final String name;
  final double size;
  final Color? color;
  final String? semanticLabel;

  @override
  Widget build(BuildContext context) {
    final c = color ?? IconTheme.of(context).color ?? const Color(0xFFEDF5F7);
    return SvgPicture.asset(
      'assets/icons/$name.svg',
      package: 'engenheiro_design',
      width: size,
      height: size,
      colorFilter: ColorFilter.mode(c, BlendMode.srcIn),
      semanticsLabel: semanticLabel,
      excludeFromSemantics: semanticLabel == null,
    );
  }
}

/// Marca horizontal proposta no pacote (aprovação do dono antes de publicar em loja).
class EngBrand extends StatelessWidget {
  const EngBrand({super.key, this.height = 48});
  final double height;

  @override
  Widget build(BuildContext context) => SvgPicture.asset('assets/marca.svg',
      package: 'engenheiro_design', height: height, semanticsLabel: 'Distribuidora de Cana Engenheiro Ltda');
}

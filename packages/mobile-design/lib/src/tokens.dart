import 'package:flutter/painting.dart';

/// Cores e medidas do pacote de design. Não usar valores soltos nas telas.
abstract final class EngColors {
  static const background = Color(0xFF09131C);
  static const surface = Color(0xFF11232F);
  static const surfaceRaised = Color(0xFF1A3040);
  static const border = Color(0xFF2A4354);
  static const text = Color(0xFFEDF5F7);
  static const muted = Color(0xFFA3BAC8);
  static const green = Color(0xFFB3F18A);
  static const cyan = Color(0xFF67D7E8);
  static const violet = Color(0xFFA9B4FA);
  static const warning = Color(0xFFE8BE75);
  static const error = Color(0xFFFA9A9A);
}

abstract final class EngSize {
  static const touchTarget = 44.0;
  static const radiusControl = 10.0;
  static const radiusPanel = 16.0;
  static const space = 8.0;
  static const bodyMobile = 16.0;
  static const caption = 12.0;
  static const headingMobile = 26.0;
}

const engBrandLegalName = 'Distribuidora de Cana Engenheiro Ltda';
const engBrandShortName = 'ENGENHEIRO';

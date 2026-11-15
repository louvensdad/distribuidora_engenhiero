import 'package:material_ui/material_ui.dart';
import 'tokens.dart';

/// Tema escuro "futurista sóbrio": fundo em camadas, acentos verde/ciano, alvos de toque >= 44.
ThemeData engTheme() {
  const scheme = ColorScheme.dark(
    primary: EngColors.green,
    onPrimary: EngColors.background,
    secondary: EngColors.cyan,
    onSecondary: EngColors.background,
    tertiary: EngColors.violet,
    error: EngColors.error,
    onError: EngColors.background,
    surface: EngColors.surface,
    onSurface: EngColors.text,
    surfaceContainerHighest: EngColors.surfaceRaised,
    outline: EngColors.border,
  );
  final base = ThemeData(useMaterial3: true, colorScheme: scheme, brightness: Brightness.dark);
  return base.copyWith(
    scaffoldBackgroundColor: EngColors.background,
    materialTapTargetSize: MaterialTapTargetSize.padded,
    textTheme: base.textTheme.apply(bodyColor: EngColors.text, displayColor: EngColors.text).copyWith(
          bodyMedium: base.textTheme.bodyMedium?.copyWith(fontSize: EngSize.bodyMobile, color: EngColors.text),
          headlineSmall: base.textTheme.headlineSmall?.copyWith(
              fontSize: EngSize.headingMobile, fontWeight: FontWeight.w700, color: EngColors.text),
        ),
    appBarTheme: const AppBarTheme(
      backgroundColor: EngColors.surface,
      foregroundColor: EngColors.text,
      elevation: 0,
      centerTitle: false,
    ),
    cardTheme: CardThemeData(
      color: EngColors.surface,
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(EngSize.radiusPanel),
        side: const BorderSide(color: EngColors.border),
      ),
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: EngColors.surface,
      indicatorColor: EngColors.cyan.withValues(alpha: 0.18),
      labelTextStyle: WidgetStateProperty.all(const TextStyle(fontSize: 12, color: EngColors.text)),
      iconTheme: WidgetStateProperty.resolveWith(
          (s) => IconThemeData(color: s.contains(WidgetState.selected) ? EngColors.cyan : EngColors.muted)),
    ),
    navigationRailTheme: NavigationRailThemeData(
      backgroundColor: EngColors.surface,
      indicatorColor: EngColors.cyan.withValues(alpha: 0.18),
      selectedIconTheme: const IconThemeData(color: EngColors.cyan),
      unselectedIconTheme: const IconThemeData(color: EngColors.muted),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size(EngSize.touchTarget, 52),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(EngSize.radiusControl)),
        textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        minimumSize: const Size(EngSize.touchTarget, 48),
        foregroundColor: EngColors.text,
        side: const BorderSide(color: EngColors.border),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(EngSize.radiusControl)),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: EngColors.background,
      labelStyle: const TextStyle(color: EngColors.muted),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(EngSize.radiusControl),
        borderSide: const BorderSide(color: EngColors.border),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(EngSize.radiusControl),
        borderSide: const BorderSide(color: EngColors.border),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(EngSize.radiusControl),
        borderSide: const BorderSide(color: EngColors.cyan, width: 2),
      ),
    ),
    dividerTheme: const DividerThemeData(color: EngColors.border, space: 1),
  );
}

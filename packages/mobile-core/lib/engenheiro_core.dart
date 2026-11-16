/// Núcleo compartilhado dos quatro apps independentes: configuração por app, sessão, shell por capacidades
/// e telas comuns. Cada app tem seu próprio main, client OIDC, applicationId e ícone.
library;

export 'src/app_config.dart';
export 'src/session_controller.dart';
export 'src/engenheiro_app.dart';
export 'src/scope.dart';
export 'src/labels.dart';
export 'src/screens/customers_screen.dart';
export 'src/screens/activity_screen.dart';
export 'src/screens/people_screen.dart';
export 'src/screens/home_summary_screen.dart';
export 'src/screens/inventory_screens.dart';
export 'src/screens/orders_screens.dart';
export 'src/screens/delivery_screens.dart';
export 'src/screens/finance_screens.dart';

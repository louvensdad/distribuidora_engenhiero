import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:flutter/widgets.dart';
import 'app_config.dart';
import 'session_controller.dart';

/// Disponibiliza configuração, sessão e API às telas.
class EngScope extends InheritedWidget {
  const EngScope({super.key, required this.config, required this.session, required super.child});

  final AppConfig config;
  final SessionController session;

  ApiClient get api => session.api;
  Me get me => session.me!;

  static EngScope of(BuildContext context) {
    final s = context.dependOnInheritedWidgetOfExactType<EngScope>();
    assert(s != null, 'EngScope ausente');
    return s!;
  }

  @override
  bool updateShouldNotify(EngScope old) => old.session != session || old.config != config;
}

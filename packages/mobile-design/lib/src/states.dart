import 'package:material_ui/material_ui.dart';
import 'eng_icon.dart';
import 'tokens.dart';

/// Estados obrigatórios de tela (design/ESTADOS_E_COMPONENTES.md).
class _StateBox extends StatelessWidget {
  const _StateBox({required this.icon, required this.title, this.message, this.action, this.color});
  final String icon;
  final String title;
  final String? message;
  final Widget? action;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final t = Theme.of(context).textTheme;
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 480),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            EngIcon(icon, size: 36, color: color ?? EngColors.muted),
            const SizedBox(height: 12),
            Text(title, style: t.titleMedium?.copyWith(fontWeight: FontWeight.w600), textAlign: TextAlign.center),
            if (message != null) ...[
              const SizedBox(height: 8),
              Text(message!, style: t.bodyMedium?.copyWith(color: EngColors.muted), textAlign: TextAlign.center),
            ],
            if (action != null) ...[const SizedBox(height: 16), action!],
          ]),
        ),
      ),
    );
  }
}

class LoadingState extends StatelessWidget {
  const LoadingState({super.key, this.label = 'Carregando'});
  final String label;
  @override
  Widget build(BuildContext context) =>
      Center(child: Semantics(label: label, liveRegion: true, child: const CircularProgressIndicator()));
}

class EmptyState extends StatelessWidget {
  const EmptyState({super.key, required this.title, this.message, this.action});
  final String title;
  final String? message;
  final Widget? action;
  @override
  Widget build(BuildContext context) => _StateBox(icon: 'search', title: title, message: message, action: action);
}

class ErrorStateView extends StatelessWidget {
  const ErrorStateView({super.key, required this.message, this.requestId, this.onRetry, this.offline = false});
  final String message;
  final String? requestId;
  final VoidCallback? onRetry;
  final bool offline;
  @override
  Widget build(BuildContext context) => Semantics(
        liveRegion: true,
        child: _StateBox(
          icon: offline ? 'offline' : 'close',
          color: offline ? EngColors.warning : EngColors.error,
          title: offline ? 'Sem conexão com o servidor' : 'Não foi possível carregar',
          message: requestId == null ? message : '$message\nCódigo para suporte: $requestId',
          action: onRetry == null
              ? null
              : OutlinedButton.icon(onPressed: onRetry, icon: const EngIcon('refresh', size: 18), label: const Text('Tentar novamente')),
        ),
      );
}

class ForbiddenStateView extends StatelessWidget {
  const ForbiddenStateView({super.key, this.message = 'Sua conta não tem acesso a esta área. Peça ao administrador, se precisar.', this.action});
  final String message;
  final Widget? action;
  @override
  Widget build(BuildContext context) => _StateBox(icon: 'lock', title: 'Sem permissão', message: message, action: action);
}

/// Função de fase futura: rotulada, sem números de exemplo.
class PendingModuleView extends StatelessWidget {
  const PendingModuleView({super.key, required this.title, required this.phase});
  final String title;
  final String phase;
  @override
  Widget build(BuildContext context) => _StateBox(
        icon: 'clock',
        title: '$title: em implementação',
        message: 'Esta função entra na fase $phase do roteiro. Nenhum dado aparece aqui até o serviço real existir.',
      );
}

class OfflineBanner extends StatelessWidget {
  const OfflineBanner({super.key});
  @override
  Widget build(BuildContext context) => Semantics(
        liveRegion: true,
        child: Container(
          width: double.infinity,
          color: EngColors.warning,
          padding: const EdgeInsets.all(8),
          child: const Text('SEM CONEXÃO — nada é confirmado até a conexão voltar.',
              textAlign: TextAlign.center, style: TextStyle(color: EngColors.background, fontWeight: FontWeight.w600)),
        ),
      );
}

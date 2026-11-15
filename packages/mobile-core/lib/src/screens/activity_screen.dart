import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:material_ui/material_ui.dart';
import '../labels.dart';
import '../scope.dart';

/// Histórico com ator real e horário do servidor; opcionalmente só um app de origem (Central do patrão).
class ActivityScreen extends StatefulWidget {
  const ActivityScreen({super.key, this.clientId, this.emptyMessage});
  final String? clientId;
  final String? emptyMessage;
  @override
  State<ActivityScreen> createState() => _ActivityScreenState();
}

class _ActivityScreenState extends State<ActivityScreen> {
  List<AuditEvent>? _items;
  String? _cursor;
  ApiException? _error;
  bool _more = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_items == null && _error == null) _load();
  }

  Future<void> _load() async {
    setState(() {
      _items = null;
      _error = null;
    });
    try {
      final p = await EngScope.of(context).api.auditEvents(clientId: widget.clientId);
      if (mounted) {
        setState(() {
          _items = p.items;
          _cursor = p.nextCursor;
        });
      }
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  Future<void> _loadMore() async {
    setState(() => _more = true);
    try {
      final p = await EngScope.of(context).api.auditEvents(clientId: widget.clientId, cursor: _cursor);
      setState(() {
        _items = [...?_items, ...p.items];
        _cursor = p.nextCursor;
      });
    } finally {
      if (mounted) setState(() => _more = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_error != null) {
      if (_error!.isForbidden) return const ForbiddenStateView();
      return ErrorStateView(message: _error!.message, requestId: _error!.requestId, offline: _error!.isNetwork, onRetry: _load);
    }
    final items = _items;
    if (items == null) return const LoadingState();
    if (items.isEmpty) return EmptyState(title: 'Nenhum movimento', message: widget.emptyMessage);
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView.separated(
        padding: const EdgeInsets.all(16),
        itemCount: items.length + (_cursor != null ? 1 : 0),
        separatorBuilder: (_, _) => const Divider(height: 16),
        itemBuilder: (context, i) {
          if (i == items.length) {
            return OutlinedButton(onPressed: _more ? null : _loadMore, child: Text(_more ? 'Carregando…' : 'Carregar mais'));
          }
          final e = items[i];
          final denied = e.result != 'SUCCESS';
          return Semantics(
            container: true,
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Padding(
                padding: const EdgeInsets.only(top: 2, right: 12),
                child: EngIcon(denied ? 'lock' : 'history', size: 20, color: denied ? EngColors.error : EngColors.cyan),
              ),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text.rich(TextSpan(children: [
                    TextSpan(text: e.actorName ?? (e.actorKind == 'SERVICE' ? 'Serviço do sistema' : 'Não identificado'),
                        style: const TextStyle(fontWeight: FontWeight.w600)),
                    TextSpan(text: ' ${labelOf(auditActionLabels, e.action)}'),
                    if (denied) TextSpan(text: e.result == 'DENIED' ? ' (negado)' : ' (falhou)', style: const TextStyle(color: EngColors.error)),
                  ])),
                  const SizedBox(height: 2),
                  Text([
                    formatDateTime(e.occurredAt),
                    if (e.clientId != null) labelOf(clientLabels, e.clientId!),
                    if (e.reason != null) 'Motivo: ${e.reason}',
                  ].join(' · '), style: const TextStyle(color: EngColors.muted, fontSize: 13)),
                ]),
              ),
            ]),
          );
        },
      ),
    );
  }
}

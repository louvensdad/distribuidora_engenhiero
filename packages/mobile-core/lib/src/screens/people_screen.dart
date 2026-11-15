import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:material_ui/material_ui.dart';
import '../labels.dart';
import '../scope.dart';

class PeopleScreen extends StatefulWidget {
  const PeopleScreen({super.key});
  @override
  State<PeopleScreen> createState() => _PeopleScreenState();
}

class _PeopleScreenState extends State<PeopleScreen> {
  Future<ApiPage<UserSummary>>? _future;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _future ??= EngScope.of(context).api.users();
  }

  @override
  Widget build(BuildContext context) => FutureBuilder<ApiPage<UserSummary>>(
        future: _future,
        builder: (context, snap) {
          if (snap.hasError) {
            final e = snap.error;
            if (e is ApiException && e.isForbidden) return const ForbiddenStateView();
            return ErrorStateView(
              message: e is ApiException ? e.message : 'Erro inesperado',
              onRetry: () => setState(() => _future = EngScope.of(context).api.users()),
            );
          }
          if (!snap.hasData) return const LoadingState();
          final people = snap.data!.items;
          if (people.isEmpty) return const EmptyState(title: 'Ninguém vinculado ainda');
          return ListView.separated(
            padding: const EdgeInsets.all(16),
            itemCount: people.length,
            separatorBuilder: (_, _) => const SizedBox(height: 8),
            itemBuilder: (_, i) {
              final u = people[i];
              final active = u.status == 'ACTIVE';
              return Card(
                child: ListTile(
                  minVerticalPadding: 12,
                  title: Text(u.displayName),
                  subtitle: Text('${labelOf(templateLabels, u.roleTemplate)} · ${labelOf(sectorLabels, u.sector)} · ${u.branch}'),
                  trailing: Text(active ? 'Ativa' : 'Bloqueada',
                      style: TextStyle(color: active ? EngColors.green : EngColors.error, fontWeight: FontWeight.w600)),
                ),
              );
            },
          );
        },
      );
}

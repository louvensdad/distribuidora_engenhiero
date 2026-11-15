import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_auth/engenheiro_auth.dart';
import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:material_ui/material_ui.dart';
import 'app_config.dart';
import 'labels.dart';
import 'scope.dart';
import 'session_controller.dart';

/// App completo a partir da configuração. [session] pode ser injetada em testes.
class EngenheiroApp extends StatefulWidget {
  const EngenheiroApp({super.key, required this.config, this.session});
  final AppConfig config;
  final SessionController? session;

  @override
  State<EngenheiroApp> createState() => _EngenheiroAppState();
}

class _EngenheiroAppState extends State<EngenheiroApp> {
  late final SessionController session = widget.session ??
      SessionController(
        auth: AuthSession(
          authenticator: AppAuthAuthenticator(AuthConfig(
            issuer: widget.config.issuer,
            clientId: widget.config.clientId,
            redirectScheme: widget.config.applicationId,
            allowInsecureConnections: widget.config.allowInsecure,
          )),
          store: OsSecureStore(),
        ),
        apiFactory: (tokens) => ApiClient(baseUrl: widget.config.apiBaseUrl, tokenProvider: tokens),
      );

  @override
  void initState() {
    super.initState();
    if (widget.config.configurationError == null) session.start();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: widget.config.appName,
      debugShowCheckedModeBanner: false,
      theme: engTheme(),
      home: EngScope(
        config: widget.config,
        session: session,
        child: ListenableBuilder(
          listenable: session,
          builder: (context, _) {
            final err = widget.config.configurationError;
            if (err != null) return Scaffold(body: ErrorStateView(message: err));
            return switch (session.state) {
              SessionStarting() => const Scaffold(body: LoadingState(label: 'Verificando sessão')),
              SessionSignedOut(:final message) => EntryScreen(message: message),
              SessionSigningIn() => const EntryScreen(busy: true),
              SessionNoAccess(:final requestId) => NoAccessScreen(requestId: requestId),
              SessionFailed(:final error) => Scaffold(
                  body: ErrorStateView(message: error.message, requestId: error.requestId, offline: error.isNetwork, onRetry: session.reload)),
              SessionReady() => const AppShell(),
            };
          },
        ),
      ),
    );
  }
}

/// Entrada segura: conta individual, senha e segundo fator no provedor de identidade (navegador do sistema).
class EntryScreen extends StatelessWidget {
  const EntryScreen({super.key, this.message, this.busy = false});
  final String? message;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    final s = EngScope.of(context);
    final t = Theme.of(context).textTheme;
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 420),
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                const EngBrand(height: 64),
                const SizedBox(height: 24),
                Text(s.config.appName, style: t.headlineSmall, textAlign: TextAlign.center),
                const SizedBox(height: 4),
                Text(engBrandLegalName, style: t.bodyMedium?.copyWith(color: EngColors.muted), textAlign: TextAlign.center),
                const SizedBox(height: 4),
                Text(s.config.audienceDescription, style: t.bodySmall?.copyWith(color: EngColors.muted), textAlign: TextAlign.center),
                const SizedBox(height: 32),
                if (message != null) ...[
                  Semantics(liveRegion: true, child: Text(message!, textAlign: TextAlign.center, style: const TextStyle(color: EngColors.warning))),
                  const SizedBox(height: 16),
                ],
                FilledButton.icon(
                  onPressed: busy ? null : s.session.signIn,
                  icon: busy ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)) : const EngIcon('shield', size: 20, color: EngColors.background),
                  label: Text(busy ? 'Abrindo login…' : 'Entrar com segurança'),
                ),
                const SizedBox(height: 16),
                Text('Use a sua conta individual. A senha e o segundo fator são confirmados pelo provedor de identidade da empresa.',
                    style: t.bodySmall?.copyWith(color: EngColors.muted), textAlign: TextAlign.center),
              ]),
            ),
          ),
        ),
      ),
    );
  }
}

class NoAccessScreen extends StatelessWidget {
  const NoAccessScreen({super.key, this.requestId});
  final String? requestId;

  @override
  Widget build(BuildContext context) {
    final s = EngScope.of(context);
    return Scaffold(
      body: SafeArea(
        child: ForbiddenStateView(
          message: 'Esta conta não tem acesso ao ${s.config.appName}. Use o aplicativo do seu setor ou peça liberação ao administrador.'
              '${requestId == null ? '' : '\nCódigo: $requestId'}',
          action: OutlinedButton(onPressed: s.session.signOut, child: const Text('Sair desta conta')),
        ),
      ),
    );
  }
}

/// Navegação principal (máx. 5 destinos) filtrada por capacidade. Tablet usa trilho lateral.
class AppShell extends StatefulWidget {
  const AppShell({super.key});
  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> {
  int _index = 0;

  @override
  Widget build(BuildContext context) {
    final s = EngScope.of(context);
    final visible = s.config.destinations.where((d) => d.allowedFor(s.me.can)).toList();
    if (visible.isEmpty) {
      return Scaffold(
        body: SafeArea(
          child: ForbiddenStateView(
            message: 'Sua conta entrou, mas ainda não tem nenhuma função liberada neste aplicativo.',
            action: OutlinedButton(onPressed: s.session.signOut, child: const Text('Sair')),
          ),
        ),
      );
    }
    final index = _index.clamp(0, visible.length - 1);
    final current = visible[index];
    final wide = MediaQuery.sizeOf(context).width >= 840;
    final body = KeyedSubtree(key: ValueKey(current.id), child: current.builder(context));

    final appBar = AppBar(
      title: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(current.label, style: const TextStyle(fontWeight: FontWeight.w700)),
        Text('${s.config.appName} · ${s.me.branchName}', style: const TextStyle(fontSize: 12, color: EngColors.muted)),
      ]),
      actions: [
        IconButton(
          tooltip: 'Minha conta',
          icon: const EngIcon('users', semanticLabel: 'Minha conta'),
          onPressed: () => Navigator.of(context).push(MaterialPageRoute<void>(
              builder: (_) => EngScope(config: s.config, session: s.session, child: const AccountScreen()))),
        ),
      ],
    );

    if (wide) {
      return Scaffold(
        appBar: appBar,
        body: Row(children: [
          NavigationRail(
            selectedIndex: index,
            labelType: NavigationRailLabelType.all,
            onDestinationSelected: (i) => setState(() => _index = i),
            destinations: [
              for (final d in visible)
                NavigationRailDestination(icon: EngIcon(d.icon), label: Text(d.label)),
            ],
          ),
          const VerticalDivider(width: 1),
          Expanded(child: body),
        ]),
      );
    }
    return Scaffold(
      appBar: appBar,
      body: body,
      bottomNavigationBar: visible.length < 2
          ? null
          : NavigationBar(
              selectedIndex: index,
              onDestinationSelected: (i) => setState(() => _index = i),
              destinations: [for (final d in visible) NavigationDestination(icon: EngIcon(d.icon), label: d.label)],
            ),
    );
  }
}

/// Conta: quem está conectado, em qual app/unidade, o que pode fazer. Sair encerra no app e no provedor.
class AccountScreen extends StatelessWidget {
  const AccountScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final s = EngScope.of(context);
    final me = s.me;
    final t = Theme.of(context).textTheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Minha conta')),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(me.displayName, style: t.titleLarge),
              const SizedBox(height: 4),
              Text('${labelOf(templateLabels, me.roleTemplate)} · ${labelOf(sectorLabels, me.sector)}', style: const TextStyle(color: EngColors.muted)),
              Text('${me.organizationName} · ${me.branchName}', style: const TextStyle(color: EngColors.muted)),
              Text(labelOf(clientLabels, me.clientId), style: const TextStyle(color: EngColors.muted)),
            ]),
          ),
        ),
        const SizedBox(height: 16),
        Text('O que você pode fazer neste app', style: t.titleMedium),
        const SizedBox(height: 8),
        Text(me.admin ? 'Acesso de administrador.' : '${me.capabilities.length} permissões liberadas pelo administrador.',
            style: const TextStyle(color: EngColors.muted)),
        const SizedBox(height: 24),
        OutlinedButton.icon(
          onPressed: () async {
            Navigator.of(context).pop();
            await s.session.signOut();
          },
          icon: const EngIcon('close', size: 18),
          label: const Text('Sair'),
        ),
      ]),
    );
  }
}

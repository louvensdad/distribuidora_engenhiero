import 'package:engenheiro_api/engenheiro_api.dart';
import 'package:engenheiro_design/engenheiro_design.dart';
import 'package:material_ui/material_ui.dart';
import '../scope.dart';

/// Clientes no escopo do usuário. Campos não liberados chegam nulos do servidor e aparecem como "oculto".
class CustomersScreen extends StatefulWidget {
  const CustomersScreen({super.key});
  @override
  State<CustomersScreen> createState() => _CustomersScreenState();
}

class _CustomersScreenState extends State<CustomersScreen> {
  final _search = TextEditingController();
  List<Customer>? _items;
  String? _cursor;
  ApiException? _error;
  bool _loadingMore = false;

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
      final p = await EngScope.of(context).api.customers(query: _search.text.trim());
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

  Future<void> _more() async {
    setState(() => _loadingMore = true);
    try {
      final p = await EngScope.of(context).api.customers(query: _search.text.trim(), cursor: _cursor);
      setState(() {
        _items = [...?_items, ...p.items];
        _cursor = p.nextCursor;
      });
    } on ApiException catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.message)));
    } finally {
      if (mounted) setState(() => _loadingMore = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = EngScope.of(context);
    final canCreate = s.me.can('customers.create');
    return Scaffold(
      floatingActionButton: canCreate
          ? FloatingActionButton.extended(
              onPressed: () async {
                final created = await Navigator.of(context).push<bool>(MaterialPageRoute(
                    builder: (_) => EngScope(config: s.config, session: s.session, child: const NewCustomerScreen())));
                if (created == true) _load();
              },
              icon: const EngIcon('plus', color: EngColors.background),
              label: const Text('Novo cliente'),
            )
          : null,
      body: Column(children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
          child: TextField(
            controller: _search,
            textInputAction: TextInputAction.search,
            onSubmitted: (_) => _load(),
            decoration: const InputDecoration(labelText: 'Buscar cliente por nome', prefixIcon: Padding(padding: EdgeInsets.all(12), child: EngIcon('search', size: 20))),
          ),
        ),
        Expanded(child: _body()),
      ]),
    );
  }

  Widget _body() {
    if (_error != null) {
      if (_error!.isForbidden) return const ForbiddenStateView();
      return ErrorStateView(message: _error!.message, requestId: _error!.requestId, offline: _error!.isNetwork, onRetry: _load);
    }
    final items = _items;
    if (items == null) return const LoadingState();
    if (items.isEmpty) {
      return EmptyState(title: _search.text.isEmpty ? 'Nenhum cliente cadastrado' : 'Nenhum cliente encontrado');
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView.separated(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 96),
        itemCount: items.length + (_cursor != null ? 1 : 0),
        separatorBuilder: (_, _) => const SizedBox(height: 8),
        itemBuilder: (context, i) {
          if (i == items.length) {
            return OutlinedButton(onPressed: _loadingMore ? null : _more, child: Text(_loadingMore ? 'Carregando…' : 'Carregar mais'));
          }
          final c = items[i];
          return Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(c.name, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 16)),
                const SizedBox(height: 6),
                _Field('Contato', c.contact, c.isMasked('contact')),
                _Field('Endereço', c.address, c.isMasked('address')),
                _Field('CPF/CNPJ', c.personalDocument, c.isMasked('personal_document')),
                _Field('Unidade', c.branch, false),
              ]),
            ),
          );
        },
      ),
    );
  }
}

class _Field extends StatelessWidget {
  const _Field(this.label, this.value, this.masked);
  final String label;
  final String? value;
  final bool masked;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(top: 2),
        child: Row(children: [
          SizedBox(width: 90, child: Text(label, style: const TextStyle(color: EngColors.muted, fontSize: 13))),
          Expanded(
            child: masked
                ? const Row(children: [EngIcon('lock', size: 14, color: EngColors.muted), SizedBox(width: 4),
                    Text('oculto', style: TextStyle(color: EngColors.muted, fontStyle: FontStyle.italic))])
                : Text(value ?? '—'),
          ),
        ]),
      );
}

class NewCustomerScreen extends StatefulWidget {
  const NewCustomerScreen({super.key});
  @override
  State<NewCustomerScreen> createState() => _NewCustomerScreenState();
}

class _NewCustomerScreenState extends State<NewCustomerScreen> {
  final _name = TextEditingController();
  final _contact = TextEditingController();
  final _address = TextEditingController();
  final _doc = TextEditingController();
  bool _busy = false;
  ApiException? _error;

  String? _fieldError(String f) => _error?.fieldErrors.where((e) => e.field == f).map((e) => e.message).firstOrNull;

  Future<void> _save() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await EngScope.of(context).api.createCustomer(
          name: _name.text.trim(), contact: _contact.text, address: _address.text, personalDocument: _doc.text);
      if (mounted) Navigator.of(context).pop(true);
    } on ApiException catch (e) {
      if (mounted) {
        setState(() {
          _error = e;
          _busy = false; // dados digitados preservados
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final me = EngScope.of(context).me;
    return Scaffold(
      appBar: AppBar(title: const Text('Novo cliente')),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        TextField(controller: _name, autofocus: true, maxLength: 200, onChanged: (_) => setState(() {}),
            decoration: InputDecoration(labelText: 'Nome *', errorText: _fieldError('name'))),
        if (me.canSeeField('operational_contact')) ...[
          const SizedBox(height: 12),
          TextField(controller: _contact, keyboardType: TextInputType.phone,
              decoration: InputDecoration(labelText: 'Contato', errorText: _fieldError('contact'))),
        ],
        if (me.canSeeField('delivery_address')) ...[
          const SizedBox(height: 12),
          TextField(controller: _address, decoration: InputDecoration(labelText: 'Endereço de entrega', errorText: _fieldError('address'))),
        ],
        if (me.canSeeField('personal_document')) ...[
          const SizedBox(height: 12),
          TextField(controller: _doc, keyboardType: TextInputType.number,
              decoration: InputDecoration(labelText: 'CPF ou CNPJ', errorText: _fieldError('personal_document'))),
        ],
        if (_error != null && _error!.fieldErrors.isEmpty) ...[
          const SizedBox(height: 12),
          Semantics(liveRegion: true, child: Text(_error!.message, style: const TextStyle(color: EngColors.error))),
        ],
        const SizedBox(height: 24),
        FilledButton(
          onPressed: _busy || _name.text.trim().isEmpty ? null : _save,
          child: Text(_busy ? 'Salvando…' : 'Cadastrar cliente'),
        ),
      ]),
    );
  }
}

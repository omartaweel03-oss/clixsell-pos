import 'package:flutter/material.dart';

import '../../l10n/app_strings.dart';
import '../../l10n/purchase_strings.dart';
import '../../models/supplier.dart';
import '../../services/api_http.dart';
import '../../services/supplier_api_client.dart';
import '../../theme/app_theme.dart';
import '../../widgets/min_width_scroll.dart';
import '../../widgets/page_header.dart';

enum _Filter { all, active, inactive }

/// Supplier Management (Purchase Orders, Phase 1) — Store Admin only.
///
/// Lists the suppliers of the admin's own store (active and inactive) with
/// their WhatsApp number and number of products; adds and edits suppliers and
/// deactivates / activates them. Suppliers are never deleted: their products
/// keep pointing to them. Phase 1 has no purchase orders and sends nothing.
class SuppliersPage extends StatefulWidget {
  const SuppliersPage({super.key, required this.api});

  final SupplierApi api;

  @override
  State<SuppliersPage> createState() => _SuppliersPageState();
}

class _SuppliersPageState extends State<SuppliersPage> {
  List<Supplier> _suppliers = const [];
  bool _loading = true;
  ApiException? _error;
  _Filter _filter = _Filter.all;
  final _search = TextEditingController();
  final Set<int> _busy = {};

  @override
  void initState() {
    super.initState();
    _search.addListener(() => setState(() {}));
    _load();
  }

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final list = await widget.api.list();
      if (!mounted) return;
      setState(() {
        _suppliers = list;
        _loading = false;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e;
        _loading = false;
      });
    }
  }

  void _toast(String message, {bool error = false}) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Text(message),
          backgroundColor: error ? AppColors.danger : null,
        ),
      );
  }

  String _describe(ApiException e, AppStrings s) => e.isNetworkError
      ? s.networkError
      : e.isForbidden
      ? s.notAllowed
      : s.requestFailed(e.message);

  void _replace(Supplier updated) => setState(
    () => _suppliers = [
      for (final x in _suppliers)
        x.supplierId == updated.supplierId ? updated : x,
    ],
  );

  Future<void> _add() async {
    final p = PurchaseStrings.of(context);
    final created = await showDialog<Supplier>(
      context: context,
      builder: (_) => SupplierFormDialog(api: widget.api),
    );
    if (created == null || !mounted) return;
    setState(
      () => _suppliers = [..._suppliers, created]
        ..sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase())),
    );
    _toast(p.supplierAdded(created.name));
  }

  Future<void> _edit(Supplier supplier) async {
    final p = PurchaseStrings.of(context);
    final saved = await showDialog<Supplier>(
      context: context,
      builder: (_) => SupplierFormDialog(api: widget.api, supplier: supplier),
    );
    if (saved == null || !mounted) return;
    _replace(saved);
    _toast(p.supplierSaved(saved.name));
  }

  Future<void> _details(Supplier supplier) async {
    await showDialog<void>(
      context: context,
      builder: (_) =>
          _SupplierDetailsDialog(api: widget.api, supplier: supplier),
    );
  }

  Future<void> _toggle(Supplier supplier) async {
    final s = AppStrings.of(context);
    final p = PurchaseStrings.of(context);
    if (supplier.active) {
      final ok = await confirmAction(
        context,
        title: p.deactivateTitle,
        body: p.deactivateBody(supplier.name, supplier.productCount),
        confirmLabel: s.deactivate,
        cancelLabel: s.cancel,
        icon: Icons.pause_circle_outline_rounded,
        color: AppColors.danger,
      );
      if (!ok || !mounted) return;
    }
    setState(() => _busy.add(supplier.supplierId));
    try {
      final updated = await widget.api.setActive(
        supplier.supplierId,
        !supplier.active,
      );
      if (!mounted) return;
      // The status answer includes the product list; keep the list's count.
      _replace(
        Supplier(
          supplierId: updated.supplierId,
          name: updated.name,
          phone: updated.phone,
          whatsappNumber: updated.whatsappNumber,
          address: updated.address,
          active: updated.active,
          productCount: updated.productCount,
        ),
      );
      _toast(
        updated.active
            ? p.supplierActivated(updated.name)
            : p.supplierDeactivated(updated.name),
      );
    } on ApiException catch (e) {
      if (mounted) _toast(_describe(e, s), error: true);
    } finally {
      if (mounted) setState(() => _busy.remove(supplier.supplierId));
    }
  }

  List<Supplier> get _visible {
    final q = _search.text.trim().toLowerCase();
    final digits = q.replaceAll(RegExp(r'[^0-9]'), '');
    return _suppliers.where((x) {
      if (_filter == _Filter.active && !x.active) return false;
      if (_filter == _Filter.inactive && x.active) return false;
      if (q.isEmpty) return true;
      return x.name.toLowerCase().contains(q) ||
          (digits.isNotEmpty &&
              ((x.whatsappNumber ?? '').contains(digits) ||
                  (x.phone ?? '')
                      .replaceAll(RegExp(r'[^0-9]'), '')
                      .contains(digits)));
    }).toList();
  }

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final p = PurchaseStrings.of(context);
    return LayoutBuilder(
      builder: (context, constraints) {
        final horizontal = constraints.maxWidth < 600
            ? AppSpacing.lg
            : AppSpacing.xxl;
        Widget body;
        if (_loading) {
          body = const Padding(
            padding: EdgeInsets.symmetric(vertical: 56),
            child: Center(child: CircularProgressIndicator()),
          );
        } else if (_error != null) {
          body = MessageState(
            icon: Icons.cloud_off_rounded,
            title: s.loadFailedTitle,
            body: _describe(_error!, s),
            actionLabel: s.retry,
            actionIcon: Icons.refresh_rounded,
            onAction: _load,
          );
        } else if (_suppliers.isEmpty) {
          body = MessageState(
            icon: Icons.local_shipping_outlined,
            title: p.noSuppliersTitle,
            body: p.noSuppliersBody,
            actionLabel: p.addSupplier,
            onAction: _add,
          );
        } else {
          final visible = _visible;
          body = Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _Toolbar(
                search: _search,
                filter: _filter,
                onFilter: (f) => setState(() => _filter = f),
              ),
              const SizedBox(height: AppSpacing.lg),
              if (visible.isEmpty)
                MessageState(
                  icon: Icons.search_off_rounded,
                  title: p.noMatchTitle,
                  body: p.noMatchBody,
                )
              else
                _SuppliersTable(
                  suppliers: visible,
                  busy: _busy,
                  onEdit: _edit,
                  onDetails: _details,
                  onToggle: _toggle,
                ),
            ],
          );
        }

        return SingleChildScrollView(
          padding: EdgeInsets.fromLTRB(
            horizontal,
            AppSpacing.xl,
            horizontal,
            AppSpacing.xxl,
          ),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 1200),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  PageHeader(
                    title: p.suppliersTitle,
                    description: p.suppliersDescription,
                    action: FilledButton.icon(
                      key: const Key('addSupplierButton'),
                      onPressed: _loading ? null : _add,
                      icon: const Icon(Icons.add_business_rounded, size: 20),
                      label: Text(p.addSupplier),
                    ),
                  ),
                  const SizedBox(height: AppSpacing.xl),
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(AppSpacing.lg + 4),
                      child: body,
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

class _Toolbar extends StatelessWidget {
  const _Toolbar({
    required this.search,
    required this.filter,
    required this.onFilter,
  });

  final TextEditingController search;
  final _Filter filter;
  final ValueChanged<_Filter> onFilter;

  @override
  Widget build(BuildContext context) {
    final p = PurchaseStrings.of(context);
    return Wrap(
      spacing: AppSpacing.lg,
      runSpacing: AppSpacing.md,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        SizedBox(
          width: 360,
          // labelText (not only a hint) so the field keeps its accessible
          // name while text is typed (lesson from accessibility finding A2).
          child: TextField(
            key: const Key('supplierSearch'),
            controller: search,
            decoration: InputDecoration(
              labelText: p.searchSuppliers,
              prefixIcon: const Icon(Icons.search_rounded),
              isDense: true,
            ),
          ),
        ),
        SegmentedButton<_Filter>(
          key: const Key('supplierFilter'),
          showSelectedIcon: false,
          segments: [
            ButtonSegment(value: _Filter.all, label: Text(p.filterAll)),
            ButtonSegment(value: _Filter.active, label: Text(p.filterActive)),
            ButtonSegment(
              value: _Filter.inactive,
              label: Text(p.filterInactive),
            ),
          ],
          selected: {filter},
          onSelectionChanged: (v) => onFilter(v.first),
        ),
      ],
    );
  }
}

/// A WhatsApp number shown left-to-right in both languages.
class _Whatsapp extends StatelessWidget {
  const _Whatsapp(this.digits);

  final String? digits;

  @override
  Widget build(BuildContext context) {
    final p = PurchaseStrings.of(context);
    if ((digits ?? '').isEmpty) {
      return Text(
        p.noWhatsapp,
        style: const TextStyle(color: AppColors.warning, fontSize: 12.5),
      );
    }
    return Directionality(
      textDirection: TextDirection.ltr,
      child: Text(
        formatWhatsapp(digits!),
        style: const TextStyle(fontWeight: FontWeight.w600),
      ),
    );
  }
}

class _SuppliersTable extends StatelessWidget {
  const _SuppliersTable({
    required this.suppliers,
    required this.busy,
    required this.onEdit,
    required this.onDetails,
    required this.onToggle,
  });

  final List<Supplier> suppliers;
  final Set<int> busy;
  final ValueChanged<Supplier> onEdit;
  final ValueChanged<Supplier> onDetails;
  final ValueChanged<Supplier> onToggle;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final p = PurchaseStrings.of(context);
    const header = TextStyle(
      fontSize: 12.5,
      fontWeight: FontWeight.w700,
      color: AppColors.textSecondary,
    );
    final small = OutlinedButton.styleFrom(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
    );

    Widget actions(Supplier x) => Wrap(
      spacing: 6,
      runSpacing: 6,
      children: [
        Tooltip(
          message: p.detailsTooltip(x.name),
          child: OutlinedButton.icon(
            key: Key('supplier-details-${x.supplierId}'),
            onPressed: () => onDetails(x),
            style: small,
            icon: const Icon(Icons.visibility_outlined, size: 16),
            label: Text(p.details),
          ),
        ),
        Tooltip(
          message: p.editTooltip(x.name),
          child: OutlinedButton.icon(
            key: Key('supplier-edit-${x.supplierId}'),
            onPressed: () => onEdit(x),
            style: small,
            icon: const Icon(Icons.edit_outlined, size: 16),
            label: Text(p.edit),
          ),
        ),
        OutlinedButton.icon(
          key: Key('supplier-toggle-${x.supplierId}'),
          onPressed: busy.contains(x.supplierId) ? null : () => onToggle(x),
          style: small.copyWith(
            foregroundColor: WidgetStatePropertyAll(
              x.active ? AppColors.danger : AppColors.success,
            ),
          ),
          icon: Icon(
            x.active
                ? Icons.pause_circle_outline_rounded
                : Icons.play_circle_outline_rounded,
            size: 16,
          ),
          label: Text(x.active ? s.deactivate : s.activate),
        ),
      ],
    );

    return MinWidthScroll(
      minWidth: 1040,
      child: Column(
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 14),
            decoration: BoxDecoration(
              color: AppColors.background,
              borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
            ),
            child: Row(
              children: [
                Expanded(flex: 4, child: Text(p.colSupplier, style: header)),
                Expanded(flex: 3, child: Text(p.colWhatsapp, style: header)),
                Expanded(flex: 2, child: Text(p.colPhone, style: header)),
                Expanded(flex: 2, child: Text(p.colProducts, style: header)),
                Expanded(flex: 3, child: Text(s.colStatus, style: header)),
                Expanded(flex: 6, child: Text(s.colActions, style: header)),
              ],
            ),
          ),
          for (final x in suppliers)
            Container(
              key: ValueKey('supplier-${x.supplierId}'),
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
              decoration: const BoxDecoration(
                border: Border(bottom: BorderSide(color: AppColors.divider)),
              ),
              child: Row(
                children: [
                  Expanded(
                    flex: 4,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          x.name,
                          style: const TextStyle(fontWeight: FontWeight.w700),
                          overflow: TextOverflow.ellipsis,
                        ),
                        if ((x.address ?? '').isNotEmpty)
                          Text(
                            x.address!,
                            style: const TextStyle(
                              color: AppColors.textSecondary,
                              fontSize: 12,
                            ),
                            overflow: TextOverflow.ellipsis,
                          ),
                      ],
                    ),
                  ),
                  Expanded(flex: 3, child: _Whatsapp(x.whatsappNumber)),
                  Expanded(
                    flex: 2,
                    child: Directionality(
                      textDirection: TextDirection.ltr,
                      child: Text(
                        x.phone ?? p.none,
                        textAlign:
                            Directionality.of(context) == TextDirection.rtl
                            ? TextAlign.right
                            : TextAlign.left,
                        style: const TextStyle(color: AppColors.textSecondary),
                      ),
                    ),
                  ),
                  Expanded(
                    flex: 2,
                    child: Text(
                      p.productsCount(x.productCount),
                      style: const TextStyle(color: AppColors.textSecondary),
                    ),
                  ),
                  Expanded(
                    flex: 3,
                    child: Align(
                      alignment: AlignmentDirectional.centerStart,
                      child: ActiveStatusPill(
                        active: x.active,
                        activeLabel: s.statusActive,
                        inactiveLabel: s.statusInactive,
                      ),
                    ),
                  ),
                  Expanded(flex: 6, child: actions(x)),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

// ── Dialogs ──────────────────────────────────────────────────────────────────

/// Client-side checks, the same rules as the backend (which checks again).
class SupplierRules {
  static String? name(String? v, PurchaseStrings p) {
    final n = (v ?? '').trim().replaceAll(RegExp(r'\s+'), ' ');
    if (n.isEmpty) return p.vNameRequired;
    if (n.length < 2) return p.vNameShort;
    if (n.length > 80) return p.vNameLong;
    return null;
  }

  static String? whatsapp(String? v, PurchaseStrings p) {
    var d = (v ?? '').trim().replaceAll(RegExp(r'[\s\-.() ]'), '');
    if (d.isEmpty) return null;
    if (d.startsWith('+')) {
      d = d.substring(1);
    } else if (d.startsWith('00')) {
      d = d.substring(2);
    }
    if (!RegExp(r'^[1-9][0-9]{7,14}$').hasMatch(d)) return p.vWhatsapp;
    return null;
  }

  static String? phone(String? v, PurchaseStrings p) {
    final t = (v ?? '').trim();
    if (t.isEmpty) return null;
    final digits = t.replaceAll(RegExp(r'[^0-9]'), '').length;
    if (t.length > 30 ||
        !RegExp(r'^[0-9+\-(). ]+$').hasMatch(t) ||
        digits < 6) {
      return p.vPhone;
    }
    return null;
  }

  static String? address(String? v, PurchaseStrings p) =>
      (v ?? '').trim().length > 200 ? p.vAddressLong : null;
}

/// Add (no [supplier]) or edit a supplier.
class SupplierFormDialog extends StatefulWidget {
  const SupplierFormDialog({super.key, required this.api, this.supplier});

  final SupplierApi api;
  final Supplier? supplier;

  @override
  State<SupplierFormDialog> createState() => _SupplierFormDialogState();
}

class _SupplierFormDialogState extends State<SupplierFormDialog> {
  final _formKey = GlobalKey<FormState>();
  late final _name = TextEditingController(text: widget.supplier?.name);
  late final _whatsapp = TextEditingController(
    text: widget.supplier?.whatsappNumber,
  );
  late final _phone = TextEditingController(text: widget.supplier?.phone);
  late final _address = TextEditingController(text: widget.supplier?.address);
  final Map<String, String> _serverErrors = {};
  bool _saving = false;
  String? _generalError;

  @override
  void dispose() {
    _name.dispose();
    _whatsapp.dispose();
    _phone.dispose();
    _address.dispose();
    super.dispose();
  }

  String? _opt(TextEditingController c) =>
      c.text.trim().isEmpty ? null : c.text.trim();

  Future<void> _save() async {
    final s = AppStrings.of(context);
    final p = PurchaseStrings.of(context);
    _serverErrors.clear();
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _saving = true;
      _generalError = null;
    });
    try {
      final existing = widget.supplier;
      final saved = existing == null
          ? await widget.api.create(
              name: _name.text,
              phone: _opt(_phone),
              whatsappNumber: _opt(_whatsapp),
              address: _opt(_address),
            )
          : await widget.api.update(
              existing.supplierId,
              name: _name.text,
              phone: _opt(_phone),
              whatsappNumber: _opt(_whatsapp),
              address: _opt(_address),
            );
      if (mounted) Navigator.of(context).pop(saved);
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        if (e.code == 'DUPLICATE_SUPPLIER') {
          final m = RegExp("'(.*)'").firstMatch(e.message);
          _serverErrors['name'] = p.duplicateName(m?.group(1) ?? _name.text);
        } else if (e.statusCode == 400) {
          // Field rules are the same on both sides; show ours in the UI language.
          for (final k in e.fieldErrors.keys) {
            _serverErrors[k] = switch (k) {
              'name' => p.vNameRequired,
              'whatsappNumber' => p.vWhatsapp,
              'phone' => p.vPhone,
              'address' => p.vAddressLong,
              _ => e.fieldErrors[k]!,
            };
          }
        }
        if (_serverErrors.isEmpty) {
          _generalError = e.isNetworkError
              ? s.networkError
              : e.isForbidden
              ? s.notAllowed
              : s.requestFailed(e.message);
        }
      });
      _formKey.currentState!.validate();
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final p = PurchaseStrings.of(context);
    final editing = widget.supplier != null;
    return AlertDialog(
      title: Text(editing ? p.editSupplier : p.addSupplier),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 460, minWidth: 340),
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (_generalError != null) ...[
                  Text(
                    _generalError!,
                    key: const Key('supplierFormError'),
                    style: const TextStyle(color: AppColors.danger),
                  ),
                  const SizedBox(height: AppSpacing.md),
                ],
                TextFormField(
                  key: const Key('supplierName'),
                  controller: _name,
                  autofocus: true,
                  decoration: InputDecoration(
                    labelText: '${p.nameLabel} *',
                    prefixIcon: const Icon(Icons.storefront_outlined),
                  ),
                  validator: (v) =>
                      _serverErrors['name'] ?? SupplierRules.name(v, p),
                ),
                const SizedBox(height: AppSpacing.lg),
                TextFormField(
                  key: const Key('supplierWhatsapp'),
                  controller: _whatsapp,
                  keyboardType: TextInputType.phone,
                  textDirection: TextDirection.ltr,
                  decoration: InputDecoration(
                    labelText: p.whatsappLabel,
                    helperText: p.whatsappHelper,
                    helperMaxLines: 2,
                    prefixIcon: const Icon(Icons.chat_outlined),
                  ),
                  validator: (v) =>
                      _serverErrors['whatsappNumber'] ??
                      SupplierRules.whatsapp(v, p),
                ),
                const SizedBox(height: AppSpacing.lg),
                TextFormField(
                  key: const Key('supplierPhone'),
                  controller: _phone,
                  keyboardType: TextInputType.phone,
                  textDirection: TextDirection.ltr,
                  decoration: InputDecoration(
                    labelText: p.phoneLabel,
                    prefixIcon: const Icon(Icons.phone_outlined),
                  ),
                  validator: (v) =>
                      _serverErrors['phone'] ?? SupplierRules.phone(v, p),
                ),
                const SizedBox(height: AppSpacing.lg),
                TextFormField(
                  key: const Key('supplierAddress'),
                  controller: _address,
                  onFieldSubmitted: (_) => _save(),
                  decoration: InputDecoration(
                    labelText: p.addressLabel,
                    prefixIcon: const Icon(Icons.place_outlined),
                  ),
                  validator: (v) =>
                      _serverErrors['address'] ?? SupplierRules.address(v, p),
                ),
              ],
            ),
          ),
        ),
      ),
      actions: [
        OutlinedButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(),
          child: Text(s.cancel),
        ),
        FilledButton(
          key: const Key('supplierSaveButton'),
          onPressed: _saving ? null : _save,
          child: Text(editing ? p.save : s.create),
        ),
      ],
    );
  }
}

class _SupplierDetailsDialog extends StatefulWidget {
  const _SupplierDetailsDialog({required this.api, required this.supplier});

  final SupplierApi api;
  final Supplier supplier;

  @override
  State<_SupplierDetailsDialog> createState() => _SupplierDetailsDialogState();
}

class _SupplierDetailsDialogState extends State<_SupplierDetailsDialog> {
  Supplier? _full;
  ApiException? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _error = null);
    try {
      final full = await widget.api.get(widget.supplier.supplierId);
      if (mounted) setState(() => _full = full);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final p = PurchaseStrings.of(context);
    final x = _full ?? widget.supplier;
    Widget row(IconData icon, Widget value) => Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Icon(icon, size: 18, color: AppColors.textSecondary),
          const SizedBox(width: AppSpacing.sm),
          Expanded(child: value),
        ],
      ),
    );

    Widget products;
    if (_error != null) {
      products = MessageState(
        icon: Icons.cloud_off_rounded,
        title: s.loadFailedTitle,
        body: _error!.isNetworkError ? s.networkError : _error!.message,
        actionLabel: s.retry,
        actionIcon: Icons.refresh_rounded,
        onAction: _load,
      );
    } else if (_full == null) {
      products = const Padding(
        padding: EdgeInsets.all(AppSpacing.lg),
        child: Center(child: CircularProgressIndicator()),
      );
    } else if (x.products.isEmpty) {
      products = Text(
        p.noProductsOfSupplier,
        style: const TextStyle(color: AppColors.textSecondary),
      );
    } else {
      products = Column(
        children: [
          for (final item in x.products)
            ListTile(
              key: ValueKey('supplier-product-${item.productId}'),
              dense: true,
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.inventory_2_outlined),
              title: Text(item.name),
              subtitle: Text(
                '${item.barcode} · ${p.stockLine(item.quantity, item.minStock)}',
              ),
            ),
        ],
      );
    }

    return AlertDialog(
      key: const Key('supplierDetailsDialog'),
      title: Row(
        children: [
          Expanded(child: Text(x.name)),
          ActiveStatusPill(
            active: x.active,
            activeLabel: s.statusActive,
            inactiveLabel: s.statusInactive,
          ),
        ],
      ),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 520, minWidth: 360),
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                p.contact,
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
              row(Icons.chat_outlined, _Whatsapp(x.whatsappNumber)),
              row(
                Icons.phone_outlined,
                Text(x.phone ?? p.none, textDirection: TextDirection.ltr),
              ),
              row(Icons.place_outlined, Text(x.address ?? p.none)),
              const Divider(height: AppSpacing.xl),
              Text(
                p.productsOfSupplier,
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: AppSpacing.sm),
              products,
            ],
          ),
        ),
      ),
      actions: [
        FilledButton(
          key: const Key('supplierDetailsClose'),
          onPressed: () => Navigator.of(context).pop(),
          child: Text(p.close),
        ),
      ],
    );
  }
}

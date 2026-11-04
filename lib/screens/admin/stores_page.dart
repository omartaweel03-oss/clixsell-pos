import 'package:flutter/material.dart';

import '../../l10n/app_strings.dart';
import '../../models/store_info.dart';
import '../../services/admin_api_clients.dart';
import '../../services/api_http.dart';
import '../../theme/app_theme.dart';
import '../../widgets/page_header.dart';
import 'staff_accounts_page.dart';

/// System Admin: all stores. Create and edit stores, activate / deactivate
/// them, and open a store's Store Admins.
class StoresPage extends StatefulWidget {
  const StoresPage({super.key, required this.http});

  final ApiHttp http;

  @override
  State<StoresPage> createState() => _StoresPageState();
}

class _StoresPageState extends State<StoresPage> {
  late final StoresApiClient _api = StoresApiClient(widget.http);
  List<StoreInfo> _stores = const [];
  bool _loading = true;
  ApiException? _error;

  /// When set, this store's Store Admins are shown instead of the list.
  StoreInfo? _adminsOf;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final stores = await _api.listStores();
      if (!mounted) return;
      setState(() {
        _stores = stores;
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

  void _replace(StoreInfo updated) {
    setState(
      () => _stores = [
        for (final st in _stores) st.storeId == updated.storeId ? updated : st,
      ],
    );
  }

  Future<void> _addOrEdit([StoreInfo? existing]) async {
    final s = AppStrings.of(context);
    final saved = await showDialog<StoreInfo>(
      context: context,
      builder: (_) => _StoreDialog(api: _api, existing: existing),
    );
    if (saved == null || !mounted) return;
    if (existing == null) {
      setState(() => _stores = [..._stores, saved]);
      _toast(s.storeCreated(saved.name));
    } else {
      _replace(saved);
      _toast(s.storeUpdated(saved.name));
    }
  }

  Future<void> _toggle(StoreInfo store) async {
    final s = AppStrings.of(context);
    if (store.isActive) {
      final ok = await confirmAction(
        context,
        title: s.deactivateStoreTitle,
        body: s.deactivateStoreBody(store.name),
        confirmLabel: s.deactivate,
        cancelLabel: s.cancel,
        icon: Icons.store_mall_directory_outlined,
        color: AppColors.danger,
      );
      if (!ok || !mounted) return;
    }
    try {
      final updated = await _api.setStoreActive(store.storeId, !store.isActive);
      if (!mounted) return;
      _replace(updated);
      _toast(
        updated.isActive
            ? s.storeActivated(updated.name)
            : s.storeDeactivated(updated.name),
      );
    } on ApiException catch (e) {
      if (mounted) {
        _toast(
          e.isNetworkError ? s.networkError : s.requestFailed(e.message),
          error: true,
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final adminsOf = _adminsOf;
    if (adminsOf != null) {
      return StaffAccountsPage(
        key: ValueKey('admins-${adminsOf.storeId}'),
        api: storeAdminsApi(widget.http, adminsOf.storeId),
        title: s.storeAdminsTitle(adminsOf.name),
        description: s.storeAdminsDescription,
        addLabel: s.addStoreAdmin,
        onBack: () {
          setState(() => _adminsOf = null);
          _load(); // refresh the admin counts
        },
      );
    }

    Widget body;
    if (_loading) {
      body = const Padding(
        padding: EdgeInsets.symmetric(vertical: 56),
        child: Center(child: CircularProgressIndicator()),
      );
    } else if (_error != null) {
      body = Card(
        child: MessageState(
          icon: Icons.cloud_off_rounded,
          title: s.loadFailedTitle,
          body: _error!.isNetworkError
              ? s.networkError
              : s.requestFailed(_error!.message),
          actionLabel: s.retry,
          actionIcon: Icons.refresh_rounded,
          onAction: _load,
        ),
      );
    } else if (_stores.isEmpty) {
      body = Card(
        child: MessageState(
          icon: Icons.storefront_outlined,
          title: s.noStoresTitle,
          body: s.noStoresBody,
          actionLabel: s.addStore,
          onAction: _addOrEdit,
        ),
      );
    } else {
      body = LayoutBuilder(
        builder: (context, c) {
          final columns = c.maxWidth >= 1000 ? 3 : (c.maxWidth >= 640 ? 2 : 1);
          const gap = AppSpacing.lg;
          final width = (c.maxWidth - gap * (columns - 1)) / columns;
          return Wrap(
            spacing: gap,
            runSpacing: gap,
            children: [
              for (final st in _stores)
                SizedBox(
                  width: width,
                  child: _StoreCard(
                    store: st,
                    onEdit: () => _addOrEdit(st),
                    onToggle: () => _toggle(st),
                    onAdmins: () => setState(() => _adminsOf = st),
                  ),
                ),
            ],
          );
        },
      );
    }

    return LayoutBuilder(
      builder: (context, constraints) {
        final horizontal = constraints.maxWidth < 600
            ? AppSpacing.lg
            : AppSpacing.xxl;
        return SingleChildScrollView(
          padding: EdgeInsets.fromLTRB(
            horizontal,
            AppSpacing.xl,
            horizontal,
            AppSpacing.xxl,
          ),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 1400),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  PageHeader(
                    title: s.storesTitle,
                    description: s.storesDescription,
                    action: FilledButton.icon(
                      key: const Key('addStoreButton'),
                      onPressed: _addOrEdit,
                      icon: const Icon(Icons.add_business_rounded, size: 20),
                      label: Text(s.addStore),
                    ),
                  ),
                  const SizedBox(height: AppSpacing.xl),
                  body,
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

class _StoreCard extends StatelessWidget {
  const _StoreCard({
    required this.store,
    required this.onEdit,
    required this.onToggle,
    required this.onAdmins,
  });

  final StoreInfo store;
  final VoidCallback onEdit;
  final VoidCallback onToggle;
  final VoidCallback onAdmins;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    Widget detail(IconData icon, String text) => Row(
      children: [
        Icon(icon, size: 16, color: AppColors.textMuted),
        const SizedBox(width: 6),
        Expanded(
          child: Text(
            text,
            style: const TextStyle(color: AppColors.textSecondary),
            overflow: TextOverflow.ellipsis,
          ),
        ),
      ],
    );

    return Card(
      key: ValueKey('store-${store.storeId}'),
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.lg + 2),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Container(
                  width: 44,
                  height: 44,
                  decoration: BoxDecoration(
                    color: store.isActive
                        ? AppColors.primarySoft
                        : AppColors.background,
                    borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
                  ),
                  child: Icon(
                    Icons.storefront_rounded,
                    color: store.isActive
                        ? AppColors.primary
                        : AppColors.textMuted,
                  ),
                ),
                const SizedBox(width: AppSpacing.md),
                Expanded(
                  child: Text(
                    store.name,
                    style: const TextStyle(
                      fontSize: 16.5,
                      fontWeight: FontWeight.w800,
                    ),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                ActiveStatusPill(
                  active: store.isActive,
                  activeLabel: s.statusActive,
                  inactiveLabel: s.statusInactive,
                ),
              ],
            ),
            const SizedBox(height: AppSpacing.md),
            detail(Icons.place_outlined, store.address ?? '—'),
            const SizedBox(height: 4),
            detail(Icons.phone_outlined, store.phone ?? '—'),
            const SizedBox(height: 4),
            detail(
              Icons.percent_rounded,
              s.taxAndDiscount(store.taxRate, store.maxDiscountPercent),
            ),
            const SizedBox(height: 4),
            detail(
              Icons.groups_outlined,
              '${s.adminsCount(store.adminCount)} · ${s.cashiersCount(store.cashierCount)}',
            ),
            const SizedBox(height: AppSpacing.lg),
            Wrap(
              spacing: AppSpacing.sm,
              runSpacing: AppSpacing.sm,
              children: [
                FilledButton.tonalIcon(
                  key: Key('storeAdmins-${store.storeId}'),
                  onPressed: onAdmins,
                  icon: const Icon(
                    Icons.admin_panel_settings_outlined,
                    size: 18,
                  ),
                  label: Text(s.manageStoreAdmins),
                ),
                OutlinedButton.icon(
                  key: Key('editStore-${store.storeId}'),
                  onPressed: onEdit,
                  icon: const Icon(Icons.edit_outlined, size: 18),
                  label: Text(s.edit),
                ),
                OutlinedButton.icon(
                  key: Key('toggleStore-${store.storeId}'),
                  onPressed: onToggle,
                  style: OutlinedButton.styleFrom(
                    foregroundColor: store.isActive
                        ? AppColors.danger
                        : AppColors.success,
                  ),
                  icon: Icon(
                    store.isActive
                        ? Icons.block_rounded
                        : Icons.check_circle_outline,
                    size: 18,
                  ),
                  label: Text(store.isActive ? s.deactivate : s.activate),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _StoreDialog extends StatefulWidget {
  const _StoreDialog({required this.api, this.existing});

  final StoresApiClient api;
  final StoreInfo? existing;

  @override
  State<_StoreDialog> createState() => _StoreDialogState();
}

class _StoreDialogState extends State<_StoreDialog> {
  final _formKey = GlobalKey<FormState>();
  late final _name = TextEditingController(text: widget.existing?.name ?? '');
  late final _address = TextEditingController(
    text: widget.existing?.address ?? '',
  );
  late final _phone = TextEditingController(text: widget.existing?.phone ?? '');
  late final _tax = TextEditingController(
    text: _num(widget.existing?.taxRate ?? 16),
  );
  late final _maxDiscount = TextEditingController(
    text: _num(widget.existing?.maxDiscountPercent ?? 10),
  );

  static String _num(double v) =>
      v == v.roundToDouble() ? v.toInt().toString() : v.toString();

  String? _percentProblem(String? v, AppStrings s) {
    final d = double.tryParse((v ?? '').trim());
    if (d == null || d < 0 || d > 100) return s.vPercent;
    return null;
  }
  final Map<String, String> _serverErrors = {};
  bool _saving = false;
  String? _generalError;

  @override
  void dispose() {
    _name.dispose();
    _address.dispose();
    _phone.dispose();
    _tax.dispose();
    _maxDiscount.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final s = AppStrings.of(context);
    _serverErrors.clear();
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _saving = true;
      _generalError = null;
    });
    try {
      final existing = widget.existing;
      final saved = existing == null
          ? await widget.api.createStore(
              name: _name.text.trim(),
              address: _address.text.trim(),
              phone: _phone.text.trim(),
              taxRate: double.parse(_tax.text.trim()),
              maxDiscountPercent: double.parse(_maxDiscount.text.trim()),
            )
          : await widget.api.updateStore(
              existing.storeId,
              name: _name.text.trim(),
              address: _address.text.trim(),
              phone: _phone.text.trim(),
              taxRate: double.parse(_tax.text.trim()),
              maxDiscountPercent: double.parse(_maxDiscount.text.trim()),
            );
      if (mounted) Navigator.of(context).pop(saved);
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        if (e.code == 'DUPLICATE_STORE_NAME') {
          _serverErrors['name'] = e.message;
        } else {
          _serverErrors.addAll(e.fieldErrors);
        }
        if (_serverErrors.isEmpty) {
          _generalError = e.isNetworkError
              ? s.networkError
              : s.requestFailed(e.message);
        }
      });
      _formKey.currentState!.validate();
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    return AlertDialog(
      title: Text(widget.existing == null ? s.addStore : s.editStore),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 420, minWidth: 320),
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_generalError != null) ...[
                Text(
                  _generalError!,
                  style: const TextStyle(color: AppColors.danger),
                ),
                const SizedBox(height: AppSpacing.md),
              ],
              TextFormField(
                key: const Key('storeNameField'),
                controller: _name,
                autofocus: true,
                decoration: InputDecoration(
                  labelText: '${s.storeNameLabel} *',
                  prefixIcon: const Icon(Icons.storefront_outlined),
                ),
                validator: (v) =>
                    _serverErrors['name'] ??
                    ((v ?? '').trim().length < 2 ? s.vStoreNameShort : null),
              ),
              const SizedBox(height: AppSpacing.lg),
              TextFormField(
                key: const Key('storeAddressField'),
                controller: _address,
                decoration: InputDecoration(
                  labelText: '${s.addressLabel} (${s.optional})',
                  prefixIcon: const Icon(Icons.place_outlined),
                ),
                validator: (v) => _serverErrors['address'],
              ),
              const SizedBox(height: AppSpacing.lg),
              TextFormField(
                key: const Key('storePhoneField'),
                controller: _phone,
                keyboardType: TextInputType.phone,
                onFieldSubmitted: (_) => _save(),
                decoration: InputDecoration(
                  labelText: '${s.phoneLabel} (${s.optional})',
                  prefixIcon: const Icon(Icons.phone_outlined),
                ),
                validator: (v) {
                  if (_serverErrors['phone'] != null) {
                    return _serverErrors['phone'];
                  }
                  final p = (v ?? '').trim();
                  if (p.isNotEmpty &&
                      !RegExp(r'^[0-9+()\- ]{1,30}$').hasMatch(p)) {
                    return s.vPhone;
                  }
                  return null;
                },
              ),
              const SizedBox(height: AppSpacing.lg),
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: TextFormField(
                      key: const Key('storeTaxField'),
                      controller: _tax,
                      keyboardType: const TextInputType.numberWithOptions(
                        decimal: true,
                      ),
                      decoration: InputDecoration(
                        labelText: s.taxRateLabel,
                        suffixText: '%',
                        prefixIcon: const Icon(Icons.percent_rounded),
                      ),
                      validator: (v) =>
                          _serverErrors['taxRate'] ?? _percentProblem(v, s),
                    ),
                  ),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: TextFormField(
                      key: const Key('storeMaxDiscountField'),
                      controller: _maxDiscount,
                      keyboardType: const TextInputType.numberWithOptions(
                        decimal: true,
                      ),
                      decoration: InputDecoration(
                        labelText: s.maxDiscountLabel,
                        suffixText: '%',
                        prefixIcon: const Icon(Icons.discount_outlined),
                      ),
                      validator: (v) =>
                          _serverErrors['maxDiscountPercent'] ??
                          _percentProblem(v, s),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
      actions: [
        OutlinedButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(),
          child: Text(s.cancel),
        ),
        FilledButton(
          key: const Key('storeSaveButton'),
          onPressed: _saving ? null : _save,
          child: Text(widget.existing == null ? s.create : s.saveChanges),
        ),
      ],
    );
  }
}

import 'package:flutter/material.dart';

import '../../l10n/package_strings.dart';
import '../../models/product_package.dart';
import '../../services/package_api_client.dart';
import '../../theme/app_theme.dart';
import '../../utils/formatters.dart';
import '../shifts/shift_dialogs.dart' show parseShiftCents, shiftError;

/// Store Admin, Product Details: the product's cartons / boxes (feature 6, D7).
class ProductPackagesCard extends StatefulWidget {
  const ProductPackagesCard({super.key, required this.api, required this.productId});

  final PackageApiClient api;
  final String productId;

  @override
  State<ProductPackagesCard> createState() => _ProductPackagesCardState();
}

class _ProductPackagesCardState extends State<ProductPackagesCard> {
  List<ProductPackage>? _packages;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final all = await widget.api.list();
      if (mounted) {
        setState(() {
          _packages = [for (final p in all) if (p.productId == widget.productId) p];
          _error = null;
        });
      }
    } catch (e) {
      if (mounted) setState(() => _error = shiftError(context, e));
    }
  }

  Future<void> _edit([ProductPackage? p]) async {
    final saved = await showDialog<bool>(
      context: context,
      builder: (_) => _PackageDialog(api: widget.api, productId: widget.productId, existing: p),
    );
    if (saved == true) await _load();
  }

  Future<void> _toggle(ProductPackage p, bool active) async {
    try {
      await widget.api.update(p.packageId, active: active);
    } catch (e) {
      if (mounted) setState(() => _error = shiftError(context, e));
    }
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    final t = PackageStrings.of(context);
    final list = _packages;
    return Container(
      key: const Key('productPackagesCard'),
      margin: const EdgeInsets.only(top: AppSpacing.xl),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(children: [
            const Icon(Icons.inventory_2_outlined, color: AppColors.primary),
            const SizedBox(width: AppSpacing.sm),
            Expanded(child: Text(t.title, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16))),
            OutlinedButton.icon(
              key: const Key('addPackage'),
              onPressed: () => _edit(),
              icon: const Icon(Icons.add_rounded),
              label: Text(t.add),
            ),
          ]),
          const SizedBox(height: AppSpacing.xs),
          Text(t.description, style: const TextStyle(color: AppColors.textSecondary)),
          if (_error != null)
            Text(_error!, key: const Key('packagesError'), style: const TextStyle(color: AppColors.danger)),
          if (list != null && list.isEmpty)
            Padding(padding: const EdgeInsets.only(top: AppSpacing.sm), child: Text(t.none)),
          for (final p in list ?? const <ProductPackage>[])
            Material(
              color: Colors.transparent,
              child: ListTile(
                key: Key('package-${p.packageId}'),
                contentPadding: EdgeInsets.zero,
                title: Text('${p.name} · ${t.piecesEach(p.unitsPerPackage)}'),
                subtitle: Text('${Formatters.currency(p.price)}${p.barcode == null ? '' : ' · ${p.barcode}'}'),
                onTap: () => _edit(p),
                trailing: Switch(
                  key: Key('packageActive-${p.packageId}'),
                  value: p.active,
                  onChanged: (v) => _toggle(p, v),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _PackageDialog extends StatefulWidget {
  const _PackageDialog({required this.api, required this.productId, this.existing});

  final PackageApiClient api;
  final String productId;
  final ProductPackage? existing;

  @override
  State<_PackageDialog> createState() => _PackageDialogState();
}

class _PackageDialogState extends State<_PackageDialog> {
  late final _name = TextEditingController(text: widget.existing?.name ?? '');
  late final _units = TextEditingController(text: widget.existing?.unitsPerPackage.toString() ?? '');
  late final _price = TextEditingController(
    text: widget.existing == null ? '' : widget.existing!.price.toStringAsFixed(2),
  );
  late final _barcode = TextEditingController(text: widget.existing?.barcode ?? '');
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _name.dispose();
    _units.dispose();
    _price.dispose();
    _barcode.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final t = PackageStrings.of(context);
    final cents = parseShiftCents(_price.text);
    final units = int.tryParse(_units.text.trim());
    final name = _name.text.trim();
    if (name.isEmpty || cents == null || cents == 0 || units == null || units < 2 || units > 10000) {
      setState(() => _error = t.invalid);
      return;
    }
    final price = '${cents ~/ 100}.${(cents % 100).toString().padLeft(2, '0')}';
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final e = widget.existing;
      if (e == null) {
        await widget.api.create(widget.productId,
            name: name, unitsPerPackage: units, price: price, barcode: _barcode.text.trim());
      } else {
        await widget.api.update(e.packageId, name: name, price: price, barcode: _barcode.text.trim());
      }
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = shiftError(context, e);
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = PackageStrings.of(context);
    final editing = widget.existing != null;
    return AlertDialog(
      key: const Key('packageDialog'),
      title: Text(editing ? widget.existing!.name : t.add),
      content: SizedBox(
        width: 380,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TextField(key: const Key('packageName'), controller: _name, maxLength: 60,
                decoration: InputDecoration(labelText: t.name)),
            TextField(
              key: const Key('packageUnits'),
              controller: _units,
              enabled: !editing, // the size never changes (past invoices keep it)
              keyboardType: TextInputType.number,
              decoration: InputDecoration(labelText: t.units, helperText: t.sizeFixed, helperMaxLines: 3),
            ),
            TextField(key: const Key('packagePrice'), controller: _price,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: InputDecoration(labelText: t.price)),
            TextField(key: const Key('packageBarcode'), controller: _barcode,
                keyboardType: TextInputType.number, decoration: InputDecoration(labelText: t.barcode)),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(top: AppSpacing.sm),
                child: Text(_error!, key: const Key('packageError'), style: const TextStyle(color: AppColors.danger)),
              ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.pop(context), child: Text(t.cancel)),
        FilledButton(key: const Key('packageSave'), onPressed: _busy ? null : _save, child: Text(t.save)),
      ],
    );
  }
}

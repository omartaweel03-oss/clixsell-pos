import 'dart:math';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../l10n/app_strings.dart';
import '../../models/product.dart';
import '../../models/product_category.dart';
import '../../state/product_store.dart';
import '../../theme/app_theme.dart';
import '../../utils/formatters.dart';
import '../../widgets/product_thumbnail.dart';
import '../../widgets/stock_status_badge.dart';
import 'product_validators.dart';
import 'widgets/confirm_dialogs.dart';

/// Add Product / Edit Product form.
///
/// * `initialProduct == null` → Add mode (empty form).
/// * `initialProduct != null` → Edit mode (form pre-filled).
///
/// On a successful save the product is written to the [ProductStore] and
/// [onSaved] is called so the shell can navigate back to the list.
class ProductFormPage extends StatefulWidget {
  const ProductFormPage({
    super.key,
    this.initialProduct,
    required this.onSaved,
    required this.onCancel,
  });

  final Product? initialProduct;
  final ValueChanged<Product> onSaved;
  final VoidCallback onCancel;

  bool get isEditing => initialProduct != null;

  @override
  State<ProductFormPage> createState() => ProductFormPageState();
}

class ProductFormPageState extends State<ProductFormPage> {
  final _formKey = GlobalKey<FormState>();

  late final TextEditingController _name;
  late final TextEditingController _barcode;
  late final TextEditingController _price;
  late final TextEditingController _quantity;
  late final TextEditingController _minStock;
  late final TextEditingController _supplier;

  ProductCategory? _category;
  Uint8List? _imageBytes;
  String? _imageName;
  String? _imageError;
  bool _pickingImage = false;
  bool _saving = false;
  AutovalidateMode _autovalidate = AutovalidateMode.disabled;

  @override
  void initState() {
    super.initState();
    final p = widget.initialProduct;
    _name = TextEditingController(text: p?.name ?? '');
    _barcode = TextEditingController(text: p?.barcode ?? '');
    _price = TextEditingController(text: p == null ? '' : _priceText(p.price));
    _quantity = TextEditingController(text: p?.quantity.toString() ?? '');
    _minStock = TextEditingController(text: p?.minStockLevel.toString() ?? '');
    _supplier = TextEditingController(text: p?.supplier ?? '');
    _category = p?.category;
    _imageBytes = p?.imageBytes;
    _imageName = p?.imageName;

    // Refresh the live status preview while typing quantity / min stock.
    _quantity.addListener(_refresh);
    _minStock.addListener(_refresh);
  }

  void _refresh() => setState(() {});

  @override
  void dispose() {
    _name.dispose();
    _barcode.dispose();
    _price.dispose();
    _quantity.dispose();
    _minStock.dispose();
    _supplier.dispose();
    super.dispose();
  }

  static String _priceText(double price) {
    final fixed = price.toStringAsFixed(2);
    return fixed.endsWith('.00') ? fixed.substring(0, fixed.length - 3) : fixed;
  }

  /// True when the user changed anything compared to the initial values.
  bool get isDirty {
    final p = widget.initialProduct;
    if (p == null) {
      return _name.text.isNotEmpty ||
          _barcode.text.isNotEmpty ||
          _price.text.isNotEmpty ||
          _quantity.text.isNotEmpty ||
          _minStock.text.isNotEmpty ||
          _supplier.text.isNotEmpty ||
          _category != null ||
          _imageBytes != null;
    }
    return _name.text.trim() != p.name ||
        _barcode.text.trim() != p.barcode ||
        _price.text.trim() != _priceText(p.price) ||
        _quantity.text.trim() != p.quantity.toString() ||
        _minStock.text.trim() != p.minStockLevel.toString() ||
        _supplier.text.trim() != p.supplier ||
        _category != p.category ||
        !identical(_imageBytes, p.imageBytes);
  }

  /// Called by the shell before navigating away. Returns true if it is OK
  /// to leave (no changes, or the user chose to discard them).
  Future<bool> confirmLeave() async {
    if (_saving || !isDirty) return true;
    return confirmDiscardChanges(context);
  }

  Future<void> _handleCancel() async {
    if (await confirmLeave()) widget.onCancel();
  }

  Future<void> _pickImage() async {
    final s = AppStrings.of(context);
    final validators = ProductValidators(s);
    setState(() => _pickingImage = true);
    try {
      final file = await FilePicker.pickFile(type: FileType.image);
      if (file == null) return; // user cancelled

      final knownSize = await file.length();
      if (knownSize != null && knownSize > ProductValidators.maxImageBytes) {
        setState(() => _imageError = s.vImageTooLarge);
        return;
      }
      final bytes = await file.readAsBytes();
      final error = validators.image(
        extension: file.extension,
        sizeInBytes: bytes.length,
      );
      setState(() {
        _imageError = error;
        if (error == null) {
          _imageBytes = bytes;
          _imageName = file.name;
        }
      });
    } catch (e) {
      debugPrint('Image pick failed: $e');
      if (mounted) setState(() => _imageError = s.vImageFailed);
    } finally {
      if (mounted) setState(() => _pickingImage = false);
    }
  }

  void _removeImage() {
    setState(() {
      _imageBytes = null;
      _imageName = null;
      _imageError = null;
    });
  }

  /// Generates a unique, valid EAN-13 barcode (prefix 729 like the samples).
  void _generateBarcode() {
    final store = ProductStoreScope.read(context);
    final rnd = Random();
    String code;
    do {
      final digits = [7, 2, 9, for (var i = 0; i < 9; i++) rnd.nextInt(10)];
      var sum = 0;
      for (var i = 0; i < digits.length; i++) {
        sum += digits[i] * (i.isEven ? 1 : 3);
      }
      final check = (10 - sum % 10) % 10;
      code = '${digits.join()}$check';
    } while (store.isBarcodeTaken(code, excludeId: widget.initialProduct?.id));
    setState(() => _barcode.text = code);
  }

  Future<void> _save() async {
    final s = AppStrings.of(context);
    final store = ProductStoreScope.read(context);
    final messenger = ScaffoldMessenger.of(context);

    final valid = _formKey.currentState!.validate();
    if (!valid || _imageError != null) {
      setState(() => _autovalidate = AutovalidateMode.onUserInteraction);
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(
          content: Text(s.vFixErrors),
          backgroundColor: AppColors.danger,
        ));
      return;
    }

    setState(() => _saving = true);
    try {
      final name = _name.text.trim();
      final barcode = _barcode.text.trim();
      final price = double.parse(_price.text.trim());
      final quantity = int.parse(_quantity.text.trim());
      final minStock = int.parse(_minStock.text.trim());
      final supplier = _supplier.text.trim();

      final Product saved;
      final existing = widget.initialProduct;
      if (existing == null) {
        saved = store.addProduct(
          name: name,
          barcode: barcode,
          category: _category!,
          price: price,
          quantity: quantity,
          minStockLevel: minStock,
          supplier: supplier,
          imageBytes: _imageBytes,
          imageName: _imageName,
        );
      } else {
        final updated = existing.copyWith(
          name: name,
          barcode: barcode,
          category: _category,
          price: price,
          quantity: quantity,
          minStockLevel: minStock,
          supplier: supplier,
          imageBytes: _imageBytes,
          imageName: _imageName,
          removeImage: _imageBytes == null,
        );
        store.updateProduct(updated);
        saved = store.findById(existing.id) ?? updated;
      }
      widget.onSaved(saved);
    } on StateError {
      // Barcode became duplicated or product vanished in the meantime.
      _formKey.currentState!.validate();
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final store = ProductStoreScope.read(context);
    final v = ProductValidators(s);
    final editing = widget.isEditing;

    // ── Fields ────────────────────────────────────────────────────────────
    final nameField = TextFormField(
      key: const Key('nameField'),
      controller: _name,
      textInputAction: TextInputAction.next,
      maxLength: ProductValidators.maxNameLength,
      decoration: InputDecoration(
        labelText: '${s.fieldName} *',
        hintText: s.fieldNameHint,
        counterText: '',
        prefixIcon: const Icon(Icons.label_outline_rounded),
      ),
      validator: v.name,
    );

    final barcodeField = TextFormField(
      key: const Key('barcodeField'),
      controller: _barcode,
      textInputAction: TextInputAction.next,
      keyboardType: TextInputType.number,
      inputFormatters: [
        FilteringTextInputFormatter.digitsOnly,
        LengthLimitingTextInputFormatter(14),
      ],
      decoration: InputDecoration(
        labelText: '${s.fieldBarcode} *',
        hintText: s.fieldBarcodeHint,
        prefixIcon: const Icon(Icons.qr_code_2_rounded),
        suffixIcon: IconButton(
          tooltip: s.generateBarcode,
          icon: const Icon(Icons.autorenew_rounded),
          onPressed: _generateBarcode,
        ),
      ),
      validator: (value) => v.barcode(
        value,
        isTaken: (code) =>
            store.isBarcodeTaken(code, excludeId: widget.initialProduct?.id),
      ),
    );

    final categoryField = DropdownButtonFormField<ProductCategory>(
      key: const Key('categoryField'),
      initialValue: _category,
      isExpanded: true,
      decoration: InputDecoration(
        labelText: '${s.fieldCategory} *',
        prefixIcon: const Icon(Icons.category_outlined),
      ),
      hint: Text(s.fieldCategoryHint),
      items: [
        for (final c in ProductCategory.values)
          DropdownMenuItem(
            value: c,
            child: Text(c.label(arabic: s.isArabic)),
          ),
      ],
      onChanged: (c) => setState(() => _category = c),
      validator: v.category,
    );

    final priceField = TextFormField(
      key: const Key('priceField'),
      controller: _price,
      textInputAction: TextInputAction.next,
      keyboardType: const TextInputType.numberWithOptions(decimal: true),
      inputFormatters: [
        FilteringTextInputFormatter.allow(RegExp(r'[0-9.]')),
        LengthLimitingTextInputFormatter(12),
      ],
      decoration: InputDecoration(
        labelText: '${s.fieldPrice} *',
        hintText: '0.00',
        prefixIcon: const Icon(Icons.payments_outlined),
      ),
      validator: v.price,
    );

    final quantityField = TextFormField(
      key: const Key('quantityField'),
      controller: _quantity,
      textInputAction: TextInputAction.next,
      keyboardType: TextInputType.number,
      inputFormatters: [
        FilteringTextInputFormatter.digitsOnly,
        LengthLimitingTextInputFormatter(7),
      ],
      decoration: InputDecoration(
        labelText: '${s.fieldQuantity} *',
        hintText: '0',
        prefixIcon: const Icon(Icons.inventory_outlined),
      ),
      validator: v.wholeNumber,
    );

    final minStockField = TextFormField(
      key: const Key('minStockField'),
      controller: _minStock,
      textInputAction: TextInputAction.next,
      keyboardType: TextInputType.number,
      inputFormatters: [
        FilteringTextInputFormatter.digitsOnly,
        LengthLimitingTextInputFormatter(7),
      ],
      decoration: InputDecoration(
        labelText: '${s.fieldMinStock} *',
        hintText: '0',
        helperText: s.fieldMinStockHelper,
        helperMaxLines: 2,
        prefixIcon: const Icon(Icons.notification_important_outlined),
      ),
      validator: v.wholeNumber,
    );

    final suppliers = store.suppliers;
    final supplierField = TextFormField(
      key: const Key('supplierField'),
      controller: _supplier,
      textInputAction: TextInputAction.done,
      onFieldSubmitted: (_) => _save(),
      decoration: InputDecoration(
        labelText: '${s.fieldSupplier} *',
        hintText: s.fieldSupplierHint,
        prefixIcon: const Icon(Icons.local_shipping_outlined),
        suffixIcon: suppliers.isEmpty
            ? null
            : PopupMenuButton<String>(
                tooltip: s.fieldSupplier,
                icon: const Icon(Icons.arrow_drop_down_rounded),
                onSelected: (value) => setState(() => _supplier.text = value),
                itemBuilder: (_) => [
                  for (final sup in suppliers)
                    PopupMenuItem(value: sup, child: Text(sup)),
                ],
              ),
      ),
      validator: v.supplier,
    );

    // ── Sections ──────────────────────────────────────────────────────────
    final basicSection = _FormSection(
      icon: Icons.info_outline_rounded,
      title: s.sectionBasicInfo,
      children: [
        nameField,
        _ResponsivePair(first: barcodeField, second: categoryField),
        supplierField,
      ],
    );

    final pricingSection = _FormSection(
      icon: Icons.sell_outlined,
      title: s.sectionPricingStock,
      children: [
        priceField,
        _ResponsivePair(first: quantityField, second: minStockField),
      ],
    );

    final imageSection = _FormSection(
      icon: Icons.image_outlined,
      title: s.sectionImage,
      children: [
        _ImagePickerBox(
          bytes: _imageBytes,
          fileName: _imageName,
          error: _imageError,
          loading: _pickingImage,
          onPick: _pickImage,
          onRemove: _removeImage,
        ),
        _StatusPreview(
          quantity: int.tryParse(_quantity.text.trim()),
          minStock: int.tryParse(_minStock.text.trim()),
        ),
        if (editing) _Timestamps(product: widget.initialProduct!),
      ],
    );

    final actions = Wrap(
      alignment: WrapAlignment.end,
      spacing: AppSpacing.md,
      runSpacing: AppSpacing.sm,
      children: [
        OutlinedButton(
          key: const Key('cancelButton'),
          onPressed: _saving ? null : _handleCancel,
          child: Text(s.cancel),
        ),
        FilledButton.icon(
          key: const Key('saveButton'),
          onPressed: _saving ? null : _save,
          icon: _saving
              ? const SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(
                      strokeWidth: 2, color: Colors.white),
                )
              : const Icon(Icons.check_rounded, size: 20),
          label: Text(editing ? s.saveChanges : s.save),
        ),
      ],
    );

    return LayoutBuilder(
      builder: (context, constraints) {
        final wide = constraints.maxWidth >= 980;
        final horizontal =
            constraints.maxWidth < 600 ? AppSpacing.lg : AppSpacing.xxl;

        return Form(
          key: _formKey,
          autovalidateMode: _autovalidate,
          child: SingleChildScrollView(
            padding: EdgeInsets.fromLTRB(
                horizontal, AppSpacing.xl, horizontal, AppSpacing.xxl),
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 1200),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    _FormHeader(
                      title: editing ? s.editProductTitle : s.addProductTitle,
                      subtitle: editing
                          ? s.editProductSubtitle(widget.initialProduct!.name)
                          : s.addProductSubtitle,
                      onBack: _handleCancel,
                    ),
                    const SizedBox(height: AppSpacing.xl),
                    if (wide)
                      Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Expanded(
                            flex: 3,
                            child: Column(
                              children: [
                                basicSection,
                                const SizedBox(height: AppSpacing.lg),
                                pricingSection,
                              ],
                            ),
                          ),
                          const SizedBox(width: AppSpacing.lg),
                          Expanded(flex: 2, child: imageSection),
                        ],
                      )
                    else ...[
                      basicSection,
                      const SizedBox(height: AppSpacing.lg),
                      pricingSection,
                      const SizedBox(height: AppSpacing.lg),
                      imageSection,
                    ],
                    const SizedBox(height: AppSpacing.xl),
                    actions,
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Form building blocks
// ─────────────────────────────────────────────────────────────────────────────

class _FormHeader extends StatelessWidget {
  const _FormHeader({
    required this.title,
    required this.subtitle,
    required this.onBack,
  });

  final String title;
  final String subtitle;
  final VoidCallback onBack;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        IconButton.outlined(
          key: const Key('formBackButton'),
          tooltip: s.backToInventory,
          onPressed: onBack,
          style: IconButton.styleFrom(
            backgroundColor: AppColors.surface,
            side: const BorderSide(color: AppColors.border),
          ),
          // BackButtonIcon is automatically mirrored in RTL.
          icon: const BackButtonIcon(),
        ),
        const SizedBox(width: AppSpacing.lg),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: const TextStyle(
                  fontSize: 26,
                  fontWeight: FontWeight.w800,
                ),
              ),
              const SizedBox(height: AppSpacing.xs),
              Text(
                subtitle,
                style: const TextStyle(color: AppColors.textSecondary),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _FormSection extends StatelessWidget {
  const _FormSection({
    required this.icon,
    required this.title,
    required this.children,
  });

  final IconData icon;
  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.xl),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Container(
                  width: 34,
                  height: 34,
                  decoration: BoxDecoration(
                    color: AppColors.primarySoft,
                    borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
                  ),
                  child: Icon(icon, size: 19, color: AppColors.primary),
                ),
                const SizedBox(width: AppSpacing.md),
                Expanded(
                  child: Text(
                    title,
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: AppSpacing.xl),
            for (var i = 0; i < children.length; i++) ...[
              if (i > 0) const SizedBox(height: AppSpacing.lg + 2),
              children[i],
            ],
          ],
        ),
      ),
    );
  }
}

/// Two fields side by side on wide screens, stacked on narrow ones.
class _ResponsivePair extends StatelessWidget {
  const _ResponsivePair({required this.first, required this.second});

  final Widget first;
  final Widget second;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, c) {
        if (c.maxWidth < 480) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [first, const SizedBox(height: AppSpacing.lg + 2), second],
          );
        }
        return Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(child: first),
            const SizedBox(width: AppSpacing.lg),
            Expanded(child: second),
          ],
        );
      },
    );
  }
}

class _ImagePickerBox extends StatelessWidget {
  const _ImagePickerBox({
    required this.bytes,
    required this.fileName,
    required this.error,
    required this.loading,
    required this.onPick,
    required this.onRemove,
  });

  final Uint8List? bytes;
  final String? fileName;
  final String? error;
  final bool loading;
  final VoidCallback onPick;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final hasImage = bytes != null && bytes!.isNotEmpty;
    final borderColor = error != null ? AppColors.danger : AppColors.border;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Material(
          color: AppColors.background,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
            side: BorderSide(color: borderColor, width: 1.2),
          ),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            key: const Key('imageDropZone'),
            onTap: loading ? null : onPick,
            child: AspectRatio(
              aspectRatio: 4 / 3,
              child: loading
                  ? const Center(child: CircularProgressIndicator())
                  : hasImage
                      ? Padding(
                          padding: const EdgeInsets.all(AppSpacing.md),
                          child: ProductThumbnail(
                            imageBytes: bytes,
                            size: double.infinity,
                            radius: AppSpacing.radiusSm,
                          ),
                        )
                      : Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Container(
                              width: 60,
                              height: 60,
                              decoration: const BoxDecoration(
                                color: AppColors.primarySoft,
                                shape: BoxShape.circle,
                              ),
                              child: const Icon(
                                Icons.cloud_upload_outlined,
                                color: AppColors.primary,
                                size: 30,
                              ),
                            ),
                            const SizedBox(height: AppSpacing.md),
                            Text(
                              s.uploadImage,
                              style: const TextStyle(
                                fontWeight: FontWeight.w700,
                                color: AppColors.primary,
                              ),
                            ),
                            const SizedBox(height: AppSpacing.xs),
                            Padding(
                              padding: const EdgeInsets.symmetric(
                                  horizontal: AppSpacing.lg),
                              child: Text(
                                s.imageHint,
                                textAlign: TextAlign.center,
                                style: const TextStyle(
                                  fontSize: 12,
                                  color: AppColors.textMuted,
                                ),
                              ),
                            ),
                          ],
                        ),
            ),
          ),
        ),
        if (error != null) ...[
          const SizedBox(height: AppSpacing.sm),
          Text(
            error!,
            style: const TextStyle(color: AppColors.danger, fontSize: 12.5),
          ),
        ],
        if (hasImage) ...[
          const SizedBox(height: AppSpacing.md),
          Row(
            children: [
              Expanded(
                child: Text(
                  fileName ?? '',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                      fontSize: 12.5, color: AppColors.textSecondary),
                ),
              ),
              TextButton.icon(
                onPressed: loading ? null : onPick,
                icon: const Icon(Icons.swap_horiz_rounded, size: 18),
                label: Text(s.changeImage),
              ),
              TextButton.icon(
                key: const Key('removeImageButton'),
                onPressed: loading ? null : onRemove,
                style: TextButton.styleFrom(foregroundColor: AppColors.danger),
                icon: const Icon(Icons.delete_outline_rounded, size: 18),
                label: Text(s.removeImage),
              ),
            ],
          ),
        ],
      ],
    );
  }
}

/// Shows which stock status the product will get with the typed values.
class _StatusPreview extends StatelessWidget {
  const _StatusPreview({required this.quantity, required this.minStock});

  final int? quantity;
  final int? minStock;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    StockStatus? status;
    if (quantity != null) {
      if (quantity! <= 0) {
        status = StockStatus.outOfStock;
      } else if (minStock != null && quantity! <= minStock!) {
        status = StockStatus.lowStock;
      } else {
        status = StockStatus.inStock;
      }
    }
    return Container(
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: AppColors.background,
        borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
      ),
      child: Row(
        children: [
          Expanded(
            child: Text(
              s.previewStatus,
              style: const TextStyle(
                color: AppColors.textSecondary,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          if (status != null)
            StockStatusBadge(status: status)
          else
            const Text('—', style: TextStyle(color: AppColors.textMuted)),
        ],
      ),
    );
  }
}

class _Timestamps extends StatelessWidget {
  const _Timestamps({required this.product});

  final Product product;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    const style = TextStyle(fontSize: 12.5, color: AppColors.textSecondary);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('${s.createdAt}: ${Formatters.date(product.createdAt)}',
            style: style),
        const SizedBox(height: 2),
        Text('${s.updatedAt}: ${Formatters.date(product.updatedAt)}',
            style: style),
      ],
    );
  }
}

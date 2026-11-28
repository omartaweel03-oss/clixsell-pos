import '../../services/package_api_client.dart';
import 'product_packages_card.dart';
import 'package:flutter/material.dart';

import '../../l10n/app_strings.dart';
import '../../models/product.dart';
import '../../services/api_http.dart';
import '../../state/product_store.dart';
import '../../theme/app_theme.dart';
import '../../utils/formatters.dart';
import '../../widgets/stock_status_badge.dart';
import 'product_delete.dart';

/// Product Details – report Figure 3.4 (page 33), UR-M5 / SR18.
/// A Store Admin screen (Cashiers keep their read-only product list).
///
/// As in the figure: the product image on the left, the information on the
/// right (name, barcode, category, price, quantity, minimum stock, supplier,
/// date added, status) and "Edit Product" / "Delete Product" at the bottom.
/// The cost price is shown as an extra row (Store Admins only; the backend
/// never sends it to Cashiers).
///
/// The product is read again from the server when the screen opens
/// (GET /api/products/{id}), so the quantity is current after sales and
/// returns. Edit and Delete reuse the existing form and the shared delete
/// with confirmation and Undo.
class ProductDetailsPage extends StatefulWidget {
  const ProductDetailsPage({
    super.key,
    required this.productId,
    required this.onBack,
    required this.onEdit,
    this.packages,
  });

  /// Cartons / boxes of the product (Store Admin; null = not shown).
  final PackageApiClient? packages;

  final String productId;
  final VoidCallback onBack;
  final ValueChanged<Product> onEdit;

  @override
  State<ProductDetailsPage> createState() => _ProductDetailsPageState();
}

class _ProductDetailsPageState extends State<ProductDetailsPage> {
  bool _loading = true;
  bool _missing = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    final store = ProductStoreScope.read(context);
    try {
      final fresh = await store.refreshProduct(widget.productId);
      if (!mounted) return;
      setState(() {
        _loading = false;
        _missing = fresh == null;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      final s = AppStrings.of(context);
      setState(() {
        _loading = false;
        _error = e.isNetworkError ? s.networkError : e.message;
      });
    }
  }

  Future<void> _delete(Product product) async {
    final deleted = await deleteProductWithUndo(context, product);
    if (deleted && mounted) widget.onBack();
  }

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final store = ProductStoreScope.of(context);
    final product = store.findById(widget.productId);

    final Widget body;
    if (product == null && _loading) {
      body = const Padding(
        padding: EdgeInsets.all(AppSpacing.xxl),
        child: Center(child: CircularProgressIndicator()),
      );
    } else if (product == null || _missing) {
      body = Padding(
        key: const Key('detailsMissing'),
        padding: const EdgeInsets.all(AppSpacing.xxl),
        child: Column(
          children: [
            const Icon(
              Icons.search_off_rounded,
              size: 48,
              color: AppColors.textMuted,
            ),
            const SizedBox(height: AppSpacing.md),
            Text(s.productNotFound, textAlign: TextAlign.center),
            const SizedBox(height: AppSpacing.lg),
            FilledButton(
              onPressed: widget.onBack,
              child: Text(s.backToProducts),
            ),
          ],
        ),
      );
    } else {
      final details = _Details(
        product: product,
        onEdit: () => widget.onEdit(product),
        onDelete: () => _delete(product),
      );
      final packages = widget.packages;
      body = packages == null
          ? details
          : Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                details,
                ProductPackagesCard(api: packages, productId: product.id),
              ],
            );
    }

    return LayoutBuilder(
      builder: (context, c) {
        final pad = c.maxWidth < 600 ? AppSpacing.md : AppSpacing.xl;
        return SingleChildScrollView(
          key: const Key('productDetailsPage'),
          padding: EdgeInsets.all(pad),
          child: Container(
            padding: EdgeInsets.all(pad),
            decoration: BoxDecoration(
              color: AppColors.surface,
              borderRadius: BorderRadius.circular(AppSpacing.radiusLg),
              border: Border.all(color: AppColors.border),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _Header(onBack: widget.onBack),
                if (_error != null) ...[
                  const SizedBox(height: AppSpacing.md),
                  Text(
                    _error!,
                    key: const Key('detailsError'),
                    style: const TextStyle(color: AppColors.danger),
                  ),
                ],
                const SizedBox(height: AppSpacing.xl),
                body,
              ],
            ),
          ),
        );
      },
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({required this.onBack});

  final VoidCallback onBack;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final title = Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        IconButton.outlined(
          key: const Key('detailsBack'),
          tooltip: s.backToProducts,
          onPressed: onBack,
          icon: const BackButtonIcon(),
        ),
        const SizedBox(width: AppSpacing.md),
        Flexible(
          child: Text(
            s.navProductDetails,
            style: const TextStyle(fontSize: 26, fontWeight: FontWeight.w800),
          ),
        ),
      ],
    );
    final date = Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
        border: Border.all(color: AppColors.border),
      ),
      child: Text(
        MaterialLocalizations.of(context).formatFullDate(DateTime.now()),
        style: const TextStyle(fontWeight: FontWeight.w600),
      ),
    );
    return Wrap(
      alignment: WrapAlignment.spaceBetween,
      crossAxisAlignment: WrapCrossAlignment.center,
      spacing: AppSpacing.lg,
      runSpacing: AppSpacing.md,
      children: [title, date],
    );
  }
}

/// Figure 3.4: image on the left, information on the right, and the buttons
/// below them (Delete under the image, Edit under the information). On a
/// narrow screen the parts are stacked instead (no separate mobile design).
class _Details extends StatelessWidget {
  const _Details({
    required this.product,
    required this.onEdit,
    required this.onDelete,
  });

  final Product product;
  final VoidCallback onEdit;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final image = _ImageBox(product: product);
    final edit = FilledButton.icon(
      key: const Key('detailsEdit'),
      onPressed: onEdit,
      icon: const Icon(Icons.edit_outlined),
      label: Text(s.editProductButton),
    );
    final delete = FilledButton.icon(
      key: const Key('detailsDelete'),
      style: FilledButton.styleFrom(backgroundColor: AppColors.danger),
      onPressed: onDelete,
      icon: const Icon(Icons.delete_outline_rounded),
      label: Text(s.deleteProductButton),
    );

    return LayoutBuilder(
      builder: (context, c) {
        final info = _InfoTable(
          product: product,
          labelWidth: c.maxWidth < 400 ? 90 : 130,
        );
        if (c.maxWidth < 720) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              info,
              const SizedBox(height: AppSpacing.lg),
              SizedBox(height: 260, child: image),
              const SizedBox(height: AppSpacing.lg),
              edit,
              const SizedBox(height: AppSpacing.sm),
              delete,
            ],
          );
        }
        // Left / right as in the figure, in Arabic and in English (the texts
        // inside keep the language's direction).
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            IntrinsicHeight(
              child: Row(
                textDirection: TextDirection.ltr,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Expanded(child: image),
                  const SizedBox(width: AppSpacing.xl),
                  Expanded(child: info),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.xl),
            Row(
              textDirection: TextDirection.ltr,
              children: [
                Expanded(child: SizedBox(height: 46, child: delete)),
                const SizedBox(width: AppSpacing.xl),
                Expanded(child: SizedBox(height: 46, child: edit)),
              ],
            ),
          ],
        );
      },
    );
  }
}

class _ImageBox extends StatelessWidget {
  const _ImageBox({required this.product});

  final Product product;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final bytes = product.imageBytes;
    return Container(
      key: const Key('detailsImage'),
      constraints: const BoxConstraints(minHeight: 300),
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(AppSpacing.radiusLg),
        border: Border.all(color: AppColors.border),
      ),
      child: bytes != null && bytes.isNotEmpty
          ? Image.memory(
              bytes,
              fit: BoxFit.contain,
              gaplessPlayback: true,
              errorBuilder: (_, _, _) => _noImage(s),
            )
          : _noImage(s),
    );
  }

  Widget _noImage(AppStrings s) => Column(
    mainAxisAlignment: MainAxisAlignment.center,
    children: [
      const Icon(
        Icons.inventory_2_outlined,
        size: 72,
        color: AppColors.primary,
      ),
      const SizedBox(height: AppSpacing.sm),
      Text(
        s.noProductImage,
        key: const Key('detailsNoImage'),
        style: const TextStyle(color: AppColors.textSecondary),
      ),
    ],
  );
}

class _InfoTable extends StatelessWidget {
  const _InfoTable({required this.product, this.labelWidth = 130});

  final Product product;

  /// Narrower on small screens so long values (barcode) do not break.
  final double labelWidth;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final p = product;
    final cost = p.costPrice;
    final rows = <(String, Widget, String)>[
      (s.detailName, _value(p.name), 'name'),
      (s.colBarcode, _value(p.barcode), 'barcode'),
      (s.colCategory, _value(p.category.label(arabic: s.isArabic)), 'category'),
      (s.colPrice, _value(Formatters.currency(p.price)), 'price'),
      (s.colQuantity, _value(Formatters.integer(p.quantity)), 'quantity'),
      (s.colMinStock, _value(Formatters.integer(p.minStockLevel)), 'minStock'),
      (s.colSupplier, _value(p.supplier), 'supplier'),
      (s.detailDateAdded, _value(Formatters.date(p.createdAt)), 'dateAdded'),
      (s.colStatus, StockStatusBadge(status: p.stockStatus), 'status'),
      // Not in the figure: the purchase cost, for the Store Admin only (this
      // screen is a Store Admin screen and the backend never sends it to
      // Cashiers).
      (
        s.detailCostPrice,
        _value(
          cost == null ? s.detailCostUnknown : Formatters.currency(cost),
          muted: cost == null,
        ),
        'costPrice',
      ),
    ];

    return Container(
      key: const Key('detailsInfo'),
      padding: const EdgeInsets.symmetric(
        horizontal: AppSpacing.lg,
        vertical: AppSpacing.sm,
      ),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(AppSpacing.radiusLg),
        border: Border.all(color: AppColors.border),
      ),
      child: Column(
        children: [
          for (var i = 0; i < rows.length; i++)
            Container(
              key: Key('detail-${rows[i].$3}'),
              padding: const EdgeInsets.symmetric(vertical: 10),
              decoration: BoxDecoration(
                border: i == rows.length - 1
                    ? null
                    : const Border(
                        bottom: BorderSide(color: AppColors.divider),
                      ),
              ),
              // As in Figure 3.4: label on the left, value on the right.
              child: Row(
                textDirection: TextDirection.ltr,
                children: [
                  SizedBox(
                    width: labelWidth,
                    child: Text(
                      rows[i].$1,
                      style: const TextStyle(
                        color: AppColors.textSecondary,
                        fontSize: 13,
                      ),
                    ),
                  ),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: Align(
                      alignment: Alignment.centerRight,
                      child: rows[i].$2,
                    ),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  static Widget _value(String text, {bool muted = false}) => Text(
    text,
    textAlign: TextAlign.right,
    maxLines: 2,
    overflow: TextOverflow.ellipsis,
    style: TextStyle(
      fontWeight: muted ? FontWeight.w400 : FontWeight.w700,
      color: muted ? AppColors.textMuted : AppColors.textPrimary,
      fontSize: 14,
    ),
  );
}

import 'package:flutter/material.dart';

import '../../../l10n/app_strings.dart';
import '../../../models/product.dart';
import '../../../theme/app_theme.dart';
import '../../../widgets/min_width_scroll.dart';
import '../../../utils/formatters.dart';
import '../../../widgets/product_thumbnail.dart';
import '../../../widgets/stock_status_badge.dart';

/// Product list for the inventory page.
///
/// * Wide layouts (≥ 960 px): a full table with all columns.
/// * Narrow layouts: one card per product so nothing gets cramped.
class ProductTable extends StatelessWidget {
  const ProductTable({
    super.key,
    required this.products,
    this.onEdit,
    this.onDelete,
  });

  final List<Product> products;

  /// Null for read-only users (Cashiers): the edit/delete actions are hidden.
  final ValueChanged<Product>? onEdit;
  final ValueChanged<Product>? onDelete;

  /// Narrowest width at which every column (incl. the actions) fits.
  static const double wideBreakpoint = 1100;

  @override
  Widget build(BuildContext context) {
    // Desktop table (Figure 3.3) at every size; narrow screens scroll sideways.
    return MinWidthScroll(
      minWidth: wideBreakpoint,
      child: _WideTable(products: products, onEdit: onEdit, onDelete: onDelete),
    );
  }
}

/// Column flex weights, shared by the header and every row so they align.
class _Cols {
  static const product = 25;
  static const barcode = 15;
  static const category = 15;
  static const price = 11;
  static const qty = 9;
  static const min = 11;
  static const supplier = 14;
  static const status = 18;
  static const actions = 11;
}

class _WideTable extends StatelessWidget {
  const _WideTable({required this.products, this.onEdit, this.onDelete});

  final List<Product> products;
  final ValueChanged<Product>? onEdit;
  final ValueChanged<Product>? onDelete;

  bool get _showActions => onEdit != null || onDelete != null;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    const headerStyle = TextStyle(
      fontSize: 12.5,
      fontWeight: FontWeight.w700,
      color: AppColors.textSecondary,
    );

    Widget h(String text, int flex, {bool center = false}) => Expanded(
      flex: flex,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8),
        child: Text(
          text,
          style: headerStyle,
          textAlign: center ? TextAlign.center : TextAlign.start,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
      ),
    );

    return Column(
      children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 14),
          decoration: BoxDecoration(
            color: AppColors.background,
            borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
          ),
          child: Row(
            children: [
              h(s.colProduct, _Cols.product),
              h(s.colBarcode, _Cols.barcode),
              h(s.colCategory, _Cols.category),
              h(s.colPrice, _Cols.price),
              h(s.colQuantity, _Cols.qty, center: true),
              h(s.colMinStock, _Cols.min, center: true),
              h(s.colSupplier, _Cols.supplier),
              h(s.colStatus, _Cols.status, center: true),
              if (_showActions) h(s.colActions, _Cols.actions, center: true),
            ],
          ),
        ),
        for (final p in products)
          _WideRow(
            key: ValueKey(p.id),
            product: p,
            onEdit: onEdit == null ? null : () => onEdit!(p),
            onDelete: onDelete == null ? null : () => onDelete!(p),
          ),
      ],
    );
  }
}

class _WideRow extends StatefulWidget {
  const _WideRow({
    super.key,
    required this.product,
    this.onEdit,
    this.onDelete,
  });

  final Product product;
  final VoidCallback? onEdit;
  final VoidCallback? onDelete;

  @override
  State<_WideRow> createState() => _WideRowState();
}

class _WideRowState extends State<_WideRow> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final p = widget.product;
    const cellStyle = TextStyle(fontSize: 13.5, color: AppColors.textPrimary);

    Widget cell(Widget child, int flex, {bool center = false}) => Expanded(
      flex: flex,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8),
        child: Align(
          alignment: center
              ? Alignment.center
              : AlignmentDirectional.centerStart,
          child: child,
        ),
      ),
    );

    Widget text(String value, {TextStyle? style}) => Text(
      value,
      style: style ?? cellStyle,
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
    );

    final qtyColor = switch (p.stockStatus) {
      StockStatus.outOfStock => AppColors.danger,
      StockStatus.lowStock => AppColors.warning,
      StockStatus.inStock => AppColors.textPrimary,
    };

    return MouseRegion(
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 120),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        decoration: BoxDecoration(
          color: _hover ? AppColors.primarySoft.withValues(alpha: 0.45) : null,
          border: const Border(bottom: BorderSide(color: AppColors.divider)),
        ),
        child: Row(
          children: [
            cell(
              Row(
                children: [
                  ProductThumbnail(imageBytes: p.imageBytes, size: 42),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: Tooltip(
                      message: p.name,
                      waitDuration: const Duration(milliseconds: 600),
                      child: text(
                        p.name,
                        style: cellStyle.copyWith(fontWeight: FontWeight.w700),
                      ),
                    ),
                  ),
                ],
              ),
              _Cols.product,
            ),
            cell(
              text(
                p.barcode,
                style: cellStyle.copyWith(
                  fontFeatures: const [FontFeature.tabularFigures()],
                  color: AppColors.textSecondary,
                ),
              ),
              _Cols.barcode,
            ),
            cell(text(p.category.label(arabic: s.isArabic)), _Cols.category),
            cell(
              text(
                Formatters.currency(p.price),
                style: cellStyle.copyWith(fontWeight: FontWeight.w600),
              ),
              _Cols.price,
            ),
            cell(
              text(
                Formatters.integer(p.quantity),
                style: cellStyle.copyWith(
                  fontWeight: FontWeight.w700,
                  color: qtyColor,
                ),
              ),
              _Cols.qty,
              center: true,
            ),
            cell(
              text(
                Formatters.integer(p.minStockLevel),
                style: cellStyle.copyWith(color: AppColors.textSecondary),
              ),
              _Cols.min,
              center: true,
            ),
            cell(
              text(
                p.supplier,
                style: cellStyle.copyWith(color: AppColors.primary),
              ),
              _Cols.supplier,
            ),
            cell(
              StockStatusBadge(status: p.stockStatus),
              _Cols.status,
              center: true,
            ),
            if (widget.onEdit != null || widget.onDelete != null)
              cell(
                Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (widget.onEdit != null)
                      _ActionIcon(
                        key: Key('edit-${p.id}'),
                        icon: Icons.edit_outlined,
                        color: AppColors.primary,
                        tooltip: s.edit,
                        onPressed: widget.onEdit!,
                      ),
                    const SizedBox(width: 6),
                    if (widget.onDelete != null)
                      _ActionIcon(
                        key: Key('delete-${p.id}'),
                        icon: Icons.delete_outline_rounded,
                        color: AppColors.danger,
                        tooltip: s.delete,
                        onPressed: widget.onDelete!,
                      ),
                  ],
                ),
                _Cols.actions,
                center: true,
              ),
          ],
        ),
      ),
    );
  }
}

class _ActionIcon extends StatelessWidget {
  const _ActionIcon({
    super.key,
    required this.icon,
    required this.color,
    required this.tooltip,
    required this.onPressed,
  });

  final IconData icon;
  final Color color;
  final String tooltip;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip,
      child: Material(
        color: color.withValues(alpha: 0.08),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
          side: BorderSide(color: color.withValues(alpha: 0.25)),
        ),
        child: InkWell(
          borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
          onTap: onPressed,
          child: SizedBox(
            width: 34,
            height: 34,
            child: Icon(icon, size: 18, color: color),
          ),
        ),
      ),
    );
  }
}

/// Compact representation used on narrow screens.

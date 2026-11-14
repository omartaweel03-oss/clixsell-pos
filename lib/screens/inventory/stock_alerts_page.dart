import 'package:flutter/material.dart';

import '../../l10n/app_strings.dart';
import '../../models/product.dart';
import '../../models/product_category.dart';
import '../../models/report.dart';
import '../../services/report_api_client.dart';
import '../../state/product_store.dart';
import '../../theme/app_theme.dart';
import '../../utils/formatters.dart';
import '../../widgets/product_thumbnail.dart';
import '../../widgets/stat_card.dart';
import '../../widgets/stock_status_badge.dart';
import '../reports/report_widgets.dart';

/// Stock Alerts screen – report Figure 3.5 (page 34), UR-M9 / SR22.
///
/// Cards: total alerts, out of stock, low stock (tap = filter); a table of
/// the products at or below their alert level (minimum stock) with an action
/// to update the product's stock. All data comes from the backend
/// (`GET /api/reports/inventory`, Store Admin, own store only) and uses the
/// existing stock rules: out of stock = quantity 0, low = quantity ≤ minimum.
///
/// The report's "Create purchase request" needs purchase orders (SR26–SR29),
/// which are not built yet, so the button is shown disabled with the reason.
class StockAlertsPage extends StatefulWidget {
  const StockAlertsPage({
    super.key,
    required this.api,
    required this.onUpdateStock,
  });

  final ReportApiClient api;

  /// Opens the product form to change the product's quantity.
  final ValueChanged<String> onUpdateStock;

  @override
  State<StockAlertsPage> createState() => _StockAlertsPageState();
}

enum _AlertFilter { all, out, low }

class _StockAlertsPageState extends State<StockAlertsPage> {
  InventoryReport? _data;
  Object? _error;
  _AlertFilter _filter = _AlertFilter.all;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _data = null;
      _error = null;
    });
    try {
      final data = await widget.api.inventory();
      if (mounted) setState(() => _data = data);
    } catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  void _toggle(_AlertFilter f) =>
      setState(() => _filter = _filter == f ? _AlertFilter.all : f);

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final data = _data;
    return LayoutBuilder(
      builder: (context, c) {
        final pad = c.maxWidth < 600 ? AppSpacing.md : AppSpacing.xl;
        return SingleChildScrollView(
          key: const Key('stockAlertsPage'),
          padding: EdgeInsets.all(pad),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _header(s, c.maxWidth),
              const SizedBox(height: AppSpacing.xl),
              if (data == null)
                ReportLoadState(error: _error, onRetry: _load)
              else ...[
                _cards(s, data),
                const SizedBox(height: AppSpacing.lg),
                _table(s, data),
                const SizedBox(height: AppSpacing.lg),
                _purchaseButton(s),
              ],
            ],
          ),
        );
      },
    );
  }

  Widget _header(AppStrings s, double width) {
    final title = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          s.alertsTitle,
          style: const TextStyle(fontSize: 26, fontWeight: FontWeight.w800),
        ),
        const SizedBox(height: 2),
        Text(
          s.alertsDescription,
          style: const TextStyle(color: AppColors.textSecondary),
        ),
      ],
    );
    final tools = Wrap(
      spacing: AppSpacing.sm,
      runSpacing: AppSpacing.sm,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          decoration: BoxDecoration(
            color: AppColors.surface,
            borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
            border: Border.all(color: AppColors.border),
          ),
          child: Text(
            MaterialLocalizations.of(context).formatFullDate(ReportClock.now()),
            style: const TextStyle(fontWeight: FontWeight.w600),
          ),
        ),
        IconButton.outlined(
          key: const Key('alertsRefresh'),
          tooltip: s.refresh,
          onPressed: _load,
          icon: const Icon(Icons.refresh_rounded),
        ),
      ],
    );
    return width < 640
        ? Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              title,
              const SizedBox(height: AppSpacing.md),
              tools,
            ],
          )
        : Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(child: title),
              const SizedBox(width: AppSpacing.lg),
              tools,
            ],
          );
  }

  /// Order of Figure 3.5 (from the start side): total, out of stock, low stock.
  Widget _cards(AppStrings s, InventoryReport d) => ReportGrid(
    minItemWidth: 220,
    maxPerRow: 3,
    children: [
      StatCard(
        key: const Key('alertsTotal'),
        title: s.alertsTotal,
        value: Formatters.integer(d.lowStock + d.outOfStock),
        subtitle: s.alertsTotalHint,
        icon: Icons.notifications_active_outlined,
        color: AppColors.info,
        softColor: AppColors.infoSoft,
        selected: _filter == _AlertFilter.all,
        onTap: () => setState(() => _filter = _AlertFilter.all),
      ),
      StatCard(
        key: const Key('alertsOut'),
        title: s.alertsOut,
        value: Formatters.integer(d.outOfStock),
        subtitle: s.alertsOutHint,
        icon: Icons.remove_shopping_cart_outlined,
        color: AppColors.danger,
        softColor: AppColors.dangerSoft,
        selected: _filter == _AlertFilter.out,
        onTap: () => _toggle(_AlertFilter.out),
      ),
      StatCard(
        key: const Key('alertsLow'),
        title: s.alertsLow,
        value: Formatters.integer(d.lowStock),
        subtitle: s.alertsLowHint,
        icon: Icons.warning_amber_rounded,
        color: AppColors.warning,
        softColor: AppColors.warningSoft,
        selected: _filter == _AlertFilter.low,
        onTap: () => _toggle(_AlertFilter.low),
      ),
    ],
  );

  String _category(AppStrings s, String? api) {
    for (final c in ProductCategory.values) {
      if (c.name == api) return c.label(arabic: s.isArabic);
    }
    return api ?? '';
  }

  Widget _table(AppStrings s, InventoryReport d) {
    final store = ProductStoreScope.maybeOf(context);
    final items = d.reorder
        .where(
          (r) => switch (_filter) {
            _AlertFilter.all => true,
            _AlertFilter.out => r.outOfStock,
            _AlertFilter.low => !r.outOfStock,
          },
        )
        .toList();
    const head = TextStyle(
      fontWeight: FontWeight.w700,
      color: AppColors.textSecondary,
      fontSize: 12.5,
    );

    Widget productCell(ReorderItem r) => Row(
      children: [
        ProductThumbnail(
          imageBytes: store?.findById('${r.productId}')?.imageBytes,
          size: 40,
        ),
        const SizedBox(width: AppSpacing.md),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                r.name,
                style: const TextStyle(fontWeight: FontWeight.w700),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              if (r.supplierName != null)
                Text(
                  r.supplierName!,
                  style: const TextStyle(
                    color: AppColors.textSecondary,
                    fontSize: 12,
                  ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
            ],
          ),
        ),
      ],
    );
    Widget badge(ReorderItem r) => StockStatusBadge(
      status: r.outOfStock ? StockStatus.outOfStock : StockStatus.lowStock,
    );
    Widget action(ReorderItem r) => IconButton(
      key: Key('restock-${r.productId}'),
      tooltip: s.updateStock,
      onPressed: () => widget.onUpdateStock('${r.productId}'),
      style: IconButton.styleFrom(
        backgroundColor: AppColors.primarySoft,
        foregroundColor: AppColors.primary,
      ),
      icon: const Icon(Icons.edit_note_rounded),
    );
    Widget qty(ReorderItem r) => Text(
      Formatters.integer(r.quantity),
      key: Key('alertQty-${r.productId}'),
      style: TextStyle(
        fontWeight: FontWeight.w800,
        color: r.outOfStock ? AppColors.danger : AppColors.warning,
      ),
    );

    final Widget body;
    if (d.reorder.isEmpty) {
      body = CardMessage(
        icon: Icons.check_circle_outline_rounded,
        text: s.noAlerts,
      );
    } else {
      body = LayoutBuilder(
        builder: (context, c) {
          if (c.maxWidth < 720) {
            // Narrow screens: one card per product.
            return Column(
              children: [
                for (final r in items)
                  Container(
                    key: Key('alert-${r.productId}'),
                    padding: const EdgeInsets.symmetric(vertical: 10),
                    decoration: const BoxDecoration(
                      border: Border(
                        bottom: BorderSide(color: AppColors.divider),
                      ),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Row(
                          children: [
                            Expanded(child: productCell(r)),
                            action(r),
                          ],
                        ),
                        const SizedBox(height: AppSpacing.sm),
                        Wrap(
                          spacing: AppSpacing.md,
                          runSpacing: AppSpacing.xs,
                          crossAxisAlignment: WrapCrossAlignment.center,
                          children: [
                            badge(r),
                            Text(_category(s, r.category)),
                            Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [Text('${s.colCurrent}: '), qty(r)],
                            ),
                            Text('${s.colAlertLevel}: ${r.minStock}'),
                          ],
                        ),
                      ],
                    ),
                  ),
              ],
            );
          }
          return Table(
            columnWidths: const {
              0: FlexColumnWidth(4),
              1: FlexColumnWidth(2.4),
              2: FlexColumnWidth(1.6),
              3: FlexColumnWidth(1.6),
              4: FlexColumnWidth(2),
              5: FlexColumnWidth(1.3),
            },
            defaultVerticalAlignment: TableCellVerticalAlignment.middle,
            children: [
              TableRow(
                decoration: BoxDecoration(
                  color: AppColors.background,
                  borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
                ),
                children: [
                  for (final h in [
                    s.colProduct,
                    s.colCategory,
                    s.colCurrent,
                    s.colAlertLevel,
                    s.colStatus,
                    s.colActions,
                  ])
                    Padding(
                      padding: const EdgeInsets.all(10),
                      child: Text(h, style: head),
                    ),
                ],
              ),
              for (final r in items)
                TableRow(
                  decoration: const BoxDecoration(
                    border: Border(
                      bottom: BorderSide(color: AppColors.divider),
                    ),
                  ),
                  children: [
                    Padding(
                      key: Key('alert-${r.productId}'),
                      padding: const EdgeInsets.all(10),
                      child: productCell(r),
                    ),
                    Padding(
                      padding: const EdgeInsets.all(10),
                      child: Text(_category(s, r.category)),
                    ),
                    Padding(padding: const EdgeInsets.all(10), child: qty(r)),
                    Padding(
                      padding: const EdgeInsets.all(10),
                      child: Text(Formatters.integer(r.minStock)),
                    ),
                    Padding(
                      padding: const EdgeInsets.all(10),
                      child: Align(
                        alignment: AlignmentDirectional.centerStart,
                        child: badge(r),
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.all(6),
                      child: Align(
                        alignment: AlignmentDirectional.centerStart,
                        child: action(r),
                      ),
                    ),
                  ],
                ),
            ],
          );
        },
      );
    }

    return ReportCard(
      key: const Key('alertsTable'),
      title: s.alertsTitle,
      trailing: _filter == _AlertFilter.all || d.reorder.isEmpty
          ? null
          : TextButton(
              key: const Key('alertsShowAll'),
              onPressed: () => setState(() => _filter = _AlertFilter.all),
              child: Text(s.alertsShowing(items.length, d.reorder.length)),
            ),
      child: body,
    );
  }

  /// Figure 3.5's "Create purchase request". Purchase orders (SR26–SR29) are
  /// not built yet, so it is disabled and says why – never a fake action.
  Widget _purchaseButton(AppStrings s) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      FilledButton.icon(
        key: const Key('createPurchaseRequest'),
        onPressed: null,
        style: FilledButton.styleFrom(
          padding: const EdgeInsets.symmetric(vertical: 16),
        ),
        icon: const Icon(Icons.shopping_cart_checkout_rounded),
        label: Text(s.createPurchaseRequest),
      ),
      const SizedBox(height: AppSpacing.sm),
      Row(
        key: const Key('purchaseNotAvailable'),
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(
            Icons.info_outline_rounded,
            size: 18,
            color: AppColors.textSecondary,
          ),
          const SizedBox(width: AppSpacing.xs),
          Expanded(
            child: Text(
              s.purchaseNotAvailable,
              style: const TextStyle(
                color: AppColors.textSecondary,
                fontSize: 12.5,
              ),
            ),
          ),
        ],
      ),
    ],
  );
}

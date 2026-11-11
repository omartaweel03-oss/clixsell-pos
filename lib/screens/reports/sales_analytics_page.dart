import 'package:flutter/material.dart';

import '../../l10n/app_strings.dart';
import '../../models/report.dart';
import '../../services/report_api_client.dart';
import '../../state/product_store.dart';
import '../../theme/app_theme.dart';
import '../../utils/formatters.dart';
import '../../widgets/product_thumbnail.dart';
import '../../widgets/sales_charts.dart';
import 'report_widgets.dart';

/// Sales Analytics screen – report Figure 3.7 (page 36), UR-M7 / SR20.
///
/// Daily sales of the selected month (line chart), monthly sales of the
/// selected year (bar chart), best-selling products of the month (donut) and
/// the products near their reorder level. All figures come from
/// `/api/reports/...` (Store Admin, own store).
class SalesAnalyticsPage extends StatefulWidget {
  const SalesAnalyticsPage({super.key, required this.api});

  final ReportApiClient api;

  @override
  State<SalesAnalyticsPage> createState() => _SalesAnalyticsPageState();
}

class _AnalyticsData {
  const _AnalyticsData(this.daily, this.monthly, this.top, this.inventory);

  final List<SalesPoint> daily;
  final List<SalesPoint> monthly;
  final TopProducts top;
  final InventoryReport inventory;
}

class _SalesAnalyticsPageState extends State<SalesAnalyticsPage> {
  late int _month = ReportClock.now().month;
  late int _year = ReportClock.now().year;
  _AnalyticsData? _data;
  Object? _error;
  int _request = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final id = ++_request;
    setState(() => _error = null);
    final period = ReportPeriod.month(_year, _month);
    try {
      final results = await Future.wait<Object>([
        widget.api.dailySales(period),
        widget.api.monthlySales(_year),
        widget.api.topProducts(period, limit: 7),
        widget.api.inventory(),
      ]);
      if (!mounted || id != _request) return; // a newer period was chosen
      setState(
        () => _data = _AnalyticsData(
          results[0] as List<SalesPoint>,
          results[1] as List<SalesPoint>,
          results[2] as TopProducts,
          results[3] as InventoryReport,
        ),
      );
    } catch (e) {
      if (!mounted || id != _request) return;
      setState(() {
        _data = null;
        _error = e;
      });
    }
  }

  void _setPeriod({int? month, int? year}) {
    setState(() {
      _month = month ?? _month;
      _year = year ?? _year;
      _data = null;
    });
    _load();
  }

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final data = _data;
    return LayoutBuilder(
      builder: (context, c) {
        final pad = c.maxWidth < 600 ? AppSpacing.md : AppSpacing.xl;
        return SingleChildScrollView(
          key: const Key('analyticsPage'),
          padding: EdgeInsets.all(pad),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              ReportHeader(
                title: s.analyticsTitle,
                description: s.analyticsDescription,
                month: _month,
                year: _year,
                onMonth: (m) => _setPeriod(month: m),
                onYear: (y) => _setPeriod(year: y),
              ),
              const SizedBox(height: AppSpacing.xl),
              if (data == null)
                ReportLoadState(error: _error, onRetry: _load)
              else
                ReportGrid(
                  minItemWidth: 420,
                  children: [
                    _dailyCard(s, data),
                    _monthlyCard(s, data),
                    _bestSellingCard(s, data),
                    _reorderCard(s, data),
                  ],
                ),
            ],
          ),
        );
      },
    );
  }

  Widget _dailyCard(AppStrings s, _AnalyticsData d) {
    final values = [for (final p in d.daily) p.netSales];
    final empty = d.daily.every((p) => p.grossSales == 0 && p.refunds == 0);
    return ReportCard(
      key: const Key('dailySalesCard'),
      title: '${s.dailySales} · ${s.monthNames[_month - 1]} $_year',
      height: 320,
      child: Stack(
        children: [
          Positioned.fill(
            child: SalesLineChart(
              key: const Key('dailyChart'),
              values: values,
              labels: [for (final p in d.daily) p.label],
              semanticLabel: s.dailySales,
            ),
          ),
          if (empty)
            Center(
              child: Container(
                key: const Key('dailyEmpty'),
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 6,
                ),
                decoration: BoxDecoration(
                  color: AppColors.surface,
                  borderRadius: BorderRadius.circular(20),
                  border: Border.all(color: AppColors.border),
                ),
                child: Text(
                  s.noSalesPeriod,
                  style: const TextStyle(color: AppColors.textSecondary),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _monthlyCard(AppStrings s, _AnalyticsData d) => ReportCard(
    key: const Key('monthlySalesCard'),
    title: '${s.monthlySales} · $_year',
    height: 320,
    child: SalesBarChart(
      key: const Key('monthlyChart'),
      values: [for (final p in d.monthly) p.netSales],
      labels: [for (final p in d.monthly) s.monthShort[int.parse(p.label) - 1]],
      highlight: _month - 1,
      semanticLabel: s.monthlySales,
    ),
  );

  Widget _bestSellingCard(AppStrings s, _AnalyticsData d) {
    final top = d.top;
    final values = [
      for (final p in top.products) p.salesValue,
      if (top.othersValue > 0) top.othersValue,
    ];
    final names = [
      for (final p in top.products) p.name,
      if (top.othersValue > 0) s.othersLabel,
    ];
    final shares = [
      for (final p in top.products) p.share,
      if (top.othersValue > 0 && top.totalSalesValue > 0)
        top.othersValue * 100 / top.totalSalesValue,
    ];
    return ReportCard(
      key: const Key('bestSellingCard'),
      title: s.bestSelling,
      height: 320,
      child: top.products.isEmpty
          ? CardMessage(icon: Icons.donut_large_rounded, text: s.noSalesPeriod)
          : Row(
              children: [
                Expanded(
                  flex: 5,
                  child: SalesDonutChart(
                    key: const Key('bestDonut'),
                    values: values,
                    semanticLabel: s.bestSelling,
                  ),
                ),
                const SizedBox(width: AppSpacing.md),
                Expanded(
                  flex: 6,
                  child: ListView(
                    children: [
                      for (var i = 0; i < names.length; i++)
                        Padding(
                          key: Key('best-$i'),
                          padding: const EdgeInsets.symmetric(vertical: 4),
                          child: Row(
                            children: [
                              Container(
                                width: 12,
                                height: 12,
                                decoration: BoxDecoration(
                                  color:
                                      chartPalette[i < top.products.length
                                          ? i
                                          : chartPalette.length - 1],
                                  shape: BoxShape.circle,
                                ),
                              ),
                              const SizedBox(width: AppSpacing.sm),
                              Expanded(
                                child: Text(
                                  names[i],
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ),
                              Text(
                                '${Formatters.number(shares[i], decimals: 0)}%',
                                key: Key('bestShare-$i'),
                                style: const TextStyle(
                                  fontWeight: FontWeight.w700,
                                ),
                              ),
                            ],
                          ),
                        ),
                      const SizedBox(height: AppSpacing.xs),
                      Text(
                        s.salesValueNote,
                        style: const TextStyle(
                          color: AppColors.textMuted,
                          fontSize: 11,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
    );
  }

  Widget _reorderCard(AppStrings s, _AnalyticsData d) {
    final store = ProductStoreScope.maybeOf(context);
    final items = d.inventory.reorder;
    return ReportCard(
      key: const Key('reorderCard'),
      title: s.nearReorder,
      height: 320,
      child: items.isEmpty
          ? CardMessage(
              icon: Icons.check_circle_outline_rounded,
              text: s.noReorder,
            )
          : ListView.separated(
              itemCount: items.length,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (context, i) {
                final r = items[i];
                final image = store?.findById('${r.productId}')?.imageBytes;
                return Padding(
                  key: Key('reorder-${r.productId}'),
                  padding: const EdgeInsets.symmetric(vertical: 6),
                  child: Row(
                    children: [
                      ProductThumbnail(imageBytes: image, size: 44),
                      const SizedBox(width: AppSpacing.md),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              r.name,
                              style: const TextStyle(
                                fontWeight: FontWeight.w700,
                              ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                            Text(
                              s.stockOfMin(r.quantity, r.minStock),
                              style: const TextStyle(
                                color: AppColors.textSecondary,
                                fontSize: 12,
                              ),
                            ),
                          ],
                        ),
                      ),
                      Tooltip(
                        message: r.outOfStock
                            ? s.outOfStockShort
                            : s.lowStockShort,
                        child: Icon(
                          r.outOfStock
                              ? Icons.highlight_off_rounded
                              : Icons.warning_amber_rounded,
                          key: Key(
                            r.outOfStock
                                ? 'outIcon-${r.productId}'
                                : 'lowIcon-${r.productId}',
                          ),
                          color: r.outOfStock
                              ? AppColors.danger
                              : AppColors.warning,
                        ),
                      ),
                    ],
                  ),
                );
              },
            ),
    );
  }
}

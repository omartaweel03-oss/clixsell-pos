import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../l10n/app_strings.dart';
import '../../models/product_category.dart';
import '../../models/report.dart';
import '../../services/report_api_client.dart';
import '../../state/product_store.dart';
import '../../theme/app_theme.dart';
import '../../utils/formatters.dart';
import '../../widgets/product_thumbnail.dart';
import '../../widgets/sales_charts.dart';
import 'report_widgets.dart';

/// Reports screen – report Figure 3.8 (page 37), UR-M7 / UR-M8, SR20 / SR21.
///
/// Cards: total sales, total (stock) value, invoices, profit; the daily sales
/// chart; the featured (best-performing) products; and the inventory report.
/// "Generate report" shows the whole report as text that can be copied.
/// All figures come from `/api/reports/...` (Store Admin, own store).
class ReportsPage extends StatefulWidget {
  const ReportsPage({super.key, required this.api});

  final ReportApiClient api;

  @override
  State<ReportsPage> createState() => _ReportsPageState();
}

enum ReportScope { month, year }

class _ReportsData {
  const _ReportsData(this.summary, this.chart, this.top, this.inventory);

  final SalesSummary summary;
  final List<SalesPoint> chart;
  final TopProducts top;
  final InventoryReport inventory;
}

class _ReportsPageState extends State<ReportsPage> {
  late int _month = ReportClock.now().month;
  late int _year = ReportClock.now().year;
  ReportScope _scope = ReportScope.month;
  _ReportsData? _data;
  Object? _error;
  int _request = 0;

  ReportPeriod get _period => _scope == ReportScope.month
      ? ReportPeriod.month(_year, _month)
      : ReportPeriod.year(_year);

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final id = ++_request;
    setState(() => _error = null);
    final period = _period;
    try {
      final results = await Future.wait<Object>([
        widget.api.summary(period),
        _scope == ReportScope.month
            ? widget.api.dailySales(period)
            : widget.api.monthlySales(_year),
        widget.api.topProducts(period, limit: 5),
        widget.api.inventory(),
      ]);
      if (!mounted || id != _request) return;
      setState(
        () => _data = _ReportsData(
          results[0] as SalesSummary,
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

  void _set({int? month, int? year, ReportScope? scope}) {
    setState(() {
      _month = month ?? _month;
      _year = year ?? _year;
      _scope = scope ?? _scope;
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
          key: const Key('reportsPage'),
          padding: EdgeInsets.all(pad),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              ReportHeader(
                title: s.reportsTitle,
                description: s.reportsDescription,
                month: _month,
                year: _year,
                monthEnabled: _scope == ReportScope.month,
                onMonth: (m) => _set(month: m),
                onYear: (y) => _set(year: y),
              ),
              const SizedBox(height: AppSpacing.xl),
              if (data == null)
                ReportLoadState(error: _error, onRetry: _load)
              else ...[
                _cards(s, data),
                const SizedBox(height: AppSpacing.md),
                _actions(s, data),
                const SizedBox(height: AppSpacing.lg),
                ReportGrid(
                  minItemWidth: 420,
                  children: [_chartCard(s, data), _featuredCard(s, data)],
                ),
                const SizedBox(height: AppSpacing.lg),
                _inventoryCard(s, data.inventory),
              ],
            ],
          ),
        );
      },
    );
  }

  // ── Summary cards (Figure 3.8, top row) ───────────────────────────────────

  Widget _cards(AppStrings s, _ReportsData d) {
    final sum = d.summary;
    final vs = _scope == ReportScope.month ? s.vsLastMonth : s.vsLastYear;
    String vsText(num current, num previous) =>
        SalesSummary.change(current, previous) == null ? s.noPreviousData : vs;
    return ReportGrid(
      minItemWidth: 240,
      maxPerRow: 4,
      children: [
        _SummaryCard(
          key: const Key('cardSales'),
          title: s.cardTotalSales,
          value: Formatters.currency(sum.netSales),
          icon: Icons.receipt_long_rounded,
          color: AppColors.danger,
          soft: AppColors.dangerSoft,
          footer: ChangeLine(
            percent: SalesSummary.change(sum.netSales, sum.previousNetSales),
            suffix: vsText(sum.netSales, sum.previousNetSales),
          ),
          note: sum.refunds > 0
              ? s.returnsHint(Formatters.currency(sum.refunds))
              : null,
        ),
        _SummaryCard(
          key: const Key('cardValue'),
          title: s.cardInventoryValue,
          value: Formatters.currency(d.inventory.inventoryValue),
          icon: Icons.request_quote_outlined,
          color: AppColors.success,
          soft: AppColors.successSoft,
          footer: Text(
            s.inventoryValueHint(d.inventory.totalProducts),
            style: const TextStyle(
              color: AppColors.textSecondary,
              fontSize: 12,
            ),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
        ),
        _SummaryCard(
          key: const Key('cardInvoices'),
          title: s.cardInvoices,
          value: Formatters.integer(sum.invoiceCount),
          icon: Icons.description_outlined,
          color: AppColors.primary,
          soft: AppColors.primarySoft,
          footer: ChangeLine(
            percent: SalesSummary.change(
              sum.invoiceCount,
              sum.previousInvoiceCount,
            ),
            suffix: vsText(sum.invoiceCount, sum.previousInvoiceCount),
          ),
        ),
        _SummaryCard(
          key: const Key('cardProfit'),
          title: s.cardProfit,
          value: sum.profitAvailable && sum.profit != null
              ? Formatters.currency(sum.profit!)
              : s.profitUnavailable,
          icon: Icons.savings_outlined,
          color: sum.profitAvailable ? AppColors.success : AppColors.textMuted,
          soft: sum.profitAvailable ? AppColors.successSoft : AppColors.divider,
          footer: Text(
            sum.profitAvailable ? vs : s.profitNeedsCost,
            key: const Key('profitNote'),
            style: const TextStyle(
              color: AppColors.textSecondary,
              fontSize: 12,
            ),
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
          ),
        ),
      ],
    );
  }

  Widget _actions(AppStrings s, _ReportsData d) => Wrap(
    spacing: AppSpacing.sm,
    runSpacing: AppSpacing.sm,
    crossAxisAlignment: WrapCrossAlignment.center,
    children: [
      SizedBox(
        width: 170,
        child: DropdownButtonFormField<ReportScope>(
          key: ValueKey('scopeSelect-$_scope'),
          initialValue: _scope,
          isDense: true,
          isExpanded: true,
          decoration: const InputDecoration(
            contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          ),
          items: [
            DropdownMenuItem(
              value: ReportScope.month,
              child: Text(s.scopeMonth),
            ),
            DropdownMenuItem(value: ReportScope.year, child: Text(s.scopeYear)),
          ],
          onChanged: (v) => v == null ? null : _set(scope: v),
        ),
      ),
      FilledButton.icon(
        key: const Key('generateReportButton'),
        onPressed: () => _showReport(s, d),
        icon: const Icon(Icons.description_rounded, size: 18),
        label: Text(s.generateReport),
      ),
    ],
  );

  // ── Daily (or monthly) chart and featured products ─────────────────────────

  Widget _chartCard(AppStrings s, _ReportsData d) {
    final month = _scope == ReportScope.month;
    final empty = d.chart.every((p) => p.grossSales == 0 && p.refunds == 0);
    final values = [for (final p in d.chart) p.netSales];
    return ReportCard(
      key: const Key('reportChartCard'),
      title: month ? s.dailySales : '${s.monthlySales} · $_year',
      height: 340,
      child: Stack(
        children: [
          Positioned.fill(
            child: month
                ? SalesLineChart(
                    key: const Key('reportDailyChart'),
                    values: values,
                    labels: [for (final p in d.chart) p.label],
                    semanticLabel: s.dailySales,
                  )
                : SalesBarChart(
                    key: const Key('reportMonthlyChart'),
                    values: values,
                    labels: [
                      for (final p in d.chart)
                        s.monthShort[int.parse(p.label) - 1],
                    ],
                    semanticLabel: s.monthlySales,
                  ),
          ),
          if (empty)
            Center(
              child: Text(
                s.noSalesPeriod,
                key: const Key('reportChartEmpty'),
                style: const TextStyle(color: AppColors.textSecondary),
              ),
            ),
        ],
      ),
    );
  }

  Widget _featuredCard(AppStrings s, _ReportsData d) {
    final store = ProductStoreScope.maybeOf(context);
    final products = d.top.products;
    return ReportCard(
      key: const Key('featuredCard'),
      title: s.featuredProducts,
      height: 340,
      child: products.isEmpty
          ? CardMessage(icon: Icons.star_outline_rounded, text: s.noSalesPeriod)
          : ListView.separated(
              itemCount: products.length + 1,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (context, i) {
                if (i == products.length) {
                  return Padding(
                    padding: const EdgeInsets.only(top: AppSpacing.sm),
                    child: Text(
                      s.salesValueNote,
                      style: const TextStyle(
                        color: AppColors.textMuted,
                        fontSize: 11,
                      ),
                    ),
                  );
                }
                final p = products[i];
                final image = p.productId == null
                    ? null
                    : store?.findById('${p.productId}')?.imageBytes;
                return Padding(
                  key: Key('featured-$i'),
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  child: Row(
                    children: [
                      ProductThumbnail(imageBytes: image, size: 44),
                      const SizedBox(width: AppSpacing.md),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              p.name,
                              style: const TextStyle(
                                fontWeight: FontWeight.w700,
                              ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                            Text(
                              s.unitsSold(p.units),
                              style: const TextStyle(
                                color: AppColors.textSecondary,
                                fontSize: 12,
                              ),
                            ),
                          ],
                        ),
                      ),
                      Text(
                        Formatters.currency(p.salesValue),
                        key: Key('featuredValue-$i'),
                        style: const TextStyle(
                          fontWeight: FontWeight.w800,
                          fontSize: 15,
                        ),
                      ),
                    ],
                  ),
                );
              },
            ),
    );
  }

  // ── Inventory report (UR-M8 / SR21) ───────────────────────────────────────

  Widget _inventoryCard(AppStrings s, InventoryReport inv) {
    Widget stat(String key, String label, String value, Color color) =>
        Container(
          key: Key(key),
          constraints: const BoxConstraints(minWidth: 130),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          decoration: BoxDecoration(
            color: color.withValues(alpha: 0.08),
            borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                label,
                style: const TextStyle(
                  color: AppColors.textSecondary,
                  fontSize: 12,
                ),
              ),
              const SizedBox(height: 2),
              Text(
                value,
                style: TextStyle(
                  color: color,
                  fontWeight: FontWeight.w800,
                  fontSize: 17,
                ),
              ),
            ],
          ),
        );
    String categoryLabel(String api) {
      for (final c in ProductCategory.values) {
        if (c.name == api) return c.label(arabic: s.isArabic);
      }
      return api;
    }

    const head = TextStyle(
      fontWeight: FontWeight.w700,
      color: AppColors.textSecondary,
      fontSize: 12,
    );
    return ReportCard(
      key: const Key('inventoryReportCard'),
      title: s.inventoryReport,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Wrap(
            spacing: AppSpacing.sm,
            runSpacing: AppSpacing.sm,
            children: [
              stat(
                'invTotal',
                s.invProducts,
                Formatters.integer(inv.totalProducts),
                AppColors.primary,
              ),
              stat(
                'invUnits',
                s.invUnits,
                Formatters.integer(inv.totalUnits),
                AppColors.info,
              ),
              stat(
                'invValue',
                s.invValue,
                Formatters.currency(inv.inventoryValue),
                AppColors.success,
              ),
              stat(
                'invIn',
                s.invInStock,
                Formatters.integer(inv.inStock),
                AppColors.success,
              ),
              stat(
                'invLow',
                s.invLow,
                Formatters.integer(inv.lowStock),
                AppColors.warning,
              ),
              stat(
                'invOut',
                s.invOut,
                Formatters.integer(inv.outOfStock),
                AppColors.danger,
              ),
            ],
          ),
          if (inv.categories.isNotEmpty) ...[
            const SizedBox(height: AppSpacing.lg),
            Table(
              columnWidths: const {
                0: FlexColumnWidth(3),
                1: FlexColumnWidth(2),
                2: FlexColumnWidth(2),
                3: FlexColumnWidth(3),
              },
              children: [
                TableRow(
                  decoration: const BoxDecoration(color: AppColors.background),
                  children: [
                    for (final h in [
                      s.categoryColumn,
                      s.productsColumn,
                      s.unitsColumn,
                      s.valueColumn,
                    ])
                      Padding(
                        padding: const EdgeInsets.all(8),
                        child: Text(h, style: head),
                      ),
                  ],
                ),
                for (final c in inv.categories)
                  TableRow(
                    decoration: const BoxDecoration(
                      border: Border(
                        bottom: BorderSide(color: AppColors.divider),
                      ),
                    ),
                    children: [
                      for (final (i, v) in [
                        categoryLabel(c.category),
                        Formatters.integer(c.products),
                        Formatters.integer(c.units),
                        Formatters.currency(c.value),
                      ].indexed)
                        Padding(
                          key: i == 0
                              ? ValueKey('invCategory-${c.category}')
                              : null,
                          padding: const EdgeInsets.all(8),
                          child: Text(v),
                        ),
                    ],
                  ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  // ── "Generate report" ─────────────────────────────────────────────────────

  String _reportText(AppStrings s, _ReportsData d) {
    final sum = d.summary;
    final b = StringBuffer()
      ..writeln('${s.appName} – ${s.reportDialogTitle}')
      ..writeln(
        '${s.rPeriod}: ${ReportPeriod.iso(sum.from)} → ${ReportPeriod.iso(sum.to)}',
      )
      ..writeln()
      ..writeln('${s.rGross}: ${Formatters.currency(sum.grossSales)}')
      ..writeln('${s.rDiscounts}: ${Formatters.currency(sum.discounts)}')
      ..writeln('${s.rTax}: ${Formatters.currency(sum.tax)}')
      ..writeln('${s.rRefunds}: ${Formatters.currency(sum.refunds)}')
      ..writeln('${s.rNet}: ${Formatters.currency(sum.netSales)}')
      ..writeln('${s.rInvoices}: ${sum.invoiceCount}')
      ..writeln('${s.rAverage}: ${Formatters.currency(sum.averageInvoice)}')
      ..writeln('${s.rReturns}: ${sum.returnCount}')
      ..writeln('${s.rUnits}: ${sum.unitsSold} / ${sum.unitsReturned}')
      ..writeln(
        '${s.rProfit}: ${sum.profitAvailable && sum.profit != null ? Formatters.currency(sum.profit!) : '${s.profitUnavailable} (${s.profitNeedsCost})'}',
      )
      ..writeln()
      ..writeln('${s.rTop}:');
    for (final p in d.top.products) {
      b.writeln(
        '  • ${p.name}: ${s.unitsSold(p.units)} · ${Formatters.currency(p.salesValue)}',
      );
    }
    if (d.top.products.isEmpty) b.writeln('  ${s.noSalesPeriod}');
    final inv = d.inventory;
    b
      ..writeln()
      ..writeln('${s.inventoryReport}:')
      ..writeln(
        '  ${s.invProducts}: ${inv.totalProducts} · ${s.invUnits}: ${inv.totalUnits} · ${s.invValue}: ${Formatters.currency(inv.inventoryValue)}',
      )
      ..writeln(
        '  ${s.invInStock}: ${inv.inStock} · ${s.invLow}: ${inv.lowStock} · ${s.invOut}: ${inv.outOfStock}',
      )
      ..writeln()
      ..write(s.salesValueNote);
    return b.toString();
  }

  Future<void> _showReport(AppStrings s, _ReportsData d) async {
    final text = _reportText(s, d);
    bool?
    copied; // null = not tried, true = copied, false = the browser refused
    await showDialog<void>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialog) => AlertDialog(
          key: const Key('reportDialog'),
          title: Text(s.reportDialogTitle),
          content: SizedBox(
            width: 520,
            child: SingleChildScrollView(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  SelectableText(
                    text,
                    key: const Key('reportText'),
                    style: const TextStyle(height: 1.5),
                  ),
                  if (copied != null) ...[
                    const SizedBox(height: AppSpacing.md),
                    Row(
                      key: const Key('copyStatus'),
                      children: [
                        Icon(
                          copied!
                              ? Icons.check_circle_rounded
                              : Icons.info_outline_rounded,
                          size: 18,
                          color: copied!
                              ? AppColors.success
                              : AppColors.warning,
                        ),
                        const SizedBox(width: AppSpacing.xs),
                        Expanded(
                          child: Text(
                            copied! ? s.reportCopied : s.copyFailed,
                            style: TextStyle(
                              color: copied!
                                  ? AppColors.success
                                  : AppColors.warning,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ],
                ],
              ),
            ),
          ),
          actions: [
            TextButton.icon(
              key: const Key('copyReportButton'),
              onPressed: () async {
                var ok = true;
                try {
                  await Clipboard.setData(ClipboardData(text: text));
                } catch (_) {
                  ok = false; // e.g. the browser did not allow the clipboard
                }
                if (ctx.mounted) setDialog(() => copied = ok);
              },
              icon: const Icon(Icons.copy_rounded, size: 18),
              label: Text(s.copyReport),
            ),
            FilledButton(
              key: const Key('reportCloseButton'),
              onPressed: () => Navigator.of(ctx).pop(),
              child: Text(s.close),
            ),
          ],
        ),
      ),
    );
  }
}

/// One of the four cards at the top of Figure 3.8.
class _SummaryCard extends StatelessWidget {
  const _SummaryCard({
    super.key,
    required this.title,
    required this.value,
    required this.icon,
    required this.color,
    required this.soft,
    required this.footer,
    this.note,
  });

  final String title;
  final String value;
  final IconData icon;
  final Color color;
  final Color soft;
  final Widget footer;
  final String? note;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(AppSpacing.lg),
    decoration: BoxDecoration(
      color: soft.withValues(alpha: 0.6),
      borderRadius: BorderRadius.circular(AppSpacing.radiusLg),
      border: Border.all(color: color.withValues(alpha: 0.25)),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: const TextStyle(fontWeight: FontWeight.w700),
                  ),
                  const SizedBox(height: AppSpacing.sm),
                  Text(
                    value,
                    key: key == null
                        ? null
                        : Key('${(key as ValueKey<String>).value}Value'),
                    style: TextStyle(
                      color: color,
                      fontSize: 22,
                      fontWeight: FontWeight.w800,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
              ),
            ),
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
              ),
              child: Icon(icon, color: color, size: 26),
            ),
          ],
        ),
        const SizedBox(height: AppSpacing.sm),
        footer,
        if (note != null) ...[
          const SizedBox(height: 2),
          Text(
            note!,
            style: const TextStyle(color: AppColors.textMuted, fontSize: 11),
          ),
        ],
      ],
    ),
  );
}

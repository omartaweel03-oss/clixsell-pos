import 'package:flutter/material.dart';

import '../../l10n/app_strings.dart';
import '../../models/sale.dart';
import '../../services/api_http.dart';
import '../../services/sales_api_client.dart';
import '../../state/pos_cart.dart';
import '../../theme/app_theme.dart';
import '../../utils/formatters.dart';
import '../../widgets/min_width_scroll.dart';
import '../../widgets/page_header.dart';
import '../../widgets/stat_card.dart';
import '../reports/report_widgets.dart';
import 'pos_dialogs.dart';

/// Suspended Invoices screen – report Figure 3.6 (page 35), UR-C8 / UR-M10,
/// SR9 / SR23. For Store Admins and Cashiers, own store only.
///
/// Uses the existing API only: `GET /api/sales/suspended` (list) and
/// `DELETE /api/sales/suspended/{id}` (delete = the invoice is marked
/// CANCELED and kept for the record). Resume opens the invoice in the POS
/// with the existing rules (current prices; asks before replacing an open
/// invoice). The report's "Customer" column shows the cashier instead,
/// because customers are not recorded in the system.
class SuspendedInvoicesPage extends StatefulWidget {
  const SuspendedInvoicesPage({
    super.key,
    required this.api,
    required this.onResume,
  });

  final SalesApiClient api;

  /// Loads the invoice into the POS and opens it.
  final Future<void> Function(Sale sale) onResume;

  @override
  State<SuspendedInvoicesPage> createState() => _SuspendedInvoicesPageState();
}

class _SuspendedInvoicesPageState extends State<SuspendedInvoicesPage> {
  static const pageSizes = [10, 20, 50];

  List<Sale>? _all;
  Object? _error;

  /// null = all months (decision C: nothing is hidden by default).
  int? _month;
  int _year = ReportClock.now().year;
  int _pageSize = 10;
  int _page = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _all = null;
      _error = null;
    });
    try {
      final list = await widget.api.listSuspended();
      if (mounted) setState(() => _all = list);
    } catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  List<Sale> get _filtered {
    final all = _all ?? const <Sale>[];
    if (_month == null) return all;
    return all
        .where((x) => x.createdAt.year == _year && x.createdAt.month == _month)
        .toList();
  }

  Future<void> _delete(Sale sale) async {
    final s = AppStrings.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final cart = PosCartScope.read(context);
    final ok = await confirmAction(
      context,
      title: s.deleteSuspendedTitle,
      body: s.deleteSuspendedBody(sale.invoiceNumber),
      confirmLabel: s.deleteLabel,
      cancelLabel: s.keepInvoice,
      icon: Icons.delete_outline_rounded,
      color: AppColors.danger,
    );
    if (!ok || !mounted) return;
    try {
      await widget.api.cancelSuspended(sale.saleId);
      // The same invoice open in the POS is cleared (as in the POS window).
      if (cart.resumedSaleId == sale.saleId) cart.reset();
      messenger.showSnackBar(
        SnackBar(content: Text(s.invoiceCanceled(sale.invoiceNumber))),
      );
    } on ApiException catch (e) {
      messenger.showSnackBar(
        SnackBar(
          content: Text(describeApiError(e, s)),
          backgroundColor: AppColors.danger,
        ),
      );
    }
    if (mounted) await _load();
  }

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final all = _all;
    return LayoutBuilder(
      builder: (context, c) {
        final pad = c.maxWidth < 600 ? AppSpacing.md : AppSpacing.xl;
        return SingleChildScrollView(
          key: const Key('suspendedPage'),
          padding: EdgeInsets.all(pad),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _header(s),
              const SizedBox(height: AppSpacing.xl),
              if (all == null)
                ReportLoadState(error: _error, onRetry: _load)
              else ...[
                _cards(s, _filtered),
                const SizedBox(height: AppSpacing.lg),
                _table(s, _filtered),
              ],
            ],
          ),
        );
      },
    );
  }

  // ── Header: title on the start side, month / year on the end side ────────

  Widget _header(AppStrings s) {
    final now = ReportClock.now();
    final years = [for (var y = now.year; y >= now.year - 4; y--) y];
    if (!years.contains(_year)) years.add(_year);
    Widget select<T>(
      Key key,
      T value,
      List<T> values,
      String Function(T) label,
      ValueChanged<T?>? onChanged,
      double width,
    ) => SizedBox(
      width: width,
      child: DropdownButtonFormField<T>(
        key: key,
        initialValue: value,
        isDense: true,
        isExpanded: true,
        decoration: const InputDecoration(
          contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        ),
        items: [
          for (final v in values)
            DropdownMenuItem(
              value: v,
              child: Text(label(v), overflow: TextOverflow.ellipsis),
            ),
        ],
        onChanged: onChanged,
      ),
    );

    final title = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          s.navSuspended,
          style: const TextStyle(fontSize: 26, fontWeight: FontWeight.w800),
        ),
        const SizedBox(height: 2),
        Text(
          s.suspDescription,
          style: const TextStyle(color: AppColors.textSecondary),
        ),
      ],
    );
    final tools = Wrap(
      spacing: AppSpacing.sm,
      runSpacing: AppSpacing.sm,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        const Icon(
          Icons.calendar_month_rounded,
          color: AppColors.textSecondary,
        ),
        select<int?>(
          ValueKey('suspMonth-$_month'),
          _month,
          [null, for (var m = 1; m <= 12; m++) m],
          (m) => m == null ? s.allMonths : s.monthNames[m - 1],
          (m) => setState(() {
            _month = m;
            _page = 0;
          }),
          150,
        ),
        select<int>(
          ValueKey('suspYear-$_year-${_month != null}'),
          _year,
          years,
          (y) => '$y',
          _month == null
              ? null
              : (y) => setState(() {
                  _year = y ?? _year;
                  _page = 0;
                }),
          100,
        ),
        IconButton.outlined(
          key: const Key('suspRefresh'),
          tooltip: s.refresh,
          onPressed: _load,
          icon: const Icon(Icons.refresh_rounded),
        ),
      ],
    );
    return Wrap(
      alignment: WrapAlignment.spaceBetween,
      crossAxisAlignment: WrapCrossAlignment.start,
      spacing: AppSpacing.lg,
      runSpacing: AppSpacing.md,
      children: [title, tools],
    );
  }

  // ── Cards (Figure 3.6, from the start side): oldest, total value, count ──

  Widget _cards(AppStrings s, List<Sale> list) {
    Sale? oldest;
    for (final x in list) {
      if (oldest == null || x.createdAt.isBefore(oldest.createdAt)) oldest = x;
    }
    final value = list.fold<double>(0, (a, x) => a + x.total);
    return ReportGrid(
      minItemWidth: 220,
      maxPerRow: 3,
      children: [
        StatCard(
          key: const Key('suspOldest'),
          title: s.suspOldest,
          value: oldest == null
              ? s.suspNone
              : s.ageText(ReportClock.now().difference(oldest.createdAt)),
          // Always a subtitle, so the three cards stay aligned when empty.
          subtitle: oldest == null
              ? s.suspOldestNone
              : s.suspOldestHint(oldest.invoiceNumber),
          icon: Icons.schedule_rounded,
          color: AppColors.warning,
          softColor: AppColors.warningSoft,
        ),
        StatCard(
          key: const Key('suspValue'),
          title: s.suspValue,
          value: Formatters.currency(value),
          subtitle: s.suspValueHint,
          icon: Icons.account_balance_wallet_outlined,
          color: AppColors.success,
          softColor: AppColors.successSoft,
        ),
        StatCard(
          key: const Key('suspCount'),
          title: s.suspCount,
          value: Formatters.integer(list.length),
          subtitle: s.suspCountHint,
          icon: Icons.assignment_outlined,
          color: AppColors.primary,
          softColor: AppColors.primarySoft,
        ),
      ],
    );
  }

  // ── Table + pages ─────────────────────────────────────────────────────

  Widget _table(AppStrings s, List<Sale> list) {
    final pages = list.isEmpty ? 1 : (list.length / _pageSize).ceil();
    final page = _page.clamp(0, pages - 1);
    final start = page * _pageSize;
    final rows = list.skip(start).take(_pageSize).toList();
    const head = TextStyle(
      fontWeight: FontWeight.w700,
      color: AppColors.textSecondary,
      fontSize: 12.5,
    );

    Widget cell(Widget child, {Key? key}) => Padding(
      key: key,
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      child: child,
    );
    String dateText(DateTime d) => Formatters.date(d);

    final Widget body;
    if (list.isEmpty) {
      body = CardMessage(
        icon: Icons.inbox_outlined,
        text: _month == null ? s.noSuspended : s.noSuspendedInMonth,
      );
    } else {
      body = MinWidthScroll(
        minWidth: 760,
        child: Table(
          columnWidths: const {
            0: FlexColumnWidth(2),
            1: FlexColumnWidth(2.2),
            2: FlexColumnWidth(1.6),
            3: FlexColumnWidth(1.1),
            4: FlexColumnWidth(2.4),
            5: FlexColumnWidth(1.6),
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
                  s.colInvoice,
                  s.colCashier,
                  s.colAmount,
                  s.colItems,
                  s.colDate,
                  s.colActions,
                ])
                  cell(Text(h, style: head)),
              ],
            ),
            for (final x in rows)
              TableRow(
                decoration: const BoxDecoration(
                  border: Border(bottom: BorderSide(color: AppColors.divider)),
                ),
                children: [
                  cell(
                    Text(
                      x.invoiceNumber,
                      style: const TextStyle(
                        color: AppColors.primary,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    key: Key('suspRow-${x.saleId}'),
                  ),
                  cell(
                    Text(
                      x.cashierName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  cell(
                    Text(
                      Formatters.currency(x.total),
                      key: Key('suspTotal-${x.saleId}'),
                      style: const TextStyle(fontWeight: FontWeight.w700),
                    ),
                  ),
                  cell(
                    Text(
                      Formatters.integer(x.itemCount),
                      key: Key('suspItems-${x.saleId}'),
                    ),
                  ),
                  cell(Text(dateText(x.createdAt))),
                  cell(
                    Wrap(
                      spacing: AppSpacing.xs,
                      // Figure 3.6 order: delete first, resume (cart) at the far end.
                      children: [
                        IconButton(
                          key: Key('delete-${x.saleId}'),
                          tooltip: s.deleteSuspendedTip,
                          onPressed: () => _delete(x),
                          style: IconButton.styleFrom(
                            backgroundColor: AppColors.dangerSoft,
                            foregroundColor: AppColors.danger,
                          ),
                          icon: const Icon(Icons.delete_outline_rounded),
                        ),
                        IconButton(
                          key: Key('resume-${x.saleId}'),
                          tooltip: s.resumeInPos,
                          onPressed: () => widget.onResume(x),
                          style: IconButton.styleFrom(
                            backgroundColor: AppColors.infoSoft,
                            foregroundColor: AppColors.info,
                          ),
                          icon: const Icon(Icons.shopping_cart_outlined),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
          ],
        ),
      );
    }

    Widget pageButton(
      String label,
      int? target, {
      Key? key,
      bool selected = false,
      IconData? icon,
    }) => Padding(
      padding: const EdgeInsets.symmetric(horizontal: 3),
      child: SizedBox(
        width: 36,
        height: 36,
        child: selected
            ? FilledButton(
                key: key,
                onPressed: () {},
                style: FilledButton.styleFrom(padding: EdgeInsets.zero),
                child: Text(label),
              )
            : OutlinedButton(
                key: key,
                onPressed: target == null
                    ? null
                    : () => setState(() => _page = target),
                style: OutlinedButton.styleFrom(padding: EdgeInsets.zero),
                child: icon == null ? Text(label) : Icon(icon, size: 18),
              ),
      ),
    );

    const window = 5;
    final first = (page - window ~/ 2).clamp(
      0,
      (pages - window).clamp(0, pages),
    );
    final last = (first + window - 1).clamp(0, pages - 1);
    final footer = Wrap(
      alignment: WrapAlignment.spaceBetween,
      crossAxisAlignment: WrapCrossAlignment.center,
      spacing: AppSpacing.lg,
      runSpacing: AppSpacing.sm,
      children: [
        SizedBox(
          width: 150,
          child: DropdownButtonFormField<int>(
            key: ValueKey('pageSize-$_pageSize'),
            initialValue: _pageSize,
            isDense: true,
            isExpanded: true,
            decoration: const InputDecoration(
              contentPadding: EdgeInsets.symmetric(
                horizontal: 12,
                vertical: 10,
              ),
            ),
            items: [
              for (final n in pageSizes)
                DropdownMenuItem(value: n, child: Text(s.perPage(n))),
            ],
            onChanged: (n) => setState(() {
              _pageSize = n ?? _pageSize;
              _page = 0;
            }),
          ),
        ),
        Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Tooltip(
              message: s.previousPage,
              child: pageButton(
                '<',
                page > 0 ? page - 1 : null,
                key: const Key('pagePrev'),
                icon: Icons.chevron_left_rounded,
              ),
            ),
            // At most 5 page numbers, around the current page, so many pages
            // never overflow the row.
            for (var i = first; i <= last; i++)
              pageButton(
                '${i + 1}',
                i,
                key: Key('page-${i + 1}'),
                selected: i == page,
              ),
            Tooltip(
              message: s.nextPage,
              child: pageButton(
                '>',
                page < pages - 1 ? page + 1 : null,
                key: const Key('pageNext'),
                icon: Icons.chevron_right_rounded,
              ),
            ),
          ],
        ),
        Text(
          list.isEmpty
              ? s.showingInvoices(0, 0, 0)
              : s.showingInvoices(start + 1, start + rows.length, list.length),
          key: const Key('suspShowing'),
          style: const TextStyle(color: AppColors.textSecondary),
        ),
      ],
    );

    return ReportCard(
      key: const Key('suspTable'),
      title: s.navSuspended,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          body,
          const SizedBox(height: AppSpacing.sm),
          Text(
            s.cashierColumnNote,
            key: const Key('cashierColumnNote'),
            style: const TextStyle(color: AppColors.textMuted, fontSize: 12),
          ),
          const SizedBox(height: AppSpacing.md),
          footer,
        ],
      ),
    );
  }
}

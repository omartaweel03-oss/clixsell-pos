import 'package:flutter/material.dart';

import '../../l10n/app_strings.dart';
import '../../l10n/guardian_strings.dart';
import '../../models/guardian.dart';
import '../../services/api_http.dart';
import '../../services/guardian_api_client.dart';
import '../../services/sales_api_client.dart';
import '../../theme/app_theme.dart';
import '../../utils/formatters.dart';
import '../pos/pos_dialogs.dart';
import '../reports/report_widgets.dart';
import 'guardian_dialogs.dart';
import 'guardian_widgets.dart';

/// Guardian Mode (report Figure 3.1, UR-M16 / SR30, UR-M17 / SR31) – Store
/// Admin only. Every number comes from `/api/guardian/...` (rule-based
/// analysis of the store's real records); this page only displays it and
/// opens existing screens. It never changes prices, stock or discounts.
///
/// Layout of Figure 3.1 (mirrored automatically in English):
/// * four cards: today's sales, critical alerts, profit risks, store health;
/// * three columns: smart alerts + unusual activity | recommendations +
///   products most at risk | store map + profit protection.
class GuardianPage extends StatefulWidget {
  const GuardianPage({
    super.key,
    required this.api,
    required this.salesApi,
    required this.onOpenProduct,
    required this.onOpenStockAlerts,
  });

  final GuardianApiClient api;
  final SalesApiClient salesApi;

  /// Opens Product Details; completes with false when the product no longer exists.
  final Future<bool> Function(int productId) onOpenProduct;
  final VoidCallback onOpenStockAlerts;

  @override
  State<GuardianPage> createState() => _GuardianPageState();
}

class _GuardianPageState extends State<GuardianPage> {
  late int _month = ReportClock.now().month;
  late int _year = ReportClock.now().year;
  GuardianDashboard? _data;
  Object? _error;
  bool _busy = false;
  int _request = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final id = ++_request;
    setState(() => _error = null);
    try {
      final d = await widget.api.dashboard(_year, _month);
      if (!mounted || id != _request) return;
      setState(() => _data = d);
    } catch (e) {
      if (!mounted || id != _request) return;
      setState(() {
        _data = null;
        _error = e;
      });
    }
  }

  void _setPeriod({int? month, int? year}) {
    final now = ReportClock.now();
    var m = month ?? _month;
    final y = year ?? _year;
    // A future month is refused by the server: use the current month instead.
    if (y == now.year && m > now.month) m = now.month;
    setState(() {
      _month = m;
      _year = y;
      _data = null;
    });
    _load();
  }

  Future<void> _toggle(bool on) async {
    final g = GuardianStrings.of(context);
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        key: Key(on ? 'guardianEnableDialog' : 'guardianDisableDialog'),
        title: Text(on ? g.enableConfirmTitle : g.disableConfirmTitle),
        content: Text(on ? g.enableConfirmBody : g.disableConfirmBody),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: Text(g.cancel),
          ),
          FilledButton(
            key: const Key('guardianConfirmToggle'),
            style: on
                ? null
                : FilledButton.styleFrom(backgroundColor: AppColors.danger),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: Text(on ? g.turnOn : g.turnOff),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    setState(() => _busy = true);
    try {
      await widget.api.setEnabled(on);
      if (!mounted) return;
      setState(() => _data = null);
      await _load();
    } catch (e) {
      if (!mounted) return;
      _snack(e is ApiException ? e.message : '$e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _snack(String text) => ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(text)));

  Future<void> _openProduct(int id) async {
    final gone = GuardianStrings.of(context).productGone;
    try {
      if (!await widget.onOpenProduct(id) && mounted) _snack(gone);
    } catch (e) {
      if (mounted) _snack(e is ApiException ? e.message : '$e');
    }
  }

  Future<void> _act(GuardianAction a) async {
    switch (a.type) {
      case 'INVOICE':
        if (a.id == null) return;
        try {
          final sale = await widget.salesApi.getSale(a.id!);
          if (!mounted) return;
          await showDialog<void>(
            context: context,
            builder: (_) => ReceiptDialog(sale: sale),
          );
        } catch (e) {
          if (mounted) _snack(e is ApiException ? e.message : '$e');
        }
      case 'PRODUCT':
        if (a.id != null) await _openProduct(a.id!);
      case 'STOCK_ALERTS':
        widget.onOpenStockAlerts();
      default:
        await showActivityReport(
          context,
          api: widget.api,
          year: _year,
          month: _month,
        );
    }
  }

  /// The rules counted by the "Profit risks" card (same as the backend).
  static const profitRiskRules = {'G2a', 'G2b', 'G3'};

  /// G2c: shown on the same card as a separate line, not in its number.
  static const marginRiskRules = {'G2c'};

  Future<void> _allAlerts({
    GuardianSeverity? severity,
    Set<String>? rules,
  }) async {
    final action = await showAllAlerts(
      context,
      api: widget.api,
      year: _year,
      month: _month,
      severity: severity,
      rules: rules,
    );
    if (action != null && mounted) await _act(action);
  }

  @override
  Widget build(BuildContext context) {
    final data = _data;
    return LayoutBuilder(
      builder: (context, c) {
        final pad = c.maxWidth < 600 ? AppSpacing.md : AppSpacing.xl;
        return SingleChildScrollView(
          key: const Key('guardianPage'),
          padding: EdgeInsets.all(pad),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              GuardianHeader(
                month: data?.enabled == true ? _month : null,
                year: data?.enabled == true ? _year : null,
                onMonth: (m) => _setPeriod(month: m),
                onYear: (y) => _setPeriod(year: y),
                onRefresh: data == null ? null : _load,
                enabled: data?.enabled == true ? true : null,
                onToggle: _busy ? null : _toggle,
              ),
              const SizedBox(height: AppSpacing.xl),
              if (data == null)
                ReportLoadState(error: _error, onRetry: _load)
              else if (!data.enabled)
                _offState(data.status)
              else
                ..._dashboard(data, c.maxWidth - pad * 2),
            ],
          ),
        );
      },
    );
  }

  // ── Off ─────────────────────────────────────────────────────────────────

  Widget _offState(GuardianStatus st) {
    final g = GuardianStrings.of(context);
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 640),
        child: Container(
          key: const Key('guardianOff'),
          padding: const EdgeInsets.all(AppSpacing.xl),
          decoration: BoxDecoration(
            color: AppColors.surface,
            borderRadius: BorderRadius.circular(AppSpacing.radiusLg),
            border: Border.all(color: AppColors.border),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(
                Icons.shield_outlined,
                size: 56,
                color: AppColors.textMuted,
              ),
              const SizedBox(height: AppSpacing.md),
              Text(
                g.offTitle,
                style: const TextStyle(
                  fontSize: 20,
                  fontWeight: FontWeight.w800,
                ),
              ),
              const SizedBox(height: AppSpacing.sm),
              Text(g.offBody, textAlign: TextAlign.center),
              const SizedBox(height: AppSpacing.sm),
              Text(
                g.ruleBasedNote,
                textAlign: TextAlign.center,
                style: const TextStyle(color: AppColors.textSecondary),
              ),
              if (st.activityTrackedSince != null) ...[
                const SizedBox(height: AppSpacing.md),
                NoteLine(
                  key: const Key('guardianTrackedSince'),
                  icon: Icons.history_rounded,
                  text: g.trackedSince(st.activityTrackedSince!),
                ),
              ],
              if (st.changedAt != null && st.changedBy != null) ...[
                const SizedBox(height: AppSpacing.xs),
                NoteLine(
                  icon: Icons.person_outline_rounded,
                  text: g.lastChange(st.changedBy!, st.changedAt!),
                ),
              ],
              const SizedBox(height: AppSpacing.lg),
              FilledButton.icon(
                key: const Key('guardianEnable'),
                onPressed: _busy ? null : () => _toggle(true),
                icon: const Icon(Icons.shield_rounded),
                label: Text(g.enable),
              ),
            ],
          ),
        ),
      ),
    );
  }

  // ── On ──────────────────────────────────────────────────────────────────

  List<Widget> _dashboard(GuardianDashboard d, double width) {
    final g = GuardianStrings.of(context);
    final cards = ReportGrid(
      minItemWidth: 230,
      maxPerRow: 4,
      children: [
        _salesCard(d),
        _criticalCard(d),
        _profitCard(d),
        _healthCard(d),
      ],
    );
    final alerts = _alertsCard(d);
    final recs = _recommendationsCard(d);
    final map = _mapCard(d);
    final activity = _activityCard(d);
    final atRisk = _atRiskCard(d);
    final profit = _profitProtectionCard(d);
    const gap = SizedBox(height: AppSpacing.lg, width: AppSpacing.lg);
    Widget column(List<Widget> children) => Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (var i = 0; i < children.length; i++) ...[
          if (i > 0) gap,
          children[i],
        ],
      ],
    );
    final Widget body;
    if (width >= 1050) {
      body = Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(child: column([alerts, activity])),
          gap,
          Expanded(child: column([recs, atRisk])),
          gap,
          Expanded(child: column([map, profit])),
        ],
      );
    } else if (width >= 700) {
      body = Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(child: column([alerts, activity, map])),
          gap,
          Expanded(child: column([recs, atRisk, profit])),
        ],
      );
    } else {
      body = column([alerts, recs, map, activity, atRisk, profit]);
    }
    return [
      NoteLine(
        key: const Key('guardianRuleBased'),
        icon: Icons.rule_rounded,
        text: g.ruleBasedNote,
      ),
      if (!d.currentPeriod) ...[
        const SizedBox(height: AppSpacing.sm),
        NoteLine(
          key: const Key('guardianPastNote'),
          icon: Icons.history_rounded,
          text: g.pastPeriodNote,
        ),
      ],
      const SizedBox(height: AppSpacing.md),
      cards,
      const SizedBox(height: AppSpacing.lg),
      body,
    ];
  }

  Widget _salesCard(GuardianDashboard d) {
    final g = GuardianStrings.of(context);
    final t = d.todaySales;
    final change = t?.changePercent;
    return GuardianCard(
      key: const Key('guardianCardSales'),
      title: g.todaySales,
      value: Formatters.currency(t?.today ?? 0),
      color: AppColors.info,
      softColor: AppColors.infoSoft,
      subtitle: change == null ? g.noYesterday : g.changeVsYesterday(change),
      subtitleColor: change == null
          ? AppColors.textSecondary
          : change >= 0
          ? AppColors.success
          : AppColors.danger,
      note: g.netSalesHint,
      visual: t == null
          ? null
          : Sparkline(values: t.last7Days, color: AppColors.info),
    );
  }

  Widget _criticalCard(GuardianDashboard d) {
    final g = GuardianStrings.of(context);
    return GuardianCard(
      key: const Key('guardianCardCritical'),
      title: g.critical,
      value: '${d.criticalCount}',
      color: AppColors.danger,
      softColor: AppColors.dangerSoft,
      icon: Icons.notifications_active_outlined,
      // Decision U8: alerts about one invoice / product are one issue.
      subtitle: d.criticalCount == 0
          ? g.none
          : d.criticalIssues > 0 && d.criticalIssues < d.criticalCount
          ? g.separateIssues(d.criticalIssues)
          : g.needAttention,
      subtitleColor: d.criticalCount == 0 ? null : AppColors.danger,
      actionLabel: g.viewAll,
      actionKey: const Key('guardianViewCritical'),
      onAction: () => _allAlerts(severity: GuardianSeverity.high),
    );
  }

  Widget _profitCard(GuardianDashboard d) {
    final g = GuardianStrings.of(context);
    final incomplete = d.costData?.incompleteCostInvoices ?? 0;
    final margin = d.marginRiskCount;
    return GuardianCard(
      key: const Key('guardianCardProfit'),
      title: g.profitRisks,
      value: '${d.profitRiskCount}',
      color: AppColors.warning,
      softColor: AppColors.warningSoft,
      icon: Icons.warning_amber_rounded,
      // Never a plain "None" while G2c margin risks exist (they are not in the number).
      subtitle: d.profitRiskCount > 0
          ? g.needAttention
          : margin > 0
          ? g.noneCountedSeeBelow
          : g.none,
      subtitleColor: d.profitRiskCount > 0 || margin > 0
          ? AppColors.warning
          : null,
      note: incomplete == 0 ? null : g.incompleteCost(incomplete),
      extra: margin == 0
          ? null
          : Align(
              alignment: AlignmentDirectional.centerStart,
              child: TextButton(
                key: const Key('guardianMarginRisks'),
                style: TextButton.styleFrom(
                  padding: const EdgeInsets.symmetric(vertical: 2),
                  minimumSize: const Size(0, 30),
                  foregroundColor: AppColors.warning,
                  alignment: AlignmentDirectional.centerStart,
                ),
                onPressed: () => _allAlerts(rules: marginRiskRules),
                child: Text(
                  g.marginRisks(margin),
                  textAlign: TextAlign.start,
                  style: const TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w700,
                    decoration: TextDecoration.underline,
                  ),
                ),
              ),
            ),
      actionLabel: g.viewAll,
      actionKey: const Key('guardianViewProfit'),
      onAction: () => _allAlerts(rules: profitRiskRules),
    );
  }

  Widget _healthCard(GuardianDashboard d) {
    final g = GuardianStrings.of(context);
    final h = d.health;
    final notes = [
      if (h?.score != null && h!.partsUsed < 4) g.partsUsed(h.partsUsed),
      if ((h?.criticalAlerts ?? 0) > 0) g.healthCritical(h!.criticalAlerts),
    ];
    return GuardianCard(
      key: const Key('guardianCardHealth'),
      title: g.health,
      value: h?.score == null ? '—' : '${h!.score}/100',
      color: h?.score == null ? AppColors.textSecondary : bandColor(h!.band),
      softColor: AppColors.primarySoft,
      subtitle: h == null
          ? g.notEnoughData
          : h.score == null
          ? g.notEnoughData
          : g.band(h.band),
      subtitleColor: bandColor(h?.band),
      // Decision U3: the open critical alerts are named next to the number.
      note: notes.isEmpty ? null : notes.join('\n'),
      noteColor: (h?.criticalAlerts ?? 0) > 0 ? AppColors.danger : null,
      visual: HealthRing(score: h?.score, band: h?.band),
      actionLabel: g.healthDetails,
      actionKey: const Key('guardianHealthDetails'),
      onAction: h == null ? null : () => showHealthDetails(context, h),
    );
  }

  Widget _alertsCard(GuardianDashboard d) {
    final g = GuardianStrings.of(context);
    return ReportCard(
      key: const Key('guardianAlerts'),
      title: g.smartAlerts(d.alertCount),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (d.topAlerts.isEmpty)
            CardMessage(icon: Icons.verified_user_outlined, text: g.noAlerts)
          else
            for (final a in d.topAlerts) ...[
              AlertTile(alert: a, onAction: _act),
              const SizedBox(height: AppSpacing.sm),
            ],
          OutlinedButton(
            key: const Key('guardianAllAlerts'),
            onPressed: () => _allAlerts(),
            child: Text(g.viewAllAlerts),
          ),
        ],
      ),
    );
  }

  Widget _recommendationsCard(GuardianDashboard d) {
    final g = GuardianStrings.of(context);
    final s = AppStrings.of(context);
    // Figure 3.1 order: order stock, products to review (offer), increase profit.
    const order = ['REORDER_SOON', 'SLOW_MOVING', 'LOW_MARGIN'];
    final recs = [
      for (final code in order)
        ...d.recommendations.where((r) => r.code == code),
    ];
    return ReportCard(
      key: const Key('guardianRecommendations'),
      title: g.recommendations,
      trailing: Text(
        g.now,
        style: const TextStyle(fontSize: 12, color: AppColors.textSecondary),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          NoteLine(icon: Icons.rule_rounded, text: g.recommendationsNote),
          const SizedBox(height: AppSpacing.sm),
          for (final r in recs) ...[
            RecommendationTile(
              recommendation: r,
              onOpen: () async {
                if (r.code == 'REORDER_SOON') {
                  widget.onOpenStockAlerts();
                  return;
                }
                final id = await showRecommendationProducts(context, r);
                if (id != null && mounted) _openProduct(id);
              },
              // Figure 3.1's "Create purchase order": purchase orders are a
              // later phase, so the button is visibly disabled with the reason.
              extra: r.code == 'REORDER_SOON' && r.count > 0
                  ? Padding(
                      padding: const EdgeInsets.only(top: AppSpacing.xs),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          OutlinedButton.icon(
                            key: const Key('guardianPurchaseDisabled'),
                            onPressed: null,
                            icon: const Icon(
                              Icons.shopping_cart_checkout_rounded,
                              size: 18,
                            ),
                            label: Text(s.createPurchaseRequest),
                          ),
                          Text(
                            s.purchaseNotAvailable,
                            style: const TextStyle(
                              fontSize: 11.5,
                              color: AppColors.textSecondary,
                            ),
                          ),
                        ],
                      ),
                    )
                  : null,
            ),
            const SizedBox(height: AppSpacing.sm),
          ],
        ],
      ),
    );
  }

  Widget _mapCard(GuardianDashboard d) {
    final g = GuardianStrings.of(context);
    final tiles = d.storeMap;
    return ReportCard(
      key: const Key('guardianMap'),
      title: g.storeMap,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          NoteLine(text: g.storeMapNote),
          const SizedBox(height: AppSpacing.sm),
          if (tiles.where((t) => !t.cashierArea).isEmpty)
            CardMessage(
              icon: Icons.inventory_2_outlined,
              text: g.noProductsYet,
            ),
          LayoutBuilder(
            builder: (context, c) {
              final perRow = (c.maxWidth / 130).floor().clamp(1, 3);
              final w = (c.maxWidth - (perRow - 1) * AppSpacing.sm) / perRow;
              return Wrap(
                spacing: AppSpacing.sm,
                runSpacing: AppSpacing.sm,
                children: [
                  for (final t in tiles)
                    SizedBox(
                      width: w,
                      child: MapTileView(
                        tile: t,
                        onTap: t.cashierArea
                            ? () => showActivityReport(
                                context,
                                api: widget.api,
                                year: _year,
                                month: _month,
                              )
                            : widget.onOpenStockAlerts,
                      ),
                    ),
                ],
              );
            },
          ),
          const SizedBox(height: AppSpacing.md),
          Wrap(
            spacing: AppSpacing.md,
            children: [
              LegendDot(color: AppColors.success, label: g.legendGood),
              LegendDot(color: AppColors.warning, label: g.legendAttention),
              LegendDot(color: AppColors.danger, label: g.legendRisk),
            ],
          ),
        ],
      ),
    );
  }

  Widget _activityCard(GuardianDashboard d) {
    final g = GuardianStrings.of(context);
    final a = d.activity;
    final since = a?.trackedSince;
    final from = d.from;
    return ReportCard(
      key: const Key('guardianActivity'),
      title: g.unusual,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (a == null || a.total == 0)
            CardMessage(icon: Icons.verified_outlined, text: g.noUnusual)
          else
            ActivityDonut(summary: a),
          const SizedBox(height: AppSpacing.sm),
          NoteLine(text: g.unusualNote),
          if (since != null &&
              from != null &&
              from.isBefore(DateTime(since.year, since.month, since.day)))
            NoteLine(
              key: const Key('guardianDeletionsSince'),
              icon: Icons.history_rounded,
              text: g.deletionsSince(GuardianStrings.date(since)),
            ),
          const SizedBox(height: AppSpacing.sm),
          OutlinedButton.icon(
            key: const Key('guardianViewActivity'),
            onPressed: () => showActivityReport(
              context,
              api: widget.api,
              year: _year,
              month: _month,
            ),
            icon: const Icon(Icons.bar_chart_rounded, size: 18),
            label: Text(g.viewActivity),
          ),
        ],
      ),
    );
  }

  Widget _atRiskCard(GuardianDashboard d) {
    final g = GuardianStrings.of(context);
    final items = d.atRisk.take(5).toList();
    return ReportCard(
      key: const Key('guardianAtRisk'),
      title: g.atRisk,
      trailing: Text(
        g.now,
        style: const TextStyle(fontSize: 12, color: AppColors.textSecondary),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (items.isEmpty)
            CardMessage(icon: Icons.inventory_outlined, text: g.noAtRisk)
          else
            AtRiskTable(items: items, onOpen: _openProduct),
          const SizedBox(height: AppSpacing.sm),
          OutlinedButton(
            key: const Key('guardianAllProducts'),
            onPressed: widget.onOpenStockAlerts,
            child: Text(g.viewAllProducts),
          ),
        ],
      ),
    );
  }

  Widget _profitProtectionCard(GuardianDashboard d) {
    final g = GuardianStrings.of(context);
    final items = d.profitProtection.take(3).toList();
    final withoutCost = d.costData?.productsWithoutCost ?? 0;
    return ReportCard(
      key: const Key('guardianProfit'),
      title: g.profitProtection,
      trailing: Text(
        g.now,
        style: const TextStyle(fontSize: 12, color: AppColors.textSecondary),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (items.isEmpty)
            CardMessage(icon: Icons.savings_outlined, text: g.noProfitRisks)
          else
            for (final i in items) ...[
              ProfitProtectionTile(
                item: i,
                onOpen: () => _openProduct(i.productId),
              ),
              const SizedBox(height: AppSpacing.sm),
            ],
          if (withoutCost > 0)
            NoteLine(
              key: const Key('guardianWithoutCost'),
              text: g.withoutCost(withoutCost),
            ),
        ],
      ),
    );
  }
}

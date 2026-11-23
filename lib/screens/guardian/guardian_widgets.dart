import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../l10n/guardian_strings.dart';
import '../../models/guardian.dart';
import '../../theme/app_theme.dart';
import '../../utils/formatters.dart';
import '../../widgets/sales_charts.dart';
import '../reports/report_widgets.dart';

/// Building blocks of the Guardian Mode screen (report Figure 3.1).

Color severityColor(GuardianSeverity s) => switch (s) {
  GuardianSeverity.high => AppColors.danger,
  GuardianSeverity.medium => AppColors.warning,
  GuardianSeverity.low => AppColors.info,
};

Color severitySoft(GuardianSeverity s) => switch (s) {
  GuardianSeverity.high => AppColors.dangerSoft,
  GuardianSeverity.medium => AppColors.warningSoft,
  GuardianSeverity.low => AppColors.infoSoft,
};

Color bandColor(HealthBand? b) => switch (b) {
  HealthBand.good => AppColors.success,
  HealthBand.attention => AppColors.warning,
  HealthBand.risk => AppColors.danger,
  null => AppColors.textMuted,
};

Color bandSoft(HealthBand? b) => switch (b) {
  HealthBand.good => AppColors.successSoft,
  HealthBand.attention => AppColors.warningSoft,
  HealthBand.risk => AppColors.dangerSoft,
  null => AppColors.divider,
};

IconData severityIcon(GuardianSeverity s) => switch (s) {
  GuardianSeverity.high => Icons.error_outline_rounded,
  GuardianSeverity.medium => Icons.warning_amber_rounded,
  GuardianSeverity.low => Icons.info_outline_rounded,
};

/// Title with the shield, the month / year, refresh and the on / off switch.
class GuardianHeader extends StatelessWidget {
  const GuardianHeader({
    super.key,
    this.month,
    this.year,
    this.onMonth,
    this.onYear,
    this.onRefresh,
    this.enabled,
    this.onToggle,
  });

  final int? month;
  final int? year;
  final ValueChanged<int>? onMonth;
  final ValueChanged<int>? onYear;
  final VoidCallback? onRefresh;

  /// Null hides the switch (off-state page has its own button).
  final bool? enabled;
  final ValueChanged<bool>? onToggle;

  @override
  Widget build(BuildContext context) {
    final g = GuardianStrings.of(context);
    final titleBlock = Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          padding: const EdgeInsets.all(10),
          decoration: BoxDecoration(
            color: AppColors.primarySoft,
            borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
          ),
          child: const Icon(
            Icons.shield_outlined,
            color: AppColors.sidebar,
            size: 28,
          ),
        ),
        const SizedBox(width: AppSpacing.md),
        Flexible(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                g.title,
                style: const TextStyle(
                  fontSize: 26,
                  fontWeight: FontWeight.w800,
                ),
              ),
              const SizedBox(height: 2),
              Text(
                g.subtitle,
                style: const TextStyle(color: AppColors.textSecondary),
              ),
            ],
          ),
        ),
      ],
    );

    final m = month, y = year;
    final thisYear = ReportClock.now().year;
    final years = [for (var v = thisYear; v >= thisYear - 4; v--) v];
    if (y != null && !years.contains(y)) years.add(y);
    final controls = Wrap(
      spacing: AppSpacing.sm,
      runSpacing: AppSpacing.sm,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        if (m != null && y != null) ...[
          const Icon(
            Icons.calendar_month_rounded,
            color: AppColors.textSecondary,
          ),
          SizedBox(
            width: 140,
            child: DropdownButtonFormField<int>(
              key: ValueKey('guardianMonthSelect-$m'),
              initialValue: m,
              isDense: true,
              isExpanded: true,
              decoration: const InputDecoration(
                contentPadding: EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 10,
                ),
              ),
              items: [
                for (var v = 1; v <= 12; v++)
                  DropdownMenuItem(
                    value: v,
                    child: Text(
                      _monthName(context, v),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
              ],
              onChanged: (v) => v == null ? null : onMonth?.call(v),
            ),
          ),
          SizedBox(
            width: 100,
            child: DropdownButtonFormField<int>(
              key: ValueKey('guardianYearSelect-$y'),
              initialValue: y,
              isDense: true,
              isExpanded: true,
              decoration: const InputDecoration(
                contentPadding: EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 10,
                ),
              ),
              items: [
                for (final v in years)
                  DropdownMenuItem(value: v, child: Text('$v')),
              ],
              onChanged: (v) => v == null ? null : onYear?.call(v),
            ),
          ),
        ],
        if (onRefresh != null)
          IconButton(
            key: const Key('guardianRefresh'),
            tooltip: g.refresh,
            onPressed: onRefresh,
            icon: const Icon(Icons.refresh_rounded),
          ),
        if (enabled != null)
          Tooltip(
            message: g.switchTooltip,
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  enabled! ? g.on : g.off,
                  style: TextStyle(
                    fontWeight: FontWeight.w700,
                    color: enabled! ? AppColors.success : AppColors.textMuted,
                  ),
                ),
                const SizedBox(width: AppSpacing.xs),
                Switch(
                  key: const Key('guardianSwitch'),
                  value: enabled!,
                  onChanged: onToggle,
                ),
              ],
            ),
          ),
      ],
    );

    return LayoutBuilder(
      builder: (context, c) => c.maxWidth < 720
          ? Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                titleBlock,
                const SizedBox(height: AppSpacing.md),
                controls,
              ],
            )
          : Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(child: titleBlock),
                const SizedBox(width: AppSpacing.lg),
                Flexible(child: controls),
              ],
            ),
    );
  }

  static String _monthName(BuildContext context, int m) {
    final ar = Localizations.localeOf(context).languageCode == 'ar';
    const en = [
      'January', 'February', 'March', 'April', 'May', 'June', 'July', //
      'August', 'September', 'October', 'November', 'December',
    ];
    const arNames = [
      'يناير', 'فبراير', 'مارس', 'أبريل', 'مايو', 'يونيو', 'يوليو', //
      'أغسطس', 'سبتمبر', 'أكتوبر', 'نوفمبر', 'ديسمبر',
    ];
    return (ar ? arNames : en)[m - 1];
  }
}

/// One of the four cards at the top of Figure 3.1.
class GuardianCard extends StatelessWidget {
  const GuardianCard({
    super.key,
    required this.title,
    required this.value,
    required this.color,
    required this.softColor,
    this.icon,
    this.subtitle,
    this.subtitleColor,
    this.note,
    this.visual,
    this.actionLabel,
    this.actionKey,
    this.onAction,
  });

  final String title;
  final String value;
  final Color color;
  final Color softColor;
  final IconData? icon;
  final String? subtitle;
  final Color? subtitleColor;
  final String? note;
  final Widget? visual;
  final String? actionLabel;
  final Key? actionKey;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        color: softColor.withValues(alpha: 0.7),
        borderRadius: BorderRadius.circular(AppSpacing.radiusLg),
        border: Border.all(color: color.withValues(alpha: 0.2)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: const TextStyle(
                    fontWeight: FontWeight.w700,
                    color: AppColors.textPrimary,
                  ),
                ),
                const SizedBox(height: AppSpacing.xs),
                Text(
                  value,
                  style: TextStyle(
                    fontSize: 24,
                    fontWeight: FontWeight.w800,
                    color: color,
                  ),
                ),
                if (subtitle != null)
                  Text(
                    subtitle!,
                    style: TextStyle(
                      fontSize: 12.5,
                      fontWeight: FontWeight.w600,
                      color: subtitleColor ?? AppColors.textSecondary,
                    ),
                  ),
                if (note != null) ...[
                  const SizedBox(height: AppSpacing.xs),
                  Text(
                    note!,
                    style: const TextStyle(
                      fontSize: 11.5,
                      color: AppColors.textSecondary,
                    ),
                  ),
                ],
                if (actionLabel != null && onAction != null)
                  Align(
                    alignment: AlignmentDirectional.centerStart,
                    child: TextButton(
                      key: actionKey,
                      style: TextButton.styleFrom(
                        padding: EdgeInsets.zero,
                        minimumSize: const Size(0, 32),
                      ),
                      onPressed: onAction,
                      child: Text(
                        actionLabel!,
                        style: const TextStyle(fontWeight: FontWeight.w700),
                      ),
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(width: AppSpacing.sm),
          visual ??
              (icon == null
                  ? const SizedBox()
                  : Icon(icon, color: color, size: 34)),
        ],
      ),
    );
  }
}

/// Small line of the last days' net sales (no axes).
class Sparkline extends StatelessWidget {
  const Sparkline({super.key, required this.values, required this.color});

  final List<double> values;
  final Color color;

  @override
  Widget build(BuildContext context) => SizedBox(
    width: 84,
    height: 44,
    child: CustomPaint(painter: _SparkPainter(values, color)),
  );
}

class _SparkPainter extends CustomPainter {
  _SparkPainter(this.values, this.color);

  final List<double> values;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    if (values.length < 2) return;
    final maxV = values.reduce(math.max);
    final minV = math.min(0.0, values.reduce(math.min));
    final range = (maxV - minV) == 0 ? 1 : maxV - minV;
    final path = Path();
    for (var i = 0; i < values.length; i++) {
      final x = size.width * i / (values.length - 1);
      final y =
          size.height - (values[i] - minV) / range * (size.height - 4) - 2;
      i == 0 ? path.moveTo(x, y) : path.lineTo(x, y);
    }
    final area = Path.from(path)
      ..lineTo(size.width, size.height)
      ..lineTo(0, size.height)
      ..close();
    canvas.drawPath(area, Paint()..color = color.withValues(alpha: 0.12));
    canvas.drawPath(
      path,
      Paint()
        ..color = color
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2,
    );
  }

  @override
  bool shouldRepaint(_SparkPainter old) => old.values != values;
}

/// The health ring: score out of 100 in the band's colour.
class HealthRing extends StatelessWidget {
  const HealthRing({super.key, required this.score, required this.band});

  final int? score;
  final HealthBand? band;

  @override
  Widget build(BuildContext context) => SizedBox(
    width: 70,
    height: 70,
    child: Stack(
      alignment: Alignment.center,
      children: [
        CustomPaint(
          size: const Size(70, 70),
          painter: _RingPainter((score ?? 0) / 100, bandColor(band)),
        ),
        Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              score == null ? '—' : '$score',
              style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w800),
            ),
            const Text(
              '/100',
              textDirection: TextDirection.ltr,
              style: TextStyle(fontSize: 10, color: AppColors.textSecondary),
            ),
          ],
        ),
      ],
    ),
  );
}

class _RingPainter extends CustomPainter {
  _RingPainter(this.fraction, this.color);

  final double fraction;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    final stroke = 8.0;
    final arc = rect.deflate(stroke / 2);
    canvas.drawArc(
      arc,
      0,
      math.pi * 2,
      false,
      Paint()
        ..color = AppColors.divider
        ..style = PaintingStyle.stroke
        ..strokeWidth = stroke,
    );
    if (fraction > 0) {
      canvas.drawArc(
        arc,
        -math.pi / 2,
        math.pi * 2 * fraction.clamp(0, 1),
        false,
        Paint()
          ..color = color
          ..style = PaintingStyle.stroke
          ..strokeCap = StrokeCap.round
          ..strokeWidth = stroke,
      );
    }
  }

  @override
  bool shouldRepaint(_RingPainter old) =>
      old.fraction != fraction || old.color != color;
}

/// One alert with its explanation, who did it (when recorded) and its action.
class AlertTile extends StatelessWidget {
  const AlertTile({super.key, required this.alert, this.onAction});

  final GuardianAlert alert;
  final ValueChanged<GuardianAction>? onAction;

  @override
  Widget build(BuildContext context) {
    final g = GuardianStrings.of(context);
    final c = severityColor(alert.severity);
    final actor = g.actorLine(alert);
    final at = alert.occurredAt;
    final action = alert.action;
    return Container(
      key: Key('guardianAlert-${alert.key}'),
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: severitySoft(alert.severity).withValues(alpha: 0.6),
        borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
        border: Border.all(color: c.withValues(alpha: 0.25)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(severityIcon(alert.severity), color: c, size: 26),
          const SizedBox(width: AppSpacing.sm),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Text(
                        g.alertTitle(alert),
                        style: const TextStyle(fontWeight: FontWeight.w800),
                      ),
                    ),
                    const SizedBox(width: AppSpacing.xs),
                    Flexible(
                      child: Text(
                        at == null
                            ? g.now
                            : '${GuardianStrings.date(at)}  ${GuardianStrings.time(at)}',
                        textAlign: TextAlign.end,
                        style: const TextStyle(
                          fontSize: 11,
                          color: AppColors.textSecondary,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 2),
                Text(
                  g.alertText(alert),
                  style: const TextStyle(fontSize: 12.5),
                ),
                if (actor != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 2),
                    child: Text(
                      actor,
                      style: const TextStyle(
                        fontSize: 12,
                        color: AppColors.textSecondary,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                if (action != null && onAction != null)
                  Align(
                    alignment: AlignmentDirectional.centerStart,
                    child: TextButton(
                      key: Key('guardianAction-${alert.key}'),
                      style: TextButton.styleFrom(
                        padding: EdgeInsets.zero,
                        minimumSize: const Size(0, 30),
                        foregroundColor: c,
                      ),
                      onPressed: () => onAction!(action),
                      child: Text(
                        g.actionLabel(action),
                        style: const TextStyle(fontWeight: FontWeight.w700),
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// A rule-based recommendation (T1 / T2 / T3).
class RecommendationTile extends StatelessWidget {
  const RecommendationTile({
    super.key,
    required this.recommendation,
    required this.onOpen,
    this.extra,
  });

  final GuardianRecommendation recommendation;
  final VoidCallback onOpen;
  final Widget? extra;

  @override
  Widget build(BuildContext context) {
    final g = GuardianStrings.of(context);
    final r = recommendation;
    final (color, soft, icon) = switch (r.code) {
      'REORDER_SOON' => (
        AppColors.success,
        AppColors.successSoft,
        Icons.shopping_cart_outlined,
      ),
      'LOW_MARGIN' => (AppColors.info, AppColors.infoSoft, Icons.trending_up),
      _ => (AppColors.warning, AppColors.warningSoft, Icons.sell_outlined),
    };
    return Container(
      key: Key('guardianRec-${r.code}'),
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: soft.withValues(alpha: 0.75),
        borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
        border: Border.all(color: color.withValues(alpha: 0.25)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, color: color, size: 26),
          const SizedBox(width: AppSpacing.sm),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  g.recTitle(r.code),
                  style: const TextStyle(fontWeight: FontWeight.w800),
                ),
                const SizedBox(height: 2),
                Text(
                  r.count == 0 ? g.recNone : g.recText(r),
                  style: const TextStyle(fontSize: 12.5),
                ),
                if (r.count > 0) ...[
                  const SizedBox(height: AppSpacing.xs),
                  FilledButton.tonal(
                    key: Key('guardianRecOpen-${r.code}'),
                    style: FilledButton.styleFrom(
                      backgroundColor: color,
                      foregroundColor: Colors.white,
                      minimumSize: const Size(0, 32),
                      padding: const EdgeInsets.symmetric(horizontal: 14),
                    ),
                    onPressed: onOpen,
                    child: Text(
                      r.code == 'REORDER_SOON'
                          ? g.openStockAlerts
                          : g.showProducts,
                    ),
                  ),
                ],
                ?extra,
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// A tile of the store map: one product category, or the cashier area.
class MapTileView extends StatelessWidget {
  const MapTileView({super.key, required this.tile, required this.onTap});

  final MapTile tile;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final g = GuardianStrings.of(context);
    final c = bandColor(tile.band);
    final icon = switch (tile.band) {
      HealthBand.good => Icons.thumb_up_alt_outlined,
      HealthBand.attention => Icons.error_outline_rounded,
      HealthBand.risk => Icons.warning_rounded,
    };
    final label = switch (tile.band) {
      HealthBand.good => g.legendGood,
      HealthBand.attention => g.legendAttention,
      HealthBand.risk => g.legendRisk,
    };
    return Material(
      color: bandSoft(tile.band).withValues(alpha: 0.8),
      borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
      child: InkWell(
        key: Key(
          'guardianTile-${tile.cashierArea ? 'cashier' : tile.category}',
        ),
        borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.all(AppSpacing.sm + 2),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
            border: Border.all(color: c.withValues(alpha: 0.3)),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                tile.cashierArea ? g.cashierArea : g.category(tile.category),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontWeight: FontWeight.w800),
              ),
              const SizedBox(height: AppSpacing.xs),
              Row(
                children: [
                  Icon(icon, color: c, size: 20),
                  const SizedBox(width: AppSpacing.xs),
                  Flexible(
                    child: Text(
                      label,
                      style: TextStyle(
                        color: c,
                        fontWeight: FontWeight.w700,
                        fontSize: 12.5,
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 2),
              Text(
                tile.cashierArea
                    ? (tile.highAlerts + tile.mediumAlerts == 0
                          ? g.noProblems
                          : g.cashierAlerts(tile.highAlerts, tile.mediumAlerts))
                    : g.stockHealth(tile.percent ?? 0),
                style: const TextStyle(
                  fontSize: 11.5,
                  color: AppColors.textSecondary,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Coloured legend dot with a label.
class LegendDot extends StatelessWidget {
  const LegendDot({super.key, required this.color, required this.label});

  final Color color;
  final String label;

  @override
  Widget build(BuildContext context) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      Container(
        width: 10,
        height: 10,
        decoration: BoxDecoration(color: color, shape: BoxShape.circle),
      ),
      const SizedBox(width: 4),
      Flexible(child: Text(label, style: const TextStyle(fontSize: 12))),
    ],
  );
}

/// Donut of the unusual-activity cases of the period, with its legend.
class ActivityDonut extends StatelessWidget {
  const ActivityDonut({super.key, required this.summary});

  final ActivitySummary summary;

  @override
  Widget build(BuildContext context) {
    final g = GuardianStrings.of(context);
    final s = summary;
    // Same order (and colours) as Figure 3.1's legend.
    final values = [s.deletions, s.cancellations, s.returns, s.highDiscounts];
    final labels = [
      g.typeDeletions,
      g.typeCancels,
      g.typeReturns,
      g.typeDiscounts,
    ];
    String pct(int v) =>
        s.total == 0 ? '0%' : '${(v * 100 / s.total).round()}%';
    return Row(
      children: [
        SizedBox(
          width: 120,
          height: 120,
          child: Stack(
            alignment: Alignment.center,
            children: [
              SalesDonutChart(
                values: [for (final v in values) v.toDouble()],
                semanticLabel: g.unusual,
              ),
              Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    '${s.total}',
                    key: const Key('guardianActivityTotal'),
                    style: const TextStyle(
                      fontSize: 20,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  SizedBox(
                    width: 64,
                    child: Text(
                      g.perDay(s.perDay),
                      textAlign: TextAlign.center,
                      style: const TextStyle(
                        fontSize: 9.5,
                        color: AppColors.textSecondary,
                      ),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
        const SizedBox(width: AppSpacing.md),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              for (var i = 0; i < values.length; i++)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 3),
                  child: Row(
                    children: [
                      Expanded(
                        child: LegendDot(
                          color: chartPalette[i],
                          label: labels[i],
                        ),
                      ),
                      Text(
                        '${values[i]} · ${pct(values[i])}',
                        style: const TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ],
                  ),
                ),
            ],
          ),
        ),
      ],
    );
  }
}

/// Risk badge (High / Medium / Low).
class RiskBadge extends StatelessWidget {
  const RiskBadge({super.key, required this.risk});

  final GuardianSeverity risk;

  @override
  Widget build(BuildContext context) {
    final g = GuardianStrings.of(context);
    final c = severityColor(risk);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: severitySoft(risk),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: c.withValues(alpha: 0.5)),
      ),
      child: Text(
        g.risk(risk),
        style: TextStyle(color: c, fontSize: 11.5, fontWeight: FontWeight.w700),
      ),
    );
  }
}

/// "Products most at risk" table (product, stock, expected stockout, risk).
class AtRiskTable extends StatelessWidget {
  const AtRiskTable({super.key, required this.items, required this.onOpen});

  final List<AtRiskProduct> items;
  final ValueChanged<int> onOpen;

  @override
  Widget build(BuildContext context) {
    final g = GuardianStrings.of(context);
    const head = TextStyle(
      fontSize: 11.5,
      fontWeight: FontWeight.w800,
      color: AppColors.textSecondary,
    );
    Widget row(List<Widget> cells) => Row(
      children: [
        Expanded(flex: 4, child: cells[0]),
        Expanded(flex: 2, child: cells[1]),
        Expanded(flex: 3, child: cells[2]),
        Expanded(
          flex: 2,
          child: Align(
            alignment: AlignmentDirectional.centerEnd,
            child: cells[3],
          ),
        ),
      ],
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        row([
          Text(g.colProduct, style: head),
          Text(g.colStock, style: head),
          Text(g.colStockout, style: head),
          Text(g.colRisk, style: head),
        ]),
        const Divider(height: AppSpacing.lg),
        for (final p in items)
          InkWell(
            key: Key('guardianRisk-${p.productId}'),
            onTap: () => onOpen(p.productId),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: row([
                Padding(
                  padding: const EdgeInsetsDirectional.only(end: 8),
                  child: Text(
                    p.name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontWeight: FontWeight.w600),
                  ),
                ),
                Text(Formatters.integer(p.quantity)),
                Text(
                  p.prediction == 'OUT_OF_STOCK'
                      ? g.soldOut
                      : p.expectedStockout == null
                      ? g.noForecast
                      : GuardianStrings.date(p.expectedStockout!),
                  style: TextStyle(
                    fontSize: 12.5,
                    color: p.expectedStockout == null
                        ? AppColors.textSecondary
                        : AppColors.textPrimary,
                  ),
                ),
                RiskBadge(risk: p.risk),
              ]),
            ),
          ),
      ],
    );
  }
}

/// One product of "Profit protection".
class ProfitProtectionTile extends StatelessWidget {
  const ProfitProtectionTile({
    super.key,
    required this.item,
    required this.onOpen,
  });

  final ProfitProtectionItem item;
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    final g = GuardianStrings.of(context);
    final danger = item.status != 'MAX_DISCOUNT_RISK';
    final c = danger ? AppColors.danger : AppColors.warning;
    return Container(
      key: Key('guardianProfitItem-${item.productId}'),
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: (danger ? AppColors.dangerSoft : AppColors.warningSoft)
            .withValues(alpha: 0.6),
        borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
        border: Border.all(color: c.withValues(alpha: 0.3)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(item.name, style: const TextStyle(fontWeight: FontWeight.w800)),
          Text(
            '${g.costPrice(item.cost)} · ${g.currentPrice(item.price)}',
            style: const TextStyle(
              fontSize: 12,
              color: AppColors.textSecondary,
            ),
          ),
          const SizedBox(height: 2),
          Text(
            g.profitItemText(item),
            style: TextStyle(
              color: c,
              fontSize: 12.5,
              fontWeight: FontWeight.w600,
            ),
          ),
          Align(
            alignment: AlignmentDirectional.centerStart,
            child: TextButton(
              style: TextButton.styleFrom(
                padding: EdgeInsets.zero,
                minimumSize: const Size(0, 30),
              ),
              onPressed: onOpen,
              child: Text(
                g.actionLabel(const GuardianAction('PRODUCT', null)),
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// A small note line with an icon.
class NoteLine extends StatelessWidget {
  const NoteLine({super.key, required this.text, this.icon});

  final String text;
  final IconData? icon;

  @override
  Widget build(BuildContext context) => Row(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Icon(
        icon ?? Icons.info_outline_rounded,
        size: 16,
        color: AppColors.textSecondary,
      ),
      const SizedBox(width: AppSpacing.xs),
      Expanded(
        child: Text(
          text,
          style: const TextStyle(fontSize: 12, color: AppColors.textSecondary),
        ),
      ),
    ],
  );
}

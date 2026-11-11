import 'package:flutter/material.dart';

import '../../l10n/app_strings.dart';
import '../../services/api_http.dart';
import '../../theme/app_theme.dart';

/// Today's date for the report pages (replaceable in tests).
class ReportClock {
  ReportClock._();

  static DateTime Function() now = DateTime.now;
}

/// Page title on one side, the period selectors on the other (Figures 3.7 / 3.8).
class ReportHeader extends StatelessWidget {
  const ReportHeader({
    super.key,
    required this.title,
    required this.description,
    required this.month,
    required this.year,
    required this.onMonth,
    required this.onYear,
    this.monthEnabled = true,
  });

  final String title;
  final String description;
  final int month;
  final int year;
  final ValueChanged<int> onMonth;
  final ValueChanged<int> onYear;
  final bool monthEnabled;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final thisYear = ReportClock.now().year;
    final years = [for (var y = thisYear; y >= thisYear - 4; y--) y];
    if (!years.contains(year)) years.add(year);

    Widget select<T>({
      required Key key,
      required String tooltip,
      required T value,
      required List<T> values,
      required String Function(T) label,
      required ValueChanged<T>? onChanged,
      double width = 140,
    }) => Tooltip(
      message: tooltip,
      child: SizedBox(
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
          onChanged: onChanged == null
              ? null
              : (v) => v == null ? null : onChanged(v),
        ),
      ),
    );

    final selectors = Wrap(
      spacing: AppSpacing.sm,
      runSpacing: AppSpacing.sm,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        const Icon(
          Icons.calendar_month_rounded,
          color: AppColors.textSecondary,
        ),
        select<int>(
          key: ValueKey('monthSelect-$month-$monthEnabled'),
          tooltip: s.monthLabel,
          value: month,
          values: [for (var m = 1; m <= 12; m++) m],
          label: (m) => s.monthNames[m - 1],
          onChanged: monthEnabled ? onMonth : null,
        ),
        select<int>(
          key: ValueKey('yearSelect-$year'),
          tooltip: s.yearLabel,
          value: year,
          values: years,
          label: (y) => '$y',
          onChanged: onYear,
          width: 100,
        ),
      ],
    );

    final titleBlock = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          title,
          style: const TextStyle(fontSize: 26, fontWeight: FontWeight.w800),
        ),
        const SizedBox(height: 2),
        Text(
          description,
          style: const TextStyle(color: AppColors.textSecondary),
        ),
      ],
    );

    return LayoutBuilder(
      builder: (context, c) => c.maxWidth < 640
          ? Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                titleBlock,
                const SizedBox(height: AppSpacing.md),
                selectors,
              ],
            )
          : Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(child: titleBlock),
                const SizedBox(width: AppSpacing.lg),
                Flexible(child: selectors),
              ],
            ),
    );
  }
}

/// White rounded card with a title, like the chart cards of Figures 3.7 / 3.8.
class ReportCard extends StatelessWidget {
  const ReportCard({
    super.key,
    required this.title,
    required this.child,
    this.height,
    this.trailing,
  });

  final String title;
  final Widget child;
  final double? height;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final body = Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: height == null ? MainAxisSize.min : MainAxisSize.max,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                title,
                style: const TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.w800,
                ),
                overflow: TextOverflow.ellipsis,
              ),
            ),
            ?trailing,
          ],
        ),
        const SizedBox(height: AppSpacing.md),
        if (height == null) child else Expanded(child: child),
      ],
    );
    return Container(
      height: height,
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(AppSpacing.radiusLg),
        border: Border.all(color: AppColors.border),
        boxShadow: [
          BoxShadow(
            color: AppColors.textPrimary.withValues(alpha: 0.04),
            blurRadius: 14,
            offset: const Offset(0, 6),
          ),
        ],
      ),
      child: body,
    );
  }
}

/// Small centred message used inside a card (empty data).
class CardMessage extends StatelessWidget {
  const CardMessage({super.key, required this.icon, required this.text});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(AppSpacing.md),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, color: AppColors.textMuted, size: 32),
          const SizedBox(height: AppSpacing.sm),
          Text(
            text,
            textAlign: TextAlign.center,
            style: const TextStyle(color: AppColors.textSecondary),
          ),
        ],
      ),
    ),
  );
}

/// Loading spinner or error with "Try again" for a whole report page.
class ReportLoadState extends StatelessWidget {
  const ReportLoadState({super.key, this.error, required this.onRetry});

  final Object? error;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    if (error == null) {
      return const Padding(
        key: Key('reportsLoading'),
        padding: EdgeInsets.all(AppSpacing.xxl),
        child: Center(child: CircularProgressIndicator()),
      );
    }
    final message = error is ApiException
        ? (error as ApiException).message
        : '$error';
    return Padding(
      key: const Key('reportsError'),
      padding: const EdgeInsets.all(AppSpacing.xxl),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(
            Icons.cloud_off_rounded,
            size: 40,
            color: AppColors.danger,
          ),
          const SizedBox(height: AppSpacing.md),
          Text(
            s.reportsLoadError,
            style: const TextStyle(fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: AppSpacing.xs),
          Text(
            message,
            textAlign: TextAlign.center,
            style: const TextStyle(color: AppColors.textSecondary),
          ),
          const SizedBox(height: AppSpacing.lg),
          FilledButton.icon(
            key: const Key('reportsRetry'),
            onPressed: onRetry,
            icon: const Icon(Icons.refresh_rounded),
            label: Text(s.retry),
          ),
        ],
      ),
    );
  }
}

/// Lays out [children] in rows of [perRow] equal columns.
class ReportGrid extends StatelessWidget {
  const ReportGrid({
    super.key,
    required this.children,
    required this.minItemWidth,
    this.maxPerRow = 2,
  });

  final List<Widget> children;
  final double minItemWidth;
  final int maxPerRow;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, c) {
      final perRow = (c.maxWidth / minItemWidth).floor().clamp(1, maxPerRow);
      final rows = <Widget>[];
      for (var i = 0; i < children.length; i += perRow) {
        final row = children.sublist(i, (i + perRow).clamp(0, children.length));
        rows.add(
          IntrinsicHeight(
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (var j = 0; j < perRow; j++) ...[
                  if (j > 0) const SizedBox(width: AppSpacing.lg),
                  Expanded(child: j < row.length ? row[j] : const SizedBox()),
                ],
              ],
            ),
          ),
        );
        if (i + perRow < children.length) {
          rows.add(const SizedBox(height: AppSpacing.lg));
        }
      }
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: rows,
      );
    },
  );
}

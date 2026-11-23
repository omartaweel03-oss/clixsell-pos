import 'package:flutter/material.dart';

import '../../l10n/guardian_strings.dart';
import '../../models/guardian.dart';
import '../../services/api_http.dart';
import '../../services/guardian_api_client.dart';
import '../../theme/app_theme.dart';
import 'guardian_widgets.dart';

/// Dialogs of the Guardian Mode screen: all alerts, the health breakdown,
/// a product list of a recommendation and the activity report.

Widget _dialogFrame({
  required BuildContext context,
  required Key key,
  required String title,
  required Widget body,
  double maxWidth = 760,
}) {
  final g = GuardianStrings.of(context);
  return Dialog(
    key: key,
    insetPadding: const EdgeInsets.all(AppSpacing.lg),
    child: ConstrainedBox(
      constraints: BoxConstraints(maxWidth: maxWidth, maxHeight: 680),
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    title,
                    style: const TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                ),
                IconButton(
                  key: const Key('guardianDialogClose'),
                  tooltip: g.close,
                  onPressed: () => Navigator.of(context).pop(),
                  icon: const Icon(Icons.close_rounded),
                ),
              ],
            ),
            const SizedBox(height: AppSpacing.sm),
            Flexible(child: body),
          ],
        ),
      ),
    ),
  );
}

/// "View all alerts": every alert of the period, filtered by severity.
/// Returns the action chosen (the caller opens the screen after closing).
Future<GuardianAction?> showAllAlerts(
  BuildContext context, {
  required GuardianApiClient api,
  required int year,
  required int month,
  GuardianSeverity? severity,
  Set<String>? rules,
}) => showDialog<GuardianAction>(
  context: context,
  builder: (_) => _AllAlerts(
    api: api,
    year: year,
    month: month,
    severity: severity,
    rules: rules,
  ),
);

class _AllAlerts extends StatefulWidget {
  const _AllAlerts({
    required this.api,
    required this.year,
    required this.month,
    this.severity,
    this.rules,
  });

  final GuardianApiClient api;
  final int year;
  final int month;
  final GuardianSeverity? severity;

  /// Only these rules (e.g. the profit-risk card: G2a, G2b, G3), or all.
  final Set<String>? rules;

  @override
  State<_AllAlerts> createState() => _AllAlertsState();
}

class _AllAlertsState extends State<_AllAlerts> {
  List<GuardianAlert>? _alerts;
  Object? _error;
  late GuardianSeverity? _severity = widget.severity;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final list = await widget.api.alerts(widget.year, widget.month);
      if (mounted) setState(() => _alerts = list);
    } catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final g = GuardianStrings.of(context);
    final all = _alerts;
    Widget body;
    if (all == null) {
      body = _error == null
          ? const Padding(
              padding: EdgeInsets.all(AppSpacing.xl),
              child: Center(child: CircularProgressIndicator()),
            )
          : Text(
              _error is ApiException
                  ? (_error as ApiException).message
                  : '$_error',
            );
    } else {
      final shown = all
          .where((a) => _severity == null || a.severity == _severity)
          .where((a) => widget.rules == null || widget.rules!.contains(a.rule))
          .toList();
      body = Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Wrap(
            spacing: AppSpacing.sm,
            children: [
              ChoiceChip(
                key: const Key('guardianFilter-all'),
                label: Text(g.filterAll),
                selected: _severity == null,
                onSelected: (_) => setState(() => _severity = null),
              ),
              for (final s in GuardianSeverity.values)
                ChoiceChip(
                  key: Key('guardianFilter-${s.name}'),
                  label: Text(g.severity(s)),
                  selected: _severity == s,
                  onSelected: (_) => setState(() => _severity = s),
                ),
            ],
          ),
          const SizedBox(height: AppSpacing.md),
          Flexible(
            child: shown.isEmpty
                ? Padding(
                    padding: const EdgeInsets.all(AppSpacing.xl),
                    child: Text(g.noAlerts, textAlign: TextAlign.center),
                  )
                : ListView.separated(
                    shrinkWrap: true,
                    itemCount: shown.length,
                    separatorBuilder: (_, _) =>
                        const SizedBox(height: AppSpacing.sm),
                    itemBuilder: (_, i) => AlertTile(
                      alert: shown[i],
                      onAction: (a) => Navigator.of(context).pop(a),
                    ),
                  ),
          ),
        ],
      );
    }
    return _dialogFrame(
      context: context,
      key: const Key('guardianAllAlertsDialog'),
      title: g.allAlertsTitle,
      body: body,
    );
  }
}

/// How the Store Health Score was calculated, part by part.
Future<void> showHealthDetails(BuildContext context, StoreHealth health) {
  final g = GuardianStrings.of(context);
  return showDialog<void>(
    context: context,
    builder: (ctx) => _dialogFrame(
      context: ctx,
      key: const Key('guardianHealthDialog'),
      title: g.health,
      maxWidth: 560,
      body: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                HealthRing(score: health.score, band: health.band),
                const SizedBox(width: AppSpacing.md),
                Expanded(
                  child: Text(
                    health.score == null
                        ? g.notEnoughData
                        : '${g.band(health.band)} · ${g.partsUsed(health.partsUsed)}',
                    style: TextStyle(
                      fontWeight: FontWeight.w800,
                      color: bandColor(health.band),
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: AppSpacing.md),
            for (final p in health.parts)
              Container(
                key: Key('guardianHealthPart-${p.part}'),
                margin: const EdgeInsets.only(bottom: AppSpacing.sm),
                padding: const EdgeInsets.all(AppSpacing.md),
                decoration: BoxDecoration(
                  color: p.available ? AppColors.background : AppColors.divider,
                  borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            g.partName(p.part),
                            style: const TextStyle(fontWeight: FontWeight.w800),
                          ),
                        ),
                        Text(
                          p.available
                              ? '${GuardianStrings.num_(p.points)} / 25'
                              : '—',
                          style: const TextStyle(fontWeight: FontWeight.w800),
                        ),
                      ],
                    ),
                    Text(
                      p.available ? g.partNumbers(p) : g.partReason(p),
                      style: const TextStyle(
                        fontSize: 12.5,
                        color: AppColors.textSecondary,
                      ),
                    ),
                    if (p.numbers['deletionsTrackedSince'] != null)
                      Text(
                        g.deletionsSince(
                          '${p.numbers['deletionsTrackedSince']}',
                        ),
                        style: const TextStyle(
                          fontSize: 12,
                          color: AppColors.textSecondary,
                        ),
                      ),
                  ],
                ),
              ),
            const SizedBox(height: AppSpacing.xs),
            NoteLine(text: g.healthFormula),
          ],
        ),
      ),
    ),
  );
}

/// The products of a recommendation (T2 / T3). Returns the product id chosen.
Future<int?> showRecommendationProducts(
  BuildContext context,
  GuardianRecommendation r,
) {
  final g = GuardianStrings.of(context);
  return showDialog<int>(
    context: context,
    builder: (ctx) => _dialogFrame(
      context: ctx,
      key: Key('guardianRecDialog-${r.code}'),
      title: g.recTitle(r.code),
      maxWidth: 560,
      body: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          NoteLine(text: g.recText(r)),
          const SizedBox(height: AppSpacing.sm),
          Flexible(
            child: ListView(
              shrinkWrap: true,
              children: [
                for (final p in r.products)
                  ListTile(
                    key: Key('guardianRecProduct-${p['productId']}'),
                    contentPadding: EdgeInsets.zero,
                    title: Text(
                      '${p['name']}',
                      style: const TextStyle(fontWeight: FontWeight.w700),
                    ),
                    subtitle: Text(g.recProductLine(r.code, p)),
                    trailing: const Icon(Icons.chevron_right_rounded),
                    onTap: () =>
                        Navigator.of(ctx).pop((p['productId'] as num).toInt()),
                  ),
              ],
            ),
          ),
        ],
      ),
    ),
  );
}

/// "View activity report": the store's activity log of the period.
Future<void> showActivityReport(
  BuildContext context, {
  required GuardianApiClient api,
  required int year,
  required int month,
}) => showDialog<void>(
  context: context,
  builder: (_) => _ActivityReport(api: api, year: year, month: month),
);

class _ActivityReport extends StatefulWidget {
  const _ActivityReport({
    required this.api,
    required this.year,
    required this.month,
  });

  final GuardianApiClient api;
  final int year;
  final int month;

  @override
  State<_ActivityReport> createState() => _ActivityReportState();
}

class _ActivityReportState extends State<_ActivityReport> {
  ActivityPage? _page;
  Object? _error;
  String? _type;
  int _pageNo = 0;
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
      final p = await widget.api.activity(
        widget.year,
        widget.month,
        type: _type,
        page: _pageNo,
      );
      if (mounted && id == _request) setState(() => _page = p);
    } catch (e) {
      if (mounted && id == _request) setState(() => _error = e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final g = GuardianStrings.of(context);
    final p = _page;
    final pages = p == null || p.pageSize == 0
        ? 1
        : ((p.total + p.pageSize - 1) ~/ p.pageSize).clamp(1, 1 << 30);
    const head = TextStyle(
      fontSize: 12,
      fontWeight: FontWeight.w800,
      color: AppColors.textSecondary,
    );
    Widget row(List<Widget> cells) => Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(flex: 3, child: cells[0]),
        Expanded(flex: 3, child: cells[1]),
        Expanded(flex: 4, child: cells[2]),
        Expanded(flex: 5, child: cells[3]),
      ],
    );
    final body = Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SizedBox(
          width: 280,
          child: DropdownButtonFormField<String?>(
            key: const Key('guardianActivityType'),
            initialValue: _type,
            isExpanded: true,
            isDense: true,
            items: [
              DropdownMenuItem(value: null, child: Text(g.allTypes)),
              for (final t in GuardianStrings.eventTypes)
                DropdownMenuItem(value: t, child: Text(g.eventLabel(t))),
            ],
            onChanged: (v) {
              _type = v;
              _pageNo = 0;
              _load();
            },
          ),
        ),
        const SizedBox(height: AppSpacing.sm),
        if (p?.trackedSince != null)
          NoteLine(text: g.trackedSince(p!.trackedSince!)),
        const SizedBox(height: AppSpacing.sm),
        row([
          Text(g.colTime, style: head),
          Text(g.colUser, style: head),
          Text(g.colEvent, style: head),
          Text(g.colDetails, style: head),
        ]),
        const Divider(),
        Flexible(
          child: p == null
              ? Padding(
                  padding: const EdgeInsets.all(AppSpacing.xl),
                  child: Center(
                    child: _error == null
                        ? const CircularProgressIndicator()
                        : Text(
                            _error is ApiException
                                ? (_error as ApiException).message
                                : '$_error',
                          ),
                  ),
                )
              : p.entries.isEmpty
              ? Padding(
                  padding: const EdgeInsets.all(AppSpacing.xl),
                  child: Text(g.noActivity, textAlign: TextAlign.center),
                )
              : ListView.builder(
                  shrinkWrap: true,
                  itemCount: p.entries.length,
                  itemBuilder: (_, i) {
                    final e = p.entries[i];
                    return Padding(
                      key: Key('guardianLog-${e.logId}'),
                      padding: const EdgeInsets.symmetric(vertical: 6),
                      child: row([
                        Text(
                          '${GuardianStrings.date(e.createdAt)}\n${GuardianStrings.time(e.createdAt)}',
                          style: const TextStyle(fontSize: 12),
                        ),
                        Text(
                          e.userName,
                          style: const TextStyle(fontSize: 12.5),
                        ),
                        Text(
                          g.eventLabel(e.type),
                          style: const TextStyle(
                            fontSize: 12.5,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        Text(
                          g.eventDetails(e),
                          style: const TextStyle(fontSize: 12.5),
                        ),
                      ]),
                    );
                  },
                ),
        ),
        if (p != null && p.total > p.pageSize) ...[
          const Divider(),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              TextButton(
                key: const Key('guardianActivityPrev'),
                onPressed: _pageNo == 0
                    ? null
                    : () {
                        _pageNo--;
                        _load();
                      },
                child: Text(g.previous),
              ),
              Text(g.pageOf(_pageNo + 1, pages)),
              TextButton(
                key: const Key('guardianActivityNext'),
                onPressed: _pageNo + 1 >= pages
                    ? null
                    : () {
                        _pageNo++;
                        _load();
                      },
                child: Text(g.next),
              ),
            ],
          ),
        ],
      ],
    );
    return _dialogFrame(
      context: context,
      key: const Key('guardianActivityDialog'),
      title: g.activityTitle,
      body: body,
      maxWidth: 860,
    );
  }
}

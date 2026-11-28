import 'package:flutter/material.dart';

import '../../l10n/shift_strings.dart';
import '../../models/cash_shift.dart';
import '../../services/shift_api_client.dart';
import '../../theme/app_theme.dart';
import '../../utils/formatters.dart';
import '../../widgets/page_header.dart';
import 'shift_dialogs.dart';

/// Store Admin: the store's cash drawers and shifts (features 1–2, D2, D3). Every amount
/// comes from the backend; this page only shows them and sends the admin's actions.
class ShiftsPage extends StatefulWidget {
  const ShiftsPage({super.key, required this.api});

  final ShiftApiClient api;

  @override
  State<ShiftsPage> createState() => _ShiftsPageState();
}

class _ShiftsPageState extends State<ShiftsPage> {
  bool? _required;
  int _rounding = 0;
  List<CashDrawer> _drawers = const [];
  List<CashShift> _shifts = const [];
  String? _status; // null = all
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    try {
      final current = await widget.api.current();
      final rounding = await widget.api.cashRoundingMinor();
      final shifts = await widget.api.list(status: _status);
      if (!mounted) return;
      setState(() {
        _required = current.requireCashShift;
        _rounding = rounding;
        _drawers = current.drawers;
        _shifts = shifts;
        _error = null;
        _loading = false;
      });
    } catch (e) {
      if (mounted) {
        setState(() {
          _error = shiftError(context, e);
          _loading = false;
        });
      }
    }
  }

  void _say(String m, {bool error = false}) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(
        content: Text(m),
        backgroundColor: error ? AppColors.danger : null,
      ));
  }

  Future<void> _act(Future<void> Function() action) async {
    try {
      await action();
    } catch (e) {
      if (mounted) _say(shiftError(context, e), error: true);
    }
    await _load();
  }

  Future<String?> _askName(String title, {String initial = ''}) {
    final t = ShiftStrings.of(context);
    final c = TextEditingController(text: initial);
    return showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: TextField(
          key: const Key('drawerNameField'),
          controller: c,
          autofocus: true,
          maxLength: 60,
          decoration: InputDecoration(labelText: t.drawerName),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: Text(t.cancel)),
          FilledButton(
            key: const Key('drawerNameSave'),
            onPressed: () => Navigator.pop(ctx, c.text.trim()),
            child: Text(t.save),
          ),
        ],
      ),
    );
  }

  Future<void> _details(CashShift shift) async {
    final t = ShiftStrings.of(context);
    ShiftDetails d;
    try {
      d = await widget.api.details(shift.shiftId);
    } catch (e) {
      if (mounted) _say(shiftError(context, e), error: true);
      return;
    }
    if (!mounted) return;
    final changed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        key: const Key('shiftDetailsDialog'),
        title: Text('${t.details} #${d.shift.shiftId}'),
        content: SizedBox(
          width: 520,
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('${d.shift.drawerName} · ${d.shift.cashierName} · '
                    '${Formatters.date(d.shift.openedAt)}'),
                const SizedBox(height: AppSpacing.sm),
                ShiftSummary(shift: d.shift),
                if (!d.shift.isOpen) ...[
                  const Divider(),
                  _kv(t.counted, Formatters.currency(d.shift.countedCash ?? 0)),
                  _kv(t.difference, signedMoney(d.shift.difference ?? 0), key: 'detailsDifference'),
                  _kv(t.closedBy, '${d.shift.closedByName ?? ''}'
                      '${d.shift.closeKind == 'ADMIN_EXCEPTION' ? ' (${t.adminCloseKind})' : ''}'),
                  if (d.shift.closeReason != null) _kv(t.reason, d.shift.closeReason!),
                  if (d.shift.closingNote != null) _kv(t.note, d.shift.closingNote!),
                ],
                const Divider(),
                Text(t.movements, style: const TextStyle(fontWeight: FontWeight.w700)),
                if (d.movements.isEmpty) Text(t.noMovements),
                for (final m in d.movements)
                  _kv('${m.type == 'CASH_IN' ? t.cashIn : t.cashOut} · ${m.reason} · ${m.createdByName}',
                      Formatters.currency(m.amount)),
                const Divider(),
                Text('${t.sales} (${d.sales.length})', style: const TextStyle(fontWeight: FontWeight.w700)),
                for (final sale in d.sales)
                  _kv('${sale.invoiceNumber} · ${sale.paymentMethod}', Formatters.currency(sale.total)),
              ],
            ),
          ),
        ),
        actions: [
          if (d.shift.isOpen) ...[
            TextButton.icon(
              key: const Key('detailsCashIn'),
              icon: const Icon(Icons.add_rounded),
              label: Text(t.cashIn),
              onPressed: () async {
                final m = await CashMovementDialog.show(ctx, widget.api, d.shift.shiftId, 'CASH_IN');
                if (m != null && ctx.mounted) Navigator.pop(ctx, true);
              },
            ),
            TextButton.icon(
              key: const Key('detailsCashOut'),
              icon: const Icon(Icons.remove_rounded),
              label: Text(t.cashOut),
              onPressed: () async {
                final m = await CashMovementDialog.show(ctx, widget.api, d.shift.shiftId, 'CASH_OUT');
                if (m != null && ctx.mounted) Navigator.pop(ctx, true);
              },
            ),
            TextButton.icon(
              key: const Key('detailsAdminClose'),
              icon: const Icon(Icons.lock_outline_rounded),
              label: Text(t.adminClose),
              onPressed: () async {
                final closed = await CloseShiftDialog.show(ctx, widget.api, d.shift, asAdmin: true);
                if (closed != null && ctx.mounted) Navigator.pop(ctx, true);
              },
            ),
          ],
          FilledButton(onPressed: () => Navigator.pop(ctx), child: Text(t.cancel)),
        ],
      ),
    );
    if (changed == true) await _load();
  }

  Widget _kv(String k, String v, {String? key}) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 2),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(child: Text(k, style: const TextStyle(color: AppColors.textSecondary))),
        const SizedBox(width: AppSpacing.sm),
        Text(v, key: key == null ? null : Key(key), style: const TextStyle(fontWeight: FontWeight.w600)),
      ],
    ),
  );

  @override
  Widget build(BuildContext context) {
    final t = ShiftStrings.of(context);
    // a Material (not a coloured box) so list tiles and switches show their ink
    Widget card(Widget child) => Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.lg),
      child: Material(
        color: AppColors.surface,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppSpacing.radiusLg),
          side: const BorderSide(color: AppColors.border),
        ),
        child: Padding(padding: const EdgeInsets.all(AppSpacing.lg), child: child),
      ),
    );
    return ListView(
      key: const Key('shiftsPage'),
      padding: const EdgeInsets.all(AppSpacing.xl),
      children: [
        PageHeader(title: t.navShifts, description: t.pageSubtitle),
        const SizedBox(height: AppSpacing.lg),
        if (_error != null)
          card(Text(_error!, key: const Key('shiftsError'), style: const TextStyle(color: AppColors.danger))),
        if (_loading && _required == null) const Center(child: CircularProgressIndicator()),
        if (_required != null) ...[
          card(SwitchListTile(
            key: const Key('requireShiftSwitch'),
            contentPadding: EdgeInsets.zero,
            title: Text(t.requireShifts, style: const TextStyle(fontWeight: FontWeight.w700)),
            subtitle: Text(t.requireShiftsHint),
            value: _required!,
            onChanged: (v) => _act(() async {
              await widget.api.setRequireCashShift(v);
            }),
          )),
          card(Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(t.cashRounding, style: const TextStyle(fontWeight: FontWeight.w700)),
              Text(t.cashRoundingHint, style: const TextStyle(color: AppColors.textSecondary)),
              const SizedBox(height: AppSpacing.sm),
              SegmentedButton<int>(
                key: const Key('cashRoundingStep'),
                segments: [
                  ButtonSegment(value: 0, label: Text(t.noRounding)),
                  for (final v in const [5, 10, 50, 100])
                    ButtonSegment(value: v, label: Text(Formatters.number(v / 100))),
                ],
                selected: {_rounding},
                onSelectionChanged: (v) => _act(() async {
                  await widget.api.setCashRoundingMinor(v.first);
                }),
              ),
            ],
          )),
          card(Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(children: [
                Expanded(child: Text(t.drawers, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16))),
                FilledButton.icon(
                  key: const Key('addDrawer'),
                  icon: const Icon(Icons.add_rounded),
                  label: Text(t.addDrawer),
                  onPressed: () async {
                    final name = await _askName(t.addDrawer);
                    if (name != null && name.isNotEmpty) {
                      await _act(() async => widget.api.addDrawer(name));
                    }
                  },
                ),
              ]),
              const SizedBox(height: AppSpacing.sm),
              for (final d in _drawers)
                ListTile(
                  key: Key('drawer-${d.drawerId}'),
                  contentPadding: EdgeInsets.zero,
                  leading: Icon(Icons.point_of_sale_rounded,
                      color: d.active ? AppColors.primary : AppColors.textMuted),
                  title: Text(d.name),
                  subtitle: Text(d.inUse ? t.statusOpen : (d.active ? t.active : t.inactive)),
                  trailing: Wrap(spacing: AppSpacing.sm, children: [
                    IconButton(
                      key: Key('renameDrawer-${d.drawerId}'),
                      icon: const Icon(Icons.edit_outlined),
                      onPressed: () async {
                        final name = await _askName(t.drawerName, initial: d.name);
                        if (name != null && name.isNotEmpty && name != d.name) {
                          await _act(() async => widget.api.updateDrawer(d.drawerId, name: name));
                        }
                      },
                    ),
                    Switch(
                      key: Key('drawerActive-${d.drawerId}'),
                      value: d.active,
                      onChanged: (v) => _act(() async => widget.api.updateDrawer(d.drawerId, active: v)),
                    ),
                  ]),
                ),
            ],
          )),
          card(Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(children: [
                Expanded(child: Text(t.shifts, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16))),
                SegmentedButton<String?>(
                  key: const Key('shiftStatusFilter'),
                  segments: [
                    ButtonSegment(value: null, label: Text(t.all)),
                    ButtonSegment(value: 'OPEN', label: Text(t.statusOpen)),
                    ButtonSegment(value: 'CLOSED', label: Text(t.statusClosed)),
                  ],
                  selected: {_status},
                  onSelectionChanged: (v) {
                    setState(() => _status = v.first);
                    _load();
                  },
                ),
              ]),
              const SizedBox(height: AppSpacing.sm),
              if (_shifts.isEmpty) Text(t.noShifts, key: const Key('noShifts')),
              for (final sh in _shifts)
                ListTile(
                  key: Key('shift-${sh.shiftId}'),
                  contentPadding: EdgeInsets.zero,
                  onTap: () => _details(sh),
                  leading: Icon(sh.isOpen ? Icons.lock_open_rounded : Icons.lock_rounded,
                      color: sh.isOpen ? AppColors.success : AppColors.textSecondary),
                  title: Text('#${sh.shiftId} · ${sh.cashierName} · ${sh.drawerName}'),
                  subtitle: Text('${t.openedAt}: ${Formatters.date(sh.openedAt)}'
                      '${sh.closedAt == null ? '' : ' · ${t.closedAt}: ${Formatters.date(sh.closedAt!)}'}'),
                  trailing: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      Text('${t.expectedCash}: ${Formatters.currency(sh.expectedCash)}'),
                      if (sh.difference != null)
                        Text('${t.difference}: ${signedMoney(sh.difference!)}',
                            style: TextStyle(
                              fontWeight: FontWeight.w700,
                              color: sh.difference == 0 ? AppColors.success : AppColors.warning,
                            )),
                    ],
                  ),
                ),
            ],
          )),
        ],
      ],
    );
  }
}

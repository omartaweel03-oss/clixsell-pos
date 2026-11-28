import 'package:flutter/material.dart';

import '../../l10n/app_strings.dart';
import '../../l10n/shift_strings.dart';
import '../../models/cash_shift.dart';
import '../../services/api_http.dart';
import '../../services/shift_api_client.dart';
import '../../theme/app_theme.dart';
import '../../utils/formatters.dart';

/// Amount typed by the user → cents; 0 allowed; Arabic digits and comma accepted; at most
/// 2 decimals. Null when not a valid amount.
int? parseShiftCents(String input) {
  var v = input.trim();
  const arabic = '٠١٢٣٤٥٦٧٨٩';
  for (var i = 0; i < 10; i++) {
    v = v.replaceAll(arabic[i], '$i');
  }
  v = v.replaceAll('٫', '.').replaceAll(',', '.');
  final m = RegExp(r'^(\d{1,7})(?:\.(\d{1,2}))?$').firstMatch(v);
  if (m == null) return null;
  return int.parse(m.group(1)!) * 100 +
      int.parse((m.group(2) ?? '').padRight(2, '0'));
}

String shiftError(BuildContext context, Object e) {
  final s = AppStrings.of(context);
  if (e is! ApiException) return e.toString();
  if (e.isNetworkError) return s.networkError;
  if (e.isForbidden) return s.notAllowed;
  final byCode = ShiftStrings.of(context).forCode(e.code);
  if (byCode != null) return byCode;
  if (e.fieldErrors.isNotEmpty) return e.fieldErrors.values.first;
  return e.message;
}

String signedMoney(double v) =>
    v > 0 ? '+${Formatters.currency(v)}' : Formatters.currency(v);

/// Opening, cash sales, refunds, in / out, expected — as calculated by the backend.
class ShiftSummary extends StatelessWidget {
  const ShiftSummary({super.key, required this.shift});

  final CashShift shift;

  @override
  Widget build(BuildContext context) {
    final t = ShiftStrings.of(context);
    Widget line(String label, double v, {String? key, bool bold = false, String sign = ''}) =>
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 3),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  label,
                  style: TextStyle(
                    color: bold ? AppColors.textPrimary : AppColors.textSecondary,
                    fontWeight: bold ? FontWeight.w700 : FontWeight.w400,
                  ),
                ),
              ),
              Text(
                '$sign${Formatters.currency(v)}',
                key: key == null ? null : Key(key),
                style: TextStyle(fontWeight: bold ? FontWeight.w800 : FontWeight.w600),
              ),
            ],
          ),
        );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        line(t.opening, shift.openingCash, key: 'shiftOpening'),
        line('${t.cashSales} (${shift.cashSalesCount})', shift.cashSales, key: 'shiftCashSales', sign: '+ '),
        line('${t.cashRefunds} (${shift.cashRefundsCount})', shift.cashRefunds, key: 'shiftCashRefunds', sign: '− '),
        line(t.cashIn, shift.cashIn, key: 'shiftCashIn', sign: '+ '),
        line(t.cashOut, shift.cashOut, key: 'shiftCashOut', sign: '− '),
        const Divider(),
        line(t.expectedCash, shift.expectedCash, key: 'shiftExpected', bold: true),
        if (shift.otherSalesCount > 0)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Text(
              '${t.otherSales}: ${Formatters.currency(shift.otherSales)} (${shift.otherSalesCount})',
              style: const TextStyle(color: AppColors.textMuted, fontSize: 12),
            ),
          ),
      ],
    );
  }
}

// ── open ────────────────────────────────────────────────────────────────────────

class OpenShiftDialog extends StatefulWidget {
  const OpenShiftDialog({super.key, required this.api, required this.drawers});

  final ShiftApiClient api;
  final List<CashDrawer> drawers;

  static Future<CashShift?> show(
    BuildContext context,
    ShiftApiClient api,
    List<CashDrawer> drawers,
  ) => showDialog<CashShift>(
    context: context,
    barrierDismissible: false,
    builder: (_) => OpenShiftDialog(api: api, drawers: drawers),
  );

  @override
  State<OpenShiftDialog> createState() => _OpenShiftDialogState();
}

class _OpenShiftDialogState extends State<OpenShiftDialog> {
  late int? _drawer = widget.drawers
      .where((d) => d.active && !d.inUse)
      .map((d) => d.drawerId)
      .firstOrNull;
  final _cash = TextEditingController(text: '0');
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _cash.dispose();
    super.dispose();
  }

  Future<void> _open() async {
    final t = ShiftStrings.of(context);
    final cents = parseShiftCents(_cash.text);
    if (cents == null) {
      setState(() => _error = t.invalidAmount);
      return;
    }
    final drawer = _drawer;
    if (drawer == null) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final shift = await widget.api.open(drawerId: drawer, openingCents: cents);
      if (mounted) Navigator.of(context).pop(shift);
    } catch (e) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = shiftError(context, e);
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = ShiftStrings.of(context);
    final usable = widget.drawers.where((d) => d.active).toList();
    return AlertDialog(
      key: const Key('openShiftDialog'),
      title: Text(t.openShift),
      content: SizedBox(
        width: 380,
        child: usable.isEmpty
            ? Text(t.noDrawers, key: const Key('noDrawers'))
            : Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  DropdownButtonFormField<int>(
                    key: const Key('shiftDrawerField'),
                    initialValue: _drawer,
                    decoration: InputDecoration(labelText: t.drawer),
                    items: [
                      for (final d in usable)
                        DropdownMenuItem(
                          value: d.drawerId,
                          enabled: !d.inUse,
                          child: Text(d.inUse ? '${d.name} (${t.drawerBusy})' : d.name),
                        ),
                    ],
                    onChanged: _busy ? null : (v) => setState(() => _drawer = v),
                  ),
                  const SizedBox(height: AppSpacing.md),
                  TextField(
                    key: const Key('openingCashField'),
                    controller: _cash,
                    enabled: !_busy,
                    keyboardType: const TextInputType.numberWithOptions(decimal: true),
                    decoration: InputDecoration(labelText: t.openingCash),
                    onSubmitted: (_) => _open(),
                  ),
                  const SizedBox(height: AppSpacing.sm),
                  Text(t.logicalDrawerNote,
                      style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
                  if (_error != null) ...[
                    const SizedBox(height: AppSpacing.sm),
                    Text(_error!, key: const Key('shiftError'),
                        style: const TextStyle(color: AppColors.danger)),
                  ],
                ],
              ),
      ),
      actions: [
        TextButton(
          key: const Key('shiftCancel'),
          onPressed: _busy ? null : () => Navigator.of(context).pop(),
          child: Text(t.cancel),
        ),
        if (usable.isNotEmpty)
          FilledButton(
            key: const Key('openShiftConfirm'),
            onPressed: _busy || _drawer == null ? null : _open,
            child: Text(t.open),
          ),
      ],
    );
  }
}

// ── close (own, or as Store Admin with a reason) ───────────────────────────────

class CloseShiftDialog extends StatefulWidget {
  const CloseShiftDialog({
    super.key,
    required this.api,
    required this.shift,
    this.asAdmin = false,
  });

  final ShiftApiClient api;
  final CashShift shift;
  final bool asAdmin;

  static Future<CashShift?> show(
    BuildContext context,
    ShiftApiClient api,
    CashShift shift, {
    bool asAdmin = false,
  }) => showDialog<CashShift>(
    context: context,
    barrierDismissible: false,
    builder: (_) => CloseShiftDialog(api: api, shift: shift, asAdmin: asAdmin),
  );

  @override
  State<CloseShiftDialog> createState() => _CloseShiftDialogState();
}

class _CloseShiftDialogState extends State<CloseShiftDialog> {
  final _counted = TextEditingController();
  final _note = TextEditingController();
  final _reason = TextEditingController();
  bool _busy = false;
  String? _error;

  int get _expectedCents => (widget.shift.expectedCash * 100).round();
  int? get _cents => parseShiftCents(_counted.text);

  @override
  void dispose() {
    _counted.dispose();
    _note.dispose();
    _reason.dispose();
    super.dispose();
  }

  Future<void> _close() async {
    final t = ShiftStrings.of(context);
    final cents = _cents;
    if (cents == null) {
      setState(() => _error = t.invalidAmount);
      return;
    }
    final note = _note.text.trim();
    if (cents != _expectedCents && note.length < 3) {
      setState(() => _error = t.noteRequired);
      return;
    }
    final reason = _reason.text.trim();
    if (widget.asAdmin && reason.length < 3) {
      setState(() => _error = t.reasonRequired);
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final closed = widget.asAdmin
          ? await widget.api.adminClose(widget.shift,
              countedCents: cents, reason: reason, note: note.isEmpty ? null : note)
          : await widget.api.close(widget.shift,
              countedCents: cents, note: note.isEmpty ? null : note);
      if (mounted) Navigator.of(context).pop(closed);
    } catch (e) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = shiftError(context, e);
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = ShiftStrings.of(context);
    final cents = _cents;
    final diff = cents == null ? null : (cents - _expectedCents) / 100;
    return AlertDialog(
      key: const Key('closeShiftDialog'),
      title: Text(widget.asAdmin ? t.adminClose : t.closeShift),
      content: SizedBox(
        width: 420,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('${widget.shift.drawerName} · ${widget.shift.cashierName}',
                  style: const TextStyle(fontWeight: FontWeight.w600)),
              const SizedBox(height: AppSpacing.sm),
              ShiftSummary(shift: widget.shift),
              const SizedBox(height: AppSpacing.md),
              TextField(
                key: const Key('countedCashField'),
                controller: _counted,
                enabled: !_busy,
                autofocus: true,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: InputDecoration(labelText: t.countedCash),
                onChanged: (_) => setState(() {}),
              ),
              if (diff != null)
                Padding(
                  padding: const EdgeInsets.only(top: AppSpacing.sm),
                  child: Text(
                    '${t.difference}: ${signedMoney(diff)}',
                    key: const Key('shiftDifference'),
                    style: TextStyle(
                      fontWeight: FontWeight.w700,
                      color: diff == 0 ? AppColors.success : AppColors.warning,
                    ),
                  ),
                ),
              const SizedBox(height: AppSpacing.sm),
              TextField(
                key: const Key('closingNoteField'),
                controller: _note,
                enabled: !_busy,
                maxLength: 300,
                decoration: InputDecoration(labelText: t.note),
              ),
              if (widget.asAdmin)
                TextField(
                  key: const Key('closeReasonField'),
                  controller: _reason,
                  enabled: !_busy,
                  maxLength: 300,
                  decoration: InputDecoration(labelText: t.reason),
                ),
              if (_error != null)
                Text(_error!, key: const Key('shiftError'),
                    style: const TextStyle(color: AppColors.danger)),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          key: const Key('shiftCancel'),
          onPressed: _busy ? null : () => Navigator.of(context).pop(),
          child: Text(t.cancel),
        ),
        FilledButton(
          key: const Key('closeShiftConfirm'),
          onPressed: _busy ? null : _close,
          child: Text(t.confirmClose),
        ),
      ],
    );
  }
}

// ── cash in / out (Store Admin) ─────────────────────────────────────────────────

class CashMovementDialog extends StatefulWidget {
  const CashMovementDialog({
    super.key,
    required this.api,
    required this.shiftId,
    required this.type,
  });

  final ShiftApiClient api;
  final int shiftId;
  final String type; // CASH_IN | CASH_OUT

  static Future<CashMovement?> show(
    BuildContext context,
    ShiftApiClient api,
    int shiftId,
    String type,
  ) => showDialog<CashMovement>(
    context: context,
    barrierDismissible: false,
    builder: (_) => CashMovementDialog(api: api, shiftId: shiftId, type: type),
  );

  @override
  State<CashMovementDialog> createState() => _CashMovementDialogState();
}

class _CashMovementDialogState extends State<CashMovementDialog> {
  final _amount = TextEditingController();
  final _reason = TextEditingController();
  // one key for this dialog: pressing Save again after a lost answer records nothing twice
  final _requestKey = ShiftApiClient.newRequestKey();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _amount.dispose();
    _reason.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final t = ShiftStrings.of(context);
    final cents = parseShiftCents(_amount.text);
    if (cents == null || cents == 0) {
      setState(() => _error = t.positiveAmount);
      return;
    }
    if (_reason.text.trim().length < 3) {
      setState(() => _error = t.reasonRequired);
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final m = await widget.api.addMovement(widget.shiftId,
          type: widget.type,
          amountCents: cents,
          reason: _reason.text.trim(),
          requestKey: _requestKey);
      if (mounted) Navigator.of(context).pop(m);
    } catch (e) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = shiftError(context, e);
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = ShiftStrings.of(context);
    return AlertDialog(
      key: const Key('cashMovementDialog'),
      title: Text(widget.type == 'CASH_IN' ? t.cashIn : t.cashOut),
      content: SizedBox(
        width: 360,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TextField(
              key: const Key('movementAmountField'),
              controller: _amount,
              enabled: !_busy,
              autofocus: true,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: InputDecoration(labelText: t.amount),
            ),
            TextField(
              key: const Key('movementReasonField'),
              controller: _reason,
              enabled: !_busy,
              maxLength: 300,
              decoration: InputDecoration(labelText: t.reason),
            ),
            if (_error != null)
              Text(_error!, key: const Key('shiftError'),
                  style: const TextStyle(color: AppColors.danger)),
          ],
        ),
      ),
      actions: [
        TextButton(
          key: const Key('shiftCancel'),
          onPressed: _busy ? null : () => Navigator.of(context).pop(),
          child: Text(t.cancel),
        ),
        FilledButton(
          key: const Key('movementConfirm'),
          onPressed: _busy ? null : _save,
          child: Text(t.save),
        ),
      ],
    );
  }
}

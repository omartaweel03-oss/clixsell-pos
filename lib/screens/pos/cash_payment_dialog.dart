import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../l10n/checkout_strings.dart';
import '../../theme/app_theme.dart';
import '../../utils/formatters.dart';

/// Cash payment (store upgrade, feature 3): "How much did the customer give
/// you?" Shows the total due, the amount received, and the change due or the
/// amount still to pay. Returns the amount received in cents (agorot), or
/// null when cancelled. Complete is enabled only when the amount covers the
/// total; the server checks it again against its own total.
class CashPaymentDialog extends StatefulWidget {
  const CashPaymentDialog({super.key, required this.totalCents});

  final int totalCents;

  static Future<int?> show(BuildContext context, int totalCents) =>
      showDialog<int>(
        context: context,
        barrierDismissible: false,
        builder: (_) => CashPaymentDialog(totalCents: totalCents),
      );

  /// "50", "50.5", "50,50", Arabic-Indic digits too. Null when not a positive
  /// amount with at most 2 decimals. Exact: no floating-point parsing.
  static int? parseCents(String input) {
    var v = input.trim();
    const arabic = '٠١٢٣٤٥٦٧٨٩';
    for (var i = 0; i < 10; i++) {
      v = v.replaceAll(arabic[i], '$i');
    }
    v = v.replaceAll('٫', '.').replaceAll(',', '.');
    final m = RegExp(r'^(\d{1,7})(?:\.(\d{1,2}))?$').firstMatch(v);
    if (m == null) return null;
    final whole = int.parse(m.group(1)!);
    final frac = (m.group(2) ?? '').padRight(2, '0');
    final cents = whole * 100 + int.parse(frac);
    return cents > 0 ? cents : null;
  }

  /// Quick amounts: the exact total, then the next round banknote amounts.
  static List<int> quickAmounts(int totalCents) {
    final out = <int>{totalCents};
    for (final note in const [10, 20, 50, 100, 200]) {
      final n = note * 100;
      final up = ((totalCents + n - 1) ~/ n) * n;
      if (up > totalCents) out.add(up);
    }
    final list = out.toList()..sort();
    return list.take(5).toList();
  }

  @override
  State<CashPaymentDialog> createState() => _CashPaymentDialogState();
}

class _CashPaymentDialogState extends State<CashPaymentDialog> {
  final _amount = TextEditingController();

  @override
  void dispose() {
    _amount.dispose();
    super.dispose();
  }

  int? get _cents => CashPaymentDialog.parseCents(_amount.text);

  bool get _ok => (_cents ?? -1) >= widget.totalCents;

  bool _closed = false;

  /// Closes once: a fast double tap must not pop the page under the dialog.
  void _submit() {
    if (!_ok || _closed) return;
    _closed = true;
    Navigator.of(context).pop(_cents);
  }

  void _set(int cents) {
    final text = cents % 100 == 0
        ? '${cents ~/ 100}'
        : (cents / 100).toStringAsFixed(2);
    setState(
      () => _amount.value = TextEditingValue(
        text: text,
        selection: TextSelection.collapsed(offset: text.length),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final t = CheckoutStrings.of(context);
    final cents = _cents;
    final typed = _amount.text.trim().isNotEmpty;

    Widget line(String label, String value, {Color? color, Key? key}) =>
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  label,
                  style: const TextStyle(color: AppColors.textSecondary),
                ),
              ),
              Text(
                value,
                key: key,
                style: TextStyle(
                  fontSize: 20,
                  fontWeight: FontWeight.w800,
                  color: color ?? AppColors.textPrimary,
                ),
              ),
            ],
          ),
        );

    Widget result;
    if (!typed) {
      result = const SizedBox(height: 36);
    } else if (cents == null) {
      result = Text(
        t.invalidAmount,
        key: const Key('cashInvalid'),
        style: const TextStyle(color: AppColors.danger),
      );
    } else if (cents >= widget.totalCents) {
      result = line(
        t.changeDue,
        Formatters.currency((cents - widget.totalCents) / 100),
        color: AppColors.success,
        key: const Key('cashChange'),
      );
    } else {
      result = Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          line(
            t.remaining,
            Formatters.currency((widget.totalCents - cents) / 100),
            color: AppColors.danger,
            key: const Key('cashRemaining'),
          ),
          Text(
            t.notEnough,
            style: const TextStyle(color: AppColors.danger, fontSize: 12),
          ),
        ],
      );
    }

    return AlertDialog(
      key: const Key('cashDialog'),
      title: Text(t.cashTitle),
      content: SizedBox(
        width: 380,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            line(
              t.totalDue,
              Formatters.currency(widget.totalCents / 100),
              color: AppColors.primary,
              key: const Key('cashTotalDue'),
            ),
            const SizedBox(height: AppSpacing.md),
            TextField(
              key: const Key('cashReceivedField'),
              controller: _amount,
              autofocus: true,
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
              ),
              inputFormatters: [
                FilteringTextInputFormatter.allow(RegExp(r'[0-9٠-٩.,٫]')),
                LengthLimitingTextInputFormatter(10),
              ],
              decoration: InputDecoration(
                labelText: t.amountReceived,
                hintText: t.amountHint,
                prefixIcon: const Icon(Icons.payments_outlined),
              ),
              onChanged: (_) => setState(() {}),
              onSubmitted: (_) => _submit(),
            ),
            const SizedBox(height: AppSpacing.sm),
            Wrap(
              spacing: AppSpacing.sm,
              runSpacing: AppSpacing.sm,
              children: [
                for (final q in CashPaymentDialog.quickAmounts(
                  widget.totalCents,
                ))
                  OutlinedButton(
                    key: Key('cashQuick-$q'),
                    onPressed: () => _set(q),
                    child: Text(
                      q == widget.totalCents
                          ? t.exactAmount
                          : Formatters.currency(q / 100),
                    ),
                  ),
              ],
            ),
            const SizedBox(height: AppSpacing.md),
            result,
          ],
        ),
      ),
      actions: [
        TextButton(
          key: const Key('cashCancel'),
          onPressed: () {
            if (_closed) return;
            _closed = true;
            Navigator.of(context).pop();
          },
          child: Text(t.cancel),
        ),
        FilledButton.icon(
          key: const Key('cashConfirm'),
          onPressed: _ok ? _submit : null,
          icon: const Icon(Icons.check_circle_outline_rounded),
          label: Text(t.completePayment),
        ),
      ],
    );
  }
}

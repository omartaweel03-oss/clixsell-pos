import 'package:flutter/material.dart';

import '../../l10n/cheque_strings.dart';
import '../../theme/app_theme.dart';
import '../../utils/formatters.dart';

/// Checkout by cheque (feature 9): number, bank, due date and notes. The amount is the invoice
/// total (the backend checks it again). Returns the details to send, or null when canceled.
class ChequePaymentDialog extends StatefulWidget {
  const ChequePaymentDialog({super.key, required this.totalCents});

  final int totalCents;

  static Future<Map<String, dynamic>?> show(BuildContext context, int totalCents) =>
      showDialog<Map<String, dynamic>>(
        context: context,
        barrierDismissible: false,
        builder: (_) => ChequePaymentDialog(totalCents: totalCents),
      );

  @override
  State<ChequePaymentDialog> createState() => _ChequePaymentDialogState();
}

class _ChequePaymentDialogState extends State<ChequePaymentDialog> {
  final _number = TextEditingController();
  final _bank = TextEditingController();
  final _notes = TextEditingController();
  DateTime? _due;
  String? _error;
  bool _closed = false;

  @override
  void dispose() {
    _number.dispose();
    _bank.dispose();
    _notes.dispose();
    super.dispose();
  }

  String _date(DateTime d) =>
      '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  void _confirm() {
    final t = ChequeStrings.of(context);
    final number = _number.text.trim(), bank = _bank.text.trim();
    if (number.isEmpty || bank.length < 2 || _due == null) {
      setState(() => _error = t.required);
      return;
    }
    if (_closed) return;
    _closed = true;
    Navigator.of(context).pop(<String, dynamic>{
      'number': number,
      'bank': bank,
      'amount': widget.totalCents / 100,
      'dueDate': _date(_due!),
      if (_notes.text.trim().isNotEmpty) 'notes': _notes.text.trim(),
    });
  }

  @override
  Widget build(BuildContext context) {
    final t = ChequeStrings.of(context);
    final now = DateTime.now();
    return AlertDialog(
      key: const Key('chequeDialog'),
      title: Text(t.title),
      content: SizedBox(
        width: 400,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(children: [
                Expanded(child: Text(t.amount, style: const TextStyle(color: AppColors.textSecondary))),
                Text(Formatters.currency(widget.totalCents / 100),
                    key: const Key('chequeAmount'), style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 18)),
              ]),
              Text(t.amountIsTotal, style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
              const SizedBox(height: AppSpacing.md),
              TextField(key: const Key('chequeNumberField'), controller: _number, maxLength: 30, autofocus: true,
                  decoration: InputDecoration(labelText: t.number)),
              TextField(key: const Key('chequeBankField'), controller: _bank, maxLength: 80,
                  decoration: InputDecoration(labelText: t.bank)),
              OutlinedButton.icon(
                key: const Key('chequeDueButton'),
                icon: const Icon(Icons.event_outlined),
                label: Text(_due == null ? t.chooseDate : '${t.dueDate}: ${_date(_due!)}'),
                onPressed: () async {
                  final picked = await showDatePicker(
                    context: context,
                    initialDate: _due ?? now,
                    firstDate: now.subtract(const Duration(days: 365)),
                    lastDate: now.add(const Duration(days: 730)),
                  );
                  if (picked != null) setState(() => _due = picked);
                },
              ),
              const SizedBox(height: AppSpacing.sm),
              TextField(key: const Key('chequeNotesField'), controller: _notes, maxLength: 300,
                  decoration: InputDecoration(labelText: t.notes)),
              if (_error != null)
                Text(_error!, key: const Key('chequeError'), style: const TextStyle(color: AppColors.danger)),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(key: const Key('chequeCancel'), onPressed: () => Navigator.of(context).pop(), child: Text(t.cancel)),
        FilledButton(key: const Key('chequeConfirm'), onPressed: _confirm, child: Text(t.confirm)),
      ],
    );
  }
}

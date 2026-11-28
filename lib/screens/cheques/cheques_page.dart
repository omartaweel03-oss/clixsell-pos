import 'package:flutter/material.dart';

import '../../l10n/cheque_strings.dart';
import '../../services/cheque_api_client.dart';
import '../../theme/app_theme.dart';
import '../../utils/formatters.dart';
import '../../widgets/page_header.dart';
import '../shifts/shift_dialogs.dart' show shiftError;

/// Store Admin: cheques (feature 9) — status, history (audit trail) and what is owed back.
class ChequesPage extends StatefulWidget {
  const ChequesPage({super.key, required this.api});

  final ChequeApiClient api;

  @override
  State<ChequesPage> createState() => _ChequesPageState();
}

/// Allowed next statuses (the backend and the database enforce the same).
const _next = {
  'RECEIVED': ['DEPOSITED', 'CANCELLED'],
  'DEPOSITED': ['CLEARED', 'BOUNCED'],
  'BOUNCED': ['DEPOSITED', 'CANCELLED'],
};

double _num(Object? v) => (v as num?)?.toDouble() ?? 0;

class _ChequesPageState extends State<ChequesPage> {
  List<Map<String, dynamic>> _cheques = const [];
  Map<String, dynamic>? _summary;
  String? _status;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final list = await widget.api.list(status: _status);
      final summary = await widget.api.summary();
      if (mounted) {
        setState(() {
          _cheques = list;
          _summary = summary;
          _error = null;
        });
      }
    } catch (e) {
      if (mounted) setState(() => _error = shiftError(context, e));
    }
  }

  void _say(String m, {bool error = false}) => ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(m), backgroundColor: error ? AppColors.danger : null));

  Future<String?> _ask(String title, String label, {bool required = true}) {
    final t = ChequeStrings.of(context);
    final c = TextEditingController();
    String? err; // kept across rebuilds of the dialog
    return showDialog<String>(
      context: context,
      builder: (ctx) => StatefulBuilder(builder: (ctx, set) {
        return AlertDialog(
          title: Text(title),
          content: TextField(key: const Key('chequeNoteField'), controller: c, maxLength: 300, autofocus: true,
              decoration: InputDecoration(labelText: label, errorText: err)),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: Text(t.cancel)),
            FilledButton(
              key: const Key('chequeNoteSave'),
              onPressed: () {
                final v = c.text.trim();
                if (required && v.length < 3) {
                  set(() => err = t.noteRequired);
                  return;
                }
                Navigator.pop(ctx, v);
              },
              child: Text(t.save),
            ),
          ],
        );
      }),
    );
  }

  Future<void> _open(Map<String, dynamic> row) async {
    final t = ChequeStrings.of(context);
    final id = (row['chequeId'] as num).toInt();
    Map<String, dynamic> d;
    try {
      d = await widget.api.details(id);
    } catch (e) {
      if (mounted) _say(shiftError(context, e), error: true);
      return;
    }
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      builder: (ctx) => StatefulBuilder(builder: (ctx, set) {
        final c = d['cheque'] as Map<String, dynamic>;
        final status = c['status'] as String;
        Future<void> act(Future<Map<String, dynamic>> Function() f) async {
          try {
            final fresh = await f();
            set(() => d = fresh);
          } catch (e) {
            if (mounted) {
              ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(content: Text(shiftError(context, e)), backgroundColor: AppColors.danger));
            }
          }
        }

        Widget kv(String k, String v, {String? key}) => Padding(
          padding: const EdgeInsets.symmetric(vertical: 2),
          child: Row(children: [
            Expanded(child: Text(k, style: const TextStyle(color: AppColors.textSecondary))),
            Text(v, key: key == null ? null : Key(key), style: const TextStyle(fontWeight: FontWeight.w600)),
          ]),
        );
        return AlertDialog(
          key: const Key('chequeDetailsDialog'),
          title: Text('${c['bankName']} · ${c['chequeNumber']}'),
          content: SizedBox(
            width: 540,
            child: SingleChildScrollView(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  kv(t.amount, Formatters.currency(_num(c['amount']))),
                  kv(t.dueDate, '${c['dueDate']}'),
                  kv(t.invoice, '${c['invoiceNumber']}'),
                  kv(t.status(status), '', key: 'chequeStatus-$status'),
                  if (c['notes'] != null) kv(t.notes, '${c['notes']}'),
                  Wrap(spacing: AppSpacing.sm, children: [
                    for (final to in _next[status] ?? const <String>[])
                      OutlinedButton(
                        key: Key('chequeMove-$to'),
                        onPressed: () async {
                          final needsNote = to == 'BOUNCED' || to == 'CANCELLED';
                          final note = await _ask(t.moveTo(to), t.note, required: needsNote);
                          if (note == null) return;
                          await act(() => widget.api.changeStatus(id, to,
                              note: note.isEmpty ? null : note, version: (c['version'] as num).toInt()));
                        },
                        child: Text(t.moveTo(to)),
                      ),
                  ]),
                  const Divider(),
                  Text(t.history, style: const TextStyle(fontWeight: FontWeight.w700)),
                  for (final e in (d['events'] as List).cast<Map<String, dynamic>>())
                    kv('${'${e['createdAt']}'.replaceFirst('T', ' ')} · ${e['userName']}'
                        '${e['note'] == null ? '' : ' · ${e['note']}'}', t.status('${e['toStatus']}')),
                  const Divider(),
                  Text(t.refunds, style: const TextStyle(fontWeight: FontWeight.w700)),
                  Text(t.notClearedHint, style: const TextStyle(color: AppColors.textMuted, fontSize: 12)),
                  for (final r in (d['refunds'] as List).cast<Map<String, dynamic>>())
                    Padding(
                      key: Key('chequeRefund-${r['chequeRefundId']}'),
                      padding: const EdgeInsets.symmetric(vertical: 4),
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text('${Formatters.currency(_num(r['amount']))} · ${t.status('${r['status']}')}'
                            '${r['paidMethod'] == null ? '' : ' · ${r['paidMethod']}'}'),
                        if (r['status'] == 'PENDING') Wrap(spacing: AppSpacing.xs, children: [
                          TextButton(
                            key: Key('refundCash-${r['chequeRefundId']}'),
                            onPressed: () => act(() => widget.api.payRefund((r['chequeRefundId'] as num).toInt(), 'CASH',
                                requestKey: ChequeApiClient.newRequestKey())),
                            child: Text(t.payCash),
                          ),
                          TextButton(
                            key: Key('refundBank-${r['chequeRefundId']}'),
                            onPressed: () => act(() => widget.api.payRefund((r['chequeRefundId'] as num).toInt(), 'BANK_TRANSFER')),
                            child: Text(t.payBank),
                          ),
                          TextButton(
                            key: Key('refundCancel-${r['chequeRefundId']}'),
                            onPressed: () async {
                              final why = await _ask(t.cancelRefund, t.reason);
                              if (why == null) return;
                              await act(() => widget.api.cancelRefund((r['chequeRefundId'] as num).toInt(), why));
                            },
                            child: Text(t.cancelRefund),
                          ),
                        ]),
                      ]),
                    ),
                ],
              ),
            ),
          ),
          actions: [FilledButton(onPressed: () => Navigator.pop(ctx), child: Text(t.cancel))],
        );
      }),
    );
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    final t = ChequeStrings.of(context);
    final s = _summary;
    Widget tile(String label, String value, String key) => Expanded(
      child: Material(
        color: AppColors.surface,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
          side: const BorderSide(color: AppColors.border),
        ),
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.md),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(label, style: const TextStyle(color: AppColors.textSecondary)),
            Text(value, key: Key(key), style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 18)),
          ]),
        ),
      ),
    );
    return ListView(
      key: const Key('chequesPage'),
      padding: const EdgeInsets.all(AppSpacing.xl),
      children: [
        PageHeader(title: t.navCheques, description: t.pageSubtitle),
        const SizedBox(height: AppSpacing.lg),
        if (_error != null) Text(_error!, key: const Key('chequesError'), style: const TextStyle(color: AppColors.danger)),
        if (s != null) ...[
          Row(children: [
            for (final st in const ['RECEIVED', 'DEPOSITED', 'CLEARED', 'BOUNCED']) ...[
              tile(t.status(st),
                  '${(s['countByStatus'] as Map)[st]} · ${Formatters.currency(_num((s['amountByStatus'] as Map)[st]))}',
                  'chequeSummary-$st'),
              const SizedBox(width: AppSpacing.sm),
            ],
          ]),
          const SizedBox(height: AppSpacing.sm),
          Row(children: [
            tile(t.dueSoon, '${s['dueSoonCount']} · ${Formatters.currency(_num(s['dueSoonAmount']))}', 'chequeDueSoon'),
            const SizedBox(width: AppSpacing.sm),
            tile(t.pendingRefunds, '${s['pendingRefundCount']} · ${Formatters.currency(_num(s['pendingRefundAmount']))}',
                'chequePendingRefunds'),
          ]),
        ],
        const SizedBox(height: AppSpacing.lg),
        SegmentedButton<String?>(
          key: const Key('chequeStatusFilter'),
          segments: [
            ButtonSegment(value: null, label: Text(t.all)),
            for (final st in const ['RECEIVED', 'DEPOSITED', 'CLEARED', 'BOUNCED', 'CANCELLED'])
              ButtonSegment(value: st, label: Text(t.status(st))),
          ],
          selected: {_status},
          onSelectionChanged: (v) {
            setState(() => _status = v.first);
            _load();
          },
        ),
        const SizedBox(height: AppSpacing.sm),
        if (_cheques.isEmpty) Text(t.none, key: const Key('chequesEmpty')),
        Material(
          color: Colors.transparent,
          child: Column(children: [
            for (final c in _cheques)
              ListTile(
                key: Key('cheque-${c['chequeId']}'),
                onTap: () => _open(c),
                leading: const Icon(Icons.receipt_long_outlined, color: AppColors.primary),
                title: Text('${c['bankName']} · ${c['chequeNumber']} · ${c['invoiceNumber']}'),
                subtitle: Text('${t.dueDate}: ${c['dueDate']} · ${t.status('${c['status']}')}'
                    '${_num(c['pendingRefunds']) > 0 ? ' · ${t.pendingRefunds}: ${Formatters.currency(_num(c['pendingRefunds']))}' : ''}'),
                trailing: Text(Formatters.currency(_num(c['amount'])), style: const TextStyle(fontWeight: FontWeight.w700)),
              ),
          ]),
        ),
      ],
    );
  }
}

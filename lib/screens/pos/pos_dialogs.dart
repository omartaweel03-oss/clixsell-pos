import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:qr_flutter/qr_flutter.dart';

import '../../l10n/app_strings.dart';
import '../../models/sale.dart';
import '../../services/api_http.dart';
import '../../services/sales_api_client.dart';
import '../../state/pos_cart.dart';
import '../../theme/app_theme.dart';
import '../../utils/formatters.dart';
import '../../widgets/page_header.dart';
import 'pos_widgets.dart';

String describeApiError(ApiException e, AppStrings s) => e.isNetworkError
    ? s.networkError
    : e.isForbidden
    ? s.notAllowed
    : e.message;

String _dateTime(BuildContext context, DateTime d) {
  final l = MaterialLocalizations.of(context);
  return '${l.formatMediumDate(d)} ${l.formatTimeOfDay(TimeOfDay.fromDateTime(d))}';
}

// ── Discount ─────────────────────────────────────────────────────────────────

/// Invoice discount as an amount or a percentage, limited to the store's maximum.
class DiscountDialog extends StatefulWidget {
  const DiscountDialog({super.key, required this.cart});

  final PosCart cart;

  @override
  State<DiscountDialog> createState() => _DiscountDialogState();
}

class _DiscountDialogState extends State<DiscountDialog> {
  late DiscountType _type = widget.cart.discountType;
  late final _value = TextEditingController(
    text: widget.cart.discountValue > 0
        ? widget.cart.discountValue.toString()
        : '',
  );
  String? _error;

  @override
  void dispose() {
    _value.dispose();
    super.dispose();
  }

  void _apply() {
    final s = AppStrings.of(context);
    final v = double.tryParse(_value.text.trim()) ?? -1;
    if (v < 0) {
      setState(() => _error = s.vPriceInvalid);
      return;
    }
    if (!widget.cart.setDiscount(_type, v)) {
      setState(() => _error = s.discountTooHigh);
      return;
    }
    Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final cart = widget.cart;
    return AlertDialog(
      title: Text(s.discountTitle),
      content: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 380, minWidth: 300),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            SegmentedButton<DiscountType>(
              segments: [
                ButtonSegment(
                  value: DiscountType.amount,
                  label: Text(s.discountAmount),
                ),
                ButtonSegment(
                  value: DiscountType.percent,
                  label: Text(s.discountPercent),
                ),
              ],
              selected: {_type},
              onSelectionChanged: (v) => setState(() => _type = v.first),
            ),
            const SizedBox(height: AppSpacing.lg),
            TextField(
              key: const Key('discountValueField'),
              controller: _value,
              autofocus: true,
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
              ),
              inputFormatters: [
                FilteringTextInputFormatter.allow(RegExp(r'[0-9.]')),
              ],
              onSubmitted: (_) => _apply(),
              decoration: InputDecoration(
                labelText: s.discountValueLabel,
                suffixText: _type == DiscountType.percent ? '%' : '₪',
                errorText: _error,
                helperText: s.maxDiscountHint(
                  cart.maxDiscountPercent,
                  Formatters.currency(cart.maxDiscountAmount),
                ),
                helperMaxLines: 2,
              ),
            ),
          ],
        ),
      ),
      actions: [
        if (cart.discount > 0)
          TextButton(
            onPressed: () {
              cart.setDiscount(DiscountType.amount, 0);
              Navigator.of(context).pop();
            },
            style: TextButton.styleFrom(foregroundColor: AppColors.danger),
            child: Text(s.removeDiscount),
          ),
        OutlinedButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(s.cancel),
        ),
        FilledButton(
          key: const Key('discountApplyButton'),
          onPressed: _apply,
          child: Text(s.apply),
        ),
      ],
    );
  }
}

// ── Suspended invoices ───────────────────────────────────────────────────────

/// Lists the store's suspended invoices. Returns the invoice to resume, or
/// null. Deleting cancels the invoice on the server (kept as CANCELED).
class SuspendedInvoicesDialog extends StatefulWidget {
  const SuspendedInvoicesDialog({
    super.key,
    required this.api,
    required this.onCanceled,
  });

  final SalesApiClient api;

  /// Called with the id of each invoice canceled from this dialog.
  final ValueChanged<int> onCanceled;

  @override
  State<SuspendedInvoicesDialog> createState() =>
      _SuspendedInvoicesDialogState();
}

class _SuspendedInvoicesDialogState extends State<SuspendedInvoicesDialog> {
  List<Sale>? _sales;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final list = await widget.api.listSuspended();
      if (mounted) setState(() => _sales = list);
    } on ApiException catch (e) {
      if (mounted) {
        setState(() => _error = describeApiError(e, AppStrings.of(context)));
      }
    }
  }

  Future<void> _delete(Sale sale) async {
    final s = AppStrings.of(context);
    final ok = await confirmAction(
      context,
      title: s.confirmCancelTitle,
      body: s.confirmCancelSuspendedBody(sale.invoiceNumber),
      confirmLabel: s.cancelInvoice,
      cancelLabel: s.keepInvoice,
      icon: Icons.highlight_off_rounded,
      color: AppColors.danger,
    );
    if (!ok || !mounted) return;
    try {
      await widget.api.cancelSuspended(sale.saleId);
      widget.onCanceled(sale.saleId);
      if (mounted) {
        setState(
          () => _sales = _sales!.where((x) => x.saleId != sale.saleId).toList(),
        );
      }
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = describeApiError(e, s));
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final sales = _sales;
    Widget body;
    if (_error != null) {
      body = Text(_error!, style: const TextStyle(color: AppColors.danger));
    } else if (sales == null) {
      body = const Padding(
        padding: EdgeInsets.all(AppSpacing.xl),
        child: Center(child: CircularProgressIndicator()),
      );
    } else if (sales.isEmpty) {
      body = Padding(
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.xl),
        child: Text(
          s.noSuspended,
          textAlign: TextAlign.center,
          style: const TextStyle(color: AppColors.textSecondary),
        ),
      );
    } else {
      body = ListView.separated(
        shrinkWrap: true,
        itemCount: sales.length,
        separatorBuilder: (_, _) => const Divider(height: 1),
        itemBuilder: (context, i) {
          final sale = sales[i];
          return Padding(
            key: ValueKey('suspended-${sale.saleId}'),
            padding: const EdgeInsets.symmetric(vertical: AppSpacing.sm),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        sale.invoiceNumber,
                        style: const TextStyle(
                          fontWeight: FontWeight.w800,
                          color: AppColors.primary,
                        ),
                      ),
                      Text(
                        s.itemsAndCashier(sale.itemCount, sale.cashierName),
                        style: const TextStyle(
                          color: AppColors.textSecondary,
                          fontSize: 12.5,
                        ),
                      ),
                      Text(
                        _dateTime(context, sale.createdAt),
                        style: const TextStyle(
                          color: AppColors.textMuted,
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                ),
                Text(
                  Formatters.currency(sale.total),
                  style: const TextStyle(fontWeight: FontWeight.w800),
                ),
                const SizedBox(width: AppSpacing.md),
                IconButton(
                  key: Key('deleteSuspended-${sale.saleId}'),
                  tooltip: s.cancelInvoice,
                  color: AppColors.danger,
                  onPressed: () => _delete(sale),
                  icon: const Icon(Icons.delete_outline_rounded),
                ),
                FilledButton.icon(
                  key: Key('resume-${sale.saleId}'),
                  onPressed: () => Navigator.of(context).pop(sale),
                  icon: const Icon(
                    Icons.shopping_cart_checkout_rounded,
                    size: 18,
                  ),
                  label: Text(s.resume),
                ),
              ],
            ),
          );
        },
      );
    }
    return AlertDialog(
      title: Text(s.suspendedTitle),
      content: SizedBox(width: 520, child: body),
      actions: [
        OutlinedButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(s.close),
        ),
      ],
    );
  }
}

// ── Return ───────────────────────────────────────────────────────────────────

/// Return flow (report Figure 3.15): find the sale by invoice number or
/// receipt QR code → choose the product → quantity and reason → confirmation.
/// Returns the result when a return was made.
class ReturnDialog extends StatefulWidget {
  const ReturnDialog({super.key, required this.api});

  final SalesApiClient api;

  @override
  State<ReturnDialog> createState() => _ReturnDialogState();
}

class _ReturnDialogState extends State<ReturnDialog> {
  final _code = TextEditingController();
  final _reason = TextEditingController();
  Sale? _sale;
  SaleLine? _line;
  int _quantity = 1;
  ReturnResult? _result;
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _code.dispose();
    _reason.dispose();
    super.dispose();
  }

  Future<void> _find() async {
    if (_code.text.trim().isEmpty) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final sale = await widget.api.lookup(_code.text);
      if (!mounted) return;
      setState(() {
        _sale = sale;
        _line = sale.items.where((l) => l.returnableQuantity > 0).firstOrNull;
        _quantity = 1;
        _busy = false;
      });
    } on ApiException catch (e) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = describeApiError(e, AppStrings.of(context));
        });
      }
    }
  }

  Future<void> _submit() async {
    final s = AppStrings.of(context);
    final sale = _sale, line = _line;
    if (sale == null || line == null) return;
    if (_reason.text.trim().length < 2) {
      setState(() => _error = s.returnReasonHint);
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final result = await widget.api.returnProduct(
        saleId: sale.saleId,
        saleItemId: line.saleItemId,
        quantity: _quantity,
        reason: _reason.text,
      );
      if (mounted) {
        setState(() {
          _result = result;
          _busy = false;
        });
      }
    } on ApiException catch (e) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = e.fieldErrors.values.firstOrNull ?? describeApiError(e, s);
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final sale = _sale;
    final result = _result;
    late final Widget body;
    List<Widget> actions;

    if (result != null) {
      body = Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(
            Icons.check_circle_rounded,
            color: AppColors.success,
            size: 48,
          ),
          const SizedBox(height: AppSpacing.md),
          Text(
            s.returnDone,
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: AppSpacing.sm),
          Text(
            '${result.productName} × ${result.quantity} · ${result.invoiceNumber}',
          ),
          const SizedBox(height: AppSpacing.xs),
          Text(
            s.refundInfo(
              Formatters.currency(result.refundAmount),
              result.refundMethod == null
                  ? '—'
                  : paymentLabel(result.refundMethod!, s),
            ),
            key: const Key('refundInfo'),
            style: const TextStyle(
              fontWeight: FontWeight.w800,
              color: AppColors.primary,
            ),
          ),
          const SizedBox(height: AppSpacing.xs),
          Text(
            s.stockRestored,
            style: const TextStyle(color: AppColors.textSecondary),
          ),
        ],
      );
      actions = [
        FilledButton(
          onPressed: () => Navigator.of(context).pop(result),
          child: Text(s.done),
        ),
      ];
    } else if (sale == null) {
      body = Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextField(
            key: const Key('returnCodeField'),
            controller: _code,
            autofocus: true,
            onSubmitted: (_) => _find(),
            decoration: InputDecoration(
              labelText: s.returnCodeLabel,
              hintText: s.returnCodeHint,
              prefixIcon: const Icon(Icons.qr_code_scanner_rounded),
              errorText: _error,
              errorMaxLines: 3,
            ),
          ),
        ],
      );
      actions = [
        OutlinedButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(s.cancel),
        ),
        FilledButton(
          key: const Key('returnFindButton'),
          onPressed: _busy ? null : _find,
          child: Text(s.find),
        ),
      ];
    } else {
      final returnable = sale.items
          .where((l) => l.returnableQuantity > 0)
          .toList();
      body = Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            '${sale.invoiceNumber} · ${_dateTime(context, sale.completedAt ?? sale.createdAt)} · '
            '${Formatters.currency(sale.total)}',
            style: const TextStyle(fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: AppSpacing.md),
          if (returnable.isEmpty)
            Text(
              s.nothingToReturn,
              style: const TextStyle(color: AppColors.textSecondary),
            )
          else ...[
            Text(
              s.chooseProduct,
              style: const TextStyle(color: AppColors.textSecondary),
            ),
            const SizedBox(height: AppSpacing.xs),
            RadioGroup<int>(
              groupValue: _line?.saleItemId,
              onChanged: (v) => setState(() {
                _line = returnable.firstWhere((l) => l.saleItemId == v);
                _quantity = 1;
              }),
              child: Column(
                children: [
                  for (final l in returnable)
                    RadioListTile<int>(
                      key: Key('returnLine-${l.saleItemId}'),
                      value: l.saleItemId,
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      title: Text(
                        l.productName,
                        style: const TextStyle(fontWeight: FontWeight.w700),
                      ),
                      subtitle: Text(
                        s.soldReturnable(l.quantity, l.returnableQuantity),
                      ),
                    ),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.sm),
            Row(
              children: [
                Text(
                  s.returnQuantity,
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
                const Spacer(),
                IconButton.outlined(
                  key: const Key('returnQtyDec'),
                  onPressed: _quantity > 1
                      ? () => setState(() => _quantity--)
                      : null,
                  icon: const Icon(Icons.remove_rounded, size: 18),
                ),
                SizedBox(
                  width: 40,
                  child: Text(
                    '$_quantity',
                    key: const Key('returnQty'),
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      fontWeight: FontWeight.w800,
                      fontSize: 16,
                    ),
                  ),
                ),
                IconButton.outlined(
                  key: const Key('returnQtyInc'),
                  onPressed: _quantity < (_line?.returnableQuantity ?? 1)
                      ? () => setState(() => _quantity++)
                      : null,
                  icon: const Icon(Icons.add_rounded, size: 18),
                ),
              ],
            ),
            const SizedBox(height: AppSpacing.md),
            TextField(
              key: const Key('returnReasonField'),
              controller: _reason,
              decoration: InputDecoration(
                labelText: '${s.returnReason} *',
                hintText: s.returnReasonHint,
                errorText: _error,
                errorMaxLines: 3,
              ),
            ),
          ],
        ],
      );
      actions = [
        TextButton(
          onPressed: () => setState(() {
            _sale = null;
            _line = null;
            _error = null;
          }),
          child: Text(s.searchAgain),
        ),
        FilledButton(
          key: const Key('confirmReturnButton'),
          onPressed: _busy || returnable.isEmpty ? null : _submit,
          style: FilledButton.styleFrom(backgroundColor: AppColors.danger),
          child: Text(s.confirmReturn),
        ),
      ];
    }

    return AlertDialog(
      title: Text(s.returnTitle),
      content: SizedBox(width: 440, child: SingleChildScrollView(child: body)),
      actions: actions,
    );
  }
}

// ── Receipt ──────────────────────────────────────────────────────────────────

/// Digital receipt with a QR code after a completed sale (report SR12).
class ReceiptDialog extends StatelessWidget {
  const ReceiptDialog({super.key, required this.sale});

  final Sale sale;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    Widget row(String a, String b, {bool bold = false}) => Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: [
          Expanded(
            child: Text(
              a,
              style: TextStyle(
                fontWeight: bold ? FontWeight.w800 : FontWeight.w400,
              ),
            ),
          ),
          const SizedBox(width: AppSpacing.sm),
          Flexible(
            child: Text(
              b,
              textAlign: TextAlign.end,
              style: TextStyle(
                fontWeight: bold ? FontWeight.w800 : FontWeight.w600,
              ),
            ),
          ),
        ],
      ),
    );
    final code = sale.receiptCode ?? sale.invoiceNumber;
    return AlertDialog(
      key: const Key('receiptDialog'),
      title: Row(
        children: [
          const Icon(Icons.check_circle_rounded, color: AppColors.success),
          const SizedBox(width: AppSpacing.sm),
          Expanded(child: Text(s.receiptTitle)),
        ],
      ),
      content: SizedBox(
        width: 380,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              row(
                sale.invoiceNumber,
                _dateTime(context, sale.completedAt ?? sale.createdAt),
                bold: true,
              ),
              row(s.cashierLabel, sale.cashierName),
              const Divider(),
              for (final l in sale.items)
                row(
                  '${l.productName} × ${l.quantity}',
                  Formatters.currency(l.lineTotal),
                ),
              const Divider(),
              row(s.subtotalLabel, Formatters.currency(sale.subtotal)),
              if (sale.discount > 0)
                row(s.discountLabel, '− ${Formatters.currency(sale.discount)}'),
              row(s.taxLabel(sale.taxRate), Formatters.currency(sale.tax)),
              row(s.totalLabel, Formatters.currency(sale.total), bold: true),
              if (sale.paymentMethod != null)
                row(s.paidBy, paymentLabel(sale.paymentMethod!, s)),
              const SizedBox(height: AppSpacing.lg),
              Center(
                child: Container(
                  padding: const EdgeInsets.all(AppSpacing.sm),
                  color: Colors.white,
                  child: QrImageView(
                    key: const Key('receiptQr'),
                    data: code,
                    size: 160,
                    backgroundColor: Colors.white,
                  ),
                ),
              ),
              const SizedBox(height: AppSpacing.xs),
              SelectableText(
                code,
                key: const Key('receiptCode'),
                textAlign: TextAlign.center,
                style: const TextStyle(
                  fontFamily: 'monospace',
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: AppSpacing.xs),
              Text(
                s.scanReceipt,
                textAlign: TextAlign.center,
                style: const TextStyle(
                  color: AppColors.textSecondary,
                  fontSize: 12.5,
                ),
              ),
            ],
          ),
        ),
      ),
      actions: [
        FilledButton(
          key: const Key('receiptCloseButton'),
          onPressed: () => Navigator.of(context).pop(),
          child: Text(s.newInvoice),
        ),
      ],
    );
  }
}

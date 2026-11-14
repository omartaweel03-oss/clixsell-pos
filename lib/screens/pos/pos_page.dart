import 'package:flutter/material.dart';

import '../../l10n/app_strings.dart';
import '../../models/product.dart';
import '../../models/product_category.dart';
import '../../models/sale.dart';
import '../../services/api_http.dart';
import '../../services/sales_api_client.dart';
import '../../state/pos_cart.dart';
import '../../state/product_store.dart';
import '../../theme/app_theme.dart';
import '../../widgets/min_width_scroll.dart';
import '../../widgets/page_header.dart';
import 'pos_dialogs.dart';
import 'pos_resume.dart';
import 'pos_widgets.dart';

/// Cashier Dashboard / POS screen (report Figure 3.2).
///
/// Products come from the user's own store (GET /api/products). The invoice
/// is built in [PosCart]; suspending, completing, cancelling and returns go
/// to the backend, which checks stock, calculates the totals and saves
/// everything for this store only.
class PosPage extends StatefulWidget {
  const PosPage({super.key, required this.api, this.message});

  final SalesApiClient api;

  /// Shown once when the page opens (e.g. "Invoice INV-000003 resumed").
  final String? message;

  @override
  State<PosPage> createState() => _PosPageState();
}

class _PosPageState extends State<PosPage> {
  final _search = TextEditingController();
  final _searchFocus = FocusNode();
  ProductCategory? _category;
  bool _busy = false;

  /// The POS has its own messenger so messages appear above the action bar
  /// instead of covering its buttons.
  final _messengerKey = GlobalKey<ScaffoldMessengerState>();

  @override
  void initState() {
    super.initState();
    final message = widget.message;
    if (message != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _toast(message);
      });
    }
  }

  @override
  void dispose() {
    _search.dispose();
    _searchFocus.dispose();
    super.dispose();
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  void _toast(String message, {bool error = false}) {
    _messengerKey.currentState
      ?..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Text(message),
          backgroundColor: error ? AppColors.danger : null,
          duration: const Duration(seconds: 3),
        ),
      );
  }

  List<Product> _visibleProducts(List<Product> all) {
    final q = _search.text.trim().toLowerCase();
    return all.where((p) {
      if (_category != null && p.category != _category) return false;
      if (q.isEmpty) return true;
      return p.name.toLowerCase().contains(q) ||
          p.barcode.contains(q) ||
          p.category.labelEn.toLowerCase().contains(q) ||
          p.category.labelAr.contains(q);
    }).toList();
  }

  Future<void> _reloadProducts() async {
    final store = ProductStoreScope.read(context);
    final cart = PosCartScope.read(context);
    await store.load();
    cart.refreshProducts(store.products);
  }

  // ── Adding products ──────────────────────────────────────────────────────

  void _add(Product product) {
    final s = AppStrings.of(context);
    switch (PosCartScope.read(context).add(product)) {
      case AddResult.added:
        break;
      case AddResult.outOfStock:
        _toast(s.outOfStockMsg(product.name), error: true);
      case AddResult.stockLimit:
        _toast(s.stockLimit(product.name, product.quantity), error: true);
    }
  }

  void _increment(String productId) {
    final s = AppStrings.of(context);
    final cart = PosCartScope.read(context);
    if (cart.increment(productId) == AddResult.stockLimit) {
      final p = cart.lineFor(productId)!.product;
      _toast(s.stockLimit(p.name, p.quantity), error: true);
    }
  }

  /// Enter in the search box: a scanned / typed barcode adds the product.
  void _onSearchSubmitted(String text) {
    final s = AppStrings.of(context);
    final code = text.trim();
    if (code.isEmpty) return;
    final products = ProductStoreScope.read(context).products;
    final exact = products.where((p) => p.barcode == code).toList();
    final matches = exact.isNotEmpty ? exact : _visibleProducts(products);
    if (matches.length == 1) {
      _add(matches.first);
      _search.clear();
      setState(() {});
    } else if (matches.isEmpty && RegExp(r'^\d+$').hasMatch(code)) {
      _toast(s.barcodeNotFound(code), error: true);
    }
    _searchFocus.requestFocus();
  }

  // ── Invoice actions ──────────────────────────────────────────────────────

  Future<void> _discount() async {
    await showDialog<void>(
      context: context,
      builder: (_) => DiscountDialog(cart: PosCartScope.read(context)),
    );
  }

  Future<void> _clearAll() async {
    final s = AppStrings.of(context);
    final cart = PosCartScope.read(context);
    final ok = await confirmAction(
      context,
      title: s.confirmCancelTitle,
      body: s.confirmCancelBody,
      confirmLabel: s.clearAll,
      cancelLabel: s.keepInvoice,
      icon: Icons.delete_outline_rounded,
      color: AppColors.danger,
    );
    if (ok) cart.clearLines();
  }

  Future<void> _complete() async {
    final s = AppStrings.of(context);
    final cart = PosCartScope.read(context);
    if (cart.isEmpty || _busy) return;
    setState(() => _busy = true);
    try {
      final sale = await widget.api.completeSale(
        cart.toDraft(withPayment: true),
      );
      cart.reset();
      if (!mounted) return;
      setState(() => _busy = false);
      _toast(s.saleCompleted(sale.invoiceNumber));
      await _reloadProducts();
      if (!mounted) return;
      await showDialog<void>(
        context: context,
        builder: (_) => ReceiptDialog(sale: sale),
      );
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() => _busy = false);
      if (e.code == 'INSUFFICIENT_STOCK') {
        await _reloadProducts();
        if (!mounted) return;
        await showDialog<void>(
          context: context,
          builder: (ctx) => AlertDialog(
            icon: const Icon(
              Icons.inventory_2_outlined,
              color: AppColors.danger,
              size: 36,
            ),
            title: Text(s.notEnoughStockTitle),
            content: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                for (final e in e.fieldErrors.entries)
                  Text('• ${e.key}: ${e.value}'),
              ],
            ),
            actions: [
              FilledButton(
                onPressed: () => Navigator.of(ctx).pop(),
                child: Text(s.close),
              ),
            ],
          ),
        );
      } else {
        _toast(describeApiError(e, s), error: true);
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _suspend() async {
    final s = AppStrings.of(context);
    final cart = PosCartScope.read(context);
    if (cart.isEmpty) {
      _toast(s.emptyInvoiceBody, error: true);
      return;
    }
    setState(() => _busy = true);
    try {
      final sale = await widget.api.suspend(cart.toDraft());
      cart.reset();
      if (mounted) _toast(s.invoiceSuspended(sale.invoiceNumber));
    } on ApiException catch (e) {
      if (mounted) _toast(describeApiError(e, s), error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _openSuspended() async {
    final s = AppStrings.of(context);
    final cart = PosCartScope.read(context);
    final sale = await showDialog<Sale>(
      context: context,
      builder: (_) => SuspendedInvoicesDialog(
        api: widget.api,
        onCanceled: (id) {
          if (cart.resumedSaleId == id) cart.reset();
        },
      ),
    );
    if (sale == null || !mounted) return;
    final missing = await loadSuspendedIntoCart(context, sale);
    if (missing == null || !mounted) return;
    _toast(resumedMessage(s, sale, missing));
  }

  Future<void> _newInvoice() async {
    final s = AppStrings.of(context);
    final cart = PosCartScope.read(context);
    if (!cart.isEmpty) {
      final ok = await confirmAction(
        context,
        title: s.confirmNewInvoiceTitle,
        body: s.confirmNewInvoiceBody,
        confirmLabel: s.yesContinue,
        cancelLabel: s.keepInvoice,
      );
      if (!ok) return;
    }
    cart.reset();
    _searchFocus.requestFocus();
  }

  Future<void> _cancel() async {
    final s = AppStrings.of(context);
    final cart = PosCartScope.read(context);
    final resumed = cart.resumedSaleId;
    if (cart.isEmpty && resumed == null) return;
    final ok = await confirmAction(
      context,
      title: s.confirmCancelTitle,
      body: resumed == null
          ? s.confirmCancelBody
          : s.confirmCancelSuspendedBody(cart.invoiceNumber!),
      confirmLabel: s.cancelInvoice,
      cancelLabel: s.keepInvoice,
      icon: Icons.highlight_off_rounded,
      color: AppColors.danger,
    );
    if (!ok || !mounted) return;
    if (resumed != null) {
      try {
        final sale = await widget.api.cancelSuspended(resumed);
        if (mounted) _toast(s.invoiceCanceled(sale.invoiceNumber));
      } on ApiException catch (e) {
        if (mounted) _toast(describeApiError(e, s), error: true);
        return;
      }
    } else {
      _toast(s.invoiceCleared);
    }
    cart.reset();
  }

  Future<void> _return() async {
    final result = await showDialog<ReturnResult>(
      context: context,
      builder: (_) => ReturnDialog(api: widget.api),
    );
    if (result != null && mounted) await _reloadProducts();
  }

  // ── Layout ───────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    final store = ProductStoreScope.of(context);
    final cart = PosCartScope.of(context);
    final products = _visibleProducts(store.products);
    final categories = [
      for (final c in ProductCategory.values)
        if (store.products.any((p) => p.category == c)) c,
    ];

    final productsPanel = PosProductsPanel(
      products: products,
      categories: categories,
      selectedCategory: _category,
      onCategory: (c) => setState(() => _category = c),
      onAdd: _add,
      cart: cart,
    );
    Widget invoicePanel({bool shrink = false}) => PosInvoicePanel(
      cart: cart,
      shrinkWrap: shrink,
      onIncrement: _increment,
      onDiscount: _discount,
      onClear: _clearAll,
    );
    final summary = PosSummaryPanel(
      cart: cart,
      busy: _busy,
      onComplete: _complete,
    );
    final actionBar = PosActionBar(
      onNew: _newInvoice,
      onSuspend: _suspend,
      onReturn: _return,
      onCancel: _cancel,
    );

    return LayoutBuilder(
      builder: (context, c) {
        final pad = c.maxWidth < 600 ? AppSpacing.md : AppSpacing.xl;
        Widget page(Widget body) => ScaffoldMessenger(
          key: _messengerKey,
          child: Scaffold(
            backgroundColor: Colors.transparent,
            body: Padding(
              padding: EdgeInsets.fromLTRB(pad, pad, pad, AppSpacing.md),
              child: body,
            ),
            bottomNavigationBar: Padding(
              padding: EdgeInsets.fromLTRB(pad, 0, pad, pad),
              child: actionBar,
            ),
          ),
        );
        final header = _PosHeader(
          search: _search,
          focus: _searchFocus,
          onChanged: () => setState(() {}),
          onSubmitted: _onSearchSubmitted,
          onSuspended: _openSuspended,
        );

        // Figure 3.2 at every size: summary | invoice | products. On narrow
        // screens the three columns keep their size and scroll sideways.
        return page(
          MinWidthScroll(
            minWidth: 1100,
            fillHeight: true,
            child: Column(
              children: [
                header,
                const SizedBox(height: AppSpacing.lg),
                Expanded(
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      SizedBox(
                        width: 300,
                        child: SingleChildScrollView(child: summary),
                      ),
                      const SizedBox(width: AppSpacing.lg),
                      Expanded(flex: 5, child: invoicePanel()),
                      const SizedBox(width: AppSpacing.lg),
                      Expanded(flex: 6, child: productsPanel),
                    ],
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}

class _PosHeader extends StatelessWidget {
  const _PosHeader({
    required this.search,
    required this.focus,
    required this.onChanged,
    required this.onSubmitted,
    required this.onSuspended,
  });

  final TextEditingController search;
  final FocusNode focus;
  final VoidCallback onChanged;
  final ValueChanged<String> onSubmitted;
  final VoidCallback onSuspended;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final title = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          s.posTitle,
          style: const TextStyle(fontSize: 26, fontWeight: FontWeight.w800),
        ),
        Text(
          s.posSubtitle,
          style: const TextStyle(color: AppColors.textSecondary),
        ),
      ],
    );
    final searchField = Tooltip(
      message: s.posSearchTooltip,
      child: TextField(
        key: const Key('posSearchField'),
        controller: search,
        focusNode: focus,
        autofocus: true,
        onChanged: (_) => onChanged(),
        onSubmitted: onSubmitted,
        decoration: InputDecoration(
          hintText: s.posSearchHint,
          fillColor: AppColors.surface,
          prefixIcon: const Icon(
            Icons.search_rounded,
            color: AppColors.textMuted,
          ),
          suffixIcon: const Icon(
            Icons.qr_code_scanner_rounded,
            color: AppColors.primary,
          ),
        ),
      ),
    );
    final suspended = OutlinedButton.icon(
      key: const Key('openSuspendedButton'),
      onPressed: onSuspended,
      icon: const Icon(Icons.pause_circle_outline_rounded, size: 18),
      label: Text(s.suspendedInvoices),
    );
    final date = Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(AppSpacing.radiusSm + 2),
        border: Border.all(color: AppColors.border),
      ),
      child: Text(
        MaterialLocalizations.of(context).formatFullDate(DateTime.now()),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13),
      ),
    );

    return LayoutBuilder(
      builder: (context, c) {
        if (c.maxWidth < 900) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              title,
              const SizedBox(height: AppSpacing.md),
              Row(
                children: [
                  Expanded(child: searchField),
                  const SizedBox(width: AppSpacing.sm),
                  suspended,
                ],
              ),
            ],
          );
        }
        return Row(
          children: [
            Expanded(flex: 3, child: title),
            Flexible(
              flex: 4,
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 380),
                child: searchField,
              ),
            ),
            const SizedBox(width: AppSpacing.md),
            suspended,
            const SizedBox(width: AppSpacing.md),
            Flexible(flex: 2, child: date),
          ],
        );
      },
    );
  }
}

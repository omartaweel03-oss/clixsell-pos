import 'package:flutter/material.dart';

import '../../l10n/app_strings.dart';
import '../../models/product.dart';
import '../../models/product_category.dart';
import '../../models/sale.dart';
import '../../state/pos_cart.dart';
import '../../theme/app_theme.dart';
import '../../utils/formatters.dart';
import '../../widgets/product_thumbnail.dart';

/// Label and icon of each payment method.
String paymentLabel(PaymentMethod m, AppStrings s) => switch (m) {
  PaymentMethod.cash => s.payCash,
  PaymentMethod.card => s.payCard,
  PaymentMethod.wallet => s.payWallet,
  PaymentMethod.bankTransfer => s.payBank,
};

IconData paymentIcon(PaymentMethod m) => switch (m) {
  PaymentMethod.cash => Icons.payments_outlined,
  PaymentMethod.card => Icons.credit_card_rounded,
  PaymentMethod.wallet => Icons.account_balance_wallet_outlined,
  PaymentMethod.bankTransfer => Icons.account_balance_outlined,
};

/// White rounded panel with a bold title, used for the three POS columns.
class PosPanel extends StatelessWidget {
  const PosPanel({
    super.key,
    required this.title,
    required this.child,
    this.trailing,
    this.expand = true,
  });

  final String title;
  final Widget child;
  final Widget? trailing;

  /// True: fills the available height (wide layout). False: as tall as its
  /// content (inside a scroll view on narrow screens).
  final bool expand;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: expand ? MainAxisSize.max : MainAxisSize.min,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    title,
                    style: const TextStyle(
                      fontSize: 17,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                ),
                ?trailing,
              ],
            ),
            const SizedBox(height: AppSpacing.md),
            if (expand) Expanded(child: child) else child,
          ],
        ),
      ),
    );
  }
}

// ── Categories / Products ────────────────────────────────────────────────────

/// Category tabs and the product grid. Tapping a product adds it.
class PosProductsPanel extends StatelessWidget {
  const PosProductsPanel({
    super.key,
    required this.products,
    required this.categories,
    required this.selectedCategory,
    required this.onCategory,
    required this.onAdd,
    required this.cart,
  });

  final List<Product> products;
  final List<ProductCategory> categories;
  final ProductCategory? selectedCategory;
  final ValueChanged<ProductCategory?> onCategory;
  final ValueChanged<Product> onAdd;
  final PosCart cart;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    Widget tab(String label, ProductCategory? value) {
      final selected = selectedCategory == value;
      return Padding(
        padding: const EdgeInsetsDirectional.only(end: AppSpacing.sm),
        child: ChoiceChip(
          key: Key('category-${value?.name ?? 'all'}'),
          label: Text(label),
          selected: selected,
          showCheckmark: false,
          onSelected: (_) => onCategory(value),
          selectedColor: AppColors.primary,
          labelStyle: TextStyle(
            color: selected ? Colors.white : AppColors.textSecondary,
            fontWeight: FontWeight.w600,
          ),
          side: BorderSide(
            color: selected ? AppColors.primary : AppColors.border,
          ),
          backgroundColor: AppColors.surface,
        ),
      );
    }

    return PosPanel(
      title: s.categoriesProducts,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(
              children: [
                tab(s.allTab, null),
                for (final c in categories) tab(c.label(arabic: s.isArabic), c),
              ],
            ),
          ),
          const SizedBox(height: AppSpacing.md),
          Expanded(
            child: products.isEmpty
                ? Center(
                    child: Text(
                      s.noProductsFound,
                      style: const TextStyle(color: AppColors.textSecondary),
                    ),
                  )
                : GridView.builder(
                    padding: EdgeInsets.zero,
                    gridDelegate:
                        const SliverGridDelegateWithMaxCrossAxisExtent(
                          maxCrossAxisExtent: 150,
                          mainAxisSpacing: AppSpacing.md,
                          crossAxisSpacing: AppSpacing.md,
                          childAspectRatio: 0.74,
                        ),
                    itemCount: products.length,
                    itemBuilder: (context, i) => _ProductCard(
                      product: products[i],
                      inCart: cart.lineFor(products[i].id)?.quantity ?? 0,
                      onTap: () => onAdd(products[i]),
                    ),
                  ),
          ),
        ],
      ),
    );
  }
}

class _ProductCard extends StatelessWidget {
  const _ProductCard({
    required this.product,
    required this.inCart,
    required this.onTap,
  });

  final Product product;
  final int inCart;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final out = product.quantity <= 0;
    final low = product.stockStatus == StockStatus.lowStock;
    return Opacity(
      opacity: out ? 0.5 : 1,
      child: Material(
        color: AppColors.surface,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
          side: BorderSide(
            color: inCart > 0 ? AppColors.primary : AppColors.border,
            width: inCart > 0 ? 1.6 : 1,
          ),
        ),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          key: Key('posProduct-${product.id}'),
          onTap: out ? null : onTap,
          child: Padding(
            padding: const EdgeInsets.all(AppSpacing.sm),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Expanded(
                  child: Stack(
                    children: [
                      Positioned.fill(
                        child: ProductThumbnail(
                          imageBytes: product.imageBytes,
                          size: double.infinity,
                          radius: AppSpacing.radiusSm,
                        ),
                      ),
                      if (inCart > 0)
                        PositionedDirectional(
                          top: 4,
                          end: 4,
                          child: CircleAvatar(
                            radius: 11,
                            backgroundColor: AppColors.primary,
                            child: Text(
                              '$inCart',
                              style: const TextStyle(
                                color: Colors.white,
                                fontSize: 11,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  product.name,
                  maxLines: 2,
                  textAlign: TextAlign.center,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 12.5,
                    fontWeight: FontWeight.w700,
                    height: 1.2,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  Formatters.currency(product.price),
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                Text(
                  out ? s.outOfStockShort : s.leftInStock(product.quantity),
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 11,
                    color: out
                        ? AppColors.danger
                        : (low ? AppColors.warning : AppColors.textMuted),
                    fontWeight: out || low ? FontWeight.w700 : FontWeight.w400,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

// ── Current invoice ──────────────────────────────────────────────────────────

class PosInvoicePanel extends StatelessWidget {
  const PosInvoicePanel({
    super.key,
    required this.cart,
    required this.onIncrement,
    required this.onDiscount,
    required this.onClear,
    this.shrinkWrap = false,
  });

  final PosCart cart;
  final ValueChanged<String> onIncrement;
  final VoidCallback onDiscount;
  final VoidCallback onClear;

  /// True inside a scroll view (narrow screens): no inner scrolling.
  final bool shrinkWrap;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    const head = TextStyle(
      fontSize: 12,
      fontWeight: FontWeight.w700,
      color: AppColors.textSecondary,
    );

    final number = Container(
      key: const Key('invoiceNumberChip'),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: AppColors.primarySoft,
        borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
      ),
      child: Text(
        cart.invoiceNumber == null
            ? s.newInvoiceChip
            : '#${cart.invoiceNumber}',
        style: const TextStyle(
          color: AppColors.primary,
          fontWeight: FontWeight.w700,
          fontSize: 12.5,
        ),
      ),
    );

    Widget lines;
    if (cart.isEmpty) {
      lines = Padding(
        padding: const EdgeInsets.symmetric(vertical: 40),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(
              Icons.shopping_cart_outlined,
              size: 40,
              color: AppColors.textMuted,
            ),
            const SizedBox(height: AppSpacing.sm),
            Text(
              s.emptyInvoiceTitle,
              style: const TextStyle(fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 4),
            Text(
              s.emptyInvoiceBody,
              textAlign: TextAlign.center,
              style: const TextStyle(
                color: AppColors.textSecondary,
                fontSize: 13,
              ),
            ),
          ],
        ),
      );
    } else {
      lines = ListView.separated(
        shrinkWrap: shrinkWrap,
        physics: shrinkWrap ? const NeverScrollableScrollPhysics() : null,
        itemCount: cart.lines.length,
        separatorBuilder: (_, _) => const Divider(height: 1),
        itemBuilder: (context, i) => _InvoiceLine(
          line: cart.lines[i],
          onIncrement: () => onIncrement(cart.lines[i].product.id),
          onDecrement: () => cart.decrement(cart.lines[i].product.id),
          onRemove: () => cart.remove(cart.lines[i].product.id),
        ),
      );
    }

    return PosPanel(
      title: s.currentInvoice,
      trailing: number,
      expand: !shrinkWrap,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: shrinkWrap ? MainAxisSize.min : MainAxisSize.max,
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 10),
            decoration: BoxDecoration(
              color: AppColors.background,
              borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
            ),
            child: Row(
              children: [
                Expanded(flex: 5, child: Text(s.colProduct, style: head)),
                Expanded(flex: 2, child: Text(s.colPrice, style: head)),
                SizedBox(
                  width: 104,
                  child: Text(
                    s.colQuantity,
                    style: head,
                    textAlign: TextAlign.center,
                  ),
                ),
                Expanded(
                  flex: 2,
                  child: Text(
                    s.colLineTotal,
                    style: head,
                    textAlign: TextAlign.end,
                  ),
                ),
                const SizedBox(width: 36),
              ],
            ),
          ),
          if (shrinkWrap) lines else Expanded(child: lines),
          const Divider(height: AppSpacing.lg),
          Wrap(
            alignment: WrapAlignment.spaceBetween,
            spacing: AppSpacing.sm,
            runSpacing: AppSpacing.sm,
            children: [
              OutlinedButton.icon(
                key: const Key('clearAllButton'),
                onPressed: cart.isEmpty ? null : onClear,
                style: OutlinedButton.styleFrom(
                  foregroundColor: AppColors.danger,
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 10,
                  ),
                ),
                icon: const Icon(Icons.delete_outline_rounded, size: 18),
                label: Text(s.clearAll),
              ),
              OutlinedButton.icon(
                key: const Key('applyDiscountButton'),
                onPressed: cart.isEmpty ? null : onDiscount,
                style: OutlinedButton.styleFrom(
                  foregroundColor: AppColors.primary,
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 10,
                  ),
                ),
                icon: const Icon(Icons.discount_outlined, size: 18),
                label: Text(s.applyDiscount),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

/// An amount on one line ("₪ 12.00" never splits); shrinks a little if the
/// column is too narrow.
class _Money extends StatelessWidget {
  const _Money(this.text, {required this.style, this.end = false});

  final String text;
  final TextStyle style;
  final bool end;

  @override
  Widget build(BuildContext context) => Align(
    alignment: end
        ? AlignmentDirectional.centerEnd
        : AlignmentDirectional.centerStart,
    child: FittedBox(
      fit: BoxFit.scaleDown,
      child: Text(text, maxLines: 1, softWrap: false, style: style),
    ),
  );
}

class _InvoiceLine extends StatelessWidget {
  const _InvoiceLine({
    required this.line,
    required this.onIncrement,
    required this.onDecrement,
    required this.onRemove,
  });

  final CartLine line;
  final VoidCallback onIncrement;
  final VoidCallback onDecrement;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    final id = line.product.id;
    Widget stepButton(IconData icon, String tip, VoidCallback onTap, Key key) =>
        SizedBox(
          width: 30,
          height: 30,
          child: IconButton(
            key: key,
            tooltip: tip,
            padding: EdgeInsets.zero,
            iconSize: 16,
            onPressed: onTap,
            icon: Icon(icon),
          ),
        );

    return Padding(
      key: ValueKey('line-$id'),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
      child: Row(
        children: [
          Expanded(
            flex: 5,
            // When the invoice column is narrow the picture is left out, so
            // the name is not cut in the middle of a word.
            child: LayoutBuilder(
              builder: (context, c) => Row(
                children: [
                  if (c.maxWidth >= 150) ...[
                    ProductThumbnail(
                      imageBytes: line.product.imageBytes,
                      size: 34,
                    ),
                    const SizedBox(width: AppSpacing.sm),
                  ],
                  Expanded(
                    child: Text(
                      line.product.name,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontWeight: FontWeight.w600,
                        fontSize: 13,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
          Expanded(
            flex: 2,
            child: _Money(
              Formatters.currency(line.product.price),
              style: const TextStyle(
                fontSize: 13,
                color: AppColors.textSecondary,
              ),
            ),
          ),
          Container(
            width: 104,
            height: 34,
            decoration: BoxDecoration(
              border: Border.all(color: AppColors.border),
              borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
            ),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                stepButton(
                  Icons.remove_rounded,
                  s.decreaseQty,
                  onDecrement,
                  Key('dec-$id'),
                ),
                Text(
                  '${line.quantity}',
                  key: Key('qty-$id'),
                  style: const TextStyle(fontWeight: FontWeight.w700),
                ),
                stepButton(
                  Icons.add_rounded,
                  s.increaseQty,
                  onIncrement,
                  Key('inc-$id'),
                ),
              ],
            ),
          ),
          Expanded(
            flex: 2,
            child: _Money(
              Formatters.currency(line.lineTotal),
              end: true,
              style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13),
            ),
          ),
          SizedBox(
            width: 36,
            child: IconButton(
              key: Key('remove-$id'),
              tooltip: s.removeItem,
              iconSize: 18,
              color: AppColors.danger,
              onPressed: onRemove,
              icon: const Icon(Icons.close_rounded),
            ),
          ),
        ],
      ),
    );
  }
}

// ── Summary + payment ────────────────────────────────────────────────────────

class PosSummaryPanel extends StatelessWidget {
  const PosSummaryPanel({
    super.key,
    required this.cart,
    required this.busy,
    required this.onComplete,
  });

  final PosCart cart;
  final bool busy;
  final VoidCallback onComplete;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    Widget row(String label, String value, {Key? key}) => Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
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
            style: const TextStyle(fontWeight: FontWeight.w700),
          ),
        ],
      ),
    );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        Card(
          child: Padding(
            padding: const EdgeInsets.all(AppSpacing.lg),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  s.invoiceSummary,
                  style: const TextStyle(
                    fontSize: 17,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                const SizedBox(height: AppSpacing.sm),
                row(
                  s.itemsCount,
                  '${cart.lineCount}',
                  key: const Key('summaryItems'),
                ),
                row(
                  s.subtotalLabel,
                  Formatters.currency(cart.subtotal),
                  key: const Key('summarySubtotal'),
                ),
                row(
                  s.discountLabel,
                  Formatters.currency(cart.discount),
                  key: const Key('summaryDiscount'),
                ),
                row(
                  s.taxLabel(cart.taxRate),
                  Formatters.currency(cart.tax),
                  key: const Key('summaryTax'),
                ),
                const SizedBox(height: AppSpacing.sm),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: AppSpacing.lg,
                    vertical: AppSpacing.lg,
                  ),
                  decoration: BoxDecoration(
                    color: AppColors.primarySoft,
                    borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
                  ),
                  child: Row(
                    children: [
                      Expanded(
                        child: Text(
                          s.totalLabel,
                          style: const TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w800,
                          ),
                        ),
                      ),
                      Text(
                        Formatters.currency(cart.total),
                        key: const Key('summaryTotal'),
                        style: const TextStyle(
                          fontSize: 22,
                          fontWeight: FontWeight.w800,
                          color: AppColors.primary,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: AppSpacing.md),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(AppSpacing.lg),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  s.paymentMethods,
                  style: const TextStyle(
                    fontSize: 17,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                const SizedBox(height: AppSpacing.sm),
                for (final m in PaymentMethod.values) ...[
                  _PaymentTile(
                    method: m,
                    selected: cart.paymentMethod == m,
                    onTap: () => cart.setPaymentMethod(m),
                  ),
                  const SizedBox(height: AppSpacing.sm),
                ],
                const SizedBox(height: AppSpacing.xs),
                FilledButton.icon(
                  key: const Key('completeSaleButton'),
                  onPressed: cart.isEmpty || busy ? null : onComplete,
                  style: FilledButton.styleFrom(
                    padding: const EdgeInsets.symmetric(vertical: 18),
                  ),
                  icon: busy
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: Colors.white,
                          ),
                        )
                      : const Icon(Icons.check_circle_outline_rounded),
                  label: Text(
                    s.completeSale,
                    style: const TextStyle(fontSize: 16),
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _PaymentTile extends StatelessWidget {
  const _PaymentTile({
    required this.method,
    required this.selected,
    required this.onTap,
  });

  final PaymentMethod method;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    return Material(
      color: selected ? AppColors.primarySoft : AppColors.surface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppSpacing.radiusSm + 2),
        side: BorderSide(
          color: selected ? AppColors.primary : AppColors.border,
          width: selected ? 1.5 : 1,
        ),
      ),
      child: InkWell(
        key: Key('pay-${method.name}'),
        borderRadius: BorderRadius.circular(AppSpacing.radiusSm + 2),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          child: Row(
            children: [
              Icon(
                paymentIcon(method),
                size: 20,
                color: selected ? AppColors.primary : AppColors.textSecondary,
              ),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: Text(
                  paymentLabel(method, s),
                  style: TextStyle(
                    fontWeight: selected ? FontWeight.w800 : FontWeight.w600,
                    color: selected ? AppColors.primary : AppColors.textPrimary,
                  ),
                ),
              ),
              Icon(
                selected ? Icons.radio_button_checked : Icons.radio_button_off,
                size: 18,
                color: selected ? AppColors.primary : AppColors.textMuted,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ── Bottom action bar ────────────────────────────────────────────────────────

class PosActionBar extends StatelessWidget {
  const PosActionBar({
    super.key,
    required this.onNew,
    required this.onSuspend,
    required this.onReturn,
    required this.onCancel,
  });

  final VoidCallback onNew;
  final VoidCallback onSuspend;
  final VoidCallback onReturn;
  final VoidCallback onCancel;

  @override
  Widget build(BuildContext context) {
    final s = AppStrings.of(context);
    Widget action(
      Key key,
      String label,
      IconData icon,
      Color fg,
      Color bg,
      VoidCallback onTap,
    ) => Material(
      color: bg,
      borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
      child: InkWell(
        key: key,
        borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 12),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(icon, color: fg, size: 20),
              const SizedBox(width: AppSpacing.sm),
              Flexible(
                child: Text(
                  label,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: fg,
                    fontWeight: FontWeight.w800,
                    fontSize: 15,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );

    final buttons = [
      action(
        const Key('newInvoiceButton'),
        s.newInvoice,
        Icons.add_circle_outline_rounded,
        AppColors.success,
        AppColors.successSoft,
        onNew,
      ),
      action(
        const Key('suspendInvoiceButton'),
        s.suspendInvoice,
        Icons.pause_circle_outline_rounded,
        const Color(0xFF9A5B00),
        AppColors.warningSoft,
        onSuspend,
      ),
      action(
        const Key('returnButton'),
        s.returnLabel,
        Icons.sync_alt_rounded,
        AppColors.danger,
        AppColors.dangerSoft,
        onReturn,
      ),
      action(
        const Key('cancelInvoiceButton'),
        s.cancelInvoice,
        Icons.highlight_off_rounded,
        AppColors.textPrimary,
        const Color(0xFFE9ECF3),
        onCancel,
      ),
    ];

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.md),
        child: LayoutBuilder(
          builder: (context, c) {
            if (c.maxWidth < 620) {
              return Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Row(
                    children: [
                      Expanded(child: buttons[0]),
                      const SizedBox(width: 8),
                      Expanded(child: buttons[1]),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      Expanded(child: buttons[2]),
                      const SizedBox(width: 8),
                      Expanded(child: buttons[3]),
                    ],
                  ),
                ],
              );
            }
            return Row(
              children: [
                for (var i = 0; i < buttons.length; i++) ...[
                  if (i > 0) const SizedBox(width: AppSpacing.md),
                  Expanded(child: buttons[i]),
                ],
              ],
            );
          },
        ),
      ),
    );
  }
}

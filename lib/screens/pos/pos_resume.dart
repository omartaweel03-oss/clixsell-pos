import 'package:flutter/material.dart';

import '../../l10n/app_strings.dart';
import '../../models/sale.dart';
import '../../state/pos_cart.dart';
import '../../state/product_store.dart';
import '../../widgets/page_header.dart';

/// Loads a suspended invoice into the POS cart – shared by the POS
/// "Suspended" window and the Suspended Invoices screen (Figure 3.6), so both
/// follow the same rules: if another invoice is open it asks first; current
/// prices are used; products deleted since are left out.
///
/// Returns null when the user keeps the current invoice, otherwise the names
/// of the products that could not be loaded (usually empty).
Future<List<String>?> loadSuspendedIntoCart(
  BuildContext context,
  Sale sale,
) async {
  final s = AppStrings.of(context);
  final cart = PosCartScope.read(context);
  final store = ProductStoreScope.read(context);
  if (!cart.isEmpty && cart.resumedSaleId != sale.saleId) {
    final ok = await confirmAction(
      context,
      title: s.replaceInvoiceTitle,
      body: s.replaceInvoiceBody,
      confirmLabel: s.yesContinue,
      cancelLabel: s.keepInvoice,
    );
    if (!ok || !context.mounted) return null;
  }
  return cart.resume(sale, store.products);
}

/// The message shown after a suspended invoice was loaded.
String resumedMessage(AppStrings s, Sale sale, List<String> missing) =>
    missing.isEmpty
    ? s.invoiceResumed(sale.invoiceNumber)
    : s.skippedProducts(missing.join(', '));

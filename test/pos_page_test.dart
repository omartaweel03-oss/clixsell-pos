import 'package:clixsell_pos/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_product_backend.dart';

/// Shini (store 1): Milk ₪5 ×24 (id 1), Bread ₪2 ×3 (id 2), Eggs ₪15 ×0 (id 3).
FakeProductBackend _backend() => FakeProductBackend()
  ..seed(name: 'Milk', barcode: '11111111', price: 5, quantity: 24, minStock: 10)
  ..seed(name: 'Bread', barcode: '22222222', price: 2, quantity: 3, minStock: 1, category: 'bakery')
  ..seed(name: 'Eggs', barcode: '33333333', price: 15, quantity: 0, minStock: 5);

Future<void> _openPos(WidgetTester tester, FakeProductBackend backend,
    {Size size = const Size(1440, 1000), Locale? locale, String user = 'shini.cashier',
    String password = 'Cashier-Pass-1'}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ClixSellApp(http: backend.newHttp(), initialLocale: locale));
  await tester.pumpAndSettle();
  await tester.enterText(find.byKey(const Key('loginUsername')), user);
  await tester.enterText(find.byKey(const Key('loginPassword')), password);
  await tester.tap(find.byKey(const Key('loginButton')));
  await tester.pumpAndSettle();
}

String _text(WidgetTester tester, String key) =>
    tester.widget<Text>(find.byKey(Key(key))).data!;

Future<void> _tap(WidgetTester tester, String key) async {
  await tester.ensureVisible(find.byKey(Key(key)));
  await tester.tap(find.byKey(Key(key)));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('cashier starts on the POS with own products; totals update live', (tester) async {
    final backend = _backend();
    await _openPos(tester, backend);

    expect(find.text('Point of Sale (POS)'), findsOneWidget);
    expect(find.byKey(const Key('posProduct-1')), findsOneWidget);
    expect(find.text('New invoice'), findsOneWidget);
    expect(backend.requests, contains('GET /api/store'));

    await _tap(tester, 'posProduct-1');
    await _tap(tester, 'posProduct-1');
    await _tap(tester, 'posProduct-2');
    expect(_text(tester, 'qty-1'), '2');
    expect(_text(tester, 'summaryItems'), '2');
    expect(_text(tester, 'summarySubtotal'), '₪ 12.00');
    expect(_text(tester, 'summaryTax'), '₪ 1.92'); // 16%
    expect(_text(tester, 'summaryTotal'), '₪ 13.92');

    await _tap(tester, 'inc-2');
    await _tap(tester, 'dec-1');
    expect(_text(tester, 'qty-1'), '1');
    expect(_text(tester, 'qty-2'), '2');
    await _tap(tester, 'remove-1');
    expect(find.byKey(const Key('qty-1')), findsNothing);
  });

  testWidgets('stock limits: out-of-stock products cannot be added, quantity stops at stock', (tester) async {
    await _openPos(tester, _backend());
    await _tap(tester, 'posProduct-3'); // Eggs: 0 in stock
    expect(find.byKey(const Key('qty-3')), findsNothing);

    for (var i = 0; i < 3; i++) {
      await _tap(tester, 'posProduct-2');
    }
    await _tap(tester, 'inc-2');
    expect(_text(tester, 'qty-2'), '3');
    expect(find.text('Only 3 of "Bread" in stock'), findsOneWidget);
  });

  testWidgets('barcode in the search box + Enter adds the product; categories filter', (tester) async {
    await _openPos(tester, _backend());
    await tester.enterText(find.byKey(const Key('posSearchField')), '22222222');
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pumpAndSettle();
    expect(_text(tester, 'qty-2'), '1');

    await _tap(tester, 'category-bakery');
    expect(find.byKey(const Key('posProduct-2')), findsOneWidget);
    expect(find.byKey(const Key('posProduct-1')), findsNothing);
    await _tap(tester, 'category-all');
    expect(find.byKey(const Key('posProduct-1')), findsOneWidget);
  });

  testWidgets('discount within the store limit; too large is refused', (tester) async {
    await _openPos(tester, _backend());
    await _tap(tester, 'posProduct-1');
    await _tap(tester, 'posProduct-1'); // 10.00

    await _tap(tester, 'applyDiscountButton');
    await tester.tap(find.text('Percent (%)'));
    await tester.enterText(find.byKey(const Key('discountValueField')), '50');
    await _tap(tester, 'discountApplyButton');
    expect(find.text('Discount is above the allowed maximum'), findsOneWidget);

    await tester.enterText(find.byKey(const Key('discountValueField')), '5');
    await _tap(tester, 'discountApplyButton');
    expect(_text(tester, 'summaryDiscount'), '₪ 0.50');
    expect(_text(tester, 'summaryTotal'), '₪ 11.02'); // 9.50 + 1.52
  });

  testWidgets('complete sale: saved on the server, stock reduced, QR receipt shown', (tester) async {
    final backend = _backend();
    await _openPos(tester, backend);
    await _tap(tester, 'posProduct-1');
    await _tap(tester, 'posProduct-1');
    await _tap(tester, 'pay-card');
    await _tap(tester, 'completeSaleButton');

    expect(backend.requests, contains('POST /api/sales'));
    final sale = backend.sales.single;
    expect(sale['status'], 'COMPLETED');
    expect(sale['paymentMethod'], 'CARD');
    expect(sale['total'], 11.6);
    expect(backend.rowById(1)!['quantity'], 22);

    expect(find.byKey(const Key('receiptDialog')), findsOneWidget);
    expect(find.byKey(const Key('receiptQr')), findsOneWidget);
    expect(find.text(sale['receiptCode'] as String), findsOneWidget);
    expect(find.text('INV-000001'), findsOneWidget);

    await _tap(tester, 'receiptCloseButton');
    expect(find.text('New invoice'), findsOneWidget); // empty invoice again
    expect(find.text('22 left'), findsOneWidget);     // stock refreshed
  });

  testWidgets('not enough stock on the server: clear message, nothing saved', (tester) async {
    final backend = _backend();
    await _openPos(tester, backend);
    await _tap(tester, 'posProduct-2');
    await _tap(tester, 'posProduct-2');
    backend.rowById(2)!['quantity'] = 1; // another cashier sold some meanwhile
    await _tap(tester, 'completeSaleButton');
    expect(find.text('Not enough stock'), findsOneWidget);
    expect(find.textContaining('Only 1 left in stock'), findsOneWidget);
    expect(backend.sales, isEmpty);
  });

  testWidgets('suspend, resume from the list, complete with the same invoice number', (tester) async {
    final backend = _backend();
    await _openPos(tester, backend);
    await _tap(tester, 'posProduct-1');
    await _tap(tester, 'suspendInvoiceButton');
    expect(backend.sales.single['status'], 'SUSPENDED');
    expect(find.text('Invoice INV-000001 was suspended'), findsOneWidget);
    expect(find.byKey(const Key('qty-1')), findsNothing);
    expect(backend.rowById(1)!['quantity'], 24); // stock not reserved

    await _tap(tester, 'openSuspendedButton');
    expect(find.text('INV-000001'), findsOneWidget);
    await _tap(tester, 'resume-1');
    expect(find.text('#INV-000001'), findsOneWidget);
    expect(_text(tester, 'qty-1'), '1');

    await _tap(tester, 'completeSaleButton');
    expect(backend.sales.single['status'], 'COMPLETED');
    expect(backend.sales.single['invoiceNumber'], 'INV-000001');
  });

  testWidgets('cancel a resumed invoice: marked CANCELED on the server', (tester) async {
    final backend = _backend();
    await _openPos(tester, backend);
    await _tap(tester, 'posProduct-1');
    await _tap(tester, 'suspendInvoiceButton');
    await _tap(tester, 'openSuspendedButton');
    await _tap(tester, 'resume-1');

    await _tap(tester, 'cancelInvoiceButton');
    expect(find.text('Cancel this invoice?'), findsOneWidget);
    await _tap(tester, 'confirmActionButton');
    expect(backend.sales.single['status'], 'CANCELED');
    expect(find.text('New invoice'), findsOneWidget);
  });

  testWidgets('return: find by invoice number, choose product, refund, stock back', (tester) async {
    final backend = _backend();
    await _openPos(tester, backend);
    await _tap(tester, 'posProduct-1');
    await _tap(tester, 'posProduct-1');
    await _tap(tester, 'completeSaleButton');
    await _tap(tester, 'receiptCloseButton');
    expect(backend.rowById(1)!['quantity'], 22);

    await _tap(tester, 'returnButton');
    await tester.enterText(find.byKey(const Key('returnCodeField')), 'INV-999999');
    await _tap(tester, 'returnFindButton');
    expect(find.textContaining('No completed invoice'), findsOneWidget);

    await tester.enterText(find.byKey(const Key('returnCodeField')), 'inv-000001');
    await _tap(tester, 'returnFindButton');
    expect(find.text('Sold 2 · can return 2'), findsOneWidget);
    await _tap(tester, 'returnQtyInc');
    expect(_text(tester, 'returnQty'), '2');
    await _tap(tester, 'returnQtyDec');
    await tester.enterText(find.byKey(const Key('returnReasonField')), 'Damaged package');
    await _tap(tester, 'confirmReturnButton');

    expect(find.text('Return completed'), findsOneWidget);
    expect(_text(tester, 'refundInfo'), 'Refund ₪ 5.80 by Cash');
    expect(backend.returns.single['quantity'], 1);
    expect(backend.rowById(1)!['quantity'], 23);
    await tester.tap(find.text('Done'));
    await tester.pumpAndSettle();
    expect(find.text('23 left'), findsOneWidget);
  });

  testWidgets('return from an invoice with several products: the chosen (second) product is returned', (tester) async {
    final backend = _backend();
    await _openPos(tester, backend);
    await _tap(tester, 'posProduct-1');                 // Milk ×1   ₪5.00
    await _tap(tester, 'posProduct-2');
    await _tap(tester, 'posProduct-2');                 // Bread ×2  ₪4.00
    await _tap(tester, 'completeSaleButton');           // total (9.00 + 16 %) = 10.44
    await _tap(tester, 'receiptCloseButton');
    expect(backend.rowById(1)!['quantity'], 23);
    expect(backend.rowById(2)!['quantity'], 1);

    await _tap(tester, 'returnButton');
    await tester.enterText(find.byKey(const Key('returnCodeField')), 'INV-000001');
    await _tap(tester, 'returnFindButton');
    final items = (backend.sales.single['items'] as List).cast<Map<String, dynamic>>();
    final breadLine = items.firstWhere((i) => i['productId'] == 2)['saleItemId'];
    expect(find.text('Sold 1 · can return 1'), findsOneWidget); // Milk (first line)
    expect(find.text('Sold 2 · can return 2'), findsOneWidget); // Bread (second line)

    await _tap(tester, 'returnLine-$breadLine');
    await _tap(tester, 'returnQtyInc');
    expect(_text(tester, 'returnQty'), '2');
    await _tap(tester, 'returnQtyInc');                 // cannot go above what was sold
    expect(_text(tester, 'returnQty'), '2');
    await tester.enterText(find.byKey(const Key('returnReasonField')), 'Stale bread');
    await _tap(tester, 'confirmReturnButton');

    final r = backend.returns.single;
    expect(r['productName'], 'Bread');
    expect(r['quantity'], 2);
    expect(r['refundAmount'], 4.64);                    // 10.44 × 4.00 / 9.00
    expect(backend.rowById(2)!['quantity'], 3);         // bread back in stock
    expect(backend.rowById(1)!['quantity'], 23);        // milk unchanged
    expect(_text(tester, 'refundInfo'), 'Refund ₪ 4.64 by Cash');
  });

  testWidgets('store isolation: the other store\'s cashier sees only its products and invoices', (tester) async {
    final backend = _backend();
    final neshan = backend.addStore('Neshan Supermarket');
    backend.addUser('neshan.cashier', 'Cashier-Pass-1', 'CASHIER', neshan, fullName: 'Omar');
    backend.seed(name: 'Neshan Juice', barcode: '11111111', price: 8, quantity: 5, minStock: 1, storeId: neshan);
    await _openPos(tester, backend);
    await _tap(tester, 'posProduct-1');
    await _tap(tester, 'suspendInvoiceButton');
    await tester.tap(find.byKey(const Key('logoutButton')));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('loginUsername')), 'neshan.cashier');
    await tester.enterText(find.byKey(const Key('loginPassword')), 'Cashier-Pass-1');
    await tester.tap(find.byKey(const Key('loginButton')));
    await tester.pumpAndSettle();
    expect(find.text('Neshan Juice'), findsOneWidget);
    expect(find.byKey(const Key('posProduct-1')), findsNothing);
    await _tap(tester, 'openSuspendedButton');
    expect(find.text('There are no suspended invoices.'), findsOneWidget);
  });

  testWidgets('store admin can open the POS too', (tester) async {
    await _openPos(tester, _backend(), user: 'shini.admin', password: 'Admin-Pass-1');
    expect(find.text('Inventory Management'), findsWidgets);
    await _tap(tester, 'nav-pos');
    expect(find.text('Point of Sale (POS)'), findsOneWidget);
  });

  testWidgets('Arabic: POS labels in Arabic and right-to-left', (tester) async {
    await _openPos(tester, _backend(), locale: const Locale('ar'));
    expect(find.text('نقطة البيع (POS)'), findsOneWidget);
    expect(find.text('إتمام البيع'), findsOneWidget);
    expect(find.text('تعليق الفاتورة'), findsOneWidget);
    final dir = Directionality.of(tester.element(find.byKey(const Key('completeSaleButton'))));
    expect(dir, TextDirection.rtl);
  });

  for (final size in const [Size(1024, 768), Size(900, 900), Size(390, 844)]) {
    testWidgets('POS keeps the desktop layout of Figure 3.2 at $size, without overflow', (tester) async {
      final backend = _backend();
      await _openPos(tester, backend, size: size);
      expect(tester.takeException(), isNull);
      // Same three columns as on a wide screen (no tabs): summary, invoice, products.
      expect(find.byKey(const Key('posInvoiceTab')), findsNothing);
      expect(find.byKey(const Key('completeSaleButton')), findsOneWidget);
      expect(find.byKey(const Key('posProduct-1')), findsOneWidget);
      expect(find.byKey(const Key('nav-pos')), findsOneWidget); // sidebar, no drawer
      // Narrower than the desktop design: the columns scroll sideways.
      expect(find.byKey(const Key('minWidthScroll')), findsOneWidget);
      await _tap(tester, 'posProduct-1');
      expect(_text(tester, 'qty-1'), '1');
      await _tap(tester, 'completeSaleButton');
      expect(find.byKey(const Key('receiptDialog')), findsOneWidget);
      expect(backend.sales.single['status'], 'COMPLETED');
      expect(tester.takeException(), isNull);
    });
  }
}

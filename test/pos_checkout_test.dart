import 'package:clixsell_pos/main.dart';
import 'package:clixsell_pos/screens/pos/cash_payment_dialog.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_product_backend.dart';

/// POS store upgrade, Milestone B:
/// feature 3 (cash amount received, change / still to pay) and feature 4
/// (Complete Sale always visible in a fixed checkout footer).

/// 24 products for long invoices (ids 1-24), and Water ₪31.90 (id 25; +16%
/// tax = ₪37.00, the example from the requirements).
FakeProductBackend _backend() {
  final b = FakeProductBackend();
  for (var i = 0; i < 24; i++) {
    b.seed(name: 'Item $i', barcode: '2${'$i'.padLeft(7, '0')}', price: 1, quantity: 50, minStock: 1);
  }
  // id 25: the newest product is shown first in the POS grid
  b.seed(name: 'Water Pack', barcode: '11111111', price: 31.90, quantity: 50, minStock: 1);
  return b;
}

Future<void> _openPos(WidgetTester tester, FakeProductBackend backend,
    {Size size = const Size(1440, 1000), Locale? locale}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ClixSellApp(http: backend.newHttp(), initialLocale: locale));
  await tester.pumpAndSettle();
  await tester.enterText(find.byKey(const Key('loginUsername')), 'shini.cashier');
  await tester.enterText(find.byKey(const Key('loginPassword')), 'Cashier-Pass-1');
  await tester.tap(find.byKey(const Key('loginButton')));
  await tester.pumpAndSettle();
}

Finder _k(String k) => find.byKey(Key(k));

Future<void> _tap(WidgetTester tester, String key) async {
  await tester.ensureVisible(_k(key));
  await tester.tap(_k(key));
  await tester.pumpAndSettle();
}

String _text(WidgetTester tester, String key) => tester.widget<Text>(_k(key)).data!;

void main() {
  group('amount parsing and quick amounts (exact cents)', () {
    test('valid amounts', () {
      expect(CashPaymentDialog.parseCents('50'), 5000);
      expect(CashPaymentDialog.parseCents('50.5'), 5050);
      expect(CashPaymentDialog.parseCents('50,50'), 5050);
      expect(CashPaymentDialog.parseCents(' 40.10 '), 4010);
      expect(CashPaymentDialog.parseCents('٥٠٫٥'), 5050); // Arabic-Indic digits
      expect(CashPaymentDialog.parseCents('0.01'), 1);
    });
    test('invalid amounts', () {
      for (final v in ['', '0', '0.00', '-5', 'abc', '50.555', '1.2.3', '.5']) {
        expect(CashPaymentDialog.parseCents(v), isNull, reason: v);
      }
    });
    test('quick amounts: exact first, then round banknotes above the total', () {
      expect(CashPaymentDialog.quickAmounts(3700), [3700, 4000, 5000, 10000, 20000]);
      expect(CashPaymentDialog.quickAmounts(5000), [5000, 6000, 10000, 20000]);
      expect(CashPaymentDialog.quickAmounts(1997), [1997, 2000, 5000, 10000, 20000]);
    });
  });

  testWidgets('37.00 paid with 50: change 13.00, recorded total 37.00', (tester) async {
    final backend = _backend();
    await _openPos(tester, backend);
    await _tap(tester, 'posProduct-25');
    expect(_text(tester, 'footerTotal'), '₪ 37.00');

    await _tap(tester, 'completeSaleButton');
    expect(_k('cashDialog'), findsOneWidget);
    expect(_text(tester, 'cashTotalDue'), '₪ 37.00');

    // Not enough: still to pay 17.00, cannot complete
    await tester.enterText(_k('cashReceivedField'), '20');
    await tester.pumpAndSettle();
    expect(_text(tester, 'cashRemaining'), '₪ 17.00');
    expect(tester.widget<FilledButton>(_k('cashConfirm')).onPressed, isNull);
    expect(backend.sales, isEmpty);

    // Enough: change 13.00
    await tester.enterText(_k('cashReceivedField'), '50');
    await tester.pumpAndSettle();
    expect(_text(tester, 'cashChange'), '₪ 13.00');
    await _tap(tester, 'cashConfirm');

    expect(backend.cashReceivedLog, [50.0]);
    final sale = backend.sales.single;
    expect(sale['status'], 'COMPLETED');
    expect(sale['total'], 37.0); // the change is not revenue
    expect(_k('receiptDialog'), findsOneWidget);
    expect(find.text('₪ 50.00'), findsOneWidget); // cash received on the receipt
    expect(find.text('₪ 13.00'), findsOneWidget); // change given
  });

  testWidgets('exact amount button; invalid input shows a message', (tester) async {
    final backend = _backend();
    await _openPos(tester, backend);
    await _tap(tester, 'posProduct-25');
    await _tap(tester, 'completeSaleButton');
    await tester.enterText(_k('cashReceivedField'), '50.555');
    await tester.pumpAndSettle();
    expect(_k('cashInvalid'), findsOneWidget);
    expect(tester.widget<FilledButton>(_k('cashConfirm')).onPressed, isNull);
    await _tap(tester, 'cashQuick-3700');
    expect(_text(tester, 'cashChange'), '₪ 0.00');
    await _tap(tester, 'cashConfirm');
    expect(backend.cashReceivedLog, [37.0]);
    expect(backend.sales.single['status'], 'COMPLETED');
  });

  testWidgets('cancel the cash dialog: nothing is sent, the invoice stays', (tester) async {
    final backend = _backend();
    await _openPos(tester, backend);
    await _tap(tester, 'posProduct-25');
    await _tap(tester, 'completeSaleButton');
    await _tap(tester, 'cashCancel');
    expect(_k('cashDialog'), findsNothing);
    expect(backend.sales, isEmpty);
    expect(_text(tester, 'qty-25'), '1');
    // the button works again
    expect(tester.widget<FilledButton>(_k('completeSaleButton')).onPressed, isNotNull);
  });

  testWidgets('card, wallet and bank transfer: no cash dialog, unchanged', (tester) async {
    final backend = _backend();
    await _openPos(tester, backend);
    for (final m in ['card', 'wallet', 'bankTransfer']) {
      await _tap(tester, 'posProduct-25');
      await _tap(tester, 'pay-$m');
      await _tap(tester, 'completeSaleButton');
      expect(_k('cashDialog'), findsNothing);
      await _tap(tester, 'receiptCloseButton');
    }
    expect(backend.sales.map((s) => s['paymentMethod']), ['CARD', 'WALLET', 'BANK_TRANSFER']);
    expect(backend.cashReceivedLog, isEmpty);
  });

  testWidgets('a double tap on Complete Sale opens one dialog and makes one sale', (tester) async {
    final backend = _backend();
    await _openPos(tester, backend);
    await _tap(tester, 'posProduct-25');
    await tester.tap(_k('completeSaleButton'));
    await tester.tap(_k('completeSaleButton'), warnIfMissed: false);
    await tester.pumpAndSettle();
    expect(_k('cashDialog'), findsOneWidget);
    await _tap(tester, 'cashQuick-5000');
    // double tap on Complete payment too
    await tester.tap(_k('cashConfirm'));
    await tester.pump(const Duration(milliseconds: 20));
    await tester.tap(_k('cashConfirm'), warnIfMissed: false);
    await tester.pumpAndSettle();
    expect(_k('cashDialog'), findsNothing);
    expect(backend.sales, hasLength(1));
    expect(backend.cashReceivedLog, [50.0]);
    // still on the POS (the page under the dialog was not closed)
    expect(_k('receiptDialog'), findsOneWidget);
    await _tap(tester, 'receiptCloseButton');
    expect(_k('completeSaleButton'), findsOneWidget);
  });

  for (final (size, locale) in const [
    (Size(1440, 900), Locale('en')),
    (Size(1366, 768), Locale('ar')),
    (Size(1280, 640), Locale('en')),
    (Size(1024, 600), Locale('ar')),
  ]) {
    testWidgets('long invoice at $size ${locale.languageCode}: Complete Sale visible without scrolling',
        (tester) async {
      final backend = _backend();
      await _openPos(tester, backend, size: size, locale: locale);
      // 24 lines, added with the barcode scanner (search box + Enter)
      for (var i = 0; i < 24; i++) {
        await tester.enterText(_k('posSearchField'), '2${'$i'.padLeft(7, '0')}');
        await tester.testTextInput.receiveAction(TextInputAction.done);
        await tester.pumpAndSettle();
      }
      expect(find.byWidgetPredicate((w) => w.key is ValueKey<String> &&
          (w.key! as ValueKey<String>).value.startsWith('qty-')), findsWidgets);
      // back to the top of every scroll view, as on a fresh screen
      for (final s in find.byType(Scrollable).evaluate().toList()) {
        final pos = (s as StatefulElement).state as ScrollableState;
        pos.position.jumpTo(pos.position.minScrollExtent);
      }
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      // hit-testable where it is: no scroll needed, nothing on top of it
      expect(_k('completeSaleButton').hitTestable(), findsOneWidget);
      final r = tester.getRect(_k('completeSaleButton'));
      expect(r.bottom, lessThanOrEqualTo(size.height));
      expect(r.top, greaterThanOrEqualTo(0));
      expect(_k('footerTotal').hitTestable(), findsOneWidget);
      expect(_text(tester, 'footerTotal'), _text(tester, 'summaryTotal'));
      expect(
        Directionality.of(tester.element(_k('checkoutFooter'))),
        locale.languageCode == 'ar' ? TextDirection.rtl : TextDirection.ltr,
      );
      await tester.tap(_k('completeSaleButton'));
      await tester.pumpAndSettle();
      expect(_k('cashDialog'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('empty invoice: Complete Sale disabled', (tester) async {
    await _openPos(tester, _backend());
    expect(tester.widget<FilledButton>(_k('completeSaleButton')).onPressed, isNull);
  });
}

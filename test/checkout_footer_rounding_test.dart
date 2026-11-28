import 'package:clixsell_pos/models/product.dart';
import 'package:clixsell_pos/models/product_category.dart';
import 'package:clixsell_pos/models/sale.dart';
import 'package:clixsell_pos/screens/pos/pos_widgets.dart';
import 'package:clixsell_pos/state/pos_cart.dart';
import 'package:clixsell_pos/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';

/// Pre-merge correction 1: with CASH the fixed footer shows the amount the customer pays after
/// rounding (what the backend requires); any other method is never rounded.
Product _oil() => Product(
  id: '9', name: 'Olive Oil', barcode: '4006381333931', category: ProductCategory.values.first, price: 19.97,
  quantity: 10, minStockLevel: 1, supplier: 'S', createdAt: DateTime(2026), updatedAt: DateTime(2026));

PosCart _cart(PaymentMethod m, {int step = 10}) {
  final cart = PosCart()
    ..taxRate = 0
    ..cashRoundingMinor = step;
  cart.add(_oil());
  cart.setPaymentMethod(m);
  return cart;
}

Future<void> _pump(WidgetTester tester, PosCart cart, {Locale locale = const Locale('en')}) async {
  tester.view.physicalSize = const Size(400, 400);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(MaterialApp(
    theme: AppTheme.light(),
    locale: locale,
    supportedLocales: const [Locale('en'), Locale('ar')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    home: Scaffold(body: SizedBox(width: 300, child: PosCheckoutFooter(cart: cart, busy: false, onComplete: () {}))),
  ));
  await tester.pumpAndSettle();
}

String _text(WidgetTester tester, String key) => tester.widget<Text>(find.byKey(Key(key))).data!;

void main() {
  testWidgets('cash: the footer shows 20.00 "Amount due (cash)" and keeps total + rounding visible', (tester) async {
    await _pump(tester, _cart(PaymentMethod.cash));
    expect(_text(tester, 'footerLabel'), 'Amount due (cash)');
    expect(_text(tester, 'footerTotal'), contains('20.00'));
    expect(_text(tester, 'footerRoundingNote'), allOf(contains('19.97'), contains('0.03')));
  });

  for (final m in [PaymentMethod.card, PaymentMethod.wallet, PaymentMethod.bankTransfer, PaymentMethod.cheque]) {
    testWidgets('${m.apiName}: never rounded — the footer shows the total 19.97', (tester) async {
      final cart = _cart(m);
      await _pump(tester, cart);
      expect(cart.roundingCents, 0);
      expect(cart.amountDueCents, 1997);
      expect(_text(tester, 'footerLabel'), 'Total');
      expect(_text(tester, 'footerTotal'), contains('19.97'));
      expect(find.byKey(const Key('footerRoundingNote')), findsNothing);
    });
  }

  testWidgets('cash without a rounding step: plain total, no note', (tester) async {
    await _pump(tester, _cart(PaymentMethod.cash, step: 0));
    expect(_text(tester, 'footerLabel'), 'Total');
    expect(_text(tester, 'footerTotal'), contains('19.97'));
    expect(find.byKey(const Key('footerRoundingNote')), findsNothing);
  });

  testWidgets('Arabic labels, right to left', (tester) async {
    await _pump(tester, _cart(PaymentMethod.cash), locale: const Locale('ar'));
    expect(_text(tester, 'footerLabel'), 'المطلوب نقداً');
    expect(_text(tester, 'footerTotal'), contains('20.00'));
    expect(_text(tester, 'footerRoundingNote'), contains('تقريب النقد'));
    expect(Directionality.of(tester.element(find.byKey(const Key('checkoutFooter')))), TextDirection.rtl);
    expect(tester.takeException(), isNull);
  });

  test('the footer amount equals the backend rule for every step (half up, cash only)', () {
    // the backend: Sale.cashRounding (nearest multiple, midpoint up); the same table is in CashRoundingApiTest
    const cases = {
      [1997, 5]: 1995, [1997, 10]: 2000, [1995, 10]: 2000, [1994, 10]: 1990,
      [1974, 50]: 1950, [1975, 50]: 2000, [1950, 100]: 2000, [1949, 100]: 1900,
    };
    cases.forEach((k, due) {
      final cart = PosCart()..taxRate = 0..cashRoundingMinor = k[1];
      cart.add(Product(id: '1', name: 'X', barcode: '4006381333931', category: ProductCategory.values.first,
          price: k[0] / 100, quantity: 5, minStockLevel: 1, supplier: 'S', createdAt: DateTime(2026), updatedAt: DateTime(2026)));
      expect(cart.amountDueCents, due, reason: '$k');
    });
  });
}

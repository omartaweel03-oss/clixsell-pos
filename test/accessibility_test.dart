import 'package:clixsell_pos/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart' show SemanticsData;
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_product_backend.dart';
import 'support/pos_checkout.dart';

/// Accessibility corrections A1–A3 (overnight mission B2): what a screen reader gets, in
/// English and Arabic. Checked on the semantics tree of the real screens.
FakeProductBackend _backend() => FakeProductBackend()
  ..seed(
    name: 'Milk',
    barcode: '11111111',
    price: 5,
    quantity: 24,
    minStock: 10,
  );

Future<void> _login(
  WidgetTester tester,
  FakeProductBackend backend,
  String user,
  String pw, {
  Locale? locale,
}) async {
  tester.view.physicalSize = const Size(1440, 1000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(
    ClixSellApp(http: backend.newHttp(), initialLocale: locale),
  );
  await tester.pumpAndSettle();
  await tester.enterText(find.byKey(const Key('loginUsername')), user);
  await tester.enterText(find.byKey(const Key('loginPassword')), pw);
  await tester.tap(find.byKey(const Key('loginButton')));
  await tester.pumpAndSettle();
}

Future<void> _tap(WidgetTester tester, String key) async {
  await tester.ensureVisible(find.byKey(Key(key)));
  await tester.tap(find.byKey(Key(key)));
  await tester.pumpAndSettle();
}

SemanticsData _sem(WidgetTester tester, Finder f) =>
    tester.getSemantics(f).getSemanticsData();

void main() {
  for (final ar in [false, true]) {
    final lang = ar ? 'AR' : 'EN';
    testWidgets(
      '$lang: A2 the POS search keeps its accessible name while typing; '
      'A3 the receipt code is spoken; A1 return quantity buttons are named',
      (tester) async {
        final handle = tester.ensureSemantics();
        final backend = _backend();
        await _login(
          tester,
          backend,
          'shini.cashier',
          'Cashier-Pass-1',
          locale: ar ? const Locale('ar') : null,
        );

        // A2: same name before and after typing
        final search = find.byKey(const Key('posSearchField'));
        final name = ar
            ? 'ابحث بالاسم أو الباركود…'
            : 'Search by name or barcode…';
        Finder named() => find.bySemanticsLabel(RegExp(RegExp.escape(name)));
        expect(named(), findsOneWidget);
        await tester.enterText(search, 'Mi');
        await tester.pumpAndSettle();
        expect(named(), findsOneWidget, reason: 'not lost while typing');
        await tester.enterText(search, '');
        await tester.pumpAndSettle();

        // A3: complete a sale; the receipt code has a spoken label with the code
        await _tap(tester, 'posProduct-1');
        await completeSaleLikeACashier(tester);
        final code = backend.sales.single['receiptCode'] as String;
        expect(
          find.text(code),
          findsOneWidget,
          reason: 'still visible and selectable',
        );
        final spoken = _sem(
          tester,
          find.byKey(const Key('receiptCodeSemantics')),
        ).label;
        expect(spoken, ar ? 'رمز الإيصال: $code' : 'Receipt code: $code');
        await _tap(tester, 'receiptCloseButton');

        // A1: the return dialog's − / + buttons have names (tooltips)
        await _tap(tester, 'returnButton');
        await tester.enterText(
          find.byKey(const Key('returnCodeField')),
          'INV-000001',
        );
        await _tap(tester, 'returnFindButton');
        final dec = ar ? 'إنقاص كمية الإرجاع' : 'Decrease return quantity';
        final inc = ar ? 'زيادة كمية الإرجاع' : 'Increase return quantity';
        expect(find.byTooltip(dec), findsOneWidget);
        expect(find.byTooltip(inc), findsOneWidget);
        expect(_sem(tester, find.byKey(const Key('returnQtyInc'))).tooltip, inc);
        expect(_sem(tester, find.byKey(const Key('returnQtyDec'))).tooltip, dec);
        expect(tester.takeException(), isNull);
        handle.dispose();
      },
    );

    testWidgets(
      '$lang: A2 the products search keeps its accessible name while typing',
      (tester) async {
        final handle = tester.ensureSemantics();
        await _login(
          tester,
          _backend(),
          'shini.admin',
          'Admin-Pass-1',
          locale: ar ? const Locale('ar') : null,
        );
        await tester.tap(find.byKey(const Key('nav-products')));
        await tester.pumpAndSettle();
        final search = find.byKey(const Key('searchField'));
        final name = ar
            ? 'ابحث باسم المنتج أو الباركود أو المورد…'
            : 'Search by name, barcode or supplier…';
        Finder named() => find.bySemanticsLabel(RegExp(RegExp.escape(name)));
        expect(named(), findsOneWidget);
        await tester.enterText(search, 'Milk');
        await tester.pumpAndSettle();
        expect(named(), findsOneWidget, reason: 'not lost while typing');
        handle.dispose();
      },
    );
  }
}

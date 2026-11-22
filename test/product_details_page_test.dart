import 'package:clixsell_pos/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_product_backend.dart';

/// Product Details – report Figure 3.4 (UR-M5 / SR18), Store Admin screen.
/// Shini: Milk ₪5 ×24 (min 10, cost 3.20), Bread ₪2 ×3 (min 10), Eggs ×0.
FakeProductBackend _backend() => FakeProductBackend()
  ..seed(
    name: 'Milk',
    barcode: '11111111',
    price: 5,
    quantity: 24,
    minStock: 10,
    supplier: 'Dairy Co',
    cost: 3.2,
  )
  ..seed(
    name: 'Bread',
    barcode: '22222222',
    price: 2,
    quantity: 3,
    minStock: 10,
  )
  ..seed(
    name: 'Eggs',
    barcode: '33333333',
    price: 15,
    quantity: 0,
    minStock: 5,
  );

/// 1×1 PNG.
const _png =
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

Future<void> _open(
  WidgetTester tester,
  FakeProductBackend backend, {
  String user = 'shini.admin',
  String password = 'Admin-Pass-1',
  Size size = const Size(1440, 1000),
  Locale? locale,
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(
    ClixSellApp(http: backend.newHttp(), initialLocale: locale),
  );
  await tester.pumpAndSettle();
  await tester.enterText(find.byKey(const Key('loginUsername')), user);
  await tester.enterText(find.byKey(const Key('loginPassword')), password);
  await tester.tap(find.byKey(const Key('loginButton')));
  await tester.pumpAndSettle();
}

Future<void> _tap(WidgetTester tester, String key) async {
  await tester.ensureVisible(find.byKey(Key(key)));
  await tester.tap(find.byKey(Key(key)));
  await tester.pumpAndSettle();
}

String _value(WidgetTester tester, String row) => tester
    .widgetList<Text>(
      find.descendant(
        of: find.byKey(Key('detail-$row')),
        matching: find.byType(Text),
      ),
    )
    .last
    .data!;

void main() {
  testWidgets(
    'Store Admin: clicking the product name opens Figure 3.4 with every field',
    (tester) async {
      final backend = _backend();
      await _open(tester, backend);
      await _tap(tester, 'open-1');

      expect(find.byKey(const Key('productDetailsPage')), findsOneWidget);
      expect(backend.requests, contains('GET /api/products/1'));
      expect(_value(tester, 'name'), 'Milk');
      expect(_value(tester, 'barcode'), '11111111');
      expect(_value(tester, 'category'), 'Dairy');
      expect(_value(tester, 'price'), '₪ 5.00');
      expect(_value(tester, 'quantity'), '24');
      expect(_value(tester, 'minStock'), '10');
      expect(_value(tester, 'supplier'), 'Dairy Co');
      expect(_value(tester, 'dateAdded'), '2025-01-01  10:00');
      expect(_value(tester, 'status'), 'In Stock');
      expect(_value(tester, 'costPrice'), '₪ 3.20'); // Store Admin only
      expect(find.byKey(const Key('detailsNoImage')), findsOneWidget);
      expect(find.byKey(const Key('detailsEdit')), findsOneWidget);
      expect(find.byKey(const Key('detailsDelete')), findsOneWidget);
      // Sidebar shows the open product.
      expect(find.byKey(const Key('nav-productDetails')), findsOneWidget);
      expect(tester.takeException(), isNull);

      // Back to the products list.
      await _tap(tester, 'detailsBack');
      expect(find.byKey(const Key('productDetailsPage')), findsNothing);
      expect(find.byKey(const Key('nav-productDetails')), findsNothing);
    },
  );

  testWidgets('image of the product; status colours for low / out of stock', (
    tester,
  ) async {
    final backend = _backend();
    backend.rowById(2)!['image'] = _png;
    await _open(tester, backend);
    await _tap(tester, 'open-2');
    expect(
      find.descendant(
        of: find.byKey(const Key('detailsImage')),
        matching: find.byType(Image),
      ),
      findsOneWidget,
    );
    expect(_value(tester, 'status'), 'Low Stock');
    expect(_value(tester, 'costPrice'), 'Not recorded');
    await _tap(tester, 'detailsBack');
    await _tap(tester, 'open-3');
    expect(_value(tester, 'status'), 'Out of Stock');
  });

  testWidgets('the screen reads the product again: current quantity', (
    tester,
  ) async {
    final backend = _backend();
    await _open(tester, backend);
    // Sold elsewhere after the list was loaded.
    backend.rowById(1)!
      ..['quantity'] = 7
      ..['stockStatus'] = 'LOW_STOCK'; // as the server calculates it
    await _tap(tester, 'open-1');
    expect(_value(tester, 'quantity'), '7');
    expect(_value(tester, 'status'), 'Low Stock');
  });

  testWidgets('after a POS sale the quantity in the details is lower', (
    tester,
  ) async {
    final backend = _backend();
    await _open(tester, backend);
    await _tap(tester, 'nav-pos');
    await _tap(tester, 'posProduct-1');
    await _tap(tester, 'posProduct-1');
    await _tap(tester, 'completeSaleButton');
    await _tap(tester, 'receiptCloseButton');
    await _tap(tester, 'nav-products');
    await _tap(tester, 'open-1');
    expect(_value(tester, 'quantity'), '22');
  });

  testWidgets('Edit opens the existing form and comes back to the details', (
    tester,
  ) async {
    final backend = _backend();
    await _open(tester, backend);
    await _tap(tester, 'open-1');
    await _tap(tester, 'detailsEdit');
    expect(find.byKey(const Key('quantityField')), findsOneWidget);
    await tester.enterText(find.byKey(const Key('quantityField')), '40');
    await _tap(tester, 'saveButton');
    expect(find.byKey(const Key('productDetailsPage')), findsOneWidget);
    expect(_value(tester, 'quantity'), '40');
    expect(backend.rowById(1)!['quantity'], 40);
    expect(find.text('"Milk" was updated'), findsOneWidget);

    // Cancel in the form also returns to the details.
    await _tap(tester, 'detailsEdit');
    await _tap(tester, 'cancelButton');
    expect(find.byKey(const Key('productDetailsPage')), findsOneWidget);
  });

  testWidgets('Delete: confirmation, deleted in the database, Undo restores', (
    tester,
  ) async {
    final backend = _backend();
    await _open(tester, backend);
    await _tap(tester, 'open-1');

    // Keep: nothing happens.
    await _tap(tester, 'detailsDelete');
    expect(find.byKey(const Key('confirmDeleteButton')), findsOneWidget);
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(backend.rowById(1), isNotNull);
    expect(find.byKey(const Key('productDetailsPage')), findsOneWidget);

    await _tap(tester, 'detailsDelete');
    await _tap(tester, 'confirmDeleteButton');
    expect(backend.rowById(1), isNull);
    expect(backend.requests, contains('DELETE /api/products/1'));
    expect(find.byKey(const Key('productDetailsPage')), findsNothing);
    expect(find.text('"Milk" was deleted'), findsOneWidget);

    await tester.tap(find.text('Undo'));
    await tester.pumpAndSettle();
    expect(backend.rows.where((r) => r['name'] == 'Milk'), hasLength(1));
    expect(find.text('Milk'), findsOneWidget);
  });

  testWidgets('a product deleted elsewhere: clear message, no crash', (
    tester,
  ) async {
    final backend = _backend();
    await _open(tester, backend);
    backend.rows.removeWhere((r) => r['productId'] == 1);
    await _tap(tester, 'open-1');
    expect(find.byKey(const Key('detailsMissing')), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.tap(find.text('Back to products'));
    await tester.pumpAndSettle();
    expect(find.text('Milk'), findsNothing); // removed from the list too
  });

  testWidgets('Cashier: products stay read-only, no Product Details', (
    tester,
  ) async {
    final backend = _backend();
    await _open(
      tester,
      backend,
      user: 'shini.cashier',
      password: 'Cashier-Pass-1',
    );
    await _tap(tester, 'nav-products');
    expect(find.text('Milk'), findsOneWidget);
    expect(find.byKey(const Key('open-1')), findsNothing);
    expect(find.byKey(const Key('edit-1')), findsNothing);
    expect(find.byKey(const Key('nav-productDetails')), findsNothing);
    await tester.tap(find.text('Milk'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('productDetailsPage')), findsNothing);
  });

  testWidgets(
    'another store: its products never appear, its id gives "not found"',
    (tester) async {
      final backend = _backend();
      final neshan = backend.addStore('Neshan Market');
      backend.addUser('neshan.admin', 'Admin-Pass-1', 'STORE_ADMIN', neshan);
      backend.seed(
        name: 'Juice',
        barcode: '44444444',
        price: 8,
        quantity: 5,
        minStock: 1,
        storeId: neshan,
      );
      await _open(tester, backend, user: 'neshan.admin');
      expect(find.text('Juice'), findsOneWidget);
      expect(find.text('Milk'), findsNothing);
      expect(find.byKey(const Key('open-1')), findsNothing);
      await _tap(tester, 'open-4');
      expect(_value(tester, 'name'), 'Juice');
      expect(
        await backend
            .apiClient(as: 'neshan.admin')
            .fetchProduct('1')
            .then((_) => 200, onError: (Object e) => 404),
        404,
      );
    },
  );

  for (final locale in const [Locale('en'), Locale('ar')]) {
    testWidgets(
      'Figure 3.4 layout (${locale.languageCode}): image left, information '
      'right, Delete under the image, Edit under the information',
      (tester) async {
        await _open(tester, _backend(), locale: locale);
        await _tap(tester, 'open-1');
        final image = tester.getCenter(find.byKey(const Key('detailsImage')));
        final info = tester.getCenter(find.byKey(const Key('detailsInfo')));
        final del = tester.getCenter(find.byKey(const Key('detailsDelete')));
        final edit = tester.getCenter(find.byKey(const Key('detailsEdit')));
        expect(image.dx, lessThan(info.dx));
        expect(del.dx, lessThan(edit.dx));
        expect(del.dy, greaterThan(image.dy));
        // Label on the left, value on the right.
        final row = find.byKey(const Key('detail-name'));
        final texts = tester.widgetList<Text>(
          find.descendant(of: row, matching: find.byType(Text)),
        );
        final label = find.text(texts.first.data!);
        final value = find.text('Milk');
        expect(
          tester.getCenter(label.last).dx,
          lessThan(tester.getCenter(value.last).dx),
        );
        if (locale.languageCode == 'ar') {
          expect(find.text('تفاصيل المنتج'), findsWidgets);
          expect(find.text('تعديل المنتج'), findsOneWidget);
          expect(find.text('حذف المنتج'), findsOneWidget);
          expect(find.text('تاريخ الإضافة'), findsOneWidget);
          expect(find.text('سعر التكلفة'), findsOneWidget);
        }
        expect(tester.takeException(), isNull);
      },
    );
  }

  for (final size in const [
    Size(1440, 1000),
    Size(1024, 768),
    Size(390, 844),
  ]) {
    testWidgets('no overflow at $size', (tester) async {
      await _open(tester, _backend(), size: size);
      await _tap(tester, 'open-1');
      expect(tester.takeException(), isNull);
      expect(find.byKey(const Key('detailsEdit')), findsOneWidget);
      expect(find.byKey(const Key('detailsDelete')), findsOneWidget);
    });
  }
}

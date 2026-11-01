import 'package:clixsell_pos/main.dart';
import 'package:clixsell_pos/state/product_store.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_product_backend.dart';

/// Server data used by most tests (ids 1, 2, 3; listed newest first):
/// Milk (in stock, ₪120), Bread (low, ₪6), Eggs (out, ₪0) → value ₪126.
FakeProductBackend _backend() => FakeProductBackend()
  ..seed(name: 'Milk', barcode: '11111111', price: 5, quantity: 24, minStock: 10)
  ..seed(name: 'Bread', barcode: '22222222', price: 2, quantity: 3, minStock: 10)
  ..seed(name: 'Eggs', barcode: '33333333', price: 15, quantity: 0, minStock: 5);

Future<ProductStore> _pumpApp(WidgetTester tester, FakeProductBackend backend,
    {Size size = const Size(1440, 1000), Locale? locale}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final store = ProductStore(api: backend.apiClient())..load();
  await tester.pumpWidget(ClixSellApp(store: store, initialLocale: locale));
  await tester.pumpAndSettle();
  return store;
}

String _statValue(WidgetTester tester, String key) {
  final texts = find.descendant(of: find.byKey(Key(key)), matching: find.byType(Text));
  // Order inside StatCard: title, value, subtitle.
  return (tester.widget<Text>(texts.at(1))).data!;
}

void main() {
  testWidgets('loads products from the server and shows dynamic stats', (tester) async {
    final backend = _backend();
    await _pumpApp(tester, backend);

    expect(backend.requests, ['GET /api/products']);
    expect(find.text('Inventory Management'), findsWidgets);
    expect(_statValue(tester, 'statTotal'), '3');
    expect(_statValue(tester, 'statLow'), '1');
    expect(_statValue(tester, 'statOut'), '1');
    expect(_statValue(tester, 'statValue'), '₪ 126.00');

    expect(find.text('Milk'), findsOneWidget);
    expect(find.text('22222222'), findsOneWidget);
    expect(find.text('In Stock'), findsWidgets);
    expect(find.text('Low Stock'), findsWidgets);
    expect(find.text('Out of Stock'), findsWidgets);
  });

  testWidgets('server down: shows a message, and Try again loads the products', (tester) async {
    final backend = _backend()..offline = true;
    await _pumpApp(tester, backend);

    expect(find.byKey(const Key('serverErrorState')), findsOneWidget);
    expect(find.text("Can't reach the server"), findsOneWidget);
    expect(find.byKey(const Key('statTotal')), findsNothing);

    backend.offline = false;
    await tester.tap(find.text('Try again'));
    await tester.pumpAndSettle();
    expect(find.text('Milk'), findsOneWidget);
    expect(_statValue(tester, 'statTotal'), '3');
  });

  testWidgets('add product: validation, POST to the server, list + stats update', (tester) async {
    final backend = _backend();
    await _pumpApp(tester, backend);

    await tester.tap(find.byKey(const Key('headerAddProductButton')));
    await tester.pumpAndSettle();
    expect(find.text('Add New Product'), findsOneWidget);

    // Empty form: errors, nothing sent.
    await tester.tap(find.byKey(const Key('saveButton')));
    await tester.pumpAndSettle();
    expect(find.text('This field is required'), findsWidgets);
    expect(find.text('Please select a category'), findsOneWidget);
    expect(backend.requests.where((r) => r.startsWith('POST')), isEmpty);

    await tester.enterText(find.byKey(const Key('nameField')), 'Orange Juice');
    await tester.enterText(find.byKey(const Key('barcodeField')), '44444444');
    await tester.tap(find.byKey(const Key('categoryField')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Beverages').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('priceField')), '8.5');
    await tester.enterText(find.byKey(const Key('quantityField')), '4');
    await tester.enterText(find.byKey(const Key('minStockField')), '6');
    await tester.enterText(find.byKey(const Key('supplierField')), 'Juice Ltd');
    await tester.pumpAndSettle();

    await tester.ensureVisible(find.byKey(const Key('saveButton')));
    await tester.tap(find.byKey(const Key('saveButton')));
    await tester.pumpAndSettle();

    // Sent to the server and stored there.
    expect(backend.requests.last, 'POST /api/products');
    expect(backend.rows.last['name'], 'Orange Juice');
    expect(backend.rows.last['category'], 'beverages');
    expect(backend.rows.last['sellingPrice'], 8.5);

    // Back on the list with the new product and updated stats.
    expect(find.text('Orange Juice'), findsOneWidget);
    expect(_statValue(tester, 'statTotal'), '4');
    expect(_statValue(tester, 'statLow'), '2');
    expect(_statValue(tester, 'statValue'), '₪ 160.00'); // 126 + 8.5*4
  });

  testWidgets('add product: server rejects a duplicate barcode, error shown on the field', (tester) async {
    final backend = _backend();
    await _pumpApp(tester, backend);
    // Someone else saved this barcode after we loaded the list.
    backend.seed(name: 'Other device', barcode: '55555555', price: 1, quantity: 1, minStock: 0);

    await tester.tap(find.byKey(const Key('headerAddProductButton')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('nameField')), 'Water');
    await tester.enterText(find.byKey(const Key('barcodeField')), '55555555');
    await tester.tap(find.byKey(const Key('categoryField')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Beverages').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('priceField')), '1');
    await tester.enterText(find.byKey(const Key('quantityField')), '10');
    await tester.enterText(find.byKey(const Key('minStockField')), '2');
    await tester.enterText(find.byKey(const Key('supplierField')), 'Water Co');
    await tester.ensureVisible(find.byKey(const Key('saveButton')));
    await tester.tap(find.byKey(const Key('saveButton')));
    await tester.pumpAndSettle();

    expect(find.text('Add New Product'), findsOneWidget); // still on the form
    expect(find.text('Another product already uses this barcode'), findsWidgets);

    // Changing the barcode clears the server error and saving works.
    await tester.enterText(find.byKey(const Key('barcodeField')), '66666666');
    await tester.ensureVisible(find.byKey(const Key('saveButton')));
    await tester.tap(find.byKey(const Key('saveButton')));
    await tester.pumpAndSettle();
    expect(find.text('Water'), findsOneWidget);
  });

  testWidgets('edit product: pre-filled form, PUT to the server, list and stats update', (tester) async {
    final backend = _backend();
    await _pumpApp(tester, backend);

    await tester.tap(find.byKey(const Key('edit-2'))); // Bread
    await tester.pumpAndSettle();

    expect(find.text('Edit Product'), findsWidgets);
    String fieldText(String key) => tester
        .widget<EditableText>(find.descendant(
            of: find.byKey(Key(key)), matching: find.byType(EditableText)))
        .controller
        .text;
    expect(fieldText('nameField'), 'Bread');
    expect(fieldText('quantityField'), '3');
    expect(fieldText('priceField'), '2');

    await tester.enterText(find.byKey(const Key('nameField')), 'White Bread');
    await tester.enterText(find.byKey(const Key('quantityField')), '30');
    await tester.ensureVisible(find.byKey(const Key('saveButton')));
    await tester.tap(find.byKey(const Key('saveButton')));
    await tester.pumpAndSettle();

    expect(backend.requests.last, 'PUT /api/products/2');
    expect(backend.rowById(2)!['name'], 'White Bread');
    expect(backend.rowById(2)!['quantity'], 30);
    expect(find.text('White Bread'), findsOneWidget);
    expect(_statValue(tester, 'statLow'), '0');
    expect(_statValue(tester, 'statValue'), '₪ 180.00'); // 120 + 2*30
  });

  testWidgets('delete product: confirmation, DELETE on the server, undo re-inserts', (tester) async {
    final backend = _backend();
    final store = await _pumpApp(tester, backend);

    // Cancel keeps the product.
    await tester.tap(find.byKey(const Key('delete-3')));
    await tester.pumpAndSettle();
    expect(find.text('Delete product?'), findsOneWidget);
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(backend.rows.length, 3);

    // Confirm deletes it on the server.
    await tester.tap(find.byKey(const Key('delete-3')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('confirmDeleteButton')));
    await tester.pumpAndSettle();

    expect(backend.requests.last, 'DELETE /api/products/3');
    expect(backend.rowById(3), isNull);
    expect(store.products.length, 2);
    expect(_statValue(tester, 'statTotal'), '2');
    expect(_statValue(tester, 'statOut'), '0');
    expect(find.text('"Eggs" was deleted'), findsOneWidget);

    // Undo inserts it again.
    await tester.tap(find.text('Undo'));
    await tester.pumpAndSettle();
    expect(backend.requests.last, 'POST /api/products');
    expect(backend.rows.length, 3);
    expect(_statValue(tester, 'statOut'), '1');
  });

  testWidgets('delete fails when the server is down: product stays, error shown', (tester) async {
    final backend = _backend();
    final store = await _pumpApp(tester, backend);

    backend.offline = true;
    await tester.tap(find.byKey(const Key('delete-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('confirmDeleteButton')));
    await tester.pumpAndSettle();

    expect(store.products.length, 3);
    expect(find.textContaining('Could not delete the product'), findsOneWidget);
  });

  testWidgets('sidebar navigation + unsaved-changes guard', (tester) async {
    await _pumpApp(tester, _backend());

    await tester.tap(find.text('Add Product').first); // sidebar item
    await tester.pumpAndSettle();
    expect(find.text('Add New Product'), findsOneWidget);

    await tester.enterText(find.byKey(const Key('nameField')), 'Draft');
    await tester.tap(find.text('Products')); // sidebar item
    await tester.pumpAndSettle();
    expect(find.text('Discard changes?'), findsOneWidget);
    await tester.tap(find.text('Keep editing'));
    await tester.pumpAndSettle();
    expect(find.text('Add New Product'), findsOneWidget);

    await tester.tap(find.text('Products'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Discard'));
    await tester.pumpAndSettle();
    expect(find.text('Inventory Management'), findsWidgets);
  });

  testWidgets('stat card filters and search narrow the list', (tester) async {
    await _pumpApp(tester, _backend());

    await tester.tap(find.byKey(const Key('statLow')));
    await tester.pumpAndSettle();
    expect(find.text('Bread'), findsOneWidget);
    expect(find.text('Milk'), findsNothing);

    await tester.tap(find.byKey(const Key('statLow'))); // toggle off
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('searchField')), '3333');
    await tester.pumpAndSettle();
    expect(find.text('Eggs'), findsOneWidget);
    expect(find.text('Milk'), findsNothing);
  });

  testWidgets('Arabic locale renders right-to-left', (tester) async {
    await _pumpApp(tester, _backend(), locale: const Locale('ar'));
    expect(find.text('إدارة المخزون'), findsWidgets);
    final dir = Directionality.of(tester.element(find.byKey(const Key('statTotal'))));
    expect(dir, TextDirection.rtl);

    await tester.tap(find.text('English'));
    await tester.pumpAndSettle();
    expect(find.text('Inventory Management'), findsWidgets);
  });

  for (final size in const [Size(900, 900), Size(390, 844)]) {
    testWidgets('responsive layout without overflow at $size', (tester) async {
      await _pumpApp(tester, _backend(), size: size);
      expect(tester.takeException(), isNull);
      await tester.tap(find.byKey(const Key('headerAddProductButton')));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.text('Add New Product'), findsOneWidget);
    });
  }
}

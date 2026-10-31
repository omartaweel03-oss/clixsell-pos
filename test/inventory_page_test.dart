import 'package:clixsell_pos/main.dart';
import 'package:clixsell_pos/models/product.dart';
import 'package:clixsell_pos/models/product_category.dart';
import 'package:clixsell_pos/state/product_store.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

Product _p(String id, String name, String barcode,
    {required int qty, required int min, required double price}) {
  final now = DateTime(2025, 1, 1);
  return Product(
    id: id,
    name: name,
    barcode: barcode,
    category: ProductCategory.dairy,
    price: price,
    quantity: qty,
    minStockLevel: min,
    supplier: 'Dairy Co',
    createdAt: now,
    updatedAt: now,
  );
}

ProductStore _store() => ProductStore(initialProducts: [
      _p('a', 'Milk', '11111111', qty: 24, min: 10, price: 5), // in stock, 120
      _p('b', 'Bread', '22222222', qty: 3, min: 10, price: 2), // low, 6
      _p('c', 'Eggs', '33333333', qty: 0, min: 5, price: 15), // out, 0
    ]);

Future<void> _pumpApp(WidgetTester tester, ProductStore store,
    {Size size = const Size(1440, 1000), Locale? locale}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ClixSellApp(store: store, initialLocale: locale));
  await tester.pumpAndSettle();
}

String _statValue(WidgetTester tester, String key) {
  final texts = find.descendant(of: find.byKey(Key(key)), matching: find.byType(Text));
  // Order inside StatCard: title, value, subtitle.
  return (tester.widget<Text>(texts.at(1))).data!;
}

void main() {
  testWidgets('shows header, dynamic stats and product list', (tester) async {
    await _pumpApp(tester, _store());

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

  testWidgets('add product: validation, save, list + stats update', (tester) async {
    final store = _store();
    await _pumpApp(tester, store);

    await tester.tap(find.byKey(const Key('headerAddProductButton')));
    await tester.pumpAndSettle();
    expect(find.text('Add New Product'), findsOneWidget);

    // Submitting empty form shows required errors and does not save.
    await tester.tap(find.byKey(const Key('saveButton')));
    await tester.pumpAndSettle();
    expect(find.text('This field is required'), findsWidgets);
    expect(find.text('Please select a category'), findsOneWidget);
    expect(store.products.length, 3);

    // Duplicate barcode is rejected.
    await tester.enterText(find.byKey(const Key('barcodeField')), '11111111');
    await tester.pumpAndSettle();
    expect(find.text('Another product already uses this barcode'), findsOneWidget);

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

    // Back on the list with the new product and updated stats.
    expect(find.text('Orange Juice'), findsOneWidget);
    expect(_statValue(tester, 'statTotal'), '4');
    expect(_statValue(tester, 'statLow'), '2');
    expect(_statValue(tester, 'statValue'), '₪ 160.00'); // 126 + 8.5*4
    expect(store.products.first.category, ProductCategory.beverages);
  });

  testWidgets('edit product: pre-filled form updates list and stats', (tester) async {
    final store = _store();
    await _pumpApp(tester, store);

    await tester.tap(find.byKey(const Key('edit-b')));
    await tester.pumpAndSettle();

    expect(find.text('Edit Product'), findsWidgets);
    final name = tester.widget<EditableText>(find.descendant(
        of: find.byKey(const Key('nameField')), matching: find.byType(EditableText)));
    expect(name.controller.text, 'Bread');
    final qty = tester.widget<EditableText>(find.descendant(
        of: find.byKey(const Key('quantityField')), matching: find.byType(EditableText)));
    expect(qty.controller.text, '3');
    final price = tester.widget<EditableText>(find.descendant(
        of: find.byKey(const Key('priceField')), matching: find.byType(EditableText)));
    expect(price.controller.text, '2');

    // Make Bread in stock and rename it.
    await tester.enterText(find.byKey(const Key('nameField')), 'White Bread');
    await tester.enterText(find.byKey(const Key('quantityField')), '30');
    await tester.ensureVisible(find.byKey(const Key('saveButton')));
    await tester.tap(find.byKey(const Key('saveButton')));
    await tester.pumpAndSettle();

    expect(find.text('White Bread'), findsOneWidget);
    expect(_statValue(tester, 'statLow'), '0');
    expect(_statValue(tester, 'statValue'), '₪ 180.00'); // 120 + 2*30
    expect(store.products.length, 3);
  });

  testWidgets('delete product requires confirmation and updates stats', (tester) async {
    final store = _store();
    await _pumpApp(tester, store);

    // Cancel keeps the product.
    await tester.tap(find.byKey(const Key('delete-c')));
    await tester.pumpAndSettle();
    expect(find.text('Delete product?'), findsOneWidget);
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(store.products.length, 3);

    // Confirm deletes it.
    await tester.tap(find.byKey(const Key('delete-c')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('confirmDeleteButton')));
    await tester.pumpAndSettle();

    expect(store.products.length, 2);
    expect(_statValue(tester, 'statTotal'), '2');
    expect(_statValue(tester, 'statOut'), '0');
    expect(find.text('"Eggs" was deleted'), findsOneWidget);

    // Undo restores it.
    await tester.tap(find.text('Undo'));
    await tester.pumpAndSettle();
    expect(store.products.length, 3);
    expect(_statValue(tester, 'statOut'), '1');
  });

  testWidgets('sidebar navigation + unsaved-changes guard', (tester) async {
    await _pumpApp(tester, _store());

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
    await _pumpApp(tester, _store());

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
    await _pumpApp(tester, _store(), locale: const Locale('ar'));
    expect(find.text('إدارة المخزون'), findsWidgets);
    final dir = Directionality.of(tester.element(find.byKey(const Key('statTotal'))));
    expect(dir, TextDirection.rtl);

    // Toggle back to English from the sidebar.
    await tester.tap(find.text('English'));
    await tester.pumpAndSettle();
    expect(find.text('Inventory Management'), findsWidgets);
  });

  for (final size in const [Size(900, 900), Size(390, 844)]) {
    testWidgets('responsive layout without overflow at $size', (tester) async {
      await _pumpApp(tester, ProductStore.withSampleData(), size: size);
      expect(tester.takeException(), isNull);
      await tester.tap(find.byKey(const Key('headerAddProductButton')));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.text('Add New Product'), findsOneWidget);
    });
  }
}

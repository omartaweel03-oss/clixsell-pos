import 'dart:typed_data';

import 'package:clixsell_pos/l10n/app_strings.dart';
import 'package:clixsell_pos/models/product.dart';
import 'package:clixsell_pos/models/product_category.dart';
import 'package:clixsell_pos/screens/inventory/product_validators.dart';
import 'package:clixsell_pos/services/product_api_client.dart';
import 'package:clixsell_pos/state/product_store.dart';
import 'package:clixsell_pos/utils/formatters.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_product_backend.dart';

Product _product(String id, {required int qty, required int min, double price = 10}) {
  final now = DateTime(2025);
  return Product(
    id: id,
    name: 'Item $id',
    barcode: '1000000$id'.padRight(8, '0'),
    category: ProductCategory.other,
    price: price,
    quantity: qty,
    minStockLevel: min,
    supplier: 'Supplier',
    createdAt: now,
    updatedAt: now,
  );
}

void main() {
  group('Stock status rules', () {
    test('quantity 0 is out of stock (even when min is 0)', () {
      expect(_product('1', qty: 0, min: 5).stockStatus, StockStatus.outOfStock);
      expect(_product('2', qty: 0, min: 0).stockStatus, StockStatus.outOfStock);
    });

    test('0 < quantity <= min is low stock', () {
      expect(_product('3', qty: 1, min: 5).stockStatus, StockStatus.lowStock);
      expect(_product('4', qty: 5, min: 5).stockStatus, StockStatus.lowStock);
    });

    test('quantity > min is in stock', () {
      expect(_product('5', qty: 6, min: 5).stockStatus, StockStatus.inStock);
    });
  });

  test('InventoryStats computes totals dynamically', () {
    final stats = InventoryStats.from([
      _product('1', qty: 0, min: 5, price: 15), // out
      _product('2', qty: 3, min: 10, price: 2), // low
      _product('3', qty: 24, min: 10, price: 5), // in
    ]);
    expect(stats.totalProducts, 3);
    expect(stats.lowStock, 1);
    expect(stats.outOfStock, 1);
    expect(stats.inventoryValue, 0 * 15 + 3 * 2 + 24 * 5);
  });

  group('Product JSON', () {
    test('fromJson reads the backend response, including its stock status', () {
      final p = Product.fromJson({
        'productId': 7,
        'name': 'Milk',
        'barcode': '12345678',
        'category': 'freshProduce',
        'sellingPrice': 5,
        'quantity': 3,
        'minStock': 10,
        'supplierName': 'Dairy Co',
        'stockStatus': 'LOW_STOCK',
        'image': 'AQID', // base64 of [1, 2, 3]
        'imageName': 'milk.png',
        'createdAt': '2026-11-01T22:37:43',
        'updatedAt': '2026-11-01T22:40:00',
      });
      expect(p.id, '7');
      expect(p.category, ProductCategory.freshProduce);
      expect(p.price, 5.0);
      expect(p.imageBytes, [1, 2, 3]);
      expect(p.serverStockStatus, StockStatus.lowStock);
      expect(p.stockStatus, StockStatus.lowStock);
      expect(p.updatedAt, DateTime(2026, 11, 1, 22, 40));
    });

    test('toRequestJson matches the backend ProductRequest', () {
      final p = _product('1', qty: 4, min: 2, price: 8.5).copyWith(
        imageBytes: Uint8List.fromList([1, 2, 3]),
        imageName: 'a.png',
      );
      expect(p.toRequestJson(), {
        'name': 'Item 1',
        'barcode': '10000001',
        'category': 'other',
        'sellingPrice': 8.5,
        'quantity': 4,
        'minStock': 2,
        'supplierName': 'Supplier',
        'image': 'AQID',
        'imageName': 'a.png',
        'costPrice': null,
      });
    });
  });

  group('ProductStore with the API', () {
    late FakeProductBackend backend;
    late ProductStore store;

    setUp(() {
      backend = FakeProductBackend()
        ..seed(name: 'Eggs', barcode: '33333333', price: 15, quantity: 0, minStock: 5)
        ..seed(name: 'Bread', barcode: '22222222', price: 2, quantity: 3, minStock: 10);
      store = ProductStore(api: backend.apiClient());
    });

    test('load fetches products from the server (SELECT)', () async {
      await store.load();
      expect(store.hasLoaded, isTrue);
      expect(store.products.map((p) => p.name), ['Bread', 'Eggs']);
      expect(store.stats.outOfStock, 1);
      expect(backend.requests, ['GET /api/products']);
    });

    test('load failure is reported, and retry works', () async {
      backend.offline = true;
      await store.load();
      expect(store.hasLoaded, isFalse);
      expect(store.loadError!.isNetworkError, isTrue);

      backend.offline = false;
      await store.load();
      expect(store.hasLoaded, isTrue);
      expect(store.loadError, isNull);
    });

    test('add sends POST and keeps the saved product (INSERT)', () async {
      await store.load();
      final saved = await store.addProduct(
        name: ' Milk ',
        barcode: '11111111',
        category: ProductCategory.dairy,
        price: 5,
        quantity: 24,
        minStockLevel: 10,
        supplier: 'Dairy Co',
      );
      expect(saved.id, '3');
      expect(saved.name, 'Milk');
      expect(store.products.first.id, '3');
      expect(store.stats.totalProducts, 3);
      expect(backend.rows.length, 3);
      expect(backend.requests.last, 'POST /api/products');
    });

    test('duplicate barcode from the server throws and changes nothing', () async {
      await store.load();
      await expectLater(
        store.addProduct(
          name: 'Copy',
          barcode: '22222222',
          category: ProductCategory.dairy,
          price: 1,
          quantity: 1,
          minStockLevel: 1,
          supplier: 'X',
        ),
        throwsA(isA<ApiException>()
            .having((e) => e.isDuplicateBarcode, 'isDuplicateBarcode', true)),
      );
      expect(store.products.length, 2);
    });

    test('update sends PUT and replaces the product (UPDATE)', () async {
      await store.load();
      final bread = store.products.firstWhere((p) => p.name == 'Bread');
      await store.updateProduct(bread.copyWith(quantity: 50));
      expect(store.findById(bread.id)!.quantity, 50);
      expect(store.findById(bread.id)!.serverStockStatus, StockStatus.inStock);
      expect(store.stats.lowStock, 0);
      expect(backend.rowById(2)!['quantity'], 50);
      expect(backend.requests.last, 'PUT /api/products/2');
    });

    test('delete sends DELETE and removes the product; restore re-inserts it', () async {
      await store.load();
      final removed = await store.deleteProduct('1');
      expect(removed!.name, 'Eggs');
      expect(store.products.length, 1);
      expect(backend.rowById(1), isNull);
      expect(backend.requests.last, 'DELETE /api/products/1');

      await store.restoreProduct(removed);
      expect(store.products.length, 2);
      expect(backend.rows.length, 2);
    });

    test('delete failure (server down) keeps the product', () async {
      await store.load();
      backend.offline = true;
      await expectLater(store.deleteProduct('1'), throwsA(isA<ApiException>()));
      expect(store.products.length, 2);
    });
  });

  group('Validators', () {
    late ProductValidators v;
    setUp(() {
      v = const ProductValidators(AppStrings.english);
    });

    test('name', () {
      expect(v.name(''), isNotNull);
      expect(v.name('A'), isNotNull);
      expect(v.name('Milk'), isNull);
    });

    test('barcode', () {
      bool none(String _) => false;
      expect(v.barcode('', isTaken: none), isNotNull);
      expect(v.barcode('12ab5678', isTaken: none), isNotNull);
      expect(v.barcode('1234567', isTaken: none), isNotNull);
      expect(v.barcode('123456789012345', isTaken: none), isNotNull);
      expect(v.barcode('12345678', isTaken: none), isNull);
      expect(v.barcode('12345678', isTaken: (_) => true), isNotNull);
    });

    test('price', () {
      expect(v.price(''), isNotNull);
      expect(v.price('0'), isNotNull);
      expect(v.price('1.234'), isNotNull);
      expect(v.price('1.2.3'), isNotNull);
      expect(v.price('5'), isNull);
      expect(v.price('5.25'), isNull);
    });

    test('whole numbers', () {
      expect(v.wholeNumber(''), isNotNull);
      expect(v.wholeNumber('-1'), isNotNull);
      expect(v.wholeNumber('2.5'), isNotNull);
      expect(v.wholeNumber('0'), isNull);
      expect(v.wholeNumber('12'), isNull);
    });

    test('image', () {
      expect(v.image(extension: 'png', sizeInBytes: 1000), isNull);
      expect(v.image(extension: 'pdf', sizeInBytes: 1000), isNotNull);
      expect(v.image(extension: 'jpg', sizeInBytes: 6 * 1024 * 1024), isNotNull);
    });
  });

  test('Formatters', () {
    expect(Formatters.number(85000), '85,000.00');
    expect(Formatters.number(1234567.5), '1,234,567.50');
    expect(Formatters.integer(1245), '1,245');
    expect(Formatters.currency(5), '₪ 5.00');
  });
}

import 'package:clixsell_pos/l10n/app_strings.dart';
import 'package:clixsell_pos/models/product.dart';
import 'package:clixsell_pos/models/product_category.dart';
import 'package:clixsell_pos/screens/inventory/product_validators.dart';
import 'package:clixsell_pos/state/product_store.dart';
import 'package:clixsell_pos/utils/formatters.dart';
import 'package:flutter_test/flutter_test.dart';

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

  group('InventoryStats', () {
    test('computes totals dynamically from the product list', () {
      final store = ProductStore(initialProducts: [
        _product('1', qty: 0, min: 5, price: 15), // out
        _product('2', qty: 3, min: 10, price: 2), // low
        _product('3', qty: 24, min: 10, price: 5), // in
      ]);
      var stats = store.stats;
      expect(stats.totalProducts, 3);
      expect(stats.lowStock, 1);
      expect(stats.outOfStock, 1);
      expect(stats.inventoryValue, 0 * 15 + 3 * 2 + 24 * 5);

      store.addProduct(
        name: 'New',
        barcode: '99999999',
        category: ProductCategory.dairy,
        price: 2.5,
        quantity: 4,
        minStockLevel: 4,
        supplier: 'X',
      );
      stats = store.stats;
      expect(stats.totalProducts, 4);
      expect(stats.lowStock, 2);
      expect(stats.inventoryValue, 126 + 10);

      store.deleteProduct('1');
      stats = store.stats;
      expect(stats.totalProducts, 3);
      expect(stats.outOfStock, 0);
    });

    test('update replaces product and rejects duplicate barcodes', () {
      final a = _product('1', qty: 1, min: 1);
      final b = _product('2', qty: 1, min: 1);
      final store = ProductStore(initialProducts: [a, b]);
      store.updateProduct(a.copyWith(quantity: 50));
      expect(store.findById('1')!.quantity, 50);
      expect(store.stats.lowStock, 1);
      expect(
        () => store.updateProduct(a.copyWith(barcode: b.barcode)),
        throwsStateError,
      );
    });

    test('restore puts a deleted product back', () {
      final store = ProductStore(initialProducts: [_product('1', qty: 1, min: 0)]);
      final removed = store.deleteProduct('1')!;
      expect(store.products, isEmpty);
      store.restoreProduct(removed);
      expect(store.products.single.id, '1');
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


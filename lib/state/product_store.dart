import 'dart:typed_data';

import 'package:flutter/widgets.dart';

import '../models/product.dart';
import '../models/product_category.dart';

/// Inventory statistics derived from the current product list.
/// Nothing here is hard-coded – it is recomputed from [ProductStore.products].
class InventoryStats {
  const InventoryStats({
    required this.totalProducts,
    required this.lowStock,
    required this.outOfStock,
    required this.inventoryValue,
  });

  final int totalProducts;
  final int lowStock;
  final int outOfStock;
  final double inventoryValue;

  factory InventoryStats.from(Iterable<Product> products) {
    var low = 0;
    var out = 0;
    var value = 0.0;
    var total = 0;
    for (final p in products) {
      total++;
      value += p.stockValue;
      switch (p.stockStatus) {
        case StockStatus.lowStock:
          low++;
        case StockStatus.outOfStock:
          out++;
        case StockStatus.inStock:
          break;
      }
    }
    return InventoryStats(
      totalProducts: total,
      lowStock: low,
      outOfStock: out,
      inventoryValue: value,
    );
  }
}

/// In-memory product repository.
///
/// Products live only in application memory for now (no database / backend).
/// Widgets listen to this [ChangeNotifier] so the product list and statistics
/// update immediately after every add, edit or delete.
class ProductStore extends ChangeNotifier {
  ProductStore({List<Product>? initialProducts})
      : _products = List.of(initialProducts ?? const []);

  /// Store pre-filled with a few demo products so the page is not empty on
  /// first launch. They can be edited or deleted like any other product.
  factory ProductStore.withSampleData() =>
      ProductStore(initialProducts: _sampleProducts());

  final List<Product> _products;
  int _idCounter = 0;

  List<Product> get products => List.unmodifiable(_products);

  InventoryStats get stats => InventoryStats.from(_products);

  /// Sorted, de-duplicated supplier names (used for form autocomplete).
  List<String> get suppliers {
    final set = <String>{for (final p in _products) p.supplier.trim()};
    set.removeWhere((s) => s.isEmpty);
    return set.toList()..sort();
  }

  Product? findById(String id) {
    for (final p in _products) {
      if (p.id == id) return p;
    }
    return null;
  }

  /// True when another product (other than [excludeId]) already uses [barcode].
  bool isBarcodeTaken(String barcode, {String? excludeId}) {
    final normalized = barcode.trim();
    return _products.any((p) => p.barcode == normalized && p.id != excludeId);
  }

  Product addProduct({
    required String name,
    required String barcode,
    required ProductCategory category,
    required double price,
    required int quantity,
    required int minStockLevel,
    required String supplier,
    Uint8List? imageBytes,
    String? imageName,
  }) {
    if (isBarcodeTaken(barcode)) {
      throw StateError('Barcode $barcode already exists');
    }
    final now = DateTime.now();
    final product = Product(
      id: _nextId(),
      name: name.trim(),
      barcode: barcode.trim(),
      category: category,
      price: price,
      quantity: quantity,
      minStockLevel: minStockLevel,
      supplier: supplier.trim(),
      imageBytes: imageBytes,
      imageName: imageName,
      createdAt: now,
      updatedAt: now,
    );
    _products.insert(0, product); // newest first
    notifyListeners();
    return product;
  }

  void updateProduct(Product updated) {
    final index = _products.indexWhere((p) => p.id == updated.id);
    if (index == -1) {
      throw StateError('Product ${updated.id} not found');
    }
    if (isBarcodeTaken(updated.barcode, excludeId: updated.id)) {
      throw StateError('Barcode ${updated.barcode} already exists');
    }
    _products[index] = updated.copyWith(updatedAt: DateTime.now());
    notifyListeners();
  }

  /// Removes the product and returns it (so the UI can offer "Undo").
  Product? deleteProduct(String id) {
    final index = _products.indexWhere((p) => p.id == id);
    if (index == -1) return null;
    final removed = _products.removeAt(index);
    notifyListeners();
    return removed;
  }

  /// Puts a previously deleted product back (used by the Undo action).
  void restoreProduct(Product product, {int index = 0}) {
    if (findById(product.id) != null) return;
    _products.insert(index.clamp(0, _products.length), product);
    notifyListeners();
  }

  String _nextId() {
    _idCounter++;
    return 'P-${DateTime.now().microsecondsSinceEpoch}-$_idCounter';
  }

  static List<Product> _sampleProducts() {
    final now = DateTime.now();
    Product p(
      int i,
      String name,
      String barcode,
      ProductCategory c,
      double price,
      int qty,
      int min,
      String supplier,
    ) =>
        Product(
          id: 'seed-$i',
          name: name,
          barcode: barcode,
          category: c,
          price: price,
          quantity: qty,
          minStockLevel: min,
          supplier: supplier,
          createdAt: now.subtract(Duration(days: 30 - i)),
          updatedAt: now.subtract(Duration(days: 30 - i)),
        );

    return [
      p(1, 'حليب 1 لتر', '7290012345678', ProductCategory.dairy, 5.00, 24, 10, 'الجنيدي'),
      p(2, 'خبز أبيض', '7290388880001', ProductCategory.bakery, 2.00, 3, 10, 'مخبز البلد'),
      p(3, 'مياه معدنية 1.5 لتر', '7290005555555', ProductCategory.beverages, 1.00, 60, 20, 'جبل المكبر'),
      p(4, 'بيض 30 حبة', '7290006565656', ProductCategory.freshProduce, 15.00, 0, 5, 'مزارع الريف'),
      p(5, 'عصير برتقال 1 لتر', '7290005555500', ProductCategory.beverages, 8.00, 6, 8, 'سنيورة'),
      p(6, 'Basmati Rice 5kg', '6281000000123', ProductCategory.groceries, 32.50, 18, 5, 'Al-Wadi Trading'),
      p(7, 'Dish Soap 750ml', '6291041500213', ProductCategory.cleaning, 9.90, 0, 4, 'CleanCo'),
      p(8, 'شيبس بطاطا', '7290100250011', ProductCategory.snacks, 3.50, 45, 15, 'الجنيدي'),
    ];
  }
}

/// Makes a single [ProductStore] available to the whole widget tree and
/// rebuilds dependants whenever the store notifies.
class ProductStoreScope extends InheritedNotifier<ProductStore> {
  const ProductStoreScope({
    super.key,
    required ProductStore store,
    required super.child,
  }) : super(notifier: store);

  static ProductStore of(BuildContext context) {
    final scope =
        context.dependOnInheritedWidgetOfExactType<ProductStoreScope>();
    assert(scope != null, 'ProductStoreScope not found in widget tree');
    return scope!.notifier!;
  }

  /// Reads the store without subscribing to updates (for callbacks).
  static ProductStore read(BuildContext context) {
    final scope = context.getInheritedWidgetOfExactType<ProductStoreScope>();
    assert(scope != null, 'ProductStoreScope not found in widget tree');
    return scope!.notifier!;
  }
}

import 'dart:typed_data';

import 'package:flutter/widgets.dart';

import '../models/product.dart';
import '../models/product_category.dart';
import '../services/product_api_client.dart';

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

/// The app's single source of product data.
///
/// Every change goes to the backend first (through [ProductApiClient]):
/// add → INSERT, update → UPDATE, delete → DELETE. Only when the backend
/// confirms the change is the local list updated, so the Product Screen
/// always shows what is stored in the database. Widgets listen to this
/// [ChangeNotifier], so the list and statistics update immediately.
class ProductStore extends ChangeNotifier {
  ProductStore({required ProductApiClient api}) : _api = api;

  final ProductApiClient _api;
  List<Product> _products = [];
  bool _isLoading = false;
  bool _hasLoaded = false;
  ApiException? _loadError;

  List<Product> get products => List.unmodifiable(_products);

  InventoryStats get stats => InventoryStats.from(_products);

  /// True while the product list is being fetched from the backend.
  bool get isLoading => _isLoading;

  /// True once the product list has been fetched successfully at least once.
  bool get hasLoaded => _hasLoaded;

  /// Why the last load failed (null when it succeeded).
  ApiException? get loadError => _loadError;

  /// Base URL of the backend (shown in "cannot reach server" messages).
  String get serverUrl => _api.baseUrl;

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

  /// Quick check against the loaded list, so the form can warn before
  /// saving. The backend makes the final decision (409 DUPLICATE_BARCODE).
  bool isBarcodeTaken(String barcode, {String? excludeId}) {
    final normalized = barcode.trim();
    return _products.any((p) => p.barcode == normalized && p.id != excludeId);
  }

  /// SELECT: loads all products from the backend.
  Future<void> load() async {
    _isLoading = true;
    _loadError = null;
    notifyListeners();
    try {
      _products = await _api.fetchProducts();
      _hasLoaded = true;
    } on ApiException catch (e) {
      _loadError = e;
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  /// INSERT: sends a new product to the backend and adds the saved result.
  ///
  /// Throws [ApiException] if the backend rejects it or cannot be reached.
  Future<Product> addProduct({
    required String name,
    required String barcode,
    required ProductCategory category,
    required double price,
    required int quantity,
    required int minStockLevel,
    required String supplier,
    Uint8List? imageBytes,
    String? imageName,
  }) async {
    final now = DateTime.now();
    final draft = Product(
      id: '',
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
    final saved = await _api.createProduct(draft);
    _products.insert(0, saved); // newest first
    notifyListeners();
    return saved;
  }

  /// UPDATE: sends the changed product to the backend and stores the result.
  ///
  /// Throws [ApiException] if the backend rejects it or cannot be reached.
  Future<Product> updateProduct(Product updated) async {
    final saved = await _api.updateProduct(updated);
    final index = _products.indexWhere((p) => p.id == saved.id);
    if (index == -1) {
      _products.insert(0, saved);
    } else {
      _products[index] = saved;
    }
    notifyListeners();
    return saved;
  }

  /// DELETE: removes the product in the backend, then from the list.
  /// Returns the removed product (so the UI can offer "Undo").
  ///
  /// Throws [ApiException] if the backend cannot delete it.
  Future<Product?> deleteProduct(String id) async {
    final existing = findById(id);
    if (existing == null) return null;
    try {
      await _api.deleteProduct(id);
    } on ApiException catch (e) {
      // Already deleted in the database (e.g. from another device): just
      // remove it here too. Any other error is passed on.
      if (!e.isNotFound) rethrow;
    }
    _products.removeWhere((p) => p.id == id);
    notifyListeners();
    return existing;
  }

  /// Undo for delete: inserts the product again (the database gives it a new id).
  Future<Product> restoreProduct(Product product, {int index = 0}) async {
    final saved = await _api.createProduct(product);
    _products.insert(index.clamp(0, _products.length), saved);
    notifyListeners();
    return saved;
  }

  /// Asks the backend's Product business logic for a new, unused barcode.
  Future<String> generateBarcode() => _api.generateBarcode();
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

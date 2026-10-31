import 'dart:typed_data';

import 'product_category.dart';

/// Stock level classification used by the statistics cards and status badges.
enum StockStatus { inStock, lowStock, outOfStock }

/// Product entity, aligned with the PRODUCTS table in the project ERD
/// (product_id, name, barcode, category, selling_price, quantity, min_stock,
/// supplier). The image is held in memory as raw bytes until a backend exists.
class Product {
  const Product({
    required this.id,
    required this.name,
    required this.barcode,
    required this.category,
    required this.price,
    required this.quantity,
    required this.minStockLevel,
    required this.supplier,
    required this.createdAt,
    required this.updatedAt,
    this.imageBytes,
    this.imageName,
  });

  final String id;
  final String name;
  final String barcode;
  final ProductCategory category;
  final double price;
  final int quantity;
  final int minStockLevel;
  final String supplier;
  final Uint8List? imageBytes;
  final String? imageName;
  final DateTime createdAt;
  final DateTime updatedAt;

  bool get hasImage => imageBytes != null && imageBytes!.isNotEmpty;

  /// Out of stock: quantity is zero.
  /// Low stock: quantity > 0 and quantity <= minimum stock level.
  /// In stock: everything else.
  StockStatus get stockStatus {
    if (quantity <= 0) return StockStatus.outOfStock;
    if (quantity <= minStockLevel) return StockStatus.lowStock;
    return StockStatus.inStock;
  }

  /// Value of this product line in the inventory (price × quantity).
  double get stockValue => price * quantity;

  Product copyWith({
    String? name,
    String? barcode,
    ProductCategory? category,
    double? price,
    int? quantity,
    int? minStockLevel,
    String? supplier,
    Uint8List? imageBytes,
    String? imageName,
    bool removeImage = false,
    DateTime? updatedAt,
  }) {
    return Product(
      id: id,
      name: name ?? this.name,
      barcode: barcode ?? this.barcode,
      category: category ?? this.category,
      price: price ?? this.price,
      quantity: quantity ?? this.quantity,
      minStockLevel: minStockLevel ?? this.minStockLevel,
      supplier: supplier ?? this.supplier,
      imageBytes: removeImage ? null : (imageBytes ?? this.imageBytes),
      imageName: removeImage ? null : (imageName ?? this.imageName),
      createdAt: createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
    );
  }

  @override
  String toString() => 'Product($id, $name, qty: $quantity)';
}

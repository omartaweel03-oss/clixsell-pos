import 'dart:convert';
import 'dart:typed_data';

import 'product_category.dart';

/// Stock level classification used by the statistics cards and status badges.
enum StockStatus { inStock, lowStock, outOfStock }

/// Product as used by the Flutter app.
///
/// Mirrors the backend's Product (table `products`): it is created from the
/// API's JSON with [Product.fromJson] and sent back with [toRequestJson].
/// The image travels as base64 text inside the JSON.
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
    this.serverStockStatus,
  });

  /// Builds a product from the backend's JSON (ProductResponse).
  factory Product.fromJson(Map<String, dynamic> json) {
    final image = json['image'] as String?;
    return Product(
      id: json['productId'].toString(),
      name: json['name'] as String,
      barcode: json['barcode'] as String,
      category: ProductCategory.values.asNameMap()[json['category']] ??
          ProductCategory.other,
      price: (json['sellingPrice'] as num).toDouble(),
      quantity: (json['quantity'] as num).toInt(),
      minStockLevel: (json['minStock'] as num).toInt(),
      supplier: json['supplierName'] as String,
      imageBytes: image == null || image.isEmpty ? null : base64Decode(image),
      imageName: json['imageName'] as String?,
      createdAt: DateTime.parse(json['createdAt'] as String),
      updatedAt: DateTime.parse(json['updatedAt'] as String),
      serverStockStatus: _statusFromApi[json['stockStatus']],
    );
  }

  /// JSON body for POST / PUT /api/products (the backend's ProductRequest).
  Map<String, dynamic> toRequestJson() => {
        'name': name,
        'barcode': barcode,
        'category': category.name,
        'sellingPrice': price,
        'quantity': quantity,
        'minStock': minStockLevel,
        'supplierName': supplier,
        'image': hasImage ? base64Encode(imageBytes!) : null,
        'imageName': hasImage ? imageName : null,
      };

  static const Map<String, StockStatus> _statusFromApi = {
    'IN_STOCK': StockStatus.inStock,
    'LOW_STOCK': StockStatus.lowStock,
    'OUT_OF_STOCK': StockStatus.outOfStock,
  };

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

  /// Stock status calculated by the backend's Product business logic.
  /// Null for a product that has not been saved yet.
  final StockStatus? serverStockStatus;

  bool get hasImage => imageBytes != null && imageBytes!.isNotEmpty;

  /// The status decided by the backend when available. Otherwise (unsaved
  /// changes) the same rules are applied locally:
  /// Out of stock: quantity is zero.
  /// Low stock: quantity > 0 and quantity <= minimum stock level.
  /// In stock: everything else.
  StockStatus get stockStatus {
    if (serverStockStatus != null) return serverStockStatus!;
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

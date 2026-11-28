/// A carton / box of a product (store upgrade, feature 6): [unitsPerPackage] pieces sold
/// together at [price]. Stock is always counted in pieces.
class ProductPackage {
  const ProductPackage({
    required this.packageId,
    required this.productId,
    required this.name,
    required this.unitsPerPackage,
    required this.price,
    required this.active,
    this.barcode,
  });

  factory ProductPackage.fromJson(Map<String, dynamic> j) => ProductPackage(
    packageId: (j['packageId'] as num).toInt(),
    productId: j['productId'].toString(),
    name: j['name'] as String,
    unitsPerPackage: (j['unitsPerPackage'] as num).toInt(),
    price: (j['price'] as num).toDouble(),
    active: j['active'] == true,
    barcode: j['barcode'] as String?,
  );

  final int packageId;
  final String productId;
  final String name;
  final int unitsPerPackage;
  final double price;
  final bool active;
  final String? barcode;
}

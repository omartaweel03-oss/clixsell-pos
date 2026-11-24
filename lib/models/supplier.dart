/// A supplier of the Store Admin's store (Supplier Management, Purchase Orders Phase 1).
///
/// [whatsappNumber] is stored in international format as digits only
/// (e.g. `970599123456`); [products] is filled only by the details request.
class Supplier {
  const Supplier({
    required this.supplierId,
    required this.name,
    this.phone,
    this.whatsappNumber,
    this.address,
    required this.active,
    required this.productCount,
    this.products = const [],
  });

  final int supplierId;
  final String name;
  final String? phone;
  final String? whatsappNumber;
  final String? address;
  final bool active;
  final int productCount;
  final List<SupplierProduct> products;

  bool get hasWhatsapp => (whatsappNumber ?? '').isNotEmpty;

  factory Supplier.fromJson(Map<String, dynamic> json) => Supplier(
    supplierId: (json['supplierId'] as num).toInt(),
    name: json['name'] as String,
    phone: json['phone'] as String?,
    whatsappNumber: json['whatsappNumber'] as String?,
    address: json['address'] as String?,
    active: json['active'] as bool? ?? true,
    productCount: (json['productCount'] as num?)?.toInt() ?? 0,
    products: [
      for (final p in (json['products'] as List<dynamic>? ?? const []))
        SupplierProduct.fromJson(p as Map<String, dynamic>),
    ],
  );
}

/// A product that uses the supplier.
class SupplierProduct {
  const SupplierProduct({
    required this.productId,
    required this.name,
    required this.barcode,
    required this.quantity,
    required this.minStock,
  });

  final int productId;
  final String name;
  final String barcode;
  final int quantity;
  final int minStock;

  factory SupplierProduct.fromJson(Map<String, dynamic> json) =>
      SupplierProduct(
        productId: (json['productId'] as num).toInt(),
        name: json['name'] as String,
        barcode: json['barcode'] as String,
        quantity: (json['quantity'] as num).toInt(),
        minStock: (json['minStock'] as num).toInt(),
      );
}

/// `970599123456` → `+970 599 123 456` (display only; always left-to-right).
String formatWhatsapp(String digits) {
  if (digits.length <= 3) return '+$digits';
  final cc = digits.substring(0, 3);
  final rest = digits.substring(3);
  final groups = <String>[];
  for (var i = 0; i < rest.length; i += 3) {
    groups.add(rest.substring(i, i + 3 > rest.length ? rest.length : i + 3));
  }
  return '+$cc ${groups.join(' ')}';
}

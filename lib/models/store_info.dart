/// A store (supermarket) as returned by the API.
class StoreInfo {
  const StoreInfo({
    required this.storeId,
    required this.name,
    required this.isActive,
    this.address,
    this.phone,
    this.adminCount = 0,
    this.cashierCount = 0,
    this.createdAt,
  });

  factory StoreInfo.fromJson(Map<String, dynamic> json) => StoreInfo(
    storeId: (json['storeId'] as num).toInt(),
    name: json['name'] as String,
    address: json['address'] as String?,
    phone: json['phone'] as String?,
    isActive: json['status'] == 'ACTIVE',
    adminCount: (json['adminCount'] as num?)?.toInt() ?? 0,
    cashierCount: (json['cashierCount'] as num?)?.toInt() ?? 0,
    createdAt: json['createdAt'] == null
        ? null
        : DateTime.parse(json['createdAt'] as String),
  );

  final int storeId;
  final String name;
  final String? address;
  final String? phone;
  final bool isActive;
  final int adminCount;
  final int cashierCount;
  final DateTime? createdAt;
}

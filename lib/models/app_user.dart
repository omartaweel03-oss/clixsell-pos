/// The three roles of the backend (see backend Role.java).
enum UserRole {
  systemAdmin('SYSTEM_ADMIN'),
  storeAdmin('STORE_ADMIN'),
  cashier('CASHIER');

  const UserRole(this.apiName);

  /// Name used by the API, e.g. "STORE_ADMIN".
  final String apiName;

  static UserRole fromApi(String? value) => UserRole.values.firstWhere(
    (r) => r.apiName == value,
    orElse: () => UserRole.cashier,
  );

  bool get worksInStore => this != UserRole.systemAdmin;
}

/// A user account as returned by the API (login, /auth/me, staff lists).
class AppUser {
  const AppUser({
    required this.userId,
    required this.username,
    required this.fullName,
    required this.role,
    required this.isActive,
    this.storeId,
    this.storeName,
    this.createdAt,
  });

  factory AppUser.fromJson(Map<String, dynamic> json) => AppUser(
    userId: (json['userId'] as num).toInt(),
    username: json['username'] as String,
    fullName: json['fullName'] as String,
    role: UserRole.fromApi(json['role'] as String?),
    isActive: json['status'] == 'ACTIVE',
    storeId: (json['storeId'] as num?)?.toInt(),
    storeName: json['storeName'] as String?,
    createdAt: json['createdAt'] == null
        ? null
        : DateTime.parse(json['createdAt'] as String),
  );

  final int userId;
  final String username;
  final String fullName;
  final UserRole role;
  final bool isActive;
  final int? storeId;
  final String? storeName;
  final DateTime? createdAt;
}

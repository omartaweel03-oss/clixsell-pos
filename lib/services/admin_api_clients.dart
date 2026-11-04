import '../models/app_user.dart';
import '../models/store_info.dart';
import 'api_http.dart';

/// System Admin: stores.
///
/// GET/POST /api/admin/stores, PUT /api/admin/stores/{id}, PUT …/{id}/status
class StoresApiClient {
  StoresApiClient(this._http);

  final ApiHttp _http;

  Future<List<StoreInfo>> listStores() async =>
      (await _http.get('/admin/stores') as List<dynamic>)
          .map((e) => StoreInfo.fromJson(e as Map<String, dynamic>))
          .toList();

  Future<StoreInfo> createStore({
    required String name,
    String? address,
    String? phone,
    double? taxRate,
    double? maxDiscountPercent,
  }) async => StoreInfo.fromJson(
    await _http.post('/admin/stores', {
          'name': name,
          'address': address,
          'phone': phone,
          'taxRate': taxRate,
          'maxDiscountPercent': maxDiscountPercent,
        })
        as Map<String, dynamic>,
  );

  Future<StoreInfo> updateStore(
    int storeId, {
    required String name,
    String? address,
    String? phone,
    double? taxRate,
    double? maxDiscountPercent,
  }) async => StoreInfo.fromJson(
    await _http.put('/admin/stores/$storeId', {
          'name': name,
          'address': address,
          'phone': phone,
          'taxRate': taxRate,
          'maxDiscountPercent': maxDiscountPercent,
        })
        as Map<String, dynamic>,
  );

  Future<StoreInfo> setStoreActive(int storeId, bool active) async =>
      StoreInfo.fromJson(
        await _http.put('/admin/stores/$storeId/status', {
              'status': active ? 'ACTIVE' : 'INACTIVE',
            })
            as Map<String, dynamic>,
      );
}

/// The accounts managed on one "staff" screen: the Store Admins of a store
/// (managed by the System Admin) or the Cashiers of the logged-in Store
/// Admin's own store. Both screens look and work the same.
abstract class StaffAccountsApi {
  Future<List<AppUser>> list();

  Future<AppUser> create({
    required String username,
    required String fullName,
    required String password,
  });

  Future<AppUser> setActive(int userId, bool active);

  Future<AppUser> resetPassword(int userId, String password);
}

class _StaffApi implements StaffAccountsApi {
  _StaffApi(this._http, this._basePath);

  final ApiHttp _http;
  final String _basePath;

  @override
  Future<List<AppUser>> list() async =>
      (await _http.get(_basePath) as List<dynamic>)
          .map((e) => AppUser.fromJson(e as Map<String, dynamic>))
          .toList();

  @override
  Future<AppUser> create({
    required String username,
    required String fullName,
    required String password,
  }) async => AppUser.fromJson(
    await _http.post(_basePath, {
          'username': username.trim(),
          'fullName': fullName.trim(),
          'password': password,
        })
        as Map<String, dynamic>,
  );

  @override
  Future<AppUser> setActive(int userId, bool active) async => AppUser.fromJson(
    await _http.put('$_basePath/$userId/status', {
          'status': active ? 'ACTIVE' : 'INACTIVE',
        })
        as Map<String, dynamic>,
  );

  @override
  Future<AppUser> resetPassword(int userId, String password) async =>
      AppUser.fromJson(
        await _http.put('$_basePath/$userId/password', {'password': password})
            as Map<String, dynamic>,
      );
}

/// System Admin: /api/admin/stores/{storeId}/admins
StaffAccountsApi storeAdminsApi(ApiHttp http, int storeId) =>
    _StaffApi(http, '/admin/stores/$storeId/admins');

/// Store Admin: /api/store/cashiers (own store, decided by the backend)
StaffAccountsApi cashiersApi(ApiHttp http) =>
    _StaffApi(http, '/store/cashiers');

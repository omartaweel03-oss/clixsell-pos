import '../models/backup_info.dart';
import '../models/store_info.dart';
import 'api_http.dart';

/// Settings screen (report Figure 3.9) → backend. The backend decides who may
/// do what; the app only shows the cards a role can use.
///
/// | Method              | Request                    | Who                      |
/// |---------------------|----------------------------|--------------------------|
/// | myStore             | GET  /api/store            | Store Admin              |
/// | updateStoreSettings | PUT  /api/store/settings   | Store Admin (own store)  |
/// | changeOwnPassword   | PUT  /api/auth/me/password | System Admin, Store Admin|
/// | demoPasswordInUse   | GET  /api/admin/security-status | System Admin        |
/// | listBackups         | GET  /api/admin/backups    | System Admin             |
/// | createBackup        | POST /api/admin/backups    | System Admin             |
class SettingsApiClient {
  SettingsApiClient(this._http);

  final ApiHttp _http;

  Future<StoreInfo> myStore() async =>
      StoreInfo.fromJson(await _http.get('/store') as Map<String, dynamic>);

  /// Settings of the user's own store. Only the values given are sent and
  /// changed (Taxes sends tax and discount, General address and phone), so
  /// two dialogs never undo each other. An empty address or phone clears it.
  Future<StoreInfo> updateStoreSettings({
    String? address,
    String? phone,
    double? taxRate,
    double? maxDiscountPercent,
  }) async => StoreInfo.fromJson(
    await _http.put('/store/settings', {
          'address': ?address,
          'phone': ?phone,
          'taxRate': ?taxRate,
          'maxDiscountPercent': ?maxDiscountPercent,
        })
        as Map<String, dynamic>,
  );

  /// The logged-in admin's own password. A wrong current password is a 400
  /// with a field error (the session stays).
  Future<void> changeOwnPassword({
    required String currentPassword,
    required String newPassword,
  }) => _http.put('/auth/me/password', {
    'currentPassword': currentPassword,
    'newPassword': newPassword,
  });

  /// System Admin only: true while the signed-in System Admin still uses a
  /// publicly documented demo password (Settings shows a warning). Only a
  /// yes / no about the caller's own account; nothing is changed.
  Future<bool> demoPasswordInUse() async {
    final body =
        await _http.get('/admin/security-status') as Map<String, dynamic>;
    return body['demoPasswordInUse'] == true;
  }

  Future<BackupList> listBackups() async => BackupList.fromJson(
    await _http.get('/admin/backups') as Map<String, dynamic>,
  );

  Future<BackupInfo> createBackup() async => BackupInfo.fromJson(
    await _http.post('/admin/backups') as Map<String, dynamic>,
  );
}

import 'dart:math';

import '../models/cash_shift.dart';
import 'api_http.dart';
import 'purchase_api_client.dart' show newClientId;

/// Cash drawers and shifts → backend (/api/drawers, /api/shifts). The backend
/// decides who may do what and calculates every amount.
class ShiftApiClient {
  ShiftApiClient(this._http);

  final ApiHttp _http;

  /// Cents as the exact decimal text the backend expects ("12.30").
  static String amount(int cents) =>
      '${cents ~/ 100}.${(cents % 100).toString().padLeft(2, '0')}';

  static final _random = Random.secure();

  /// A new key per cash movement, so a retried request is not recorded twice.
  /// Built from 16 random bytes (a UUID), the same on the VM and on the web:
  /// an integer bound like 1 << 32 is not valid in a web build.
  static String newRequestKey() => 'mv-${newClientId(_random)}';

  Future<CurrentShift> current() async => CurrentShift.fromJson(
    await _http.get('/shifts/current') as Map<String, dynamic>,
  );

  Future<CashShift> open({
    required int drawerId,
    required int openingCents,
  }) async => CashShift.fromJson(
    await _http.post('/shifts', {
          'drawerId': drawerId,
          'openingCash': num.parse(amount(openingCents)),
        })
        as Map<String, dynamic>,
  );

  Future<CashShift> close(
    CashShift shift, {
    required int countedCents,
    String? note,
  }) async => CashShift.fromJson(
    await _http.post('/shifts/${shift.shiftId}/close', {
          'countedCash': num.parse(amount(countedCents)),
          'note': ?note,
          'version': shift.version,
        })
        as Map<String, dynamic>,
  );

  Future<CashShift> adminClose(
    CashShift shift, {
    required int countedCents,
    required String reason,
    String? note,
  }) async => CashShift.fromJson(
    await _http.post('/shifts/${shift.shiftId}/admin-close', {
          'countedCash': num.parse(amount(countedCents)),
          'reason': reason,
          'note': ?note,
          'version': shift.version,
        })
        as Map<String, dynamic>,
  );

  Future<List<CashShift>> list({String? status}) async {
    final body =
        await _http.get('/shifts${status == null ? '' : '?status=$status'}')
            as List;
    return [
      for (final s in body) CashShift.fromJson(s as Map<String, dynamic>),
    ];
  }

  Future<ShiftDetails> details(int shiftId) async => ShiftDetails.fromJson(
    await _http.get('/shifts/$shiftId') as Map<String, dynamic>,
  );

  Future<CashMovement> addMovement(
    int shiftId, {
    required String type,
    required int amountCents,
    required String reason,
    required String requestKey,
  }) async => CashMovement.fromJson(
    await _http.post('/shifts/$shiftId/movements', {
          'type': type,
          'amount': num.parse(amount(amountCents)),
          'reason': reason,
          'requestKey': requestKey,
        })
        as Map<String, dynamic>,
  );

  Future<List<CashDrawer>> drawers() async {
    final body = await _http.get('/drawers') as List;
    return [
      for (final d in body) CashDrawer.fromJson(d as Map<String, dynamic>),
    ];
  }

  Future<CashDrawer> addDrawer(String name) async => CashDrawer.fromJson(
    await _http.post('/drawers', {'name': name}) as Map<String, dynamic>,
  );

  Future<CashDrawer> updateDrawer(int id, {String? name, bool? active}) async =>
      CashDrawer.fromJson(
        await _http.patch('/drawers/$id', {'name': ?name, 'active': ?active})
            as Map<String, dynamic>,
      );

  /// D2: the Store Admin makes shifts mandatory (or optional) for the store.
  Future<bool> setRequireCashShift(bool on) async {
    final body =
        await _http.put('/store/settings', {'requireCashShift': on})
            as Map<String, dynamic>;
    return body['requireCashShift'] == true;
  }

  /// D4: the store's cash rounding step in agorot (0 = none).
  Future<int> cashRoundingMinor() async {
    final body = await _http.get('/store') as Map<String, dynamic>;
    return (body['cashRoundingMinor'] as num?)?.toInt() ?? 0;
  }

  Future<int> setCashRoundingMinor(int step) async {
    final body =
        await _http.put('/store/settings', {'cashRoundingMinor': step})
            as Map<String, dynamic>;
    return (body['cashRoundingMinor'] as num?)?.toInt() ?? 0;
  }
}

import 'dart:math';

import 'api_http.dart';

/// Cheque management (feature 9, Store Admin) → /api/cheques, /api/cheque-refunds.
/// The JSON maps are shown as they come: the backend decides every amount and transition.
class ChequeApiClient {
  ChequeApiClient(this._http);

  final ApiHttp _http;
  static final _random = Random.secure();

  static String newRequestKey() =>
      'chq-${DateTime.now().microsecondsSinceEpoch}-${_random.nextInt(1 << 32)}';

  Future<List<Map<String, dynamic>>> list({String? status}) async {
    final body = await _http.get('/cheques${status == null ? '' : '?status=$status'}') as List;
    return [for (final c in body) c as Map<String, dynamic>];
  }

  Future<Map<String, dynamic>> summary() async =>
      await _http.get('/cheques/summary') as Map<String, dynamic>;

  Future<Map<String, dynamic>> details(int id) async =>
      await _http.get('/cheques/$id') as Map<String, dynamic>;

  Future<Map<String, dynamic>> changeStatus(int id, String toStatus, {String? note, int? version}) async =>
      await _http.post('/cheques/$id/status', {'toStatus': toStatus, 'note': ?note, 'version': ?version})
          as Map<String, dynamic>;

  Future<Map<String, dynamic>> payRefund(int refundId, String method, {String? requestKey}) async =>
      await _http.post('/cheque-refunds/$refundId/pay', {'method': method, 'requestKey': ?requestKey})
          as Map<String, dynamic>;

  Future<Map<String, dynamic>> cancelRefund(int refundId, String reason) async =>
      await _http.post('/cheque-refunds/$refundId/cancel', {'reason': reason}) as Map<String, dynamic>;
}

import '../models/sale.dart';
import '../models/store_info.dart';
import 'api_http.dart';

/// The current invoice as sent to the backend: only product ids and
/// quantities (prices always come from the database).
class SaleDraft {
  const SaleDraft({
    required this.lines,
    this.saleId,
    this.discountType,
    this.discountValue = 0,
    this.paymentMethod,
  });

  /// productId → quantity
  final Map<String, int> lines;
  final int? saleId;
  final DiscountType? discountType;
  final double discountValue;
  final PaymentMethod? paymentMethod;

  Map<String, dynamic> toJson() => {
    'saleId': saleId,
    'items': [
      for (final e in lines.entries)
        {'productId': int.parse(e.key), 'quantity': e.value},
    ],
    'discountType': discountValue > 0 ? discountType?.apiName : null,
    'discountValue': discountValue > 0 ? discountValue : null,
    'paymentMethod': paymentMethod?.apiName,
  };
}

/// Cashier Dashboard API (own store only, decided by the backend):
///
/// | Method          | HTTP                                | Database |
/// |-----------------|-------------------------------------|----------|
/// | completeSale    | POST   /api/sales                   | INSERT sale, items, payment, receipt; UPDATE stock |
/// | suspend         | POST / PUT /api/sales/suspended     | INSERT / UPDATE (SUSPENDED) |
/// | listSuspended   | GET    /api/sales/suspended         | SELECT |
/// | getSale         | GET    /api/sales/{id}              | SELECT |
/// | cancelSuspended | DELETE /api/sales/suspended/{id}    | UPDATE status = CANCELED |
/// | lookup          | GET    /api/sales/lookup?code=      | SELECT |
/// | returnProduct   | POST   /api/returns                 | INSERT return; UPDATE stock |
/// | storeSettings   | GET    /api/store                   | SELECT (tax rate, max discount) |
class SalesApiClient {
  SalesApiClient(this._http);

  final ApiHttp _http;

  Future<Sale> completeSale(SaleDraft draft) async => Sale.fromJson(
    await _http.post('/sales', draft.toJson()) as Map<String, dynamic>,
  );

  /// Suspends a new invoice, or suspends a resumed one again (keeps its number).
  Future<Sale> suspend(SaleDraft draft) async {
    final body = draft.toJson();
    final json = draft.saleId == null
        ? await _http.post('/sales/suspended', body)
        : await _http.put('/sales/suspended/${draft.saleId}', body);
    return Sale.fromJson(json as Map<String, dynamic>);
  }

  Future<List<Sale>> listSuspended() async =>
      (await _http.get('/sales/suspended') as List<dynamic>)
          .map((e) => Sale.fromJson(e as Map<String, dynamic>))
          .toList();

  Future<Sale> getSale(int saleId) async =>
      Sale.fromJson(await _http.get('/sales/$saleId') as Map<String, dynamic>);

  Future<Sale> cancelSuspended(int saleId) async => Sale.fromJson(
    await _http.delete('/sales/suspended/$saleId') as Map<String, dynamic>,
  );

  /// A completed sale by invoice number (e.g. INV-000012) or receipt QR code.
  Future<Sale> lookup(String code) async => Sale.fromJson(
    await _http.get(
          '/sales/lookup?code=${Uri.encodeQueryComponent(code.trim())}',
        )
        as Map<String, dynamic>,
  );

  Future<ReturnResult> returnProduct({
    required int saleId,
    required int saleItemId,
    required int quantity,
    required String reason,
  }) async => ReturnResult.fromJson(
    await _http.post('/returns', {
          'saleId': saleId,
          'saleItemId': saleItemId,
          'quantity': quantity,
          'reason': reason.trim(),
        })
        as Map<String, dynamic>,
  );

  /// The user's own store, including its tax rate and maximum discount.
  Future<StoreInfo> storeSettings() async =>
      StoreInfo.fromJson(await _http.get('/store') as Map<String, dynamic>);
}

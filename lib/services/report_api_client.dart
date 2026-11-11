import '../models/report.dart';
import 'api_http.dart';

/// Reports & Sales Analytics API (Store Admin only; the backend always uses
/// the logged-in user's own store):
///
/// | Method       | HTTP                                         |
/// |--------------|----------------------------------------------|
/// | summary      | GET /api/reports/summary?from=&to=           |
/// | dailySales   | GET /api/reports/daily-sales?from=&to=       |
/// | monthlySales | GET /api/reports/monthly-sales?year=         |
/// | topProducts  | GET /api/reports/top-products?from=&to=&limit= |
/// | inventory    | GET /api/reports/inventory                   |
class ReportApiClient {
  ReportApiClient(this._http);

  final ApiHttp _http;

  Future<SalesSummary> summary(ReportPeriod p) async => SalesSummary.fromJson(
    await _http.get('/reports/summary?${p.query}') as Map<String, dynamic>,
  );

  Future<List<SalesPoint>> dailySales(ReportPeriod p) async => [
    for (final d
        in await _http.get('/reports/daily-sales?${p.query}') as List<dynamic>)
      SalesPoint.day(d as Map<String, dynamic>),
  ];

  Future<List<SalesPoint>> monthlySales(int year) async {
    final j =
        await _http.get('/reports/monthly-sales?year=$year')
            as Map<String, dynamic>;
    return [
      for (final m in j['months'] as List<dynamic>)
        SalesPoint.month(m as Map<String, dynamic>),
    ];
  }

  Future<TopProducts> topProducts(ReportPeriod p, {int limit = 7}) async =>
      TopProducts.fromJson(
        await _http.get('/reports/top-products?${p.query}&limit=$limit')
            as Map<String, dynamic>,
      );

  Future<InventoryReport> inventory() async => InventoryReport.fromJson(
    await _http.get('/reports/inventory') as Map<String, dynamic>,
  );
}

import '../models/product_package.dart';
import 'api_http.dart';

/// Cartons / boxes (feature 6) → backend. GET /api/packages (store users);
/// POST /api/products/{id}/packages and PATCH /api/packages/{id} (Store Admin).
class PackageApiClient {
  PackageApiClient(this._http);

  final ApiHttp _http;

  Future<List<ProductPackage>> list() async {
    final body = await _http.get('/packages') as List;
    return [for (final p in body) ProductPackage.fromJson(p as Map<String, dynamic>)];
  }

  Future<ProductPackage> create(
    String productId, {
    required String name,
    required int unitsPerPackage,
    required String price,
    String? barcode,
  }) async => ProductPackage.fromJson(
    await _http.post('/products/$productId/packages', {
          'name': name,
          'unitsPerPackage': unitsPerPackage,
          'price': num.parse(price),
          if (barcode != null && barcode.isNotEmpty) 'barcode': barcode,
        })
        as Map<String, dynamic>,
  );

  Future<ProductPackage> update(
    int packageId, {
    String? name,
    String? price,
    String? barcode,
    bool? active,
  }) async => ProductPackage.fromJson(
    await _http.patch('/packages/$packageId', {
          'name': ?name,
          if (price != null) 'price': num.parse(price),
          'barcode': ?barcode,
          'active': ?active,
        })
        as Map<String, dynamic>,
  );
}

import '../models/product.dart';
import 'api_http.dart';

export 'api_http.dart' show ApiException;

/// Talks to the backend Product API (as the logged-in Store Admin or Cashier;
/// the backend only returns products of that user's store):
///
/// | Method            | HTTP                                  | Database |
/// |-------------------|---------------------------------------|----------|
/// | fetchProducts     | GET    /api/products                  | SELECT   |
/// | createProduct     | POST   /api/products                  | INSERT   |
/// | updateProduct     | PUT    /api/products/{id}             | UPDATE   |
/// | deleteProduct     | DELETE /api/products/{id}             | DELETE   |
/// | generateBarcode   | GET    /api/products/generate-barcode | –        |
class ProductApiClient {
  ProductApiClient(this._http);

  final ApiHttp _http;

  String get baseUrl => _http.baseUrl;

  Future<List<Product>> fetchProducts() async {
    final list = await _http.get('/products') as List<dynamic>;
    return list
        .map((e) => Product.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  /// Inserts [product] (its id is ignored) and returns the saved product.
  Future<Product> createProduct(Product product) async => Product.fromJson(
    await _http.post('/products', product.toRequestJson())
        as Map<String, dynamic>,
  );

  /// Updates the product with [Product.id] and returns the saved product.
  Future<Product> updateProduct(Product product) async => Product.fromJson(
    await _http.put('/products/${product.id}', product.toRequestJson())
        as Map<String, dynamic>,
  );

  Future<void> deleteProduct(String id) => _http.delete('/products/$id');

  /// Asks the backend's Product business logic for a new, unused barcode.
  Future<String> generateBarcode() async {
    final body =
        await _http.get('/products/generate-barcode') as Map<String, dynamic>;
    return body['barcode'] as String;
  }
}

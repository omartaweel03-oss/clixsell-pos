import 'api_http.dart';

/// POS favorites (feature 5) → backend: GET /api/favorites (store users),
/// PUT /api/favorites {productIds} (Store Admin, the complete new list in order).
class FavoriteApiClient {
  FavoriteApiClient(this._http);

  final ApiHttp _http;

  static const int maxFavorites = 40;

  /// Product ids in the order the Store Admin chose.
  Future<List<String>> list() async {
    final body = await _http.get('/favorites') as List;
    return [for (final f in body) (f as Map<String, dynamic>)['productId'].toString()];
  }

  Future<List<String>> save(List<String> productIds) async {
    final body =
        await _http.put('/favorites', {
              'productIds': [for (final id in productIds) int.parse(id)],
            })
            as List;
    return [for (final f in body) (f as Map<String, dynamic>)['productId'].toString()];
  }
}

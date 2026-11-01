import 'dart:convert';

import 'package:clixsell_pos/services/product_api_client.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

/// An in-memory imitation of the Spring Boot Product API, used by the
/// Flutter tests. It answers the same URLs with the same JSON shapes and
/// error codes (404, 409) as the real backend.
class FakeProductBackend {
  static const String baseUrl = 'http://fake-server/api';

  /// "Database rows", in ProductResponse JSON format.
  final List<Map<String, dynamic>> rows = [];

  /// Every request received, e.g. "POST /api/products".
  final List<String> requests = [];

  /// When true, every request fails as if the server were not running.
  bool offline = false;

  int _nextId = 1;

  late final http.Client client = MockClient(_handle);

  ProductApiClient apiClient() =>
      ProductApiClient(httpClient: client, baseUrl: baseUrl);

  /// Adds a row directly, as if it were already in the database.
  Map<String, dynamic> seed({
    required String name,
    required String barcode,
    String category = 'dairy',
    required double price,
    required int quantity,
    required int minStock,
    String supplier = 'Dairy Co',
  }) {
    final row = _row(_nextId++, {
      'name': name,
      'barcode': barcode,
      'category': category,
      'sellingPrice': price,
      'quantity': quantity,
      'minStock': minStock,
      'supplierName': supplier,
    }, createdAt: '2025-01-01T10:00:00');
    rows.add(row);
    return row;
  }

  Map<String, dynamic>? rowById(int id) {
    for (final r in rows) {
      if (r['productId'] == id) return r;
    }
    return null;
  }

  Future<http.Response> _handle(http.Request request) async {
    if (offline) throw http.ClientException('Connection refused');
    final path = request.url.path; // e.g. /api/products/3
    requests.add('${request.method} $path');

    if (path == '/api/products' && request.method == 'GET') {
      final sorted = [...rows]
        ..sort((a, b) => (b['productId'] as int).compareTo(a['productId'] as int));
      return _json(200, sorted);
    }
    if (path == '/api/products/generate-barcode') {
      return _json(200, {'barcode': '7290000000017'});
    }
    if (path == '/api/products' && request.method == 'POST') {
      final body = jsonDecode(request.body) as Map<String, dynamic>;
      if (_barcodeTaken(body['barcode'] as String, null)) {
        return _duplicate(body['barcode'] as String);
      }
      final row = _row(_nextId++, body);
      rows.add(row);
      return _json(201, row);
    }

    final match = RegExp(r'^/api/products/(\d+)$').firstMatch(path);
    if (match != null) {
      final id = int.parse(match.group(1)!);
      final existing = rowById(id);
      if (existing == null) {
        return _json(404, {
          'status': 404,
          'code': 'PRODUCT_NOT_FOUND',
          'message': 'Product $id was not found',
          'fieldErrors': {},
        });
      }
      if (request.method == 'DELETE') {
        rows.remove(existing);
        return http.Response('', 204);
      }
      if (request.method == 'PUT') {
        final body = jsonDecode(request.body) as Map<String, dynamic>;
        if (_barcodeTaken(body['barcode'] as String, id)) {
          return _duplicate(body['barcode'] as String);
        }
        final row = _row(id, body, createdAt: existing['createdAt'] as String);
        rows[rows.indexOf(existing)] = row;
        return _json(200, row);
      }
    }
    return _json(400, {
      'status': 400,
      'code': 'BAD_REQUEST',
      'message': 'Unexpected request',
      'fieldErrors': {},
    });
  }

  bool _barcodeTaken(String barcode, int? exceptId) =>
      rows.any((r) => r['barcode'] == barcode && r['productId'] != exceptId);

  static http.Response _duplicate(String barcode) => _json(409, {
        'status': 409,
        'code': 'DUPLICATE_BARCODE',
        'message': 'Another product already uses barcode $barcode',
        'fieldErrors': {'barcode': 'Another product already uses barcode $barcode'},
      });

  /// Builds a ProductResponse row, including the backend's business-logic
  /// results (stockStatus, stockValue).
  static Map<String, dynamic> _row(int id, Map<String, dynamic> body,
      {String createdAt = '2025-06-01T12:00:00'}) {
    final quantity = (body['quantity'] as num).toInt();
    final minStock = (body['minStock'] as num).toInt();
    final price = (body['sellingPrice'] as num).toDouble();
    final status = quantity <= 0
        ? 'OUT_OF_STOCK'
        : quantity <= minStock
            ? 'LOW_STOCK'
            : 'IN_STOCK';
    return {
      'productId': id,
      'name': body['name'],
      'barcode': body['barcode'],
      'category': body['category'],
      'sellingPrice': price,
      'quantity': quantity,
      'minStock': minStock,
      'supplierId': 1,
      'supplierName': body['supplierName'],
      'stockStatus': status,
      'stockValue': price * quantity,
      'image': body['image'],
      'imageName': body['imageName'],
      'createdAt': createdAt,
      'updatedAt': '2025-06-01T12:00:00',
    };
  }

  static http.Response _json(int status, Object body) => http.Response(
        jsonEncode(body),
        status,
        headers: {'content-type': 'application/json; charset=utf-8'},
      );
}

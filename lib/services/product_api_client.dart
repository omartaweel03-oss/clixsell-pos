import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../config/api_config.dart';
import '../models/product.dart';

/// An API call that failed.
///
/// * [isNetworkError]: the server could not be reached (not running, offline, timeout).
/// * Otherwise [statusCode] / [code] come from the backend's JSON error, e.g.
///   409 `DUPLICATE_BARCODE`, 404 `PRODUCT_NOT_FOUND`, 400 `VALIDATION_FAILED`.
class ApiException implements Exception {
  const ApiException({
    required this.message,
    this.statusCode,
    this.code,
    this.fieldErrors = const {},
  });

  const ApiException.network(this.message)
      : statusCode = null,
        code = 'NETWORK_ERROR',
        fieldErrors = const {};

  final int? statusCode;
  final String? code;
  final String message;

  /// Backend field name → error message (e.g. `barcode` → "...").
  final Map<String, String> fieldErrors;

  bool get isNetworkError => statusCode == null;
  bool get isNotFound => statusCode == 404;
  bool get isDuplicateBarcode => code == 'DUPLICATE_BARCODE';

  @override
  String toString() => 'ApiException($statusCode, $code, $message)';
}

/// Talks to the backend Product API:
///
/// | Method            | HTTP                                | Database |
/// |-------------------|-------------------------------------|----------|
/// | fetchProducts     | GET    /api/products                | SELECT   |
/// | createProduct     | POST   /api/products                | INSERT   |
/// | updateProduct     | PUT    /api/products/{id}           | UPDATE   |
/// | deleteProduct     | DELETE /api/products/{id}           | DELETE   |
/// | generateBarcode   | GET    /api/products/generate-barcode | –      |
class ProductApiClient {
  ProductApiClient({http.Client? httpClient, String? baseUrl})
      : _http = httpClient ?? http.Client(),
        baseUrl = baseUrl ?? ApiConfig.baseUrl;

  final http.Client _http;
  final String baseUrl;

  static const _jsonHeaders = {'Content-Type': 'application/json'};

  Uri _uri(String path) => Uri.parse('$baseUrl$path');

  Future<List<Product>> fetchProducts() async {
    final response = await _send(() => _http.get(_uri('/products')));
    final list = jsonDecode(utf8.decode(response.bodyBytes)) as List<dynamic>;
    return list
        .map((e) => Product.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  /// Inserts [product] (its id is ignored) and returns the saved product.
  Future<Product> createProduct(Product product) async {
    final response = await _send(() => _http.post(
          _uri('/products'),
          headers: _jsonHeaders,
          body: jsonEncode(product.toRequestJson()),
        ));
    return _decodeProduct(response);
  }

  /// Updates the product with [Product.id] and returns the saved product.
  Future<Product> updateProduct(Product product) async {
    final response = await _send(() => _http.put(
          _uri('/products/${product.id}'),
          headers: _jsonHeaders,
          body: jsonEncode(product.toRequestJson()),
        ));
    return _decodeProduct(response);
  }

  Future<void> deleteProduct(String id) async {
    await _send(() => _http.delete(_uri('/products/$id')));
  }

  /// Asks the backend's Product business logic for a new, unused barcode.
  Future<String> generateBarcode() async {
    final response =
        await _send(() => _http.get(_uri('/products/generate-barcode')));
    final body = jsonDecode(utf8.decode(response.bodyBytes)) as Map<String, dynamic>;
    return body['barcode'] as String;
  }

  Product _decodeProduct(http.Response response) => Product.fromJson(
      jsonDecode(utf8.decode(response.bodyBytes)) as Map<String, dynamic>);

  /// Runs a request, turning network failures and error responses into
  /// [ApiException].
  Future<http.Response> _send(Future<http.Response> Function() request) async {
    final http.Response response;
    try {
      response = await request().timeout(ApiConfig.timeout);
    } on TimeoutException {
      throw const ApiException.network('The server did not respond in time');
    } catch (e) {
      // http.ClientException (and browser XMLHttpRequest errors) end up here.
      throw ApiException.network('Could not reach the server: $e');
    }

    if (response.statusCode >= 200 && response.statusCode < 300) {
      return response;
    }
    throw _errorFrom(response);
  }

  static ApiException _errorFrom(http.Response response) {
    try {
      final body = jsonDecode(utf8.decode(response.bodyBytes)) as Map<String, dynamic>;
      final fields = (body['fieldErrors'] as Map<String, dynamic>?) ?? const {};
      return ApiException(
        statusCode: response.statusCode,
        code: body['code'] as String?,
        message: (body['message'] as String?) ?? 'Request failed',
        fieldErrors: fields.map((k, v) => MapEntry(k, v.toString())),
      );
    } catch (_) {
      return ApiException(
        statusCode: response.statusCode,
        message: 'Request failed (HTTP ${response.statusCode})',
      );
    }
  }
}

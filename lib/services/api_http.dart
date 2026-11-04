import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../config/api_config.dart';

/// An API call that failed.
///
/// * [isNetworkError]: the server could not be reached (not running, offline, timeout).
/// * Otherwise [statusCode] / [code] come from the backend's JSON error, e.g.
///   401 `INVALID_CREDENTIALS`, 403 `FORBIDDEN`, 404 `PRODUCT_NOT_FOUND`,
///   409 `DUPLICATE_BARCODE`, 429 `ACCOUNT_LOCKED`.
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
  bool get isUnauthorized => statusCode == 401;
  bool get isForbidden => statusCode == 403;
  bool get isNotFound => statusCode == 404;
  bool get isDuplicateBarcode => code == 'DUPLICATE_BARCODE';

  @override
  String toString() => 'ApiException($statusCode, $code, $message)';
}

/// Sends JSON requests to the ClixSell backend.
///
/// After login, [token] is set and sent as `Authorization: Bearer <token>`
/// with every request. If the server answers 401 to a logged-in request
/// (session expired, user or store deactivated), [onUnauthorized] is called
/// so the app can return to the Login screen.
class ApiHttp {
  ApiHttp({http.Client? client, String? baseUrl})
    : client = client ?? http.Client(),
      baseUrl = baseUrl ?? ApiConfig.baseUrl;

  final http.Client client;
  final String baseUrl;

  /// Login token of the current user, or null when logged out.
  String? token;

  /// Called when a logged-in request is refused with 401.
  void Function()? onUnauthorized;

  Map<String, String> get _headers => {
    'Content-Type': 'application/json',
    if (token != null) 'Authorization': 'Bearer $token',
  };

  Uri _uri(String path) => Uri.parse('$baseUrl$path');

  Future<dynamic> get(String path) =>
      _send(() => client.get(_uri(path), headers: _headers));

  Future<dynamic> post(String path, [Object? body]) => _send(
    () => client.post(
      _uri(path),
      headers: _headers,
      body: body == null ? null : jsonEncode(body),
    ),
  );

  Future<dynamic> put(String path, Object? body) => _send(
    () => client.put(_uri(path), headers: _headers, body: jsonEncode(body)),
  );

  Future<dynamic> delete(String path) =>
      _send(() => client.delete(_uri(path), headers: _headers));

  /// Runs a request and returns the decoded JSON body (null when empty).
  /// Network failures and error responses become [ApiException].
  Future<dynamic> _send(Future<http.Response> Function() request) async {
    final sentWithToken = token != null;
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
      if (response.bodyBytes.isEmpty) return null;
      return jsonDecode(utf8.decode(response.bodyBytes));
    }
    final error = _errorFrom(response);
    if (error.isUnauthorized && sentWithToken) {
      onUnauthorized?.call();
    }
    throw error;
  }

  static ApiException _errorFrom(http.Response response) {
    try {
      final body =
          jsonDecode(utf8.decode(response.bodyBytes)) as Map<String, dynamic>;
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

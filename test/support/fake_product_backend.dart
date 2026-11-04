import 'dart:convert';

import 'package:clixsell_pos/services/api_http.dart';
import 'package:clixsell_pos/services/product_api_client.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

/// An in-memory imitation of the ClixSell Spring Boot API, used by the
/// Flutter tests. It answers the same URLs with the same JSON shapes and
/// error codes as the real backend, including login tokens, the role rules
/// (401 / 403) and per-store data isolation.
///
/// Starts with:
/// * `sysadmin` / `Admin-Pass-1` (System Admin)
/// * store 1 "Shini Supermarket" with `shini.admin` / `Admin-Pass-1` and
///   `shini.cashier` / `Cashier-Pass-1`
class FakeProductBackend {
  FakeProductBackend() {
    addUser('sysadmin', 'Admin-Pass-1', 'SYSTEM_ADMIN', null, fullName: 'System Administrator');
    final shini = addStore('Shini Supermarket', address: 'Ramallah');
    addUser('shini.admin', 'Admin-Pass-1', 'STORE_ADMIN', shini, fullName: 'Shini Manager');
    addUser('shini.cashier', 'Cashier-Pass-1', 'CASHIER', shini, fullName: 'Ahmad Saleh');
  }

  static const String baseUrl = 'http://fake-server/api';

  /// "Database rows", in ProductResponse JSON format (with storeId).
  final List<Map<String, dynamic>> rows = [];
  final List<Map<String, dynamic>> stores = [];
  final List<Map<String, dynamic>> users = [];
  final Map<String, String> _passwords = {}; // username → password
  final Map<String, int> _sessions = {}; // token → userId

  /// Every request received, e.g. "POST /api/products".
  final List<String> requests = [];

  /// When true, every request fails as if the server were not running.
  bool offline = false;

  /// When true, only the product list fails (as if that call broke).
  bool productsUnavailable = false;

  int _nextProductId = 1;
  int _nextStoreId = 1;
  int _nextUserId = 1;
  int _nextToken = 1;

  late final http.Client client = MockClient(_handle);

  // ── Seeding helpers ────────────────────────────────────────────────────

  int addStore(String name, {String? address, bool active = true}) {
    final id = _nextStoreId++;
    stores.add({
      'storeId': id,
      'name': name,
      'address': address,
      'phone': null,
      'status': active ? 'ACTIVE' : 'INACTIVE',
      'createdAt': '2026-11-01T09:00:00',
    });
    return id;
  }

  int addUser(String username, String password, String role, int? storeId,
      {String fullName = 'Test User', bool active = true}) {
    final id = _nextUserId++;
    users.add({
      'userId': id,
      'username': username,
      'fullName': fullName,
      'role': role,
      'storeId': storeId,
      'status': active ? 'ACTIVE' : 'INACTIVE',
      'createdAt': '2026-11-01T09:00:00',
    });
    _passwords[username] = password;
    return id;
  }

  /// Adds a product row directly, as if it were already in the database.
  Map<String, dynamic> seed({
    required String name,
    required String barcode,
    String category = 'dairy',
    required double price,
    required int quantity,
    required int minStock,
    String supplier = 'Dairy Co',
    int storeId = 1,
  }) {
    final row = _productRow(_nextProductId++, storeId, {
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

  /// Ends every login, e.g. to simulate an expired session.
  void endAllSessions() => _sessions.clear();

  /// A logged-in [ProductApiClient] for [username] (no UI needed).
  ProductApiClient apiClient({String as = 'shini.admin'}) {
    final token = 'token-${_nextToken++}';
    _sessions[token] = userByName(as)!['userId'] as int;
    final api = ApiHttp(client: client, baseUrl: baseUrl)..token = token;
    return ProductApiClient(api);
  }

  ApiHttp newHttp() => ApiHttp(client: client, baseUrl: baseUrl);

  Map<String, dynamic>? rowById(int id) => _find(rows, 'productId', id);
  Map<String, dynamic>? userByName(String username) {
    for (final u in users) {
      if (u['username'] == username) return u;
    }
    return null;
  }

  static Map<String, dynamic>? _find(List<Map<String, dynamic>> list, String key, int id) {
    for (final r in list) {
      if (r[key] == id) return r;
    }
    return null;
  }

  // ── Request handling ───────────────────────────────────────────────────

  Future<http.Response> _handle(http.Request request) async {
    if (offline) throw http.ClientException('Connection refused');
    final path = request.url.path; // e.g. /api/products/3
    final method = request.method;
    requests.add('$method $path');
    Map<String, dynamic> body() =>
        request.body.isEmpty ? {} : jsonDecode(request.body) as Map<String, dynamic>;

    if (path == '/api/auth/login' && method == 'POST') return _login(body());

    final user = _currentUser(request);
    if (user == null) return _error(401, 'UNAUTHORIZED', 'Please log in');
    final role = user['role'] as String;
    final storeId = user['storeId'] as int?;

    if (path == '/api/auth/logout') {
      _sessions.remove(_token(request));
      return http.Response('', 204);
    }

    // System Admin area
    if (path.startsWith('/api/admin/')) {
      if (role != 'SYSTEM_ADMIN') return _forbidden();
      return _admin(path, method, body);
    }
    // Store Admin: cashiers of own store
    if (path.startsWith('/api/store/cashiers')) {
      if (role != 'STORE_ADMIN') return _forbidden();
      return _staff(storeId!, 'CASHIER', path.substring('/api/store/cashiers'.length), method, body);
    }
    // Products: Store Admin (all) and Cashier (read only), own store
    if (path.startsWith('/api/products')) {
      if (role == 'SYSTEM_ADMIN') return _forbidden();
      if (role == 'CASHIER' && (method != 'GET' || path.endsWith('generate-barcode'))) {
        return _forbidden();
      }
      return _products(storeId!, path, method, body);
    }
    return _error(400, 'BAD_REQUEST', 'Unexpected request');
  }

  String? _token(http.Request r) {
    final h = r.headers['Authorization'] ?? r.headers['authorization'];
    return h != null && h.startsWith('Bearer ') ? h.substring(7) : null;
  }

  Map<String, dynamic>? _currentUser(http.Request r) {
    final token = _token(r);
    final userId = token == null ? null : _sessions[token];
    if (userId == null) return null;
    final user = _find(users, 'userId', userId);
    if (user == null || user['status'] != 'ACTIVE') return null;
    final storeId = user['storeId'] as int?;
    if (storeId != null && _find(stores, 'storeId', storeId)?['status'] != 'ACTIVE') {
      return null;
    }
    return user;
  }

  http.Response _login(Map<String, dynamic> body) {
    final username = (body['username'] as String? ?? '').trim().toLowerCase();
    final user = userByName(username);
    if (user == null || _passwords[username] != body['password']) {
      return _error(401, 'INVALID_CREDENTIALS', 'Invalid username or password');
    }
    final storeId = user['storeId'] as int?;
    if (user['status'] != 'ACTIVE' ||
        (storeId != null && _find(stores, 'storeId', storeId)?['status'] != 'ACTIVE')) {
      return _error(401, 'ACCOUNT_DISABLED', 'This account or its store has been deactivated');
    }
    final token = 'token-${_nextToken++}';
    _sessions[token] = user['userId'] as int;
    return _json(200, {
      'token': token,
      'expiresAt': '2026-11-02T09:00:00',
      'user': _userJson(user),
    });
  }

  Map<String, dynamic> _userJson(Map<String, dynamic> u) {
    final storeId = u['storeId'] as int?;
    return {
      ...u,
      'storeName': storeId == null ? null : _find(stores, 'storeId', storeId)?['name'],
    };
  }

  Map<String, dynamic> _storeJson(Map<String, dynamic> st) => {
        ...st,
        'adminCount': users
            .where((u) => u['storeId'] == st['storeId'] && u['role'] == 'STORE_ADMIN')
            .length,
        'cashierCount': users
            .where((u) => u['storeId'] == st['storeId'] && u['role'] == 'CASHIER')
            .length,
      };

  http.Response _admin(String path, String method, Map<String, dynamic> Function() body) {
    if (path == '/api/admin/stores' && method == 'GET') {
      return _json(200, stores.map(_storeJson).toList());
    }
    if (path == '/api/admin/stores' && method == 'POST') {
      final b = body();
      final name = (b['name'] as String? ?? '').trim();
      if (stores.any((st) => (st['name'] as String).toLowerCase() == name.toLowerCase())) {
        return _error(409, 'DUPLICATE_STORE_NAME', 'A store with this name already exists',
            {'name': 'A store with this name already exists'});
      }
      final id = addStore(name, address: b['address'] as String?);
      _find(stores, 'storeId', id)!['phone'] = b['phone'];
      return _json(201, _storeJson(_find(stores, 'storeId', id)!));
    }
    final store = RegExp(r'^/api/admin/stores/(\d+)(/.*)?$').firstMatch(path);
    if (store != null) {
      final id = int.parse(store.group(1)!);
      final st = _find(stores, 'storeId', id);
      if (st == null) return _error(404, 'STORE_NOT_FOUND', 'Store $id was not found');
      final rest = store.group(2) ?? '';
      if (rest == '' && method == 'PUT') {
        final b = body();
        st['name'] = b['name'];
        st['address'] = b['address'];
        st['phone'] = b['phone'];
        return _json(200, _storeJson(st));
      }
      if (rest == '/status' && method == 'PUT') {
        st['status'] = body()['status'];
        return _json(200, _storeJson(st));
      }
      if (rest.startsWith('/admins')) {
        return _staff(id, 'STORE_ADMIN', rest.substring('/admins'.length), method, body);
      }
    }
    return _error(400, 'BAD_REQUEST', 'Unexpected request');
  }

  http.Response _staff(int storeId, String role, String rest, String method,
      Map<String, dynamic> Function() body) {
    if (rest == '' && method == 'GET') {
      return _json(200, [
        for (final u in users)
          if (u['storeId'] == storeId && u['role'] == role) _userJson(u)
      ]);
    }
    if (rest == '' && method == 'POST') {
      final b = body();
      final username = (b['username'] as String? ?? '').trim().toLowerCase();
      if (userByName(username) != null) {
        return _error(409, 'DUPLICATE_USERNAME', 'This username is already taken',
            {'username': 'This username is already taken'});
      }
      final id = addUser(username, b['password'] as String, role, storeId,
          fullName: (b['fullName'] as String).trim());
      return _json(201, _userJson(_find(users, 'userId', id)!));
    }
    final m = RegExp(r'^/(\d+)/(status|password)$').firstMatch(rest);
    if (m != null && method == 'PUT') {
      final id = int.parse(m.group(1)!);
      final u = _find(users, 'userId', id);
      if (u == null || u['storeId'] != storeId || u['role'] != role) {
        return _error(404, 'USER_NOT_FOUND', 'User $id was not found');
      }
      if (m.group(2) == 'status') {
        u['status'] = body()['status'];
        if (u['status'] == 'INACTIVE') _sessions.removeWhere((_, v) => v == id);
      } else {
        _passwords[u['username'] as String] = body()['password'] as String;
        _sessions.removeWhere((_, v) => v == id);
      }
      return _json(200, _userJson(u));
    }
    return _error(400, 'BAD_REQUEST', 'Unexpected request');
  }

  http.Response _products(int storeId, String path, String method,
      Map<String, dynamic> Function() body) {
    final mine = rows.where((r) => r['storeId'] == storeId).toList();
    if (path == '/api/products' && method == 'GET') {
      if (productsUnavailable) throw http.ClientException('Connection reset');
      mine.sort((a, b) => (b['productId'] as int).compareTo(a['productId'] as int));
      return _json(200, mine);
    }
    if (path == '/api/products/generate-barcode') {
      return _json(200, {'barcode': '7290000000017'});
    }
    if (path == '/api/products' && method == 'POST') {
      final b = body();
      if (_barcodeTaken(mine, b['barcode'] as String, null)) return _duplicate(b['barcode'] as String);
      final row = _productRow(_nextProductId++, storeId, b);
      rows.add(row);
      return _json(201, row);
    }
    final match = RegExp(r'^/api/products/(\d+)$').firstMatch(path);
    if (match != null) {
      final id = int.parse(match.group(1)!);
      final existing = _find(mine, 'productId', id); // other stores' products: not found
      if (existing == null) {
        return _error(404, 'PRODUCT_NOT_FOUND', 'Product $id was not found');
      }
      if (method == 'DELETE') {
        rows.remove(existing);
        return http.Response('', 204);
      }
      if (method == 'PUT') {
        final b = body();
        if (_barcodeTaken(mine, b['barcode'] as String, id)) return _duplicate(b['barcode'] as String);
        final row = _productRow(id, storeId, b, createdAt: existing['createdAt'] as String);
        rows[rows.indexOf(existing)] = row;
        return _json(200, row);
      }
    }
    return _error(400, 'BAD_REQUEST', 'Unexpected request');
  }

  bool _barcodeTaken(List<Map<String, dynamic>> mine, String barcode, int? exceptId) =>
      mine.any((r) => r['barcode'] == barcode && r['productId'] != exceptId);

  static http.Response _duplicate(String barcode) => _error(
        409,
        'DUPLICATE_BARCODE',
        'Another product in this store already uses barcode $barcode',
        {'barcode': 'Another product in this store already uses barcode $barcode'},
      );

  static http.Response _forbidden() =>
      _error(403, 'FORBIDDEN', 'You are not allowed to do this');

  /// Builds a ProductResponse row, including the backend's business-logic
  /// results (stockStatus, stockValue).
  static Map<String, dynamic> _productRow(int id, int storeId, Map<String, dynamic> body,
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
      'storeId': storeId,
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

  static http.Response _error(int status, String code, String message,
          [Map<String, String> fieldErrors = const {}]) =>
      _json(status, {
        'status': status,
        'code': code,
        'message': message,
        'fieldErrors': fieldErrors,
      });

  static http.Response _json(int status, Object body) => http.Response(
        jsonEncode(body),
        status,
        headers: {'content-type': 'application/json; charset=utf-8'},
      );
}

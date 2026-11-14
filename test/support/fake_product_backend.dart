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
    addUser(
      'sysadmin',
      'Admin-Pass-1',
      'SYSTEM_ADMIN',
      null,
      fullName: 'System Administrator',
    );
    final shini = addStore('Shini Supermarket', address: 'Ramallah');
    addUser(
      'shini.admin',
      'Admin-Pass-1',
      'STORE_ADMIN',
      shini,
      fullName: 'Shini Manager',
    );
    addUser(
      'shini.cashier',
      'Cashier-Pass-1',
      'CASHIER',
      shini,
      fullName: 'Ahmad Saleh',
    );
  }

  static const String baseUrl = 'http://fake-server/api';

  /// "Database rows", in ProductResponse JSON format (with storeId).
  final List<Map<String, dynamic>> rows = [];
  final List<Map<String, dynamic>> stores = [];
  final List<Map<String, dynamic>> users = [];

  /// Sales ("sales" + their lines), in SaleResponse JSON format.
  final List<Map<String, dynamic>> sales = [];
  final List<Map<String, dynamic>> returns = [];

  /// saleItemId → purchase cost copied when the sale was completed (server side only).
  final Map<int, double?> unitCosts = {};
  int _nextSaleId = 1;
  int _nextSaleItemId = 1;
  final Map<int, int> _invoiceCounter = {}; // storeId → next number
  final Map<String, String> _passwords = {}; // username → password
  final Map<String, int> _sessions = {}; // token → userId

  /// Every request received, e.g. "POST /api/products".
  final List<String> requests = [];

  /// Date/time given to completed sales and returns (change it to build a history).
  String saleTime = '2026-11-04T10:05:00';

  /// When true the reports API answers 500 (to test the error state).
  bool failReports = false;

  /// Delay of the reports API answers (to see the loading state).
  Duration? reportsDelay;

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

  int addUser(
    String username,
    String password,
    String role,
    int? storeId, {
    String fullName = 'Test User',
    bool active = true,
  }) {
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
    double? cost,
  }) {
    final row = _productRow(_nextProductId++, storeId, {
      'name': name,
      'barcode': barcode,
      'category': category,
      'sellingPrice': price,
      'quantity': quantity,
      'minStock': minStock,
      'supplierName': supplier,
      'costPrice': cost,
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

  static Map<String, dynamic>? _find(
    List<Map<String, dynamic>> list,
    String key,
    int id,
  ) {
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
    Map<String, dynamic> body() => request.body.isEmpty
        ? {}
        : jsonDecode(request.body) as Map<String, dynamic>;

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
      return _staff(
        storeId!,
        'CASHIER',
        path.substring('/api/store/cashiers'.length),
        method,
        body,
      );
    }
    // Own store settings
    if (path == '/api/store' && method == 'GET') {
      if (role == 'SYSTEM_ADMIN') return _forbidden();
      return _json(200, _storeJson(_find(stores, 'storeId', storeId!)!));
    }
    // POS: sales and returns (Store Admin and Cashier, own store)
    if (path.startsWith('/api/sales') || path.startsWith('/api/returns')) {
      if (role == 'SYSTEM_ADMIN') return _forbidden();
      return _pos(user, storeId!, path, method, body, request);
    }
    // Reports & Sales Analytics: Store Admin only, own store
    if (path.startsWith('/api/reports')) {
      if (role != 'STORE_ADMIN') return _forbidden();
      if (reportsDelay != null) await Future<void>.delayed(reportsDelay!);
      if (failReports) {
        return _error(500, 'SERVER_ERROR', 'Report server error');
      }
      return _reports(storeId!, path, request.url.queryParameters);
    }
    // Products: Store Admin (all) and Cashier (read only), own store
    if (path.startsWith('/api/products')) {
      if (role == 'SYSTEM_ADMIN') return _forbidden();
      if (role == 'CASHIER' &&
          (method != 'GET' || path.endsWith('generate-barcode'))) {
        return _forbidden();
      }
      final response = _products(storeId!, path, method, body);
      if (role != 'CASHIER') return response;
      // Cashiers never receive purchase costs (same as the backend).
      final data = jsonDecode(response.body);
      Object strip(Object? row) => {
        ...(row as Map<String, dynamic>),
        'costPrice': null,
      };
      return _json(
        response.statusCode,
        data is List ? data.map(strip).toList() : strip(data),
      );
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
    if (storeId != null &&
        _find(stores, 'storeId', storeId)?['status'] != 'ACTIVE') {
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
        (storeId != null &&
            _find(stores, 'storeId', storeId)?['status'] != 'ACTIVE')) {
      return _error(
        401,
        'ACCOUNT_DISABLED',
        'This account or its store has been deactivated',
      );
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
      'storeName': storeId == null
          ? null
          : _find(stores, 'storeId', storeId)?['name'],
    };
  }

  Map<String, dynamic> _storeJson(Map<String, dynamic> st) => {
    'taxRate': 16.0,
    'maxDiscountPercent': 10.0,
    ...st,
    'adminCount': users
        .where(
          (u) => u['storeId'] == st['storeId'] && u['role'] == 'STORE_ADMIN',
        )
        .length,
    'cashierCount': users
        .where((u) => u['storeId'] == st['storeId'] && u['role'] == 'CASHIER')
        .length,
  };

  http.Response _admin(
    String path,
    String method,
    Map<String, dynamic> Function() body,
  ) {
    if (path == '/api/admin/stores' && method == 'GET') {
      return _json(200, stores.map(_storeJson).toList());
    }
    if (path == '/api/admin/stores' && method == 'POST') {
      final b = body();
      final name = (b['name'] as String? ?? '').trim();
      if (stores.any(
        (st) => (st['name'] as String).toLowerCase() == name.toLowerCase(),
      )) {
        return _error(
          409,
          'DUPLICATE_STORE_NAME',
          'A store with this name already exists',
          {'name': 'A store with this name already exists'},
        );
      }
      final id = addStore(name, address: b['address'] as String?);
      _find(stores, 'storeId', id)!['phone'] = b['phone'];
      return _json(201, _storeJson(_find(stores, 'storeId', id)!));
    }
    final store = RegExp(r'^/api/admin/stores/(\d+)(/.*)?$').firstMatch(path);
    if (store != null) {
      final id = int.parse(store.group(1)!);
      final st = _find(stores, 'storeId', id);
      if (st == null) {
        return _error(404, 'STORE_NOT_FOUND', 'Store $id was not found');
      }
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
        return _staff(
          id,
          'STORE_ADMIN',
          rest.substring('/admins'.length),
          method,
          body,
        );
      }
    }
    return _error(400, 'BAD_REQUEST', 'Unexpected request');
  }

  http.Response _staff(
    int storeId,
    String role,
    String rest,
    String method,
    Map<String, dynamic> Function() body,
  ) {
    if (rest == '' && method == 'GET') {
      return _json(200, [
        for (final u in users)
          if (u['storeId'] == storeId && u['role'] == role) _userJson(u),
      ]);
    }
    if (rest == '' && method == 'POST') {
      final b = body();
      final username = (b['username'] as String? ?? '').trim().toLowerCase();
      if (userByName(username) != null) {
        return _error(
          409,
          'DUPLICATE_USERNAME',
          'This username is already taken',
          {'username': 'This username is already taken'},
        );
      }
      final id = addUser(
        username,
        b['password'] as String,
        role,
        storeId,
        fullName: (b['fullName'] as String).trim(),
      );
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

  http.Response _products(
    int storeId,
    String path,
    String method,
    Map<String, dynamic> Function() body,
  ) {
    final mine = rows.where((r) => r['storeId'] == storeId).toList();
    if (path == '/api/products' && method == 'GET') {
      if (productsUnavailable) throw http.ClientException('Connection reset');
      mine.sort(
        (a, b) => (b['productId'] as int).compareTo(a['productId'] as int),
      );
      return _json(200, mine);
    }
    if (path == '/api/products/generate-barcode') {
      return _json(200, {'barcode': '7290000000017'});
    }
    if (path == '/api/products' && method == 'POST') {
      final b = body();
      if (_barcodeTaken(mine, b['barcode'] as String, null)) {
        return _duplicate(b['barcode'] as String);
      }
      final row = _productRow(_nextProductId++, storeId, b);
      rows.add(row);
      return _json(201, row);
    }
    final match = RegExp(r'^/api/products/(\d+)$').firstMatch(path);
    if (match != null) {
      final id = int.parse(match.group(1)!);
      final existing = _find(
        mine,
        'productId',
        id,
      ); // other stores' products: not found
      if (existing == null) {
        return _error(404, 'PRODUCT_NOT_FOUND', 'Product $id was not found');
      }
      if (method == 'DELETE') {
        rows.remove(existing);
        return http.Response('', 204);
      }
      if (method == 'PUT') {
        final b = body();
        if (_barcodeTaken(mine, b['barcode'] as String, id)) {
          return _duplicate(b['barcode'] as String);
        }
        final row = _productRow(
          id,
          storeId,
          b,
          createdAt: existing['createdAt'] as String,
        );
        rows[rows.indexOf(existing)] = row;
        return _json(200, row);
      }
    }
    return _error(400, 'BAD_REQUEST', 'Unexpected request');
  }

  // ── POS (mirrors SaleService / ReturnService of the backend) ─────────────

  double _money(num v) => (v * 100).round() / 100;

  double _taxRate(int storeId) =>
      ((_find(stores, 'storeId', storeId)?['taxRate']) as num?)?.toDouble() ??
      16.0;

  double _maxDiscount(int storeId) =>
      ((_find(stores, 'storeId', storeId)?['maxDiscountPercent']) as num?)
          ?.toDouble() ??
      10.0;

  Future<http.Response> _pos(
    Map<String, dynamic> user,
    int storeId,
    String path,
    String method,
    Map<String, dynamic> Function() body,
    http.Request request,
  ) async {
    final mine = sales.where((x) => x['storeId'] == storeId).toList();
    if (path == '/api/sales/suspended' && method == 'GET') {
      return _json(
        200,
        mine
            .where((x) => x['status'] == 'SUSPENDED')
            .toList()
            .reversed
            .toList(),
      );
    }
    if (path == '/api/sales/lookup') {
      final code = (request.url.queryParameters['code'] ?? '').trim();
      for (final x in mine) {
        if (x['status'] == 'COMPLETED' &&
            ((x['invoiceNumber'] as String).toUpperCase() ==
                    code.toUpperCase() ||
                x['receiptCode'] == code)) {
          return _json(200, x);
        }
      }
      return _error(
        404,
        'SALE_NOT_FOUND',
        "No completed invoice or receipt '$code' in this store",
      );
    }
    if (path == '/api/returns' && method == 'POST') {
      return _return(user, storeId, body());
    }

    final suspendedId = RegExp(
      r'^/api/sales/suspended/(\d+)$',
    ).firstMatch(path);
    if (suspendedId != null && method == 'DELETE') {
      final sale = _find(mine, 'saleId', int.parse(suspendedId.group(1)!));
      if (sale == null) {
        return _error(404, 'SALE_NOT_FOUND', 'Invoice was not found');
      }
      if (sale['status'] != 'SUSPENDED') {
        return _error(
          409,
          'SALE_NOT_SUSPENDED',
          'Only a suspended invoice can be canceled',
        );
      }
      sale['status'] = 'CANCELED';
      return _json(200, sale);
    }
    final getId = RegExp(r'^/api/sales/(\d+)$').firstMatch(path);
    if (getId != null && method == 'GET') {
      final sale = _find(mine, 'saleId', int.parse(getId.group(1)!));
      return sale == null
          ? _error(404, 'SALE_NOT_FOUND', 'Invoice was not found')
          : _json(200, sale);
    }
    final complete = path == '/api/sales' && method == 'POST';
    final suspendNew = path == '/api/sales/suspended' && method == 'POST';
    if (complete || suspendNew || (suspendedId != null && method == 'PUT')) {
      final b = body();
      final saleId = suspendedId != null
          ? int.parse(suspendedId.group(1)!)
          : b['saleId'] as int?;
      return _saveSale(
        user,
        storeId,
        b,
        saleId: suspendNew ? null : saleId,
        complete: complete,
      );
    }
    return _error(400, 'BAD_REQUEST', 'Unexpected request');
  }

  http.Response _saveSale(
    Map<String, dynamic> user,
    int storeId,
    Map<String, dynamic> b, {
    required int? saleId,
    required bool complete,
  }) {
    final lines = (b['items'] as List<dynamic>? ?? const []);
    if (lines.isEmpty) {
      return _error(400, 'VALIDATION_FAILED', 'Some fields are not valid', {
        'items': 'Add at least one product to the invoice',
      });
    }
    Map<String, dynamic>? existing;
    if (saleId != null) {
      existing = _find(
        sales.where((x) => x['storeId'] == storeId).toList(),
        'saleId',
        saleId,
      );
      if (existing == null) {
        return _error(404, 'SALE_NOT_FOUND', 'Invoice was not found');
      }
      if (existing['status'] != 'SUSPENDED') {
        return _error(
          409,
          'SALE_NOT_SUSPENDED',
          'Invoice is no longer suspended',
        );
      }
    }
    final items = <Map<String, dynamic>>[];
    for (final l in lines) {
      final m = l as Map<String, dynamic>;
      final product = rows
          .where(
            (r) => r['productId'] == m['productId'] && r['storeId'] == storeId,
          )
          .firstOrNull;
      if (product == null) {
        return _error(
          404,
          'PRODUCT_NOT_FOUND',
          'Product ${m['productId']} is not available in this store',
        );
      }
      final qty = m['quantity'] as int;
      final price = (product['sellingPrice'] as num).toDouble();
      items.add({
        'saleItemId': _nextSaleItemId++,
        'productId': product['productId'],
        'productName': product['name'],
        'barcode': product['barcode'],
        'quantity': qty,
        'unitPrice': price,
        'lineTotal': _money(price * qty),
        'returnedQuantity': 0,
        'returnableQuantity': qty,
      });
    }
    final subtotal = _money(
      items.fold<double>(0, (a, i) => a + (i['lineTotal'] as double)),
    );
    final dv = (b['discountValue'] as num?)?.toDouble() ?? 0;
    final discount = _money(
      b['discountType'] == 'PERCENT' ? subtotal * dv / 100 : dv,
    );
    if (discount > _money(subtotal * _maxDiscount(storeId) / 100)) {
      return _error(400, 'VALIDATION_FAILED', 'Some fields are not valid', {
        'discountValue': 'Discount cannot be more than 10% of the subtotal',
      });
    }
    final rate = _taxRate(storeId);
    final tax = _money((subtotal - discount) * rate / 100);
    if (complete) {
      if (b['paymentMethod'] == null) {
        return _error(400, 'VALIDATION_FAILED', 'Some fields are not valid', {
          'paymentMethod': 'Choose a payment method',
        });
      }
      final shortages = <String, String>{};
      for (final i in items) {
        final p = rowById(i['productId'] as int)!;
        if ((p['quantity'] as int) < (i['quantity'] as int)) {
          shortages[i['productName'] as String] =
              'Only ${p['quantity']} left in stock';
        }
      }
      if (shortages.isNotEmpty) {
        return _error(409, 'INSUFFICIENT_STOCK', 'Not enough stock', shortages);
      }
      for (final i in items) {
        final p = rowById(i['productId'] as int)!;
        _setQuantity(p, (p['quantity'] as int) - (i['quantity'] as int));
      }
    }
    if (complete) {
      for (final i in items) {
        unitCosts[i['saleItemId'] as int] =
            (rowById(i['productId'] as int)?['costPrice'] as num?)?.toDouble();
      }
    }
    final number =
        existing?['invoiceNumber'] as String? ?? _nextInvoice(storeId);
    final sale = {
      'saleId': existing?['saleId'] ?? _nextSaleId++,
      'storeId': storeId,
      'invoiceNumber': number,
      'status': complete ? 'COMPLETED' : 'SUSPENDED',
      'cashierName': user['fullName'],
      'items': items,
      'itemCount': items.length,
      'subtotal': subtotal,
      'discount': discount,
      'taxRate': rate,
      'tax': tax,
      'total': _money(subtotal - discount + tax),
      'paymentMethod': complete ? b['paymentMethod'] : null,
      'receiptCode': complete
          ? 'CLIXSELL-TEST${_nextSaleId.toString().padLeft(4, '0')}'
          : null,
      'createdAt': existing?['createdAt'] ?? saleTime,
      'completedAt': complete ? saleTime : null,
    };
    if (existing != null) {
      sales[sales.indexOf(existing)] = sale;
    } else {
      sales.add(sale);
    }
    return _json(complete || existing == null ? 201 : 200, sale);
  }

  String _nextInvoice(int storeId) {
    final n = _invoiceCounter[storeId] ?? 1;
    _invoiceCounter[storeId] = n + 1;
    return 'INV-${n.toString().padLeft(6, '0')}';
  }

  void _setQuantity(Map<String, dynamic> row, int quantity) {
    final min = row['minStock'] as int;
    row['quantity'] = quantity;
    row['stockStatus'] = quantity <= 0
        ? 'OUT_OF_STOCK'
        : (quantity <= min ? 'LOW_STOCK' : 'IN_STOCK');
    row['stockValue'] = (row['sellingPrice'] as double) * quantity;
  }

  http.Response _return(
    Map<String, dynamic> user,
    int storeId,
    Map<String, dynamic> b,
  ) {
    final sale = _find(
      sales.where((x) => x['storeId'] == storeId).toList(),
      'saleId',
      b['saleId'] as int,
    );
    if (sale == null) {
      return _error(404, 'SALE_NOT_FOUND', 'Invoice was not found');
    }
    final item = (sale['items'] as List)
        .cast<Map<String, dynamic>>()
        .where((i) => i['saleItemId'] == b['saleItemId'])
        .firstOrNull;
    if (item == null) {
      return _error(404, 'SALE_ITEM_NOT_FOUND', 'Not on the invoice');
    }
    final qty = b['quantity'] as int;
    if (qty > (item['returnableQuantity'] as int)) {
      return _error(400, 'VALIDATION_FAILED', 'Some fields are not valid', {
        'quantity':
            'Only ${item['returnableQuantity']} of this product can still be returned',
      });
    }
    item['returnedQuantity'] = (item['returnedQuantity'] as int) + qty;
    item['returnableQuantity'] = (item['returnableQuantity'] as int) - qty;
    final p = rowById(item['productId'] as int);
    if (p != null) _setQuantity(p, (p['quantity'] as int) + qty);
    final refund = _money(
      (sale['total'] as double) *
          (item['lineTotal'] as double) /
          (sale['subtotal'] as double) *
          qty /
          (item['quantity'] as int),
    );
    final r = {
      'returnId': returns.length + 1,
      'invoiceNumber': sale['invoiceNumber'],
      'productName': item['productName'],
      'quantity': qty,
      'reason': b['reason'],
      'refundAmount': refund,
      'refundMethod': sale['paymentMethod'],
      'returnDate': saleTime,
      'storeId': storeId,
      'saleId': sale['saleId'],
      'saleItemId': item['saleItemId'],
      'stillReturnable': item['returnableQuantity'],
    };
    returns.add(r);
    return _json(201, r);
  }

  // ── Reports (same rules as the backend ReportService) ──────────────────

  http.Response _reports(int storeId, String path, Map<String, String> q) {
    double r2(num v) => (v * 100).round() / 100;
    List<Map<String, dynamic>> done(String from, String toExcl) => sales
        .where((x) => x['storeId'] == storeId && x['status'] == 'COMPLETED')
        .where(
          (x) =>
              (x['completedAt'] as String).compareTo(from) >= 0 &&
              (x['completedAt'] as String).compareTo(toExcl) < 0,
        )
        .toList();
    List<Map<String, dynamic>> rets(String from, String toExcl) => returns
        .where((x) => x['storeId'] == storeId)
        .where(
          (x) =>
              (x['returnDate'] as String).compareTo(from) >= 0 &&
              (x['returnDate'] as String).compareTo(toExcl) < 0,
        )
        .toList();
    String iso(DateTime d) =>
        '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
    double sum(List<Map<String, dynamic>> l, String k) =>
        l.fold<double>(0, (a, x) => a + (x[k] as num).toDouble());

    if (path == '/api/reports/inventory') {
      final mine = rows.where((r) => r['storeId'] == storeId).toList();
      final cats = <String, List<Map<String, dynamic>>>{};
      for (final r in mine) {
        cats.putIfAbsent(r['category'] as String, () => []).add(r);
      }
      final reorder = mine.where((r) => r['stockStatus'] != 'IN_STOCK').toList()
        ..sort((a, b) {
          final ao = a['stockStatus'] == 'OUT_OF_STOCK' ? 0 : 1;
          final bo = b['stockStatus'] == 'OUT_OF_STOCK' ? 0 : 1;
          return ao != bo
              ? ao - bo
              : (a['quantity'] as int) - (b['quantity'] as int);
        });
      return _json(200, {
        'totalProducts': mine.length,
        'totalUnits': mine.fold<int>(0, (a, r) => a + (r['quantity'] as int)),
        'inventoryValue': r2(sum(mine, 'stockValue')),
        'inStock': mine.where((r) => r['stockStatus'] == 'IN_STOCK').length,
        'lowStock': mine.where((r) => r['stockStatus'] == 'LOW_STOCK').length,
        'outOfStock': mine
            .where((r) => r['stockStatus'] == 'OUT_OF_STOCK')
            .length,
        'categories': [
          for (final e
              in (cats.entries.toList()
                ..sort((a, b) => a.key.compareTo(b.key))))
            {
              'category': e.key,
              'products': e.value.length,
              'units': e.value.fold<int>(
                0,
                (a, r) => a + (r['quantity'] as int),
              ),
              'value': r2(sum(e.value, 'stockValue')),
            },
        ],
        'reorder': [
          for (final r in reorder)
            {
              'productId': r['productId'],
              'name': r['name'],
              'barcode': r['barcode'],
              'category': r['category'],
              'quantity': r['quantity'],
              'minStock': r['minStock'],
              'stockStatus': r['stockStatus'],
              'supplierName': r['supplierName'],
            },
        ],
      });
    }

    if (path == '/api/reports/monthly-sales') {
      final year = int.tryParse(q['year'] ?? '');
      if (year == null) {
        return _error(400, 'VALIDATION_FAILED', 'Year is required');
      }
      final months = [
        for (var m = 1; m <= 12; m++)
          () {
            final f = '$year-${m.toString().padLeft(2, '0')}-01T00:00:00';
            final t = iso(DateTime(year, m + 1, 1));
            final sl = done(f, '${t}T00:00:00');
            final rl = rets(f, '${t}T00:00:00');
            return {
              'month': m,
              'grossSales': r2(sum(sl, 'total')),
              'refunds': r2(sum(rl, 'refundAmount')),
              'netSales': r2(sum(sl, 'total') - sum(rl, 'refundAmount')),
              'invoices': sl.length,
            };
          }(),
      ];
      return _json(200, {
        'year': year,
        'months': months,
        'totalNetSales': r2(
          months.fold<double>(0, (a, m) => a + (m['netSales'] as double)),
        ),
      });
    }

    final fromD = DateTime.tryParse(q['from'] ?? '');
    final toD = DateTime.tryParse(q['to'] ?? '');
    if (fromD == null || toD == null || toD.isBefore(fromD)) {
      return _error(400, 'VALIDATION_FAILED', 'Invalid period');
    }
    final from = '${iso(fromD)}T00:00:00';
    final toExcl = '${iso(toD.add(const Duration(days: 1)))}T00:00:00';

    if (path == '/api/reports/daily-sales') {
      return _json(200, [
        for (
          var d = fromD;
          !d.isAfter(toD);
          d = DateTime(d.year, d.month, d.day + 1)
        )
          () {
            final f = '${iso(d)}T00:00:00';
            final t = '${iso(DateTime(d.year, d.month, d.day + 1))}T00:00:00';
            final sl = done(f, t);
            final rl = rets(f, t);
            return {
              'date': iso(d),
              'grossSales': r2(sum(sl, 'total')),
              'refunds': r2(sum(rl, 'refundAmount')),
              'netSales': r2(sum(sl, 'total') - sum(rl, 'refundAmount')),
              'invoices': sl.length,
            };
          }(),
      ]);
    }

    if (path == '/api/reports/top-products') {
      final limit = int.tryParse(q['limit'] ?? '7') ?? 7;
      final byProduct = <String, Map<String, dynamic>>{};
      void add(Map<String, dynamic> i, int units, double value) {
        final key = '${i['productId'] ?? 'name:${i['productName']}'}';
        final e = byProduct.putIfAbsent(
          key,
          () => {
            'productId': i['productId'],
            'name': i['productName'],
            'units': 0,
            'value': 0.0,
          },
        );
        e['units'] = (e['units'] as int) + units;
        e['value'] = (e['value'] as double) + value;
      }

      for (final sale in done(from, toExcl)) {
        for (final i in (sale['items'] as List).cast<Map<String, dynamic>>()) {
          add(
            i,
            i['quantity'] as int,
            (sale['total'] as double) *
                (i['lineTotal'] as double) /
                (sale['subtotal'] as double),
          );
        }
      }
      for (final r in rets(from, toExcl)) {
        final i = _itemOfReturn(r);
        add(i, -(r['quantity'] as int), -(r['refundAmount'] as num).toDouble());
      }
      final rowsAll = byProduct.values.toList();
      final total = rowsAll.fold<double>(
        0,
        (a, e) => a + (e['value'] as double),
      );
      final units = rowsAll.fold<int>(0, (a, e) => a + (e['units'] as int));
      final all =
          rowsAll
              .where(
                (e) => (e['units'] as int) > 0 && (e['value'] as double) > 0,
              )
              .toList()
            ..sort(
              (a, b) => (b['value'] as double).compareTo(a['value'] as double),
            );
      final top = all.take(limit).toList();
      final topValue = top.fold<double>(
        0,
        (a, e) => a + (e['value'] as double),
      );
      return _json(200, {
        'from': iso(fromD),
        'to': iso(toD),
        'totalSalesValue': r2(total),
        'totalUnits': units,
        'products': [
          for (final e in top)
            {
              'productId': e['productId'],
              'name': e['name'],
              'units': e['units'],
              'salesValue': r2(e['value'] as double),
              'share': total <= 0
                  ? 0.0
                  : r2((e['value'] as double) * 100 / total),
            },
        ],
        'othersValue': r2(total - topValue),
        'othersUnits':
            units - top.fold<int>(0, (a, e) => a + (e['units'] as int)),
      });
    }

    if (path == '/api/reports/summary') {
      final sl = done(from, toExcl);
      final rl = rets(from, toExcl);
      final monthly =
          fromD.day == 1 &&
          toD.day == DateTime(toD.year, toD.month + 1, 0).day &&
          fromD.month == toD.month &&
          fromD.year == toD.year;
      final yearly =
          fromD.month == 1 &&
          fromD.day == 1 &&
          toD.month == 12 &&
          toD.day == 31 &&
          fromD.year == toD.year;
      final pf = yearly
          ? DateTime(fromD.year - 1, 1, 1)
          : monthly
          ? DateTime(fromD.year, fromD.month - 1, 1)
          : fromD.subtract(Duration(days: toD.difference(fromD).inDays + 1));
      final pt = yearly
          ? DateTime(fromD.year - 1, 12, 31)
          : monthly
          ? DateTime(fromD.year, fromD.month, 0)
          : fromD.subtract(const Duration(days: 1));
      final pfs = '${iso(pf)}T00:00:00';
      final pts = '${iso(pt.add(const Duration(days: 1)))}T00:00:00';
      final psl = done(pfs, pts);
      final prl = rets(pfs, pts);
      final gross = sum(sl, 'total');
      return _json(200, {
        'from': iso(fromD),
        'to': iso(toD),
        'grossSales': r2(gross),
        'discounts': r2(sum(sl, 'discount')),
        'tax': r2(sum(sl, 'tax')),
        'refunds': r2(sum(rl, 'refundAmount')),
        'netSales': r2(gross - sum(rl, 'refundAmount')),
        'invoiceCount': sl.length,
        'returnCount': rl.length,
        'unitsSold': sl.fold<int>(
          0,
          (a, x) =>
              a +
              (x['items'] as List).fold<int>(
                0,
                (b, i) => b + ((i as Map)['quantity'] as int),
              ),
        ),
        'unitsReturned': rl.fold<int>(0, (a, x) => a + (x['quantity'] as int)),
        'netUnitsSold':
            _units(sl) - rl.fold<int>(0, (a, x) => a + (x['quantity'] as int)),
        'averageInvoice': sl.isEmpty ? 0.0 : r2(gross / sl.length),
        'previous': {
          'from': iso(pf),
          'to': iso(pt),
          'netSales': r2(sum(psl, 'total') - sum(prl, 'refundAmount')),
          'invoiceCount': psl.length,
          'netUnitsSold':
              _units(psl) -
              prl.fold<int>(0, (a, x) => a + (x['quantity'] as int)),
          'profit':
              _profit(sl, rl)['complete'] == true &&
                  _profit(psl, prl)['complete'] == true
              ? _profit(psl, prl)['amount']
              : null,
        },
        'profit': _profit(sl, rl),
      });
    }
    return _error(404, 'NOT_FOUND', 'Unknown report');
  }

  int _units(List<Map<String, dynamic>> sales) => sales.fold<int>(
    0,
    (a, x) =>
        a +
        (x['items'] as List).fold<int>(
          0,
          (b, i) => b + ((i as Map)['quantity'] as int),
        ),
  );

  Map<String, dynamic> _itemOfReturn(Map<String, dynamic> r) {
    final sale = sales.firstWhere((x) => x['saleId'] == r['saleId']);
    return (sale['items'] as List).cast<Map<String, dynamic>>().firstWhere(
      (i) => i['saleItemId'] == r['saleItemId'],
    );
  }

  /// Same rule as the backend: revenue without tax after the invoice discount
  /// − the cost saved at sale time; returns subtracted on their date.
  Map<String, dynamic> _profit(
    List<Map<String, dynamic>> sl,
    List<Map<String, dynamic>> rl,
  ) {
    double r2(num v) => (v * 100).round() / 100;
    var lines = 0, missing = 0;
    var revenue = 0.0, covered = 0.0, cost = 0.0;
    void line(
      Map<String, dynamic> sale,
      Map<String, dynamic> i,
      int units,
      int sign,
    ) {
      lines++;
      final rev =
          (i['lineTotal'] as double) *
          ((sale['subtotal'] as double) - (sale['discount'] as double)) /
          (sale['subtotal'] as double) *
          units /
          (i['quantity'] as int);
      revenue += sign * rev;
      final c = unitCosts[i['saleItemId']];
      if (c == null) {
        missing++;
      } else {
        covered += sign * rev;
        cost += sign * c * units;
      }
    }

    for (final sale in sl) {
      for (final i in (sale['items'] as List).cast<Map<String, dynamic>>()) {
        line(sale, i, i['quantity'] as int, 1);
      }
    }
    for (final r in rl) {
      line(
        sales.firstWhere((x) => x['saleId'] == r['saleId']),
        _itemOfReturn(r),
        r['quantity'] as int,
        -1,
      );
    }
    if (missing == 0) {
      return {
        'status': 'COMPLETE',
        'available': true,
        'complete': true,
        'amount': r2(covered - cost),
        'revenueExTax': r2(revenue),
        'coveredRevenueExTax': r2(covered),
        'costOfGoods': r2(cost),
        'coveragePercent': 100.0,
        'linesWithoutCost': 0,
        'reason': null,
      };
    }
    if (missing == lines) {
      return {
        'status': 'NOT_AVAILABLE',
        'available': false,
        'complete': false,
        'amount': null,
        'revenueExTax': r2(revenue),
        'coveredRevenueExTax': 0.0,
        'costOfGoods': 0.0,
        'coveragePercent': 0.0,
        'linesWithoutCost': missing,
        'reason': 'NO_COST_PRICE',
      };
    }
    return {
      'status': 'PARTIAL',
      'available': true,
      'complete': false,
      'amount': r2(covered - cost),
      'revenueExTax': r2(revenue),
      'coveredRevenueExTax': r2(covered),
      'costOfGoods': r2(cost),
      'coveragePercent': revenue > 0
          ? r2((covered * 100 / revenue).clamp(0, 100))
          : null,
      'linesWithoutCost': missing,
      'reason': 'MISSING_COST',
    };
  }

  bool _barcodeTaken(
    List<Map<String, dynamic>> mine,
    String barcode,
    int? exceptId,
  ) => mine.any((r) => r['barcode'] == barcode && r['productId'] != exceptId);

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
  static Map<String, dynamic> _productRow(
    int id,
    int storeId,
    Map<String, dynamic> body, {
    String createdAt = '2025-06-01T12:00:00',
  }) {
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
      'costPrice': (body['costPrice'] as num?)?.toDouble(),
      'createdAt': createdAt,
      'updatedAt': '2025-06-01T12:00:00',
    };
  }

  static http.Response _error(
    int status,
    String code,
    String message, [
    Map<String, String> fieldErrors = const {},
  ]) => _json(status, {
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

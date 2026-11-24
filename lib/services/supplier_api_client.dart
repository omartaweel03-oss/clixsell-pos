import '../models/supplier.dart';
import 'api_http.dart';

/// Supplier Management (Store Admin): /api/suppliers.
///
/// The store is decided by the backend from the login; no store id is sent.
/// There is no delete: suppliers are deactivated and activated.
abstract class SupplierApi {
  Future<List<Supplier>> list();

  Future<Supplier> get(int supplierId);

  Future<Supplier> create({
    required String name,
    String? phone,
    String? whatsappNumber,
    String? address,
  });

  Future<Supplier> update(
    int supplierId, {
    required String name,
    String? phone,
    String? whatsappNumber,
    String? address,
  });

  Future<Supplier> setActive(int supplierId, bool active);
}

class SupplierApiClient implements SupplierApi {
  SupplierApiClient(this._http);

  final ApiHttp _http;

  static Map<String, dynamic> _body(
    String name,
    String? phone,
    String? whatsappNumber,
    String? address,
  ) => {
    // The name is sent as typed: the backend keeps an unchanged legacy name
    // exactly as stored and normalizes a changed one.
    'name': name,
    'phone': phone,
    'whatsappNumber': whatsappNumber,
    'address': address,
  };

  @override
  Future<List<Supplier>> list() async =>
      (await _http.get('/suppliers') as List<dynamic>)
          .map((e) => Supplier.fromJson(e as Map<String, dynamic>))
          .toList();

  @override
  Future<Supplier> get(int supplierId) async => Supplier.fromJson(
    await _http.get('/suppliers/$supplierId') as Map<String, dynamic>,
  );

  @override
  Future<Supplier> create({
    required String name,
    String? phone,
    String? whatsappNumber,
    String? address,
  }) async => Supplier.fromJson(
    await _http.post('/suppliers', _body(name, phone, whatsappNumber, address))
        as Map<String, dynamic>,
  );

  @override
  Future<Supplier> update(
    int supplierId, {
    required String name,
    String? phone,
    String? whatsappNumber,
    String? address,
  }) async => Supplier.fromJson(
    await _http.put(
          '/suppliers/$supplierId',
          _body(name, phone, whatsappNumber, address),
        )
        as Map<String, dynamic>,
  );

  @override
  Future<Supplier> setActive(int supplierId, bool active) async =>
      Supplier.fromJson(
        await _http.put('/suppliers/$supplierId/status', {'active': active})
            as Map<String, dynamic>,
      );
}

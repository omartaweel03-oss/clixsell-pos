import 'package:clixsell_pos/models/app_user.dart';
import 'package:clixsell_pos/services/admin_api_clients.dart';
import 'package:clixsell_pos/services/api_http.dart';
import 'package:clixsell_pos/state/auth_session.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_product_backend.dart';

void main() {
  late FakeProductBackend backend;
  late AuthSession session;

  setUp(() {
    backend = FakeProductBackend();
    session = AuthSession(http: backend.newHttp());
  });

  group('AuthSession', () {
    test('login stores the token and the user (role + store)', () async {
      await session.login('shini.cashier', 'Cashier-Pass-1');
      expect(session.isLoggedIn, isTrue);
      expect(session.http.token, isNotNull);
      expect(session.user!.role, UserRole.cashier);
      expect(session.user!.storeName, 'Shini Supermarket');
    });

    test('wrong password is refused and nothing is stored', () async {
      await expectLater(
        session.login('shini.cashier', 'wrong'),
        throwsA(isA<ApiException>()
            .having((e) => e.code, 'code', 'INVALID_CREDENTIALS')),
      );
      expect(session.isLoggedIn, isFalse);
      expect(session.http.token, isNull);
    });

    test('logout ends the session on the server and locally', () async {
      await session.login('shini.admin', 'Admin-Pass-1');
      await session.logout();
      expect(session.isLoggedIn, isFalse);
      expect(session.http.token, isNull);
      expect(backend.requests.last, 'POST /api/auth/logout');
    });

    test('a 401 on a logged-in request logs the user out (session ended)', () async {
      await session.login('shini.admin', 'Admin-Pass-1');
      backend.endAllSessions();
      await expectLater(cashiersApi(session.http).list(), throwsA(isA<ApiException>()));
      expect(session.isLoggedIn, isFalse);
      expect(session.sessionExpired, isTrue);
    });
  });

  group('Store isolation through the API client', () {
    test('each store admin sees only their own store\'s products', () async {
      final neshan = backend.addStore('Neshan Supermarket');
      backend.addUser('neshan.admin', 'Admin-Pass-1', 'STORE_ADMIN', neshan);
      backend.seed(name: 'Shini Milk', barcode: '11111111', price: 5, quantity: 1, minStock: 0);
      final neshanMilk = backend.seed(
          name: 'Neshan Milk', barcode: '11111111', price: 6, quantity: 1, minStock: 0, storeId: neshan);

      final shiniApi = backend.apiClient(as: 'shini.admin');
      final neshanApi = backend.apiClient(as: 'neshan.admin');
      expect((await shiniApi.fetchProducts()).map((p) => p.name), ['Shini Milk']);
      expect((await neshanApi.fetchProducts()).map((p) => p.name), ['Neshan Milk']);

      // Shini's admin cannot delete Neshan's product.
      await expectLater(
        shiniApi.deleteProduct(neshanMilk['productId'].toString()),
        throwsA(isA<ApiException>().having((e) => e.isNotFound, 'isNotFound', true)),
      );
      expect(backend.rowById(neshanMilk['productId'] as int), isNotNull);
    });
  });

  group('Staff and store API clients', () {
    test('store admin creates, deactivates and resets a cashier', () async {
      await session.login('shini.admin', 'Admin-Pass-1');
      final api = cashiersApi(session.http);

      final created = await api.create(
          username: 'Shini.Sara', fullName: 'Sara Odeh', password: 'Sara-Pass-12');
      expect(created.username, 'shini.sara');
      expect(created.storeName, 'Shini Supermarket');
      expect((await api.list()).map((u) => u.username), ['shini.cashier', 'shini.sara']);

      final off = await api.setActive(created.userId, false);
      expect(off.isActive, isFalse);
      await api.resetPassword(created.userId, 'Another-Pass-1');
    });

    test('duplicate username is reported as DUPLICATE_USERNAME', () async {
      await session.login('shini.admin', 'Admin-Pass-1');
      await expectLater(
        cashiersApi(session.http)
            .create(username: 'sysadmin', fullName: 'X Y', password: 'Some-Pass-1'),
        throwsA(isA<ApiException>().having((e) => e.code, 'code', 'DUPLICATE_USERNAME')),
      );
    });

    test('a cashier is refused (403) on staff and store APIs', () async {
      await session.login('shini.cashier', 'Cashier-Pass-1');
      await expectLater(cashiersApi(session.http).list(),
          throwsA(isA<ApiException>().having((e) => e.isForbidden, 'isForbidden', true)));
      await expectLater(StoresApiClient(session.http).listStores(),
          throwsA(isA<ApiException>().having((e) => e.isForbidden, 'isForbidden', true)));
      expect(session.isLoggedIn, isTrue); // 403 does not log out
    });

    test('system admin creates a store and its store admin', () async {
      await session.login('sysadmin', 'Admin-Pass-1');
      final stores = StoresApiClient(session.http);
      final neshan = await stores.createStore(name: 'Neshan Supermarket', address: 'Nablus');
      expect(neshan.isActive, isTrue);

      final admin = await storeAdminsApi(session.http, neshan.storeId)
          .create(username: 'neshan.admin', fullName: 'Neshan Manager', password: 'Neshan-Pass-1');
      expect(admin.role, UserRole.storeAdmin);
      expect(admin.storeId, neshan.storeId);

      final listed = await stores.listStores();
      expect(listed.firstWhere((s) => s.storeId == neshan.storeId).adminCount, 1);
    });
  });
}

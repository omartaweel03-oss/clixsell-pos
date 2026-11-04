import 'package:clixsell_pos/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_product_backend.dart';

/// Server data used by most tests (store 1 "Shini", ids 1, 2, 3; newest first):
/// Milk (in stock, ₪120), Bread (low, ₪6), Eggs (out, ₪0) → value ₪126.
FakeProductBackend _backend() => FakeProductBackend()
  ..seed(name: 'Milk', barcode: '11111111', price: 5, quantity: 24, minStock: 10)
  ..seed(name: 'Bread', barcode: '22222222', price: 2, quantity: 3, minStock: 10)
  ..seed(name: 'Eggs', barcode: '33333333', price: 15, quantity: 0, minStock: 5);

Future<void> _pumpApp(WidgetTester tester, FakeProductBackend backend,
    {Size size = const Size(1440, 1000), Locale? locale}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ClixSellApp(http: backend.newHttp(), initialLocale: locale));
  await tester.pumpAndSettle();
}

Future<void> _login(WidgetTester tester, String username, String password) async {
  await tester.enterText(find.byKey(const Key('loginUsername')), username);
  await tester.enterText(find.byKey(const Key('loginPassword')), password);
  await tester.tap(find.byKey(const Key('loginButton')));
  await tester.pumpAndSettle();
}

/// Opens the app and logs in as Shini's Store Admin.
Future<void> _pumpAsStoreAdmin(WidgetTester tester, FakeProductBackend backend,
    {Size size = const Size(1440, 1000), Locale? locale}) async {
  await _pumpApp(tester, backend, size: size, locale: locale);
  await _login(tester, 'shini.admin', 'Admin-Pass-1');
}

String _statValue(WidgetTester tester, String key) {
  final texts = find.descendant(of: find.byKey(Key(key)), matching: find.byType(Text));
  // Order inside StatCard: title, value, subtitle.
  return (tester.widget<Text>(texts.at(1))).data!;
}

void main() {
  // ── Login ────────────────────────────────────────────────────────────────

  testWidgets('starts on the Login screen; wrong password shows an error', (tester) async {
    final backend = _backend();
    await _pumpApp(tester, backend);
    expect(find.byKey(const Key('loginButton')), findsOneWidget);
    expect(find.text('Inventory Management'), findsNothing);

    await _login(tester, 'shini.admin', 'wrong-password');
    expect(find.text('Invalid username or password.'), findsOneWidget);
    expect(find.byKey(const Key('loginButton')), findsOneWidget);
    expect(backend.requests.where((r) => r.contains('/products')), isEmpty);
  });

  testWidgets('empty fields are validated before contacting the server', (tester) async {
    final backend = _backend();
    await _pumpApp(tester, backend);
    await tester.tap(find.byKey(const Key('loginButton')));
    await tester.pumpAndSettle();
    expect(find.text('This field is required'), findsNWidgets(2));
    expect(backend.requests, isEmpty);
  });

  testWidgets('deactivated store: its users get a clear message', (tester) async {
    final backend = _backend();
    backend.stores.first['status'] = 'INACTIVE';
    await _pumpApp(tester, backend);
    await _login(tester, 'shini.cashier', 'Cashier-Pass-1');
    expect(find.textContaining('has been deactivated'), findsOneWidget);
  });

  // ── Store Admin: products (existing behaviour, now behind login) ─────────

  testWidgets('store admin: loads products from the server and shows dynamic stats', (tester) async {
    final backend = _backend();
    await _pumpAsStoreAdmin(tester, backend);

    expect(backend.requests, containsAllInOrder(['POST /api/auth/login', 'GET /api/products']));
    expect(find.text('Inventory Management'), findsWidgets);
    expect(_statValue(tester, 'statTotal'), '3');
    expect(_statValue(tester, 'statLow'), '1');
    expect(_statValue(tester, 'statOut'), '1');
    expect(_statValue(tester, 'statValue'), '₪ 126.00');

    expect(find.text('Milk'), findsOneWidget);
    expect(find.text('22222222'), findsOneWidget);
    expect(find.byKey(const Key('currentUserCard')), findsOneWidget);
    expect(find.text('Shini Manager'), findsOneWidget);
    expect(find.byKey(const Key('nav-cashiers')), findsOneWidget);
    expect(find.byKey(const Key('nav-stores')), findsNothing);
  });

  testWidgets('server down: shows a message, and Try again loads the products', (tester) async {
    final backend = _backend()..productsUnavailable = true;
    await _pumpAsStoreAdmin(tester, backend);

    expect(find.byKey(const Key('serverErrorState')), findsOneWidget);
    expect(find.text("Can't reach the server"), findsOneWidget);

    backend.productsUnavailable = false;
    await tester.tap(find.text('Try again'));
    await tester.pumpAndSettle();
    expect(find.text('Milk'), findsOneWidget);
    expect(_statValue(tester, 'statTotal'), '3');
  });

  testWidgets('add product: validation, POST to the server, list + stats update', (tester) async {
    final backend = _backend();
    await _pumpAsStoreAdmin(tester, backend);

    await tester.tap(find.byKey(const Key('headerAddProductButton')));
    await tester.pumpAndSettle();
    expect(find.text('Add New Product'), findsOneWidget);

    await tester.tap(find.byKey(const Key('saveButton')));
    await tester.pumpAndSettle();
    expect(find.text('This field is required'), findsWidgets);
    expect(backend.requests.where((r) => r == 'POST /api/products'), isEmpty);

    await tester.enterText(find.byKey(const Key('nameField')), 'Orange Juice');
    await tester.enterText(find.byKey(const Key('barcodeField')), '44444444');
    await tester.tap(find.byKey(const Key('categoryField')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Beverages').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('priceField')), '8.5');
    await tester.enterText(find.byKey(const Key('quantityField')), '4');
    await tester.enterText(find.byKey(const Key('minStockField')), '6');
    await tester.enterText(find.byKey(const Key('supplierField')), 'Juice Ltd');
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('saveButton')));
    await tester.tap(find.byKey(const Key('saveButton')));
    await tester.pumpAndSettle();

    expect(backend.requests.last, 'POST /api/products');
    expect(backend.rows.last['name'], 'Orange Juice');
    expect(backend.rows.last['storeId'], 1);
    expect(find.text('Orange Juice'), findsOneWidget);
    expect(_statValue(tester, 'statTotal'), '4');
    expect(_statValue(tester, 'statValue'), '₪ 160.00'); // 126 + 8.5*4
  });

  testWidgets('edit product: pre-filled form, PUT to the server, list and stats update', (tester) async {
    final backend = _backend();
    await _pumpAsStoreAdmin(tester, backend);

    await tester.tap(find.byKey(const Key('edit-2'))); // Bread
    await tester.pumpAndSettle();
    String fieldText(String key) => tester
        .widget<EditableText>(find.descendant(
            of: find.byKey(Key(key)), matching: find.byType(EditableText)))
        .controller
        .text;
    expect(fieldText('nameField'), 'Bread');
    expect(fieldText('quantityField'), '3');

    await tester.enterText(find.byKey(const Key('nameField')), 'White Bread');
    await tester.enterText(find.byKey(const Key('quantityField')), '30');
    await tester.ensureVisible(find.byKey(const Key('saveButton')));
    await tester.tap(find.byKey(const Key('saveButton')));
    await tester.pumpAndSettle();

    expect(backend.requests.last, 'PUT /api/products/2');
    expect(backend.rowById(2)!['name'], 'White Bread');
    expect(find.text('White Bread'), findsOneWidget);
    expect(_statValue(tester, 'statLow'), '0');
  });

  testWidgets('delete product: confirmation, DELETE on the server, undo re-inserts', (tester) async {
    final backend = _backend();
    await _pumpAsStoreAdmin(tester, backend);

    await tester.tap(find.byKey(const Key('delete-3')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(backend.rows.length, 3);

    await tester.tap(find.byKey(const Key('delete-3')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('confirmDeleteButton')));
    await tester.pumpAndSettle();
    expect(backend.requests.last, 'DELETE /api/products/3');
    expect(backend.rowById(3), isNull);
    expect(_statValue(tester, 'statTotal'), '2');

    await tester.tap(find.text('Undo'));
    await tester.pumpAndSettle();
    expect(backend.rows.length, 3);
  });

  testWidgets('add product: server rejects a duplicate barcode, error shown on the field', (tester) async {
    final backend = _backend();
    await _pumpAsStoreAdmin(tester, backend);
    backend.seed(name: 'Other device', barcode: '55555555', price: 1, quantity: 1, minStock: 0);

    await tester.tap(find.byKey(const Key('headerAddProductButton')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('nameField')), 'Water');
    await tester.enterText(find.byKey(const Key('barcodeField')), '55555555');
    await tester.tap(find.byKey(const Key('categoryField')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Beverages').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('priceField')), '1');
    await tester.enterText(find.byKey(const Key('quantityField')), '10');
    await tester.enterText(find.byKey(const Key('minStockField')), '2');
    await tester.enterText(find.byKey(const Key('supplierField')), 'Water Co');
    await tester.ensureVisible(find.byKey(const Key('saveButton')));
    await tester.tap(find.byKey(const Key('saveButton')));
    await tester.pumpAndSettle();

    expect(find.text('Add New Product'), findsOneWidget);
    expect(find.text('Another product already uses this barcode'), findsWidgets);
  });

  // ── Cashier: read only ───────────────────────────────────────────────────

  testWidgets('cashier sees their store\'s products read-only, no admin pages', (tester) async {
    final backend = _backend();
    await _pumpApp(tester, backend);
    await _login(tester, 'shini.cashier', 'Cashier-Pass-1');

    expect(find.text('Milk'), findsOneWidget);
    expect(_statValue(tester, 'statTotal'), '3');
    expect(find.text('View only'), findsOneWidget);
    expect(find.text('Ahmad Saleh'), findsOneWidget);
    expect(find.byKey(const Key('headerAddProductButton')), findsNothing);
    expect(find.byKey(const Key('edit-1')), findsNothing);
    expect(find.byKey(const Key('delete-1')), findsNothing);
    expect(find.byKey(const Key('nav-addProduct')), findsNothing);
    expect(find.byKey(const Key('nav-cashiers')), findsNothing);
    expect(find.byKey(const Key('nav-stores')), findsNothing);
  });

  testWidgets('two stores: after logout the next user sees only their own store', (tester) async {
    final backend = _backend();
    final neshan = backend.addStore('Neshan Supermarket');
    backend.addUser('neshan.cashier', 'Cashier-Pass-1', 'CASHIER', neshan, fullName: 'Omar Khalil');
    backend.seed(name: 'Neshan Juice', barcode: '11111111', price: 8, quantity: 9, minStock: 2, storeId: neshan);

    await _pumpApp(tester, backend);
    await _login(tester, 'shini.cashier', 'Cashier-Pass-1');
    expect(find.text('Milk'), findsOneWidget);
    expect(find.text('Neshan Juice'), findsNothing);

    await tester.tap(find.byKey(const Key('logoutButton')));
    await tester.pumpAndSettle();
    expect(backend.requests, contains('POST /api/auth/logout'));
    expect(find.byKey(const Key('loginButton')), findsOneWidget);

    await _login(tester, 'neshan.cashier', 'Cashier-Pass-1');
    expect(find.text('Neshan Juice'), findsOneWidget);
    expect(find.text('Milk'), findsNothing);
    expect(_statValue(tester, 'statTotal'), '1');
  });

  // ── Store Admin: cashiers ────────────────────────────────────────────────

  testWidgets('store admin adds a cashier, then deactivates it', (tester) async {
    final backend = _backend();
    await _pumpAsStoreAdmin(tester, backend);
    await tester.tap(find.byKey(const Key('nav-cashiers')));
    await tester.pumpAndSettle();
    expect(find.text('shini.cashier'), findsOneWidget);

    await tester.tap(find.byKey(const Key('addAccountButton')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('accountFullName')), 'Sara Odeh');
    await tester.enterText(find.byKey(const Key('accountUsername')), 'shini.cashier');
    await tester.enterText(find.byKey(const Key('accountPassword')), 'short');
    await tester.tap(find.byKey(const Key('accountSaveButton')));
    await tester.pumpAndSettle();
    expect(find.text('Password must be at least 8 characters'), findsOneWidget);

    await tester.enterText(find.byKey(const Key('accountPassword')), 'Sara-Pass-12');
    await tester.tap(find.byKey(const Key('accountSaveButton')));
    await tester.pumpAndSettle();
    expect(find.text('This username is already taken'), findsOneWidget); // from the server

    await tester.enterText(find.byKey(const Key('accountUsername')), 'shini.sara');
    await tester.tap(find.byKey(const Key('accountSaveButton')));
    await tester.pumpAndSettle();
    expect(find.text('shini.sara'), findsOneWidget);
    final sara = backend.userByName('shini.sara')!;
    expect(sara['storeId'], 1);
    expect(sara['role'], 'CASHIER');

    await tester.tap(find.byKey(Key('toggle-${sara['userId']}')));
    await tester.pumpAndSettle();
    expect(find.text('Deactivate account?'), findsOneWidget);
    await tester.tap(find.byKey(const Key('confirmActionButton')));
    await tester.pumpAndSettle();
    expect(sara['status'], 'INACTIVE');
    expect(find.text('Inactive'), findsOneWidget);
  });

  // ── System Admin: stores and store admins ────────────────────────────────

  testWidgets('system admin creates a store and its store admin', (tester) async {
    final backend = _backend();
    await _pumpApp(tester, backend);
    await _login(tester, 'sysadmin', 'Admin-Pass-1');

    expect(find.text('Stores'), findsWidgets);
    expect(find.text('Shini Supermarket'), findsOneWidget);
    expect(find.byKey(const Key('nav-products')), findsNothing);
    expect(backend.requests.where((r) => r.contains('/products')), isEmpty);

    await tester.tap(find.byKey(const Key('addStoreButton')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('storeNameField')), 'Neshan Supermarket');
    await tester.enterText(find.byKey(const Key('storeAddressField')), 'Nablus');
    await tester.tap(find.byKey(const Key('storeSaveButton')));
    await tester.pumpAndSettle();
    expect(find.text('Neshan Supermarket'), findsOneWidget);
    final neshanId = backend.stores.last['storeId'];

    await tester.tap(find.byKey(Key('storeAdmins-$neshanId')));
    await tester.pumpAndSettle();
    expect(find.text('Store Admins – Neshan Supermarket'), findsOneWidget);
    await tester.tap(find.byKey(const Key('addAccountButton')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('accountFullName')), 'Neshan Manager');
    await tester.enterText(find.byKey(const Key('accountUsername')), 'neshan.admin');
    await tester.enterText(find.byKey(const Key('accountPassword')), 'Neshan-Pass-1');
    await tester.tap(find.byKey(const Key('accountSaveButton')));
    await tester.pumpAndSettle();
    expect(find.text('neshan.admin'), findsOneWidget);
    expect(backend.userByName('neshan.admin')!['storeId'], neshanId);
    expect(backend.userByName('neshan.admin')!['role'], 'STORE_ADMIN');

    await tester.tap(find.byKey(const Key('pageBackButton')));
    await tester.pumpAndSettle();
    expect(find.text('1 admin · 0 cashiers'), findsOneWidget);
  });

  testWidgets('system admin deactivates a store after confirmation', (tester) async {
    final backend = _backend();
    await _pumpApp(tester, backend);
    await _login(tester, 'sysadmin', 'Admin-Pass-1');

    await tester.tap(find.byKey(const Key('toggleStore-1')));
    await tester.pumpAndSettle();
    expect(find.text('Deactivate store?'), findsOneWidget);
    await tester.tap(find.byKey(const Key('confirmActionButton')));
    await tester.pumpAndSettle();
    expect(backend.stores.first['status'], 'INACTIVE');
    expect(find.text('Inactive'), findsOneWidget);
  });

  // ── Session ──────────────────────────────────────────────────────────────

  testWidgets('session ended on the server: back to Login with a message', (tester) async {
    final backend = _backend();
    await _pumpAsStoreAdmin(tester, backend);
    backend.endAllSessions();

    await tester.tap(find.byKey(const Key('nav-cashiers'))); // loads cashiers → 401
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('loginButton')), findsOneWidget);
    expect(find.text('Your session has ended. Please log in again.'), findsOneWidget);
  });

  testWidgets('sidebar navigation + unsaved-changes guard', (tester) async {
    await _pumpAsStoreAdmin(tester, _backend());

    await tester.tap(find.text('Add Product').first);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('nameField')), 'Draft');
    await tester.tap(find.text('Products'));
    await tester.pumpAndSettle();
    expect(find.text('Discard changes?'), findsOneWidget);
    await tester.tap(find.text('Discard'));
    await tester.pumpAndSettle();
    expect(find.text('Inventory Management'), findsWidgets);
  });

  testWidgets('stat card filters and search narrow the list', (tester) async {
    await _pumpAsStoreAdmin(tester, _backend());

    await tester.tap(find.byKey(const Key('statLow')));
    await tester.pumpAndSettle();
    expect(find.text('Bread'), findsOneWidget);
    expect(find.text('Milk'), findsNothing);

    await tester.tap(find.byKey(const Key('statLow')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('searchField')), '3333');
    await tester.pumpAndSettle();
    expect(find.text('Eggs'), findsOneWidget);
    expect(find.text('Milk'), findsNothing);
  });

  testWidgets('Arabic locale: login and app are right-to-left', (tester) async {
    await _pumpApp(tester, _backend(), locale: const Locale('ar'));
    expect(find.text('تسجيل الدخول'), findsWidgets);
    await _login(tester, 'shini.admin', 'Admin-Pass-1');
    expect(find.text('إدارة المخزون'), findsWidgets);
    final dir = Directionality.of(tester.element(find.byKey(const Key('statTotal'))));
    expect(dir, TextDirection.rtl);
  });

  for (final size in const [Size(900, 900), Size(390, 844)]) {
    testWidgets('responsive layout without overflow at $size', (tester) async {
      await _pumpApp(tester, _backend(), size: size);
      expect(tester.takeException(), isNull);
      await _login(tester, 'shini.admin', 'Admin-Pass-1');
      expect(tester.takeException(), isNull);
      await tester.tap(find.byKey(const Key('headerAddProductButton')));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.text('Add New Product'), findsOneWidget);
    });
  }
}

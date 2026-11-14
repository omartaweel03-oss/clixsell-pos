import 'dart:convert';

import 'package:clixsell_pos/main.dart';
import 'package:clixsell_pos/screens/reports/report_widgets.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_product_backend.dart';

/// Shini: Milk 24 (min 10, normal, id 1), Bread 2 (min 2, low, id 2),
/// Eggs 0 (min 5, out, id 3), Tea 1 (min 3, low, id 4).
FakeProductBackend _backend() => FakeProductBackend()
  ..seed(name: 'Milk', barcode: '11111111', price: 5, quantity: 24, minStock: 10)
  ..seed(name: 'Bread', barcode: '22222222', price: 2, quantity: 2, minStock: 2, category: 'bakery', supplier: 'Ramallah Bakery')
  ..seed(name: 'Eggs', barcode: '33333333', price: 15, quantity: 0, minStock: 5)
  ..seed(name: 'Tea', barcode: '44444444', price: 12, quantity: 1, minStock: 3, category: 'groceries');

Future<void> _open(WidgetTester tester, FakeProductBackend backend,
    {String user = 'shini.admin', String password = 'Admin-Pass-1', Size size = const Size(1440, 1000),
    Locale? locale, bool goToAlerts = true}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ClixSellApp(http: backend.newHttp(), initialLocale: locale));
  await tester.pumpAndSettle();
  await tester.enterText(find.byKey(const Key('loginUsername')), user);
  await tester.enterText(find.byKey(const Key('loginPassword')), password);
  await tester.tap(find.byKey(const Key('loginButton')));
  await tester.pumpAndSettle();
  if (goToAlerts) await _nav(tester, 'stockAlerts');
}

Future<void> _nav(WidgetTester tester, String page) async {
  if (find.byKey(Key('nav-$page')).evaluate().isEmpty) {
    await tester.tap(find.byTooltip('Open navigation menu'));
    await tester.pumpAndSettle();
  }
  await tester.ensureVisible(find.byKey(Key('nav-$page')));
  await tester.tap(find.byKey(Key('nav-$page')));
  await tester.pumpAndSettle();
}

Finder _in(String key, String text) => find.descendant(of: find.byKey(Key(key)), matching: find.text(text));

String _qty(WidgetTester tester, int id) => tester.widget<Text>(find.byKey(Key('alertQty-$id'))).data!;

Future<String> _token(FakeProductBackend b, String user, String password) async {
  final r = await b.client.post(Uri.parse('http://localhost:8080/api/auth/login'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({'username': user, 'password': password}));
  return (jsonDecode(r.body) as Map<String, dynamic>)['token'] as String;
}

Future<Map<String, dynamic>> _post(FakeProductBackend b, String token, String path, Object body) async {
  final r = await b.client.post(Uri.parse('http://localhost:8080$path'),
      headers: {'Content-Type': 'application/json', 'Authorization': 'Bearer $token'},
      body: jsonEncode(body));
  expect(r.statusCode, 201, reason: r.body);
  return jsonDecode(r.body) as Map<String, dynamic>;
}

void main() {
  setUp(() => ReportClock.now = () => DateTime(2026, 11, 14));
  tearDown(() => ReportClock.now = DateTime.now);

  testWidgets('Stock Alerts (Fig 3.5): counts, rows and details from the server', (tester) async {
    final backend = _backend();
    await _open(tester, backend);

    expect(find.text('Products that need to be reordered'), findsOneWidget);
    expect(backend.requests, contains('GET /api/reports/inventory'));
    expect(_in('alertsTotal', '3'), findsOneWidget); // 1 out + 2 low
    expect(_in('alertsOut', '1'), findsOneWidget);
    expect(_in('alertsLow', '2'), findsOneWidget);
    expect(_in('alertBadge', '3'), findsOneWidget);   // sidebar badge

    // Out of stock first, then low stock; the normal product (Milk) is not listed.
    expect(find.byKey(const Key('alert-3')), findsOneWidget);
    expect(find.byKey(const Key('alert-4')), findsOneWidget);
    expect(find.byKey(const Key('alert-2')), findsOneWidget);
    expect(find.byKey(const Key('alert-1')), findsNothing);
    double y(String k) => tester.getTopLeft(find.byKey(Key(k))).dy;
    expect(y('alert-3'), lessThan(y('alert-4')));
    expect(_qty(tester, 3), '0');
    expect(_qty(tester, 2), '2');
    expect(find.text('Ramallah Bakery'), findsOneWidget); // supplier under the name
    expect(find.text('Bakery'), findsOneWidget);          // category
    expect(find.text('Out of Stock'), findsWidgets);
    expect(find.text('Low Stock'), findsWidgets);
  });

  testWidgets('cards filter the list', (tester) async {
    await _open(tester, _backend());
    await tester.tap(find.byKey(const Key('alertsOut')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('alert-3')), findsOneWidget);
    expect(find.byKey(const Key('alert-2')), findsNothing);
    expect(find.text('Showing 1 of 3 alerts'), findsOneWidget);

    await tester.tap(find.byKey(const Key('alertsLow')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('alert-3')), findsNothing);
    expect(find.byKey(const Key('alert-2')), findsOneWidget);
    expect(find.byKey(const Key('alert-4')), findsOneWidget);

    await tester.tap(find.byKey(const Key('alertsShowAll')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('alert-3')), findsOneWidget);
  });

  testWidgets('"Update stock" opens the product, saving returns here and the alert disappears', (tester) async {
    final backend = _backend();
    await _open(tester, backend);
    await tester.tap(find.byKey(const Key('restock-2'))); // Bread
    await tester.pumpAndSettle();
    expect(find.text('Edit Product'), findsWidgets);
    await tester.enterText(find.byKey(const Key('quantityField')), '30');
    await tester.ensureVisible(find.byKey(const Key('saveButton')));
    await tester.tap(find.byKey(const Key('saveButton')));
    await tester.pumpAndSettle();

    expect(backend.rowById(2)!['quantity'], 30);            // PUT → UPDATE
    expect(find.byKey(const Key('stockAlertsPage')), findsOneWidget); // back on Stock Alerts
    expect(find.byKey(const Key('alert-2')), findsNothing);
    expect(_in('alertsTotal', '2'), findsOneWidget);
    expect(_in('alertBadge', '2'), findsOneWidget);
  });

  testWidgets('alerts follow sales and returns made at the POS', (tester) async {
    final backend = _backend();
    final t = await _token(backend, 'shini.cashier', 'Cashier-Pass-1');
    // Sell 14 milk: 24 → 10 = its minimum → low-stock alert.
    final sale = await _post(backend, t, '/api/sales', {
      'items': [
        {'productId': 1, 'quantity': 14},
      ],
      'paymentMethod': 'CASH',
    });
    await _open(tester, backend);
    expect(find.byKey(const Key('alert-1')), findsOneWidget);
    expect(_qty(tester, 1), '10');
    expect(_in('alertsLow', '3'), findsOneWidget);

    // Return 1: 10 → 11 → not an alert any more (Refresh reloads from the server).
    final item = (sale['items'] as List).first as Map<String, dynamic>;
    await _post(backend, t, '/api/returns', {
      'saleId': sale['saleId'],
      'saleItemId': item['saleItemId'],
      'quantity': 1,
      'reason': 'Damaged',
    });
    await tester.tap(find.byKey(const Key('alertsRefresh')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('alert-1')), findsNothing);
    expect(_in('alertsLow', '2'), findsOneWidget);
  });

  testWidgets('"Create purchase request" is disabled and says why (no fake action)', (tester) async {
    await _open(tester, _backend());
    final button = tester.widget<ButtonStyleButton>(find.byKey(const Key('createPurchaseRequest')));
    expect(button.onPressed, isNull);
    expect(find.textContaining('Purchase requests are not available yet'), findsOneWidget);
  });

  testWidgets('cashiers have no Stock Alerts; each store sees only its own', (tester) async {
    final backend = _backend();
    final neshan = backend.addStore('Neshan Market');
    backend.addUser('neshan.admin', 'Admin-Pass-1', 'STORE_ADMIN', neshan, fullName: 'Rami');
    backend.seed(name: 'Neshan Oil', barcode: '55555555', price: 30, quantity: 0, minStock: 3, storeId: neshan);
    backend.seed(name: 'Neshan Water', barcode: '66666666', price: 1, quantity: 90, minStock: 10, storeId: neshan);

    await _open(tester, backend, user: 'shini.cashier', password: 'Cashier-Pass-1', goToAlerts: false);
    expect(find.byKey(const Key('nav-stockAlerts')), findsNothing);
    await tester.tap(find.byKey(const Key('logoutButton')));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('loginUsername')), 'neshan.admin');
    await tester.enterText(find.byKey(const Key('loginPassword')), 'Admin-Pass-1');
    await tester.tap(find.byKey(const Key('loginButton')));
    await tester.pumpAndSettle();
    await _nav(tester, 'stockAlerts');
    expect(find.text('Neshan Oil'), findsOneWidget);
    expect(find.text('Eggs'), findsNothing);
    expect(_in('alertsTotal', '1'), findsOneWidget);
  });

  testWidgets('no alerts: a clear message; server error: try again', (tester) async {
    final backend = FakeProductBackend()
      ..seed(name: 'Milk', barcode: '11111111', price: 5, quantity: 24, minStock: 10);
    backend.failReports = true;
    await _open(tester, backend);
    expect(find.byKey(const Key('reportsError')), findsOneWidget);
    backend.failReports = false;
    await tester.tap(find.byKey(const Key('reportsRetry')));
    await tester.pumpAndSettle();
    expect(find.text('No alerts: every product is above its alert level.'), findsOneWidget);
    expect(_in('alertsTotal', '0'), findsOneWidget);
    expect(find.byKey(const Key('alertBadge')), findsNothing);
  });

  testWidgets('Arabic: right-to-left, cards in the order of Figure 3.5', (tester) async {
    await _open(tester, _backend(), locale: const Locale('ar'));
    expect(find.text('تنبيهات المخزون'), findsWidgets);
    expect(find.text('المنتجات التي تحتاج إلى إضافة طلب'), findsOneWidget);
    expect(find.text('إجمالي التنبيهات'), findsOneWidget);
    expect(find.text('نفدت بالفعل'), findsOneWidget);
    expect(find.text('إنشاء طلب شراء'), findsOneWidget);
    expect(find.text('الحد التنبيهي'), findsOneWidget);
    double x(String k) => tester.getCenter(find.byKey(Key(k))).dx;
    expect(x('alertsTotal'), greaterThan(x('alertsOut')));
    expect(x('alertsOut'), greaterThan(x('alertsLow')));
  });

  for (final size in const [Size(1440, 1000), Size(1024, 768), Size(390, 844)]) {
    testWidgets('Stock Alerts keeps the desktop table at $size, without overflow', (tester) async {
      await _open(tester, _backend(), size: size);
      expect(tester.takeException(), isNull);
      expect(find.byKey(const Key('alert-3')), findsOneWidget);
      // Desktop design of Figure 3.5 at every size: the table with its columns, sidebar (no drawer).
      expect(find.text('Alert level'), findsOneWidget);
      expect(find.byKey(const Key('nav-stockAlerts')), findsOneWidget);
      expect(find.byTooltip('Open navigation menu'), findsNothing);
    });
  }
}

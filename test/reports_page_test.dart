import 'dart:convert';

import 'package:clixsell_pos/main.dart';
import 'package:clixsell_pos/screens/reports/report_widgets.dart';
import 'package:clixsell_pos/widgets/sales_charts.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_product_backend.dart';

/// Shini: Milk ₪5 ×24 (id 1, min 10), Bread ₪2 ×3 (id 2, min 1), Eggs ₪15 ×0 (id 3).
///
/// History (same as the backend ReportApiIntegrationTest):
///   05 Mar  milk ×2 + bread ×1, cash                → 13.92
///   20 Mar  milk ×4, 10 % discount, card            → 20.88
///   21 Mar  return 1 milk of the first invoice      → refund 5.80
///   02 Apr  bread ×1                                → 2.32
/// March: net 29.00, 2 invoices. Stock: milk 19, bread 1 (low), eggs 0 (out) → value 97.00.
Future<FakeProductBackend> _history() async {
  final b = FakeProductBackend()
    ..seed(name: 'Milk', barcode: '11111111', price: 5, quantity: 24, minStock: 10)
    ..seed(name: 'Bread', barcode: '22222222', price: 2, quantity: 3, minStock: 1, category: 'bakery')
    ..seed(name: 'Eggs', barcode: '33333333', price: 15, quantity: 0, minStock: 5);
  final t = await _token(b, 'shini.cashier', 'Cashier-Pass-1');
  b.saleTime = '2026-03-05T10:00:00';
  final first = await _call(b, t, '/api/sales', {
    'items': [
      {'productId': 1, 'quantity': 2},
      {'productId': 2, 'quantity': 1},
    ],
    'paymentMethod': 'CASH',
  });
  b.saleTime = '2026-03-20T18:30:00';
  await _call(b, t, '/api/sales', {
    'items': [
      {'productId': 1, 'quantity': 4},
    ],
    'discountType': 'PERCENT',
    'discountValue': 10,
    'paymentMethod': 'CARD',
  });
  b.saleTime = '2026-03-21T09:00:00';
  final milkLine = (first['items'] as List).first as Map<String, dynamic>;
  await _call(b, t, '/api/returns', {
    'saleId': first['saleId'],
    'saleItemId': milkLine['saleItemId'],
    'quantity': 1,
    'reason': 'Damaged',
  });
  b.saleTime = '2026-04-02T09:15:00';
  await _call(b, t, '/api/sales', {
    'items': [
      {'productId': 2, 'quantity': 1},
    ],
    'paymentMethod': 'CASH',
  });
  return b;
}

Future<String> _token(FakeProductBackend b, String user, String password) async {
  final r = await b.client.post(
    Uri.parse('http://localhost:8080/api/auth/login'),
    headers: {'Content-Type': 'application/json'},
    body: jsonEncode({'username': user, 'password': password}),
  );
  return (jsonDecode(r.body) as Map<String, dynamic>)['token'] as String;
}

Future<Map<String, dynamic>> _call(FakeProductBackend b, String token, String path, Object body) async {
  final r = await b.client.post(
    Uri.parse('http://localhost:8080$path'),
    headers: {'Content-Type': 'application/json', 'Authorization': 'Bearer $token'},
    body: jsonEncode(body),
  );
  expect(r.statusCode, 201, reason: r.body);
  return jsonDecode(r.body) as Map<String, dynamic>;
}

Future<void> _open(WidgetTester tester, FakeProductBackend backend,
    {String user = 'shini.admin', String password = 'Admin-Pass-1', String? page,
    Size size = const Size(1440, 1000), Locale? locale}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ClixSellApp(http: backend.newHttp(), initialLocale: locale));
  await tester.pumpAndSettle();
  await tester.enterText(find.byKey(const Key('loginUsername')), user);
  await tester.enterText(find.byKey(const Key('loginPassword')), password);
  await tester.tap(find.byKey(const Key('loginButton')));
  await tester.pumpAndSettle();
  if (page != null) await _nav(tester, page);
}

Future<void> _nav(WidgetTester tester, String page) async {
  if (find.byKey(Key('nav-$page')).evaluate().isEmpty) {
    // narrow screens: the menu is in the drawer
    await tester.tap(find.byTooltip('Open navigation menu'));
    await tester.pumpAndSettle();
  }
  await tester.ensureVisible(find.byKey(Key('nav-$page')));
  await tester.tap(find.byKey(Key('nav-$page')));
  await tester.pumpAndSettle();
}

String _text(WidgetTester tester, String key) => tester.widget<Text>(find.byKey(Key(key))).data!;

Future<void> _choose(WidgetTester tester, Finder dropdown, String option) async {
  await tester.ensureVisible(dropdown);
  await tester.tap(dropdown);
  await tester.pumpAndSettle();
  await tester.tap(find.text(option).last);
  await tester.pumpAndSettle();
}

void main() {
  setUp(() => ReportClock.now = () => DateTime(2026, 3, 15));
  tearDown(() => ReportClock.now = DateTime.now);

  testWidgets('Reports and Sales Analytics are in the Store Admin menu, not the Cashier menu', (tester) async {
    final backend = await _history();
    await _open(tester, backend);
    expect(find.byKey(const Key('nav-reports')), findsOneWidget);
    expect(find.byKey(const Key('nav-salesAnalytics')), findsOneWidget);

    await tester.tap(find.byKey(const Key('logoutButton')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('loginUsername')), 'shini.cashier');
    await tester.enterText(find.byKey(const Key('loginPassword')), 'Cashier-Pass-1');
    await tester.tap(find.byKey(const Key('loginButton')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('nav-reports')), findsNothing);
    expect(find.byKey(const Key('nav-salesAnalytics')), findsNothing);
  });

  testWidgets('Reports (Fig 3.8): cards, daily chart, featured products and inventory from the server', (tester) async {
    final backend = await _history();
    await _open(tester, backend, page: 'reports');

    expect(find.text('Sales and profit report'), findsOneWidget);
    expect(backend.requests, containsAll([
      'GET /api/reports/summary',
      'GET /api/reports/daily-sales',
      'GET /api/reports/top-products',
      'GET /api/reports/inventory',
    ]));
    expect(_text(tester, 'cardSalesValue'), '₪ 29.00');        // 34.80 − 5.80 refund
    expect(_text(tester, 'cardInvoicesValue'), '2');
    expect(_text(tester, 'cardValueValue'), '₪ 97.00');        // 19 × 5 + 1 × 2
    expect(_text(tester, 'cardProfitValue'), 'Not available'); // no cost price: never estimated
    expect(_text(tester, 'profitNote'), 'Needs the purchase cost of products');
    expect(find.text('After returns of ₪ 5.80'), findsOneWidget);
    expect(find.text('No sales in the previous period'), findsNWidgets(2));

    final chart = tester.widget<SalesLineChart>(find.byKey(const Key('reportDailyChart')));
    expect(chart.values, hasLength(31));
    expect(chart.values[4], 13.92);   // 5 March
    expect(chart.values[19], 20.88);  // 20 March
    expect(chart.values[20], -5.80);  // 21 March: refund
    expect(chart.values[14], 0);

    expect(find.descendant(of: find.byKey(const Key('featured-0')), matching: find.text('Milk')), findsOneWidget);
    expect(_text(tester, 'featuredValue-0'), '₪ 26.68');
    expect(_text(tester, 'featuredValue-1'), '₪ 2.32');
    expect(find.text('5 sold'), findsOneWidget);

    expect(find.descendant(of: find.byKey(const Key('invValue')), matching: find.text('₪ 97.00')), findsOneWidget);
    expect(find.descendant(of: find.byKey(const Key('invLow')), matching: find.text('1')), findsOneWidget);
    expect(find.descendant(of: find.byKey(const Key('invOut')), matching: find.text('1')), findsOneWidget);
    expect(find.byKey(const ValueKey('invCategory-bakery')), findsOneWidget);
    expect(find.byKey(const ValueKey('invCategory-dairy')), findsOneWidget);
  });

  testWidgets('Reports: changing the month and the scope reloads the figures', (tester) async {
    final backend = await _history();
    await _open(tester, backend, page: 'reports');

    await _choose(tester, find.byKey(const ValueKey('monthSelect-3-true')), 'April');
    expect(_text(tester, 'cardSalesValue'), '₪ 2.32');
    expect(_text(tester, 'cardInvoicesValue'), '1');
    expect(find.text('92.0%'), findsOneWidget);  // 2.32 vs 29.00 in March
    expect(find.text('50.0%'), findsOneWidget);  // 1 vs 2 invoices
    expect(find.byIcon(Icons.arrow_downward_rounded), findsNWidgets(2));

    await _choose(tester, find.byKey(const ValueKey('scopeSelect-ReportScope.month')), 'Whole year');
    expect(_text(tester, 'cardSalesValue'), '₪ 31.32');
    expect(_text(tester, 'cardInvoicesValue'), '3');
    final bars = tester.widget<SalesBarChart>(find.byKey(const Key('reportMonthlyChart')));
    expect(bars.values[2], 29.00);
    expect(bars.values[3], 2.32);
    // the month cannot be chosen for a whole-year report
    final month = tester.widget<DropdownButton<int>>(find.descendant(
        of: find.byKey(const ValueKey('monthSelect-4-false')), matching: find.byType(DropdownButton<int>)));
    expect(month.onChanged, isNull);
  });

  testWidgets('Reports: "Generate report" shows the whole report as text', (tester) async {
    final backend = await _history();
    await _open(tester, backend, page: 'reports');
    await tester.tap(find.byKey(const Key('generateReportButton')));
    await tester.pumpAndSettle();
    final text = tester.widget<SelectableText>(find.byKey(const Key('reportText'))).data!;
    expect(text, contains('Period: 2026-03-01 → 2026-03-31'));
    expect(text, contains('Sales (completed invoices): ₪ 34.80'));
    expect(text, contains('Discounts given: ₪ 2.00'));
    expect(text, contains('Refunds (returns): ₪ 5.80'));
    expect(text, contains('Net sales: ₪ 29.00'));
    expect(text, contains('Profit: Not available'));
    expect(text, contains('• Milk: 5 sold · ₪ 26.68'));
    await tester.tap(find.byKey(const Key('reportCloseButton')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('reportDialog')), findsNothing);
  });

  testWidgets('Sales Analytics (Fig 3.7): daily, monthly, best-selling and products to reorder', (tester) async {
    final backend = await _history();
    await _open(tester, backend, page: 'salesAnalytics');

    expect(find.text('Complete analysis of sales performance'), findsOneWidget);
    final daily = tester.widget<SalesLineChart>(find.byKey(const Key('dailyChart')));
    expect(daily.values, hasLength(31));
    expect(daily.values[4], 13.92);
    final monthly = tester.widget<SalesBarChart>(find.byKey(const Key('monthlyChart')));
    expect(monthly.values, hasLength(12));
    expect(monthly.values[2], 29.00);
    expect(monthly.values[3], 2.32);
    expect(monthly.highlight, 2); // March

    expect(find.byKey(const Key('bestDonut')), findsOneWidget);
    expect(find.descendant(of: find.byKey(const Key('best-0')), matching: find.text('Milk')), findsOneWidget);
    expect(_text(tester, 'bestShare-0'), '92%');
    expect(_text(tester, 'bestShare-1'), '8%');

    // Out of stock first, then low stock; in-stock milk is not listed.
    expect(find.byKey(const Key('reorder-3')), findsOneWidget);
    expect(find.byKey(const Key('outIcon-3')), findsOneWidget);
    expect(find.byKey(const Key('lowIcon-2')), findsOneWidget);
    expect(find.byKey(const Key('reorder-1')), findsNothing);
    expect(find.text('1 in stock · min 1'), findsOneWidget);
    expect(
      tester.getTopLeft(find.byKey(const Key('reorder-3'))).dy,
      lessThan(tester.getTopLeft(find.byKey(const Key('reorder-2'))).dy),
    );

    // Another month: April has one sale of bread.
    await _choose(tester, find.byKey(const ValueKey('monthSelect-3-true')), 'April');
    final april = tester.widget<SalesLineChart>(find.byKey(const Key('dailyChart')));
    expect(april.values, hasLength(30));
    expect(april.values[1], 2.32);
    expect(tester.widget<SalesBarChart>(find.byKey(const Key('monthlyChart'))).highlight, 3);
    expect(find.descendant(of: find.byKey(const Key('best-0')), matching: find.text('Bread')), findsOneWidget);

    // A month without sales.
    await _choose(tester, find.byKey(const ValueKey('monthSelect-4-true')), 'June');
    expect(find.byKey(const Key('dailyEmpty')), findsOneWidget);
    expect(find.descendant(of: find.byKey(const Key('bestSellingCard')), matching: find.text('No sales in this period')),
        findsOneWidget);
  });

  testWidgets('each store sees only its own reports', (tester) async {
    final backend = await _history();
    final neshan = backend.addStore('Neshan Market');
    backend.addUser('neshan.admin', 'Admin-Pass-1', 'STORE_ADMIN', neshan, fullName: 'Rami');
    backend.seed(name: 'Neshan Juice', barcode: '44444444', price: 8, quantity: 5, minStock: 1, storeId: neshan);
    await _open(tester, backend, user: 'neshan.admin', page: 'reports');
    expect(_text(tester, 'cardSalesValue'), '₪ 0.00');
    expect(_text(tester, 'cardInvoicesValue'), '0');
    expect(_text(tester, 'cardValueValue'), '₪ 40.00');
    expect(find.text('Milk'), findsNothing);
    expect(find.byKey(const Key('reportChartEmpty')), findsOneWidget);

    await _nav(tester, 'salesAnalytics');
    expect(find.text('All products are above their minimum stock level'), findsOneWidget);
    expect(find.byKey(const Key('reorder-3')), findsNothing);
  });

  testWidgets('loading and error states: the report can be retried', (tester) async {
    final backend = await _history();
    backend.failReports = true;
    await _open(tester, backend, page: 'reports');
    expect(find.byKey(const Key('reportsError')), findsOneWidget);
    expect(find.text('Could not load the report.'), findsOneWidget);

    backend
      ..failReports = false
      ..reportsDelay = const Duration(milliseconds: 300);
    await tester.tap(find.byKey(const Key('reportsRetry')));
    await tester.pump();
    expect(find.byKey(const Key('reportsLoading')), findsOneWidget);
    await tester.pumpAndSettle();
    expect(_text(tester, 'cardSalesValue'), '₪ 29.00');
  });

  testWidgets('Arabic: both screens in Arabic, right-to-left', (tester) async {
    final backend = await _history();
    await _open(tester, backend, page: 'reports', locale: const Locale('ar'));
    expect(find.text('تقرير المبيعات والأرباح'), findsOneWidget);
    expect(find.text('إجمالي الأرباح'), findsOneWidget);
    expect(find.text('غير متاح'), findsOneWidget);
    expect(find.text('المنتجات المميزة'), findsOneWidget);
    expect(find.text('توليد تقرير'), findsOneWidget);
    expect(Directionality.of(tester.element(find.byKey(const Key('cardSales')))), TextDirection.rtl);

    await _nav(tester, 'salesAnalytics');
    expect(find.text('تحليل شامل لأداء المبيعات'), findsOneWidget);
    expect(find.text('أكثر المنتجات مبيعاً'), findsOneWidget);
    expect(find.text('الأصناف التي أوشكت على النفاد'), findsOneWidget);
    expect(find.text('مارس'), findsOneWidget); // month selector
  });

  for (final size in const [Size(1440, 1000), Size(1024, 768), Size(390, 844)]) {
    testWidgets('both screens without overflow at $size', (tester) async {
      final backend = await _history();
      await _open(tester, backend, page: 'reports', size: size);
      expect(tester.takeException(), isNull);
      expect(find.byKey(const Key('cardSales')), findsOneWidget);
      await _nav(tester, 'salesAnalytics');
      expect(tester.takeException(), isNull);
      expect(find.byKey(const Key('dailyChart')), findsOneWidget);
    });
  }
}

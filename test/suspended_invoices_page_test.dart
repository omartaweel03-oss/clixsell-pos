import 'dart:convert';

import 'package:clixsell_pos/main.dart';
import 'package:clixsell_pos/screens/reports/report_widgets.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_product_backend.dart';

/// Shini: Milk ₪5 ×24 (id 1), Bread ₪2 ×30 (id 2). Tax 16 %.
/// Suspended (by the cashier "Ahmad Saleh"):
///   20 Oct 12:00  milk ×1            → 5.80   (oldest)
///   14 Nov 08:00  milk ×2            → 11.60
///   14 Nov 09:30  bread ×1           → 2.32
/// "Now" = 14 Nov 2026 10:00.
FakeProductBackend _backend() => FakeProductBackend()
  ..seed(
    name: 'Milk',
    barcode: '11111111',
    price: 5,
    quantity: 24,
    minStock: 10,
  )
  ..seed(
    name: 'Bread',
    barcode: '22222222',
    price: 2,
    quantity: 30,
    minStock: 1,
    category: 'bakery',
  );

Future<String> _token(
  FakeProductBackend b,
  String user,
  String password,
) async {
  final r = await b.client.post(
    Uri.parse('http://localhost:8080/api/auth/login'),
    headers: {'Content-Type': 'application/json'},
    body: jsonEncode({'username': user, 'password': password}),
  );
  return (jsonDecode(r.body) as Map<String, dynamic>)['token'] as String;
}

Future<Map<String, dynamic>> _suspend(
  FakeProductBackend b,
  String token,
  int productId,
  int qty,
  String at,
) async {
  b.saleTime = at;
  final r = await b.client.post(
    Uri.parse('http://localhost:8080/api/sales/suspended'),
    headers: {
      'Content-Type': 'application/json',
      'Authorization': 'Bearer $token',
    },
    body: jsonEncode({
      'items': [
        {'productId': productId, 'quantity': qty},
      ],
    }),
  );
  expect(r.statusCode, 201, reason: r.body);
  return jsonDecode(r.body) as Map<String, dynamic>;
}

Future<FakeProductBackend> _history() async {
  final b = _backend();
  final t = await _token(b, 'shini.cashier', 'Cashier-Pass-1');
  await _suspend(b, t, 1, 1, '2026-10-20T12:00:00');
  await _suspend(b, t, 1, 2, '2026-11-14T08:00:00');
  await _suspend(b, t, 2, 1, '2026-11-14T09:30:00');
  return b;
}

Future<void> _open(
  WidgetTester tester,
  FakeProductBackend backend, {
  String user = 'shini.admin',
  String password = 'Admin-Pass-1',
  Size size = const Size(1440, 1000),
  Locale? locale,
  bool go = true,
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(
    ClixSellApp(http: backend.newHttp(), initialLocale: locale),
  );
  await tester.pumpAndSettle();
  await tester.enterText(find.byKey(const Key('loginUsername')), user);
  await tester.enterText(find.byKey(const Key('loginPassword')), password);
  await tester.tap(find.byKey(const Key('loginButton')));
  await tester.pumpAndSettle();
  if (go) await _nav(tester, 'suspendedInvoices');
}

Future<void> _nav(WidgetTester tester, String page) async {
  await tester.ensureVisible(find.byKey(Key('nav-$page')));
  await tester.tap(find.byKey(Key('nav-$page')));
  await tester.pumpAndSettle();
}

Finder _in(String key, String text) =>
    find.descendant(of: find.byKey(Key(key)), matching: find.text(text));
String _text(WidgetTester tester, String key) =>
    tester.widget<Text>(find.byKey(Key(key))).data!;
int _rows() => find
    .byWidgetPredicate(
      (w) =>
          w.key is ValueKey<String> &&
          (w.key! as ValueKey<String>).value.startsWith('suspRow-'),
    )
    .evaluate()
    .length;

Future<void> _choose(
  WidgetTester tester,
  Finder dropdown,
  String option,
) async {
  await tester.ensureVisible(dropdown);
  await tester.tap(dropdown);
  await tester.pumpAndSettle();
  await tester.tap(find.text(option).last);
  await tester.pumpAndSettle();
}

void main() {
  setUp(() => ReportClock.now = () => DateTime(2026, 11, 14, 10));
  tearDown(() => ReportClock.now = DateTime.now);

  testWidgets(
    'Fig 3.6: cards, table with the Cashier column, data from the server',
    (tester) async {
      final backend = await _history();
      await _open(tester, backend);

      expect(
        find.text('All suspended invoices that have not been completed'),
        findsOneWidget,
      );
      expect(backend.requests, contains('GET /api/sales/suspended'));
      expect(_in('suspCount', '3'), findsOneWidget);
      expect(
        _in('suspValue', '₪ 19.72'),
        findsOneWidget,
      ); // 5.80 + 11.60 + 2.32
      expect(_in('suspOldest', '24 days ago'), findsOneWidget);
      expect(_in('suspOldest', 'Invoice INV-000001'), findsOneWidget);

      expect(_rows(), 3);
      expect(
        _in('suspTable', 'Cashier'),
        findsOneWidget,
      ); // clearly labelled (not "Customer")
      expect(find.text('Customer'), findsNothing);
      expect(find.text('Ahmad Saleh'), findsNWidgets(3));
      expect(find.byKey(const Key('cashierColumnNote')), findsOneWidget);
      final s = backend.sales;
      expect(_text(tester, 'suspTotal-${s[1]['saleId']}'), '₪ 11.60');
      expect(_text(tester, 'suspItems-${s[1]['saleId']}'), '1');
      // newest first
      double y(int i) =>
          tester.getTopLeft(find.byKey(Key('suspRow-${s[i]['saleId']}'))).dy;
      expect(y(2), lessThan(y(1)));
      expect(y(1), lessThan(y(0)));
      expect(_text(tester, 'suspShowing'), 'Showing 1 – 3 of 3 invoices');
    },
  );

  testWidgets('month filter (default: all months)', (tester) async {
    await _open(tester, await _history());
    await _choose(
      tester,
      find.byKey(const ValueKey('suspMonth-null')),
      'November',
    );
    expect(_rows(), 2);
    expect(_in('suspCount', '2'), findsOneWidget);
    expect(_in('suspValue', '₪ 13.92'), findsOneWidget);
    expect(_in('suspOldest', '2 h ago'), findsOneWidget);
    await _choose(
      tester,
      find.byKey(const ValueKey('suspMonth-11')),
      'October',
    );
    expect(_rows(), 1);
    await _choose(
      tester,
      find.byKey(const ValueKey('suspMonth-10')),
      'September',
    );
    expect(find.text('No suspended invoices in this month.'), findsOneWidget);
    await _choose(
      tester,
      find.byKey(const ValueKey('suspMonth-9')),
      'All months',
    );
    expect(_rows(), 3);
  });

  testWidgets('pages: 10 per page, page buttons and page size', (tester) async {
    final backend = _backend();
    final t = await _token(backend, 'shini.cashier', 'Cashier-Pass-1');
    for (var i = 0; i < 23; i++) {
      await _suspend(
        backend,
        t,
        2,
        1,
        '2026-11-${(i % 9 + 1).toString().padLeft(2, '0')}T10:00:00',
      );
    }
    await _open(tester, backend);
    expect(_rows(), 10);
    expect(_text(tester, 'suspShowing'), 'Showing 1 – 10 of 23 invoices');
    await tester.ensureVisible(find.byKey(const Key('page-3')));
    await tester.tap(find.byKey(const Key('page-3')));
    await tester.pumpAndSettle();
    expect(_rows(), 3);
    expect(_text(tester, 'suspShowing'), 'Showing 21 – 23 of 23 invoices');
    await tester.tap(find.byKey(const Key('pagePrev')));
    await tester.pumpAndSettle();
    expect(_text(tester, 'suspShowing'), 'Showing 11 – 20 of 23 invoices');
    await _choose(
      tester,
      find.byKey(const ValueKey('pageSize-10')),
      '50 per page',
    );
    expect(_rows(), 23);
  });

  testWidgets(
    'resume: opens the POS with the invoice, then completes with the same number',
    (tester) async {
      final backend = await _history();
      await _open(tester, backend);
      final sale = backend.sales[1]; // milk ×2
      await tester.tap(find.byKey(Key('resume-${sale['saleId']}')));
      await tester.pumpAndSettle();

      expect(
        find.byKey(const Key('posSearchField')),
        findsOneWidget,
      ); // on the POS
      expect(find.text('#${sale['invoiceNumber']}'), findsOneWidget);
      expect(_text(tester, 'qty-1'), '2');
      expect(
        find.text('Invoice ${sale['invoiceNumber']} resumed'),
        findsOneWidget,
      );

      await tester.tap(find.byKey(const Key('completeSaleButton')));
      await tester.pumpAndSettle();
      final done = backend.sales.firstWhere(
        (x) => x['saleId'] == sale['saleId'],
      );
      expect(done['status'], 'COMPLETED');
      expect(done['invoiceNumber'], sale['invoiceNumber']);
      await tester.tap(find.byKey(const Key('receiptCloseButton')));
      await tester.pumpAndSettle();

      await _nav(tester, 'suspendedInvoices');
      expect(_rows(), 2);
      expect(find.byKey(Key('suspRow-${sale['saleId']}')), findsNothing);
    },
  );

  testWidgets(
    'resume while another invoice is open asks first (existing rule)',
    (tester) async {
      final backend = await _history();
      await _open(tester, backend, go: false);
      await _nav(tester, 'pos');
      await tester.tap(
        find.byKey(const Key('posProduct-2')),
      ); // POS has an open invoice
      await tester.pumpAndSettle();
      await _nav(tester, 'suspendedInvoices');
      await tester.tap(find.byKey(Key('resume-${backend.sales[0]['saleId']}')));
      await tester.pumpAndSettle();
      expect(find.text('Replace the current invoice?'), findsOneWidget);
      await tester.tap(find.text('Keep invoice'));
      await tester.pumpAndSettle();
      expect(
        find.byKey(const Key('suspendedPage')),
        findsOneWidget,
      ); // stayed here

      await tester.tap(find.byKey(Key('resume-${backend.sales[0]['saleId']}')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Yes, continue'));
      await tester.pumpAndSettle();
      expect(find.text('#INV-000001'), findsOneWidget);
      expect(_text(tester, 'qty-1'), '1');
    },
  );

  testWidgets(
    'delete = cancel: confirmation, CANCELED on the server, record kept',
    (tester) async {
      final backend = await _history();
      await _open(tester, backend);
      final sale = backend.sales[2];
      await tester.tap(find.byKey(Key('delete-${sale['saleId']}')));
      await tester.pumpAndSettle();
      expect(find.text('Delete this suspended invoice?'), findsOneWidget);
      await tester.tap(find.text('Keep invoice'));
      await tester.pumpAndSettle();
      expect(sale['status'], 'SUSPENDED');

      await tester.tap(find.byKey(Key('delete-${sale['saleId']}')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('confirmActionButton')));
      await tester.pumpAndSettle();
      expect(
        backend.requests,
        contains('DELETE /api/sales/suspended/${sale['saleId']}'),
      );
      expect(
        backend.sales.firstWhere(
          (x) => x['saleId'] == sale['saleId'],
        )['status'],
        'CANCELED',
      );
      expect(backend.sales, hasLength(3)); // kept
      expect(_rows(), 2);
      expect(_in('suspCount', '2'), findsOneWidget);
      expect(
        find.text('Invoice ${sale['invoiceNumber']} was canceled'),
        findsOneWidget,
      );

      // Resuming right after: that message must not stay over the POS buttons.
      await tester.tap(find.byKey(Key('resume-${backend.sales[0]['saleId']}')));
      await tester.pumpAndSettle();
      expect(
        find.text('Invoice ${sale['invoiceNumber']} was canceled'),
        findsNothing,
      );
      expect(find.text('Invoice INV-000001 resumed'), findsOneWidget);
    },
  );

  testWidgets(
    'cashier has the screen, System Admin not; each store sees its own',
    (tester) async {
      final backend = await _history();
      final neshan = backend.addStore('Neshan Market');
      backend.addUser(
        'neshan.cashier',
        'Cashier-Pass-1',
        'CASHIER',
        neshan,
        fullName: 'Rana',
      );
      backend.seed(
        name: 'Neshan Juice',
        barcode: '44444444',
        price: 8,
        quantity: 5,
        minStock: 1,
        storeId: neshan,
      );
      final nt = await _token(backend, 'neshan.cashier', 'Cashier-Pass-1');
      await _suspend(backend, nt, 3, 1, '2026-11-13T10:00:00');

      await _open(
        tester,
        backend,
        user: 'shini.cashier',
        password: 'Cashier-Pass-1',
      );
      expect(_rows(), 3);
      expect(_in('suspTable', 'Rana'), findsNothing);
      await tester.tap(find.byKey(const Key('logoutButton')));
      await tester.pumpAndSettle();

      await tester.enterText(
        find.byKey(const Key('loginUsername')),
        'neshan.cashier',
      );
      await tester.enterText(
        find.byKey(const Key('loginPassword')),
        'Cashier-Pass-1',
      );
      await tester.tap(find.byKey(const Key('loginButton')));
      await tester.pumpAndSettle();
      await _nav(tester, 'suspendedInvoices');
      expect(_rows(), 1);
      expect(_in('suspTable', 'Rana'), findsOneWidget);
      expect(_in('suspValue', '₪ 9.28'), findsOneWidget);
      await tester.tap(find.byKey(const Key('logoutButton')));
      await tester.pumpAndSettle();

      await tester.enterText(
        find.byKey(const Key('loginUsername')),
        'sysadmin',
      );
      await tester.enterText(
        find.byKey(const Key('loginPassword')),
        'Admin-Pass-1',
      );
      await tester.tap(find.byKey(const Key('loginButton')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('nav-suspendedInvoices')), findsNothing);
    },
  );

  testWidgets('no suspended invoices: clear message and empty cards', (
    tester,
  ) async {
    await _open(tester, _backend());
    expect(find.text('There are no suspended invoices.'), findsOneWidget);
    expect(_in('suspCount', '0'), findsOneWidget);
    expect(_in('suspValue', '₪ 0.00'), findsOneWidget);
  });

  testWidgets('Arabic: right-to-left in the order of Figure 3.6', (
    tester,
  ) async {
    await _open(tester, await _history(), locale: const Locale('ar'));
    expect(
      find.text('جميع الفواتير المعلقة التي لم يتم إكمالها'),
      findsOneWidget,
    );
    expect(_in('suspTable', 'الكاشير'), findsOneWidget);
    expect(find.text('رمز الفاتورة'), findsOneWidget);
    expect(find.text('كل الأشهر'), findsOneWidget);
    double x(String k) => tester.getCenter(find.byKey(Key(k))).dx;
    expect(x('suspOldest'), greaterThan(x('suspValue')));
    expect(x('suspValue'), greaterThan(x('suspCount')));
  });

  for (final size in const [
    Size(1440, 1000),
    Size(1024, 768),
    Size(390, 844),
  ]) {
    testWidgets('desktop layout without overflow at $size', (tester) async {
      await _open(tester, await _history(), size: size);
      expect(tester.takeException(), isNull);
      expect(_in('suspTable', 'Cashier'), findsOneWidget);
      expect(_rows(), 3);
    });
  }
}

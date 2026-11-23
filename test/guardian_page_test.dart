import 'package:clixsell_pos/main.dart';
import 'package:clixsell_pos/screens/reports/report_widgets.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_product_backend.dart';

/// Guardian Mode screen (report Figure 3.1). The rules themselves are tested
/// in the backend; here the screen must show exactly what the API sends, in
/// English and Arabic, open the right existing screens and never offer an
/// action that does not exist.
FakeProductBackend _backend() => FakeProductBackend()
  ..seed(name: 'Milk', barcode: '11111111', price: 5, quantity: 8, minStock: 10)
  ..seed(
    name: 'Cheese',
    barcode: '22222222',
    price: 10,
    quantity: 30,
    minStock: 5,
    cost: 9.5,
  )
  ..seed(
    name: 'Eggs',
    barcode: '33333333',
    price: 15,
    quantity: 0,
    minStock: 5,
  );

Map<String, dynamic> _alert(
  String key,
  String rule,
  String code,
  String severity,
  String category,
  Map<String, dynamic> params, {
  Map<String, dynamic>? actor,
  String? actorNote,
  String? at,
  Map<String, dynamic>? action,
}) => {
  'key': key,
  'rule': rule,
  'code': code,
  'severity': severity,
  'category': category,
  'params': params,
  'evidence': <Object>[],
  'actor': actor,
  'actorNote': actorNote,
  'occurredAt': at,
  'day': at?.substring(0, 10),
  'action': action,
};

final _discount = _alert(
  'G1:sale:1',
  'G1',
  'HIGH_DISCOUNT',
  'MEDIUM',
  'DISCOUNT',
  {
    'invoiceNumber': 'INV-000001',
    'subtotal': 50.0,
    'discount': 5.0,
    'percent': 10.0,
    'maxPercent': 10.0,
    'maxSource': 'AT_SALE',
    'thresholdPercent': 7.5,
    'sharePercent': 75,
    'minAmount': 5.0,
    'total': 52.2,
    'startedBy': 'Ahmad Odeh',
  },
  actor: {'userId': 7, 'name': 'Sara Nasser', 'source': 'ACTIVITY_LOG'},
  at: '2026-11-22T10:25:00',
  action: {'type': 'INVOICE', 'id': 1},
);

final _loss = _alert(
  'G2b:sale:2',
  'G2b',
  'CONFIRMED_LOSS',
  'HIGH',
  'PROFIT',
  {
    'invoiceNumber': 'INV-000002',
    'subtotal': 10.0,
    'discount': 0.0,
    'netRevenue': 10.0,
    'cost': 15.0,
    'loss': 5.0,
    'lines': 1,
    'startedBy': null,
  },
  at: '2026-11-22T11:00:00',
  action: {'type': 'INVOICE', 'id': 2},
);

final _low = _alert(
  'G5:low',
  'G5',
  'LOW_STOCK',
  'MEDIUM',
  'STOCK',
  {
    'count': 1,
    'products': ['Milk'],
  },
  action: {'type': 'STOCK_ALERTS', 'id': null},
);

final _deletions = _alert(
  'G9:user:3:2026-11-22',
  'G9',
  'PRODUCT_DELETIONS',
  'HIGH',
  'ACTIVITY',
  {
    'count': 10,
    'mediumLimit': 5,
    'highLimit': 10,
    'products': ['Old 1'],
    'day': '2026-11-22',
  },
  actor: {'userId': 3, 'name': 'Shini Manager', 'source': 'ACTIVITY_LOG'},
  at: '2026-11-22T12:13:00',
  action: {'type': 'ACTIVITY', 'id': null},
);

final _oldCancels = _alert(
  'G7:day:2026-11-21',
  'G7',
  'CANCELED_INVOICES',
  'HIGH',
  'ACTIVITY',
  {
    'count': 6,
    'completed': 5,
    'percent': 54.5,
    'minCount': 6,
    'minPercent': 20,
    'day': '2026-11-21',
    'byUser': <Object>[],
    'notRecorded': 6,
  },
  actorNote: 'NOT_RECORDED',
  at: '2026-11-21T15:00:00',
);

Map<String, dynamic> _dashboard({
  bool enoughData = true,
  bool current = true,
}) => {
  'from': '2026-11-01',
  'to': '2026-11-22',
  'currentPeriod': current,
  'todaySales': {
    'today': 12450.0,
    'yesterdaySameTime': 10550.85,
    'changePercent': 18.0,
    'last7Days': [
      for (var i = 16; i <= 22; i++)
        {'date': '2026-11-$i', 'netSales': 9000.0 + i * 100},
    ],
  },
  'criticalCount': 2,
  'profitRiskCount': 1,
  'costData': {
    'invoices': 40,
    'completeCostInvoices': 30,
    'incompleteCostInvoices': 10,
    'confirmedLossInvoices': 1,
    'productsWithoutCost': 2,
  },
  'health': enoughData
      ? {
          'score': 92,
          'band': 'GOOD',
          'partsUsed': 4,
          'reason': null,
          'parts': [
            {
              'part': 'STOCK',
              'available': true,
              'points': 21.25,
              'reason': null,
              'numbers': {'healthyProducts': 34, 'products': 40},
            },
            {
              'part': 'PROFIT',
              'available': true,
              'points': 24.44,
              'reason': null,
              'numbers': {
                'invoices': 120,
                'completeCostInvoices': 90,
                'confirmedLossInvoices': 2,
              },
            },
            {
              'part': 'DISCOUNTS',
              'available': true,
              'points': 23.75,
              'reason': null,
              'numbers': {'invoices': 120, 'highDiscountInvoices': 6},
            },
            {
              'part': 'ACTIVITY',
              'available': true,
              'points': 22.5,
              'reason': null,
              'numbers': {'activeDays': 20, 'daysWithAlert': 2},
            },
          ],
        }
      : {
          'score': null,
          'band': null,
          'partsUsed': 1,
          'reason': 'INSUFFICIENT_DATA',
          'parts': [
            {
              'part': 'STOCK',
              'available': true,
              'points': 18.75,
              'reason': null,
              'numbers': {'healthyProducts': 3, 'products': 4},
            },
            for (final p in ['PROFIT', 'DISCOUNTS', 'ACTIVITY'])
              {
                'part': p,
                'available': false,
                'points': null,
                'reason': 'NOT_ENOUGH_INVOICES',
                'numbers': {'invoices': 3, 'minInvoices': 5},
              },
          ],
        },
  'topAlerts': [_loss, _discount, _low],
  'allAlerts': [_loss, _deletions, _discount, _low, _oldCancels],
  'alertCount': 4,
  'recommendations': [
    {
      'rule': 'T1',
      'code': 'REORDER_SOON',
      'count': 1,
      'products': [
        {
          'productId': 1,
          'name': 'Milk',
          'quantity': 8,
          'daysLeft': 2.7,
          'expectedStockout': '2026-11-24',
        },
      ],
    },
    {
      'rule': 'T2',
      'code': 'LOW_MARGIN',
      'count': 1,
      'products': [
        {
          'productId': 2,
          'name': 'Cheese',
          'price': 10.0,
          'cost': 9.5,
          'marginPercent': 5.0,
        },
      ],
    },
    {'rule': 'T3', 'code': 'SLOW_MOVING', 'count': 0, 'products': <Object>[]},
  ],
  'storeMap': [
    {
      'kind': 'CATEGORY',
      'category': 'dairy',
      'percent': 33,
      'band': 'RISK',
      'products': 3,
      'healthyProducts': 1,
      'highAlerts': 0,
      'mediumAlerts': 0,
    },
    {
      'kind': 'CASHIER_AREA',
      'category': null,
      'percent': null,
      'band': 'ATTENTION',
      'products': 0,
      'healthyProducts': 0,
      'highAlerts': 0,
      'mediumAlerts': 1,
    },
  ],
  'atRiskProducts': [
    {
      'productId': 1,
      'name': 'Milk',
      'quantity': 8,
      'minStock': 10,
      'expectedStockout': '2026-11-24',
      'daysLeft': 2.7,
      'unitsPerDay': 3.0,
      'prediction': 'OK',
      'risk': 'HIGH',
    },
    {
      'productId': 3,
      'name': 'Eggs',
      'quantity': 0,
      'minStock': 5,
      'expectedStockout': null,
      'daysLeft': 0.0,
      'unitsPerDay': null,
      'prediction': 'OUT_OF_STOCK',
      'risk': 'HIGH',
    },
  ],
  'profitProtection': [
    {
      'productId': 2,
      'name': 'Cheese',
      'cost': 9.5,
      'price': 10.0,
      'maxDiscountPercent': 10.0,
      'priceAfterMaxDiscount': 9.0,
      'safeDiscountPercent': 5.0,
      'status': 'MAX_DISCOUNT_RISK',
    },
  ],
  'activity': {
    'highDiscounts': 1,
    'cancellations': 3,
    'returns': 0,
    'deletions': 5,
    'total': 9,
    'days': 22,
    'perDay': 0.4,
    'trackedSince': '2026-11-01T09:00:00',
  },
};

Future<void> _open(
  WidgetTester tester,
  FakeProductBackend backend, {
  String user = 'shini.admin',
  String password = 'Admin-Pass-1',
  Size size = const Size(1440, 1000),
  Locale? locale,
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
}

Future<void> _tap(WidgetTester tester, Finder f) async {
  await tester.ensureVisible(f);
  await tester.pumpAndSettle();
  await tester.tap(f);
  await tester.pumpAndSettle();
}

Future<void> _tapKey(WidgetTester tester, String key) =>
    _tap(tester, find.byKey(Key(key)));

/// A backend whose store 1 has Guardian Mode ON with the sample dashboard.
FakeProductBackend _onBackend({bool enoughData = true}) {
  final b = _backend();
  b.guardianOn[1] = true;
  b.guardianDashboards[1] = _dashboard(enoughData: enoughData);
  return b;
}

void main() {
  setUp(() => ReportClock.now = () => DateTime(2026, 11, 22, 15));
  tearDown(() => ReportClock.now = DateTime.now);

  testWidgets('off by default: Products is the first page; turning it on asks '
      'first and then shows the dashboard', (tester) async {
    final backend = _backend()..guardianDashboards[1] = _dashboard();
    await _open(tester, backend);
    expect(find.byKey(const Key('guardianPage')), findsNothing);
    expect(find.byKey(const Key('nav-guardian')), findsOneWidget);

    await _tapKey(tester, 'nav-guardian');
    expect(find.byKey(const Key('guardianOff')), findsOneWidget);
    expect(find.byKey(const Key('guardianTrackedSince')), findsOneWidget);
    expect(find.textContaining('2026-11-01'), findsOneWidget);
    expect(find.byKey(const Key('guardianCardSales')), findsNothing);

    await _tapKey(tester, 'guardianEnable');
    expect(find.byKey(const Key('guardianEnableDialog')), findsOneWidget);
    await _tap(tester, find.text('Cancel'));
    expect(backend.guardianOn[1], isNot(true)); // nothing changed
    await _tapKey(tester, 'guardianEnable');
    await _tapKey(tester, 'guardianConfirmToggle');
    expect(backend.requests, contains('PUT /api/guardian/status'));
    expect(backend.guardianOn[1], isTrue);
    expect(find.byKey(const Key('guardianCardSales')), findsOneWidget);
    expect(find.text('₪ 12,450.00'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('when it is on, Guardian Mode is the Store Admin\'s first page', (
    tester,
  ) async {
    await _open(tester, _onBackend());
    expect(find.byKey(const Key('guardianPage')), findsOneWidget);
    expect(find.byKey(const Key('guardianSwitch')), findsOneWidget);
  });

  testWidgets('every section shows exactly the backend numbers (English)', (
    tester,
  ) async {
    await _open(tester, _onBackend());
    // Cards.
    expect(find.text('+18% vs yesterday (same time)'), findsOneWidget);
    expect(find.text('2'), findsWidgets); // critical
    expect(find.text('92/100'), findsOneWidget);
    expect(find.text('Good'), findsWidgets);
    expect(
      find.text('10 invoice(s) with missing cost: profit not judged'),
      findsOneWidget,
    );
    expect(find.byKey(const Key('guardianRuleBased')), findsOneWidget);
    // Smart alerts with their explanation and the recorded user.
    expect(find.text('Smart alerts (4)'), findsOneWidget);
    expect(
      find.text('Completed by: Sara Nasser · started by: Ahmad Odeh'),
      findsOneWidget,
    );
    expect(
      find.textContaining('Limit: 7.5% = 75% of the maximum 10%'),
      findsOneWidget,
    );
    expect(find.textContaining('cost ₪ 15.00 → loss ₪ 5.00'), findsOneWidget);
    // Recommendations: rule-based, and purchase orders visibly not available.
    expect(find.text('Smart recommendations'), findsOneWidget);
    expect(find.byKey(const Key('guardianRec-REORDER_SOON')), findsOneWidget);
    expect(find.byKey(const Key('guardianRec-SLOW_MOVING')), findsOneWidget);
    expect(find.text('No products right now.'), findsOneWidget);
    final purchase = tester.widget<ButtonStyleButton>(
      find.byKey(const Key('guardianPurchaseDisabled')),
    );
    expect(purchase.onPressed, isNull);
    expect(
      find.textContaining('Purchase requests are not available yet'),
      findsOneWidget,
    );
    // Map, at-risk table, profit protection, unusual activity.
    expect(find.byKey(const Key('guardianTile-dairy')), findsOneWidget);
    expect(find.text('Stock health 33%'), findsOneWidget);
    expect(find.byKey(const Key('guardianTile-cashier')), findsOneWidget);
    expect(find.byKey(const Key('guardianRisk-1')), findsOneWidget);
    expect(find.text('2026-11-24'), findsWidgets);
    expect(find.text('Out of stock'), findsWidgets);
    expect(find.byKey(const Key('guardianProfitItem-2')), findsOneWidget);
    expect(find.textContaining('Safe discount: up to 5%'), findsOneWidget);
    expect(find.byKey(const Key('guardianWithoutCost')), findsOneWidget);
    expect(find.byKey(const Key('guardianActivityTotal')), findsOneWidget);
    expect(find.text('0.4 case(s) / day'), findsOneWidget);
    // Nothing pretends to be AI or an action that does not exist.
    expect(find.text('Clixsell AI'), findsNothing);
    expect(find.text('Apply discount'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('Arabic: Figure 3.1 order from the right (RTL)', (tester) async {
    await _open(tester, _onBackend(), locale: const Locale('ar'));
    expect(find.text('وضع الحارس الذكي'), findsOneWidget);
    expect(find.text('مبيعات اليوم'), findsOneWidget);
    expect(find.text('تنبيهات ذكية (4)'), findsOneWidget);
    expect(find.text('التوصيات الذكية'), findsOneWidget);
    expect(find.text('خريطة المتجر الحية'), findsOneWidget);
    expect(find.text('المنتجات الأكثر عرضة للخطر'), findsOneWidget);
    expect(
      find.text('أكملها: Sara Nasser · بدأها: Ahmad Odeh'),
      findsOneWidget,
    );
    double x(String key) => tester.getCenter(find.byKey(Key(key))).dx;
    // Cards: today's sales on the right … health on the left.
    expect(x('guardianCardSales'), greaterThan(x('guardianCardCritical')));
    expect(x('guardianCardProfit'), greaterThan(x('guardianCardHealth')));
    // Columns: alerts (right), recommendations (middle), map (left).
    expect(x('guardianAlerts'), greaterThan(x('guardianRecommendations')));
    expect(x('guardianRecommendations'), greaterThan(x('guardianMap')));
    expect(x('guardianActivity'), greaterThan(x('guardianAtRisk')));
    expect(x('guardianAtRisk'), greaterThan(x('guardianProfit')));
    expect(tester.takeException(), isNull);
  });

  testWidgets('not enough data: no health number, the reasons are shown', (
    tester,
  ) async {
    await _open(tester, _onBackend(enoughData: false));
    expect(find.text('—'), findsWidgets);
    expect(find.text('Not enough data'), findsWidgets);
    await _tapKey(tester, 'guardianHealthDetails');
    expect(find.byKey(const Key('guardianHealthDialog')), findsOneWidget);
    expect(
      find.text('Not available: fewer than 5 completed invoices'),
      findsNWidgets(3),
    );
    expect(find.text('3 of 4 products not low / out of stock'), findsOneWidget);
  });

  testWidgets('health breakdown of a full score', (tester) async {
    await _open(tester, _onBackend());
    await _tapKey(tester, 'guardianHealthDetails');
    expect(find.text('21.25 / 25'), findsOneWidget);
    expect(find.text('24.44 / 25'), findsOneWidget);
    expect(find.text('6 high discount(s) in 120 invoices'), findsOneWidget);
  });

  testWidgets('choosing a past month asks the backend for that month', (
    tester,
  ) async {
    final backend = _onBackend();
    await _open(tester, backend);
    expect(backend.lastGuardianQuery, {'year': '2026', 'month': '11'});
    backend.guardianDashboards[1] = _dashboard(current: false);
    await _tap(tester, find.byKey(const ValueKey('guardianMonthSelect-11')));
    await _tap(tester, find.text('October').last);
    expect(backend.lastGuardianQuery, {'year': '2026', 'month': '10'});
    expect(find.byKey(const Key('guardianPastNote')), findsOneWidget);
  });

  testWidgets('buttons open the existing screens', (tester) async {
    final backend = _onBackend();
    await _open(tester, backend);
    // A real sale (id 1) so "View invoice" has an invoice to show.
    await _tapKey(tester, 'nav-pos');
    await _tapKey(tester, 'posProduct-2');
    await _tapKey(tester, 'completeSaleButton');
    await _tapKey(tester, 'receiptCloseButton');
    await _tapKey(tester, 'nav-guardian');

    await _tapKey(tester, 'guardianAction-G1:sale:1');
    expect(backend.requests, contains('GET /api/sales/1'));
    expect(find.byKey(const Key('receiptDialog')), findsOneWidget);
    await _tapKey(tester, 'receiptCloseButton');

    await _tapKey(
      tester,
      'guardianRisk-1',
    ); // at-risk product → Product Details
    expect(find.byKey(const Key('productDetailsPage')), findsOneWidget);
    await _tapKey(tester, 'nav-guardian');

    await _tapKey(tester, 'guardianAllProducts'); // → Stock Alerts
    expect(find.byKey(const Key('stockAlertsPage')), findsOneWidget);
    await _tapKey(tester, 'nav-guardian');

    await _tapKey(tester, 'guardianRecOpen-LOW_MARGIN'); // → product list
    expect(
      find.byKey(const Key('guardianRecDialog-LOW_MARGIN')),
      findsOneWidget,
    );
    await _tapKey(tester, 'guardianRecProduct-2');
    expect(find.byKey(const Key('productDetailsPage')), findsOneWidget);
    await _tapKey(tester, 'nav-guardian');

    await _tapKey(tester, 'guardianViewActivity'); // → activity report
    expect(find.byKey(const Key('guardianActivityDialog')), findsOneWidget);
    await _tapKey(tester, 'guardianDialogClose');

    // A product deleted meanwhile: a clear message, no crash.
    backend.rows.removeWhere((r) => r['productId'] == 2);
    await _tap(
      tester,
      find.descendant(
        of: find.byKey(const Key('guardianProfitItem-2')),
        matching: find.text('View product'),
      ),
    );
    expect(find.text('This product no longer exists.'), findsOneWidget);
    expect(find.byKey(const Key('guardianPage')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('view all alerts, filter by severity, act from the list', (
    tester,
  ) async {
    await _open(tester, _onBackend());
    await _tapKey(tester, 'guardianAllAlerts');
    expect(find.byKey(const Key('guardianAllAlertsDialog')), findsOneWidget);
    expect(
      find.byKey(const Key('guardianAlert-G9:user:3:2026-11-22')),
      findsOneWidget,
    );
    // Cancellations before the activity log: no names, said plainly.
    await tester.scrollUntilVisible(
      find.byKey(const Key('guardianAlert-G7:day:2026-11-21')),
      200,
      scrollable: find.descendant(
        of: find.byKey(const Key('guardianAllAlertsDialog')),
        matching: find.byType(Scrollable),
      ),
    );
    expect(
      find.descendant(
        of: find.byKey(const Key('guardianAlert-G7:day:2026-11-21')),
        matching: find.text(
          'User not recorded (before the activity log started)',
        ),
      ),
      findsOneWidget,
    );
    await _tapKey(tester, 'guardianFilter-high');
    final dialog = find.byKey(const Key('guardianAllAlertsDialog'));
    expect(
      find.descendant(
        of: dialog,
        matching: find.byKey(const Key('guardianAlert-G1:sale:1')),
      ),
      findsNothing,
    );
    expect(
      find.descendant(
        of: dialog,
        matching: find.byKey(const Key('guardianAlert-G2b:sale:2')),
      ),
      findsOneWidget,
    );
    await _tap(
      tester,
      find.descendant(
        of: dialog,
        matching: find.byKey(const Key('guardianAction-G9:user:3:2026-11-22')),
      ),
    );
    expect(find.byKey(const Key('guardianAllAlertsDialog')), findsNothing);
    expect(find.byKey(const Key('guardianActivityDialog')), findsOneWidget);
    // The critical card opens the same list already filtered.
    await _tapKey(tester, 'guardianDialogClose');
    await _tapKey(tester, 'guardianViewCritical');
    expect(
      tester
          .widget<ChoiceChip>(find.byKey(const Key('guardianFilter-high')))
          .selected,
      isTrue,
    );
  });

  testWidgets(
    'turning it off asks first, keeps the data and shows who changed it',
    (tester) async {
      final backend = _onBackend();
      await _open(tester, backend);
      await _tapKey(tester, 'guardianSwitch');
      expect(find.byKey(const Key('guardianDisableDialog')), findsOneWidget);
      await _tap(tester, find.text('Cancel'));
      expect(backend.guardianOn[1], isTrue);
      await _tapKey(tester, 'guardianSwitch');
      await _tapKey(tester, 'guardianConfirmToggle');
      expect(backend.guardianOn[1], isFalse);
      expect(find.byKey(const Key('guardianOff')), findsOneWidget);
      expect(
        find.textContaining('Last changed by Shini Manager'),
        findsOneWidget,
      );
      expect(backend.guardianDashboards[1], isNotNull); // nothing deleted
    },
  );

  testWidgets('Cashier and System Admin have no Guardian Mode', (tester) async {
    final backend = _onBackend();
    await _open(
      tester,
      backend,
      user: 'shini.cashier',
      password: 'Cashier-Pass-1',
    );
    expect(find.byKey(const Key('nav-guardian')), findsNothing);
    expect(find.byKey(const Key('guardianPage')), findsNothing);
    expect(backend.requests.where((r) => r.contains('/api/guardian')), isEmpty);
  });

  testWidgets('System Admin has no Guardian Mode', (tester) async {
    final backend = _onBackend();
    await _open(tester, backend, user: 'sysadmin', password: 'Admin-Pass-1');
    expect(find.byKey(const Key('nav-guardian')), findsNothing);
    expect(backend.requests.where((r) => r.contains('/api/guardian')), isEmpty);
  });

  testWidgets('another store never sees this store\'s Guardian data', (
    tester,
  ) async {
    final backend = _onBackend();
    final neshan = backend.addStore('Neshan Market');
    backend.addUser('neshan.admin', 'Admin-Pass-1', 'STORE_ADMIN', neshan);
    backend.guardianOn[neshan] = true;
    await _open(tester, backend, user: 'neshan.admin');
    expect(find.byKey(const Key('guardianPage')), findsOneWidget);
    expect(find.textContaining('Sara Nasser'), findsNothing);
    expect(find.text('₪ 12,450.00'), findsNothing);
    expect(find.text('No alerts for this period.'), findsOneWidget);
    expect(find.text('Smart alerts (0)'), findsOneWidget);
  });

  for (final size in const [
    Size(1440, 1000),
    Size(1024, 768),
    Size(390, 844),
  ]) {
    for (final locale in const [Locale('en'), Locale('ar')]) {
      testWidgets('no overflow at $size (${locale.languageCode})', (
        tester,
      ) async {
        await _open(tester, _onBackend(), size: size, locale: locale);
        expect(find.byKey(const Key('guardianPage')), findsOneWidget);
        expect(tester.takeException(), isNull);
        // The off state too.
        await _tapKey(tester, 'guardianSwitch');
        await _tapKey(tester, 'guardianConfirmToggle');
        expect(find.byKey(const Key('guardianOff')), findsOneWidget);
        expect(tester.takeException(), isNull);
      });
    }
  }
}

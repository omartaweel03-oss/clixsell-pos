import 'dart:convert';

import 'package:clixsell_pos/screens/shifts/pos_shift_bar.dart';
import 'package:clixsell_pos/screens/shifts/shift_dialogs.dart';
import 'package:clixsell_pos/screens/shifts/shifts_page.dart';
import 'package:clixsell_pos/services/api_http.dart';
import 'package:clixsell_pos/services/shift_api_client.dart';
import 'package:clixsell_pos/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

/// A small imitation of /api/drawers and /api/shifts with the backend's JSON shapes.
/// Like the backend, IT calculates the expected cash; the app only shows it.
class FakeShiftServer {
  bool requireCashShift = false;
  int cashRoundingMinor = 0;
  final drawers = <Map<String, dynamic>>[
    {'drawerId': 1, 'name': 'Till 1', 'active': true, 'inUse': false},
  ];
  Map<String, dynamic>? openShift;
  final closed = <Map<String, dynamic>>[];
  final movements = <Map<String, dynamic>>[];
  final requests = <http.Request>[];
  String? failNextWithCode;
  int failNextStatus = 409;

  Map<String, dynamic> _shift({double opening = 100}) => {
    'shiftId': 7,
    'drawerId': 1,
    'drawerName': 'Till 1',
    'cashierId': 3,
    'cashierName': 'Ahmad Saleh',
    'status': 'OPEN',
    'openedAt': '2026-11-28T08:00:00',
    'openingCash': opening,
    'cashSales': 74.0,
    'cashSalesCount': 2,
    'cashRefunds': 37.0,
    'cashRefundsCount': 1,
    'cashIn': 20.0,
    'cashOut': 30.5,
    'chequeRefundsPaid': 0,
    'otherSales': 37.0,
    'otherSalesCount': 1,
    'expectedCash': opening + 74 - 37 + 20 - 30.5,
    'version': 0,
  };

  http.Response _json(Object? body, [int status = 200]) => http.Response(
    jsonEncode(body),
    status,
    headers: {'content-type': 'application/json; charset=utf-8'},
  );

  Future<http.Response> handle(http.Request r) async {
    requests.add(r);
    final code = failNextWithCode;
    if (code != null && r.method != 'GET') {
      failNextWithCode = null;
      return _json({'status': failNextStatus, 'code': code, 'message': 'backend text', 'fieldErrors': {}},
          failNextStatus);
    }
    final p = r.url.path.replaceFirst('/api', '');
    final body = r.body.isEmpty ? null : jsonDecode(r.body) as Map<String, dynamic>;
    if (p == '/shifts/current') {
      return _json({'shift': openShift, 'requireCashShift': requireCashShift, 'drawers': drawers});
    }
    if (p == '/shifts' && r.method == 'POST') {
      openShift = _shift(opening: (body!['openingCash'] as num).toDouble());
      drawers.first['inUse'] = true;
      return _json(openShift, 201);
    }
    if (p == '/shifts' && r.method == 'GET') {
      return _json([?openShift, ...closed]);
    }
    if (RegExp(r'^/shifts/\d+/(admin-)?close$').hasMatch(p)) {
      final s = Map<String, dynamic>.of(openShift!);
      final counted = (body!['countedCash'] as num).toDouble();
      s
        ..['status'] = 'CLOSED'
        ..['closedAt'] = '2026-11-28T16:00:00'
        ..['closedByName'] = p.contains('admin') ? 'Shini Manager' : 'Ahmad Saleh'
        ..['closeKind'] = p.contains('admin') ? 'ADMIN_EXCEPTION' : 'CASHIER'
        ..['closeReason'] = body['reason']
        ..['countedCash'] = counted
        ..['difference'] = counted - (s['expectedCash'] as num)
        ..['closingNote'] = body['note']
        ..['version'] = 1;
      openShift = null;
      drawers.first['inUse'] = false;
      closed.add(s);
      return _json(s);
    }
    if (RegExp(r'^/shifts/\d+/movements$').hasMatch(p)) {
      final again = movements.where((m) => m['requestKey'] == body!['requestKey']);
      if (again.isNotEmpty) return _json(again.first, 201);
      final m = {
        'movementId': movements.length + 1,
        'type': body!['type'],
        'amountMinor': ((body['amount'] as num) * 100).round(),
        'reason': body['reason'],
        'createdBy': 2,
        'createdByName': 'Shini Manager',
        'createdAt': '2026-11-28T12:00:00',
        'requestKey': body['requestKey'],
      };
      movements.add(m);
      return _json(m, 201);
    }
    if (RegExp(r'^/shifts/\d+$').hasMatch(p)) {
      return _json({'shift': openShift ?? closed.last, 'sales': [], 'movements': movements});
    }
    if (p == '/drawers' && r.method == 'POST') {
      final d = {'drawerId': drawers.length + 1, 'name': body!['name'], 'active': true, 'inUse': false};
      drawers.add(d);
      return _json(d, 201);
    }
    if (p == '/store') {
      return _json({'requireCashShift': requireCashShift, 'cashRoundingMinor': cashRoundingMinor});
    }
    if (p == '/store/settings') {
      if (body!.containsKey('requireCashShift')) requireCashShift = body['requireCashShift'] == true;
      if (body.containsKey('cashRoundingMinor')) cashRoundingMinor = body['cashRoundingMinor'] as int;
      return _json({'requireCashShift': requireCashShift, 'cashRoundingMinor': cashRoundingMinor});
    }
    return _json({'status': 404, 'code': 'NOT_FOUND', 'message': 'no', 'fieldErrors': {}}, 404);
  }

  ShiftApiClient client() => ShiftApiClient(
    ApiHttp(baseUrl: 'http://fake/api', client: MockClient(handle)),
  );

  List<Map<String, dynamic>> sent(String method, Pattern path) => [
    for (final r in requests)
      if (r.method == method && r.url.path.contains(path))
        r.body.isEmpty ? <String, dynamic>{} : jsonDecode(r.body) as Map<String, dynamic>,
  ];
}

Future<void> _pump(WidgetTester tester, Widget page, {Locale locale = const Locale('en')}) async {
  tester.view.physicalSize = const Size(1440, 1000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(MaterialApp(
    theme: AppTheme.light(),
    locale: locale,
    supportedLocales: const [Locale('en'), Locale('ar')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    home: Scaffold(body: page),
  ));
  await tester.pumpAndSettle();
}

Finder _k(String k) => find.byKey(Key(k));

Future<void> _tap(WidgetTester tester, Finder f) async {
  await tester.ensureVisible(f);
  await tester.tap(f);
  await tester.pumpAndSettle();
}

void main() {
  test('amounts: 0 allowed, at most 2 decimals, Arabic digits accepted', () {
    expect(parseShiftCents('0'), 0);
    expect(parseShiftCents('100'), 10000);
    expect(parseShiftCents('12.5'), 1250);
    expect(parseShiftCents('١٢٫٣٤'), 1234);
    expect(parseShiftCents('12,34'), 1234);
    expect(parseShiftCents('1.001'), isNull);
    expect(parseShiftCents('-1'), isNull);
    expect(parseShiftCents('abc'), isNull);
    expect(ShiftApiClient.amount(12345), '123.45');
    expect(ShiftApiClient.amount(5), '0.05');
  });

  group('POS shift bar', () {
    testWidgets('opens a shift: sends the drawer and the counted opening cash', (tester) async {
      final server = FakeShiftServer();
      final messages = <String>[];
      await _pump(tester, PosShiftBar(api: server.client(), onMessage: (m, {error = false}) => messages.add(m)));
      expect(find.text('No open shift'), findsOneWidget);
      await _tap(tester, _k('posOpenShift'));
      expect(_k('openShiftDialog'), findsOneWidget);
      expect(find.textContaining('does not open a physical drawer'), findsOneWidget);
      await tester.enterText(_k('openingCashField'), '100.00');
      await _tap(tester, _k('openShiftConfirm'));
      expect(server.sent('POST', '/shifts'), [
        {'drawerId': 1, 'openingCash': 100},
      ]);
      expect(find.textContaining('Shift open · Till 1'), findsOneWidget);
      expect(messages.single, 'Shift opened on Till 1');
    });

    testWidgets('an invalid opening amount is refused before anything is sent', (tester) async {
      final server = FakeShiftServer();
      await _pump(tester, PosShiftBar(api: server.client()));
      await _tap(tester, _k('posOpenShift'));
      await tester.enterText(_k('openingCashField'), '1.001');
      await _tap(tester, _k('openShiftConfirm'));
      expect(_k('shiftError'), findsOneWidget);
      expect(server.sent('POST', '/shifts'), isEmpty);
    });

    testWidgets('closing: expected from the backend, difference shown, a difference needs a note', (tester) async {
      final server = FakeShiftServer()..openShift = null;
      server.openShift = {...FakeShiftServer()._shift()};
      final messages = <String>[];
      await _pump(tester, PosShiftBar(api: server.client(), onMessage: (m, {error = false}) => messages.add(m)));
      await _tap(tester, _k('posCloseShift'));
      // 100 + 74 − 37 + 20 − 30.50 = 126.50, as the backend sent it
      expect(tester.widget<Text>(_k('shiftExpected')).data, contains('126.50'));
      await tester.enterText(_k('countedCashField'), '125.50');
      await tester.pump();
      expect(tester.widget<Text>(_k('shiftDifference')).data, contains('-1.00'));
      await _tap(tester, _k('closeShiftConfirm'));
      expect(find.textContaining('explain the difference'), findsOneWidget);
      expect(server.sent('POST', '/close'), isEmpty);
      await tester.enterText(_k('closingNoteField'), '1.00 missing');
      await _tap(tester, _k('closeShiftConfirm'));
      expect(server.sent('POST', '/close'), [
        {'countedCash': 125.5, 'note': '1.00 missing', 'version': 0},
      ]);
      expect(messages.single, contains('-1.00'));
      expect(find.text('No open shift'), findsOneWidget);
    });

    testWidgets('a store that requires shifts shows the warning; Arabic, right to left', (tester) async {
      final server = FakeShiftServer()..requireCashShift = true;
      await _pump(tester, PosShiftBar(api: server.client()), locale: const Locale('ar'));
      expect(find.text('هذا المتجر يتطلب وردية نقدية مفتوحة قبل البيع.'), findsOneWidget);
      expect(Directionality.of(tester.element(_k('posShiftBar'))), TextDirection.rtl);
      expect(find.text('فتح وردية'), findsOneWidget);
    });

    testWidgets("the backend's refusal is shown in the user's language", (tester) async {
      final server = FakeShiftServer()..failNextWithCode = 'DRAWER_IN_USE';
      await _pump(tester, PosShiftBar(api: server.client()), locale: const Locale('ar'));
      await _tap(tester, _k('posOpenShift'));
      await _tap(tester, _k('openShiftConfirm'));
      expect(find.text('هذا الدرج عليه وردية مفتوحة.'), findsOneWidget);
      expect(_k('openShiftDialog'), findsOneWidget); // nothing opened, the dialog stays
    });
  });

  group('Store Admin: cash shifts page', () {
    testWidgets('mandatory switch, add drawer, shift list with difference', (tester) async {
      final server = FakeShiftServer();
      server.openShift = server._shift();
      await _pump(tester, ShiftsPage(api: server.client()));
      expect(find.text('Cash shifts'), findsOneWidget);
      await _tap(tester, _k('requireShiftSwitch'));
      expect(server.sent('PUT', '/store/settings'), [
        {'requireCashShift': true},
      ]);
      expect(tester.widget<SwitchListTile>(_k('requireShiftSwitch')).value, isTrue);

      await _tap(tester, _k('addDrawer'));
      await tester.enterText(_k('drawerNameField'), 'Till 2');
      await _tap(tester, _k('drawerNameSave'));
      expect(server.sent('POST', '/drawers'), [
        {'name': 'Till 2'},
      ]);
      expect(find.text('Till 2'), findsOneWidget);
      expect(_k('shift-7'), findsOneWidget);
    });

    testWidgets('cash out from the details: one request key, so a retry records nothing twice', (tester) async {
      final server = FakeShiftServer();
      server.openShift = server._shift();
      await _pump(tester, ShiftsPage(api: server.client()));
      await _tap(tester, _k('shift-7'));
      expect(_k('shiftDetailsDialog'), findsOneWidget);
      await _tap(tester, _k('detailsCashOut'));
      await tester.enterText(_k('movementAmountField'), '50');
      await tester.enterText(_k('movementReasonField'), 'Bank deposit');
      server
        ..failNextWithCode = 'NETWORK_GLITCH'
        ..failNextStatus = 503;
      await _tap(tester, _k('movementConfirm'));
      expect(_k('cashMovementDialog'), findsOneWidget); // the first attempt failed
      await _tap(tester, _k('movementConfirm'));
      final sent = server.sent('POST', '/movements');
      expect(sent, hasLength(2));
      expect(sent[0]['requestKey'], sent[1]['requestKey']);
      expect(sent[1], containsPair('type', 'CASH_OUT'));
      expect(sent[1], containsPair('amount', 50));
      expect(server.movements, hasLength(1));
    });

    testWidgets('exceptional closing by the Store Admin needs a reason', (tester) async {
      final server = FakeShiftServer();
      server.openShift = server._shift();
      await _pump(tester, ShiftsPage(api: server.client()));
      await _tap(tester, _k('shift-7'));
      await _tap(tester, _k('detailsAdminClose'));
      await tester.enterText(_k('countedCashField'), '126.50');
      await _tap(tester, _k('closeShiftConfirm'));
      expect(find.textContaining('Enter the reason'), findsOneWidget);
      expect(server.sent('POST', 'admin-close'), isEmpty);
      await tester.enterText(_k('closeReasonField'), 'Cashier went home ill');
      await _tap(tester, _k('closeShiftConfirm'));
      expect(server.sent('POST', 'admin-close'), [
        {'countedCash': 126.5, 'reason': 'Cashier went home ill', 'version': 0},
      ]);
    });

    testWidgets('cash rounding step: none by default, the Store Admin chooses 0.10', (tester) async {
      final server = FakeShiftServer();
      await _pump(tester, ShiftsPage(api: server.client()));
      final seg = tester.widget<SegmentedButton<int>>(_k('cashRoundingStep'));
      expect(seg.selected, {0});
      await _tap(tester, find.text('0.10'));
      expect(server.sent('PUT', '/store/settings').last, {'cashRoundingMinor': 10});
      expect(tester.widget<SegmentedButton<int>>(_k('cashRoundingStep')).selected, {10});
    });

    testWidgets('Arabic, right to left', (tester) async {
      final server = FakeShiftServer();
      await _pump(tester, ShiftsPage(api: server.client()), locale: const Locale('ar'));
      expect(find.text('الورديات النقدية'), findsOneWidget);
      expect(find.text('إلزام وردية نقدية لكل عملية بيع'), findsOneWidget);
      expect(Directionality.of(tester.element(_k('shiftsPage'))), TextDirection.rtl);
      expect(tester.takeException(), isNull);
    });
  });
}

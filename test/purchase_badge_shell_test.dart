import 'dart:convert';

import 'package:clixsell_pos/models/app_user.dart';
import 'package:clixsell_pos/screens/app_shell.dart';
import 'package:clixsell_pos/services/api_http.dart';
import 'package:clixsell_pos/services/product_api_client.dart';
import 'package:clixsell_pos/state/pos_cart.dart';
import 'package:clixsell_pos/state/product_store.dart';
import 'package:clixsell_pos/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'support/fake_purchase_api.dart';

/// D2 (final corrections): the Purchasing badge in the sidebar shows the
/// server's exact overdue count right after login, after an order action, and
/// when the manager returns to the Purchasing section — with no timer polling.
/// The real AppShell talks to a mock HTTP server here.
class _Server {
  int overdue = 3;
  String status = 'MESSAGE_GENERATED';
  final List<String> requests = [];

  int get summaries =>
      requests.where((r) => r == 'GET /api/purchase-orders/summary').length;

  Future<http.Response> handle(http.Request r) async {
    final key = '${r.method} ${r.url.path}';
    requests.add(key);
    Object? body;
    if (r.url.path == '/api/purchase-orders/summary') {
      body = {
        'byStatus': {'PENDING_APPROVAL': 0, 'SENT': 1},
        'open': 1,
        'overdue': overdue,
        'overdueByStatus': {},
      };
    } else if (r.url.path == '/api/purchase-orders') {
      body = {
        'page': 0,
        'size': 20,
        'total': 1,
        'orders': [
          {
            'orderId': 1,
            'orderNumber': 'PO-000001',
            'status': status,
            'source': 'MANUAL',
            'supplierId': 7,
            'supplierName': 'Dairy Co',
            'itemCount': 1,
            'createdBy': {'userId': 1, 'fullName': 'Ahmad'},
            'createdAt': '2026-11-20T09:00:00',
            'overdue': true,
            'version': 2,
          },
        ],
      };
    } else if (r.url.path == '/api/purchase-orders/1/events') {
      body = [];
    } else if (r.url.path == '/api/purchase-orders/1/mark-sent') {
      status = 'SENT';
      overdue = 2; // the server's count after the action
      body = orderJson(status: 'SENT', version: 3, message: messageJson());
    } else if (r.url.path == '/api/purchase-orders/1') {
      body = orderJson(
        status: status,
        version: status == 'SENT' ? 3 : 2,
        message: messageJson(),
      );
    } else if (r.url.path == '/api/store') {
      body = {'storeId': 1, 'name': 'Shini', 'status': 'ACTIVE', 'taxRate': 16};
    } else if (r.url.path == '/api/guardian/status') {
      body = {'enabled': false};
    } else if (r.url.path.startsWith('/api/products') ||
        r.url.path.startsWith('/api/suppliers')) {
      body = [];
    } else {
      body = {};
    }
    return http.Response(
      jsonEncode(body),
      200,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );
  }
}

Future<_Server> _pumpShell(
  WidgetTester tester, {
  UserRole role = UserRole.storeAdmin,
  Locale locale = const Locale('en'),
}) async {
  tester.view.physicalSize = const Size(1440, 1000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final server = _Server();
  final api = ApiHttp(
    baseUrl: 'http://x/api',
    client: MockClient(server.handle),
  )..token = 't';
  final user = AppUser(
    userId: 1,
    username: 'shini.admin',
    fullName: 'Ahmad',
    role: role,
    isActive: true,
    storeId: 1,
    storeName: 'Shini',
  );
  await tester.pumpWidget(
    MaterialApp(
      theme: AppTheme.light(),
      locale: locale,
      supportedLocales: const [Locale('en'), Locale('ar')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      home: ProductStoreScope(
        store: ProductStore(api: ProductApiClient(api)),
        child: PosCartScope(
          cart: PosCart(),
          child: AppShell(
            user: user,
            http: api,
            onToggleLanguage: () {},
            onSetLanguage: (_) {},
            onLogout: () {},
          ),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return server;
}

Finder _badge(String text) => find.descendant(
  of: find.byKey(const Key('nav-purchaseOrders')),
  matching: find.text(text),
);

Future<void> _tap(WidgetTester tester, Finder f) async {
  await tester.ensureVisible(f);
  await tester.tap(f);
  await tester.pumpAndSettle();
}

void main() {
  for (final locale in const [Locale('en'), Locale('ar')]) {
    testWidgets(
      'D2 (${locale.languageCode}): badge after login, after an action, on return; no polling',
      (tester) async {
        final server = await _pumpShell(tester, locale: locale);
        // after login / first authenticated screen
        expect(server.summaries, 1);
        expect(_badge('3'), findsOneWidget);

        // no timer: ten minutes later nothing was asked again
        await tester.pump(const Duration(minutes: 10));
        await tester.pumpAndSettle();
        expect(server.summaries, 1);

        // the list page reads the same exact summary
        server.overdue = 4;
        await _tap(tester, find.byKey(const Key('nav-purchaseOrders')));
        expect(_badge('4'), findsOneWidget);

        // an order action → refreshed once from the server
        await _tap(tester, find.byKey(const Key('po-open-1')));
        final before = server.summaries;
        await _tap(tester, find.byKey(const Key('poMarkSent')));
        await _tap(tester, find.byKey(const Key('checkDialogBox')));
        await _tap(tester, find.byKey(const Key('checkDialogConfirm')));
        expect(
          server.requests,
          contains('POST /api/purchase-orders/1/mark-sent'),
        );
        expect(server.summaries, before + 1);
        expect(_badge('2'), findsOneWidget);

        // leaving Purchasing, then returning to it (Suppliers) → refreshed
        await _tap(tester, find.byKey(const Key('nav-products')));
        server.overdue = 7;
        final beforeReturn = server.summaries;
        await _tap(tester, find.byKey(const Key('nav-suppliers')));
        expect(server.summaries, beforeReturn + 1);
        expect(_badge('7'), findsOneWidget);
        // moving inside Purchasing does not ask again
        await _tap(
          tester,
          find.byKey(const Key('nav-purchaseRecommendations')),
        );
        expect(server.summaries, beforeReturn + 1);
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets('a cashier never asks for purchase counts', (tester) async {
    final server = await _pumpShell(tester, role: UserRole.cashier);
    expect(server.summaries, 0);
  });
}

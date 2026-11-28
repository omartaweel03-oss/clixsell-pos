import 'dart:convert';

import 'package:clixsell_pos/models/sale.dart';
import 'package:clixsell_pos/screens/cheques/cheques_page.dart';
import 'package:clixsell_pos/screens/pos/cheque_payment_dialog.dart';
import 'package:clixsell_pos/services/api_http.dart';
import 'package:clixsell_pos/services/cheque_api_client.dart';
import 'package:clixsell_pos/services/sales_api_client.dart';
import 'package:clixsell_pos/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

Future<void> _pump(WidgetTester tester, Widget w, {Locale locale = const Locale('en')}) async {
  tester.view.physicalSize = const Size(1440, 1000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(MaterialApp(
    theme: AppTheme.light(),
    locale: locale,
    supportedLocales: const [Locale('en'), Locale('ar')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    home: Scaffold(body: w),
  ));
  await tester.pumpAndSettle();
}

Finder _k(String k) => find.byKey(Key(k));

Future<void> _tap(WidgetTester tester, Finder f) async {
  await tester.ensureVisible(f);
  await tester.tap(f);
  await tester.pumpAndSettle();
}

/// /api/cheques imitation: the lifecycle and refunds as the backend answers them.
class _ChequeServer {
  String status = 'RECEIVED';
  int version = 0;
  final events = <Map<String, dynamic>>[
    {'fromStatus': null, 'toStatus': 'RECEIVED', 'userName': 'Ahmad Saleh', 'createdAt': '2026-11-28T10:00:00', 'note': null},
  ];
  final refunds = <Map<String, dynamic>>[];
  final posts = <MapEntry<String, Map<String, dynamic>>>[];

  Map<String, dynamic> get cheque => {
    'chequeId': 1, 'saleId': 9, 'invoiceNumber': 'INV-000009', 'chequeNumber': '100245', 'bankName': 'Bank of Palestine',
    'amount': 1000.0, 'dueDate': '2026-12-01', 'notes': null, 'status': status, 'createdByName': 'Ahmad Saleh',
    'createdAt': '2026-11-28T10:00:00', 'updatedAt': '2026-11-28T10:00:00', 'version': version,
    'pendingRefunds': refunds.where((r) => r['status'] == 'PENDING').fold<double>(0, (a, r) => a + (r['amount'] as double)),
  };

  Map<String, dynamic> get details => {'cheque': cheque, 'events': events, 'refunds': refunds};

  http.Response _json(Object body, [int code = 200]) =>
      http.Response(jsonEncode(body), code, headers: {'content-type': 'application/json; charset=utf-8'});

  Future<http.Response> handle(http.Request r) async {
    final p = r.url.path.replaceFirst('/api', '');
    if (r.method == 'POST') {
      final b = jsonDecode(r.body) as Map<String, dynamic>;
      posts.add(MapEntry(p, b));
      if (p.endsWith('/status')) {
        events.add({'fromStatus': status, 'toStatus': b['toStatus'], 'userName': 'Shini Manager',
          'createdAt': '2026-11-28T12:00:00', 'note': b['note']});
        status = b['toStatus'] as String;
        version++;
      } else if (p.endsWith('/pay')) {
        if (status != 'CLEARED') {
          return _json({'status': 409, 'code': 'CHEQUE_NOT_CLEARED', 'message': 'x', 'fieldErrors': {}}, 409);
        }
        refunds.first..['status'] = 'PAID'..['paidMethod'] = b['method'];
      }
      return _json(details);
    }
    if (p == '/cheques/summary') {
      return _json({
        'countByStatus': {'RECEIVED': status == 'RECEIVED' ? 1 : 0, 'DEPOSITED': status == 'DEPOSITED' ? 1 : 0,
          'CLEARED': status == 'CLEARED' ? 1 : 0, 'BOUNCED': status == 'BOUNCED' ? 1 : 0, 'CANCELLED': 0},
        'amountByStatus': {'RECEIVED': 1000.0, 'DEPOSITED': 0.0, 'CLEARED': 0.0, 'BOUNCED': 0.0, 'CANCELLED': 0.0},
        'dueSoonCount': 1, 'dueSoonAmount': 1000.0, 'pendingRefundCount': refunds.length, 'pendingRefundAmount': 0.0,
      });
    }
    if (p == '/cheques/1') return _json(details);
    return _json([cheque]);
  }

  ChequeApiClient client() => ChequeApiClient(ApiHttp(baseUrl: 'http://x/api', client: MockClient(handle)));
}

void main() {
  test('the sale sends the cheque with the cheque payment', () {
    final d = const SaleDraft(lines: {'7': 2}, paymentMethod: PaymentMethod.cheque)
        .withCheque({'number': '100245', 'bank': 'Bank of Palestine', 'amount': 1000.0, 'dueDate': '2026-12-01'});
    expect(d.toJson()['paymentMethod'], 'CHEQUE');
    expect(d.toJson()['cheque'], {'number': '100245', 'bank': 'Bank of Palestine', 'amount': 1000.0, 'dueDate': '2026-12-01'});
    expect(PaymentMethod.fromApi('CHEQUE'), PaymentMethod.cheque);
  });

  testWidgets('checkout dialog: amount is the total; number, bank and due date are required', (tester) async {
    Map<String, dynamic>? result;
    await _pump(tester, Builder(builder: (ctx) => TextButton(
      onPressed: () async => result = await ChequePaymentDialog.show(ctx, 100000),
      child: const Text('pay'),
    )));
    await _tap(tester, find.text('pay'));
    expect(tester.widget<Text>(_k('chequeAmount')).data, contains('1,000.00'));
    await _tap(tester, _k('chequeConfirm'));
    expect(_k('chequeError'), findsOneWidget);
    await tester.enterText(_k('chequeNumberField'), '100245');
    await tester.enterText(_k('chequeBankField'), 'Bank of Palestine');
    await _tap(tester, _k('chequeDueButton'));
    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();
    await _tap(tester, _k('chequeConfirm'));
    expect(result!['number'], '100245');
    expect(result!['bank'], 'Bank of Palestine');
    expect(result!['amount'], 1000.0);
    expect(result!['dueDate'], matches(RegExp(r'^\d{4}-\d{2}-\d{2}$')));
  });

  testWidgets('Store Admin: lifecycle with a required note for BOUNCED; payout refused until cleared', (tester) async {
    final server = _ChequeServer();
    server.refunds.add({'chequeRefundId': 4, 'returnId': 2, 'amount': 500.0, 'status': 'PENDING', 'paidMethod': null,
      'decidedByName': null, 'decidedAt': null, 'reason': null, 'createdAt': '2026-11-28T11:00:00', 'version': 0});
    await _pump(tester, ChequesPage(api: server.client()));
    expect(find.text('Cheques'), findsOneWidget);
    await _tap(tester, _k('cheque-1'));
    expect(_k('chequeMove-DEPOSITED'), findsOneWidget);
    expect(_k('chequeMove-CLEARED'), findsNothing); // not offered from RECEIVED
    await _tap(tester, _k('chequeMove-DEPOSITED'));
    await _tap(tester, _k('chequeNoteSave')); // no note needed
    expect(server.posts.last.value, {'toStatus': 'DEPOSITED', 'version': 0});
    await _tap(tester, _k('chequeMove-BOUNCED'));
    await _tap(tester, _k('chequeNoteSave'));
    expect(find.textContaining('Explain why'), findsOneWidget); // a note is required
    await tester.enterText(_k('chequeNoteField'), 'Insufficient funds');
    await _tap(tester, _k('chequeNoteSave'));
    expect(server.posts.last.value, {'toStatus': 'BOUNCED', 'note': 'Insufficient funds', 'version': 1});
    // the history shows who did what
    expect(find.textContaining('Insufficient funds'), findsOneWidget);
    // pay before clearing: refused with the reason shown
    await _tap(tester, _k('refundBank-4'));
    expect(find.text('The cheque has not cleared yet.'), findsOneWidget);
    expect(server.refunds.first['status'], 'PENDING');
  });

  testWidgets('cash payout sends a request key; Arabic, right to left', (tester) async {
    final server = _ChequeServer()..status = 'CLEARED';
    server.refunds.add({'chequeRefundId': 4, 'returnId': 2, 'amount': 500.0, 'status': 'PENDING', 'paidMethod': null,
      'decidedByName': null, 'decidedAt': null, 'reason': null, 'createdAt': '2026-11-28T11:00:00', 'version': 0});
    await _pump(tester, ChequesPage(api: server.client()), locale: const Locale('ar'));
    expect(find.text('الشيكات'), findsOneWidget);
    expect(Directionality.of(tester.element(_k('chequesPage'))), TextDirection.rtl);
    await _tap(tester, _k('cheque-1'));
    await _tap(tester, _k('refundCash-4'));
    final pay = server.posts.last;
    expect(pay.key, '/cheque-refunds/4/pay');
    expect(pay.value['method'], 'CASH');
    expect(pay.value['requestKey'], startsWith('chq-'));
    expect(server.refunds.first['status'], 'PAID');
  });
}

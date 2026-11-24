import 'dart:convert';

import 'package:clixsell_pos/models/purchase_order.dart';
import 'package:clixsell_pos/screens/purchasing/purchase_order_details_page.dart';
import 'package:clixsell_pos/screens/purchasing/purchase_order_form_page.dart';
import 'package:clixsell_pos/screens/purchasing/purchase_orders_page.dart';
import 'package:clixsell_pos/screens/purchasing/purchase_recommendations_page.dart';
import 'package:clixsell_pos/services/api_http.dart';
import 'package:clixsell_pos/services/purchase_api_client.dart';
import 'package:clixsell_pos/theme/app_theme.dart';
import 'package:clixsell_pos/widgets/app_sidebar.dart';
import 'package:clixsell_pos/models/app_user.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'support/fake_purchase_api.dart';

Future<void> _pump(
  WidgetTester tester,
  Widget page, {
  Locale locale = const Locale('en'),
  Size size = const Size(1440, 900),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(
    MaterialApp(
      theme: AppTheme.light(),
      locale: locale,
      supportedLocales: const [Locale('en'), Locale('ar')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      // a new key every time: a page pumped again starts fresh (no state kept from the previous pump)
      home: Scaffold(
        body: KeyedSubtree(key: UniqueKey(), child: page),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

Widget _details(
  FakePurchaseApi api, {
  FakeUrlOpener? opener,
  FakeClipboard? clipboard,
  void Function(int)? onEdit,
}) => PurchaseOrderDetailsPage(
  api: api,
  orderId: 1,
  onBack: () {},
  onEdit: onEdit ?? (_) {},
  opener: opener ?? FakeUrlOpener(),
  clipboard: clipboard ?? FakeClipboard(),
);

Finder _key(String k) => find.byKey(Key(k));

Future<void> _tap(WidgetTester tester, Finder f) async {
  await tester.ensureVisible(f);
  await tester.tap(f);
  await tester.pumpAndSettle();
}

bool _enabled(WidgetTester tester, String key) =>
    tester.widget<ButtonStyleButton>(_key(key)).onPressed != null;

void main() {
  // ── the API client sends exactly the allowed fields ──────────────────────────────────────
  group('PurchaseApiClient', () {
    late List<http.Request> sent;
    late PurchaseApiClient api;
    setUp(() {
      sent = [];
      api = PurchaseApiClient(
        ApiHttp(
          baseUrl: 'http://x/api',
          client: MockClient((r) async {
            sent.add(r);
            if (r.url.path.endsWith('/cancel') &&
                jsonDecode(r.body)['reason'] == 'conflict') {
              return http.Response(
                jsonEncode({
                  'status': 409,
                  'code': 'REORDER_CAUTION_ACK_REQUIRED',
                  'message': 'm',
                  'fieldErrors': {},
                  'details': {
                    'cautions': [
                      {'orderId': 9, 'type': 'CONTACT_UNKNOWN'},
                    ],
                  },
                }),
                409,
              );
            }
            final body =
                r.url.path.contains('whatsapp') || r.url.path.endsWith('copied')
                ? {'eventId': 1, 'duplicate': false}
                : r.url.path.endsWith('purchase-orders') && r.method == 'GET'
                ? {'page': 0, 'size': 20, 'total': 0, 'orders': []}
                : orderJson();
            return http.Response(
              jsonEncode(body),
              200,
              headers: {'content-type': 'application/json'},
            );
          }),
        ),
      );
    });

    test(
      'create sends the clientRequestId and the order fields only (no store, status, price or cost)',
      () async {
        final id = newClientId();
        await api.create(
          CreateOrderRequest(
            clientRequestId: id,
            supplierId: 7,
            source: 'MANUAL',
            note: null,
            lines: const [OrderLine(101, 5)],
            acks: const [CautionAck(101, 9)],
          ),
        );
        final b = jsonDecode(sent.single.body) as Map<String, dynamic>;
        expect(b.keys.toSet(), {
          'clientRequestId',
          'supplierId',
          'source',
          'note',
          'items',
          'acknowledgedCautions',
        });
        expect(b['clientRequestId'], id);
        expect(
          RegExp(
            r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
          ).hasMatch(id),
          isTrue,
        );
        expect(b['items'], [
          {'productId': 101, 'quantity': 5},
        ]);
      },
    );

    test(
      'cancel sends the contact fields only when given; errors keep their details',
      () async {
        await api.cancel(1, version: 2, reason: 'Plan changed');
        expect((jsonDecode(sent.last.body) as Map).keys.toSet(), {
          'version',
          'reason',
        });
        await api.cancel(
          1,
          version: 2,
          reason: 'Plan changed',
          contactStatus: ContactStatus.unknown,
        );
        expect(jsonDecode(sent.last.body), {
          'version': 2,
          'reason': 'Plan changed',
          'contactStatus': 'UNKNOWN',
        });
        await api.cancel(
          1,
          version: 3,
          reason: 'x y z',
          acknowledgeSupplierNotNotified: true,
        );
        expect(jsonDecode(sent.last.body), {
          'version': 3,
          'reason': 'x y z',
          'acknowledgeSupplierNotNotified': true,
        });
        try {
          await api.cancel(1, version: 2, reason: 'conflict');
          fail('expected 409');
        } on ApiException catch (e) {
          expect(e.code, 'REORDER_CAUTION_ACK_REQUIRED');
          expect(
            (e.detailsMap['cautions'] as List).first['type'],
            'CONTACT_UNKNOWN',
          );
        }
      },
    );

    test(
      'manual records send their explicit confirmations; search is a server query',
      () async {
        await api.markSent(1, 2);
        expect(jsonDecode(sent.last.body), {
          'version': 2,
          'confirmSentInWhatsapp': true,
        });
        await api.openRequested(1, 'id-1');
        expect(jsonDecode(sent.last.body), {
          'clientActionId': 'id-1',
          'confirmed': true,
        });
        await api.openResult(1, 'id-1', false);
        expect(jsonDecode(sent.last.body), {
          'clientActionId': 'id-1',
          'result': 'FAILED',
        });
        await api.close(1, 4);
        expect(jsonDecode(sent.last.body), {
          'version': 4,
          'confirmNoReceiving': true,
        });
        await api.list(
          statuses: const [PurchaseOrderStatus.sent],
          query: ' PO-0001 ',
          page: 2,
        );
        expect(sent.last.url.queryParameters, {
          'status': 'SENT',
          'q': 'PO-0001',
          'page': '2',
          'size': '20',
        });
      },
    );
  });

  // ── list ─────────────────────────────────────────────────────────────────────────────────
  testWidgets(
    'list: server rows, server search, status filter, paging, empty and error states',
    (tester) async {
      final api = FakePurchaseApi()
        ..listRows = [
          {
            'orderId': 1,
            'orderNumber': 'PO-000001',
            'status': 'SENT',
            'source': 'GUARDIAN',
            'supplierId': 7,
            'supplierName': 'Dairy Co',
            'itemCount': 2,
            'createdBy': {'userId': 1, 'fullName': 'Ahmad'},
            'createdAt': '2026-11-20T09:00:00',
            'overdue': true,
            'version': 3,
          },
        ]
        ..listTotal = 45;
      int? opened;
      await _pump(
        tester,
        PurchaseOrdersPage(
          api: api,
          onOpenOrder: (id) => opened = id,
          onNewOrder: () {},
        ),
      );
      expect(find.text('PO-000001'), findsOneWidget);
      expect(find.text('Recorded as sent'), findsWidgets);
      expect(find.text('Guardian'), findsOneWidget);
      expect(find.textContaining('of'), findsWidgets);
      // overdue card counts the server's overdue flag
      expect(
        find.descendant(of: _key('poCardOverdue'), matching: find.text('1')),
        findsOneWidget,
      );
      await tester.enterText(_key('purchaseOrderSearch'), 'dairy');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      expect(api.listQueries.where((q) => q['size'] == 20).last['q'], 'dairy');
      await _tap(tester, _key('poFilter-SENT'));
      expect(
        api.listQueries.where((q) => q['size'] == 20).last['statuses'],
        'SENT',
      );
      await _tap(tester, _key('poNextPage'));
      expect(api.listQueries.where((q) => q['size'] == 20).last['page'], 1);
      await _tap(tester, _key('po-open-1'));
      expect(opened, 1);

      final empty = FakePurchaseApi();
      await _pump(
        tester,
        PurchaseOrdersPage(api: empty, onOpenOrder: (_) {}, onNewOrder: () {}),
      );
      expect(find.text('No purchase orders yet'), findsOneWidget);

      final broken = FakePurchaseApi()
        ..failNext['list'] = const ApiException.network('down');
      await _pump(
        tester,
        PurchaseOrdersPage(api: broken, onOpenOrder: (_) {}, onNewOrder: () {}),
      );
      expect(_key('purchaseError'), findsOneWidget);
      await _tap(tester, _key('purchaseErrorAction'));
      expect(_key('purchaseError'), findsNothing);
    },
  );

  // ── details: actions follow allowedActions in every state ────────────────────────────────
  testWidgets(
    'details: the buttons are exactly the allowed actions of each state; final orders are read only',
    (tester) async {
      const expected = {
        'PENDING_APPROVAL': ['poEdit', 'poApprove', 'poReject', 'poCancel'],
        'APPROVED': ['poPrepare', 'poCancel'],
        'MESSAGE_GENERATED': [
          'poOpenWhatsapp',
          'poCopy',
          'poMarkSent',
          'poCancel',
        ],
        'SENT': ['poCopy', 'poConfirmSupplier', 'poCancel', 'poMore'],
        'CONFIRMED': ['poCopy', 'poClose', 'poCancel', 'poMore'],
        'CLOSED': <String>[],
        'REJECTED': <String>[],
        'CANCELED': <String>[],
      };
      const all = [
        'poEdit',
        'poApprove',
        'poReject',
        'poCancel',
        'poPrepare',
        'poOpenWhatsapp',
        'poCopy',
        'poMarkSent',
        'poConfirmSupplier',
        'poClose',
        'poSupplierCancel',
        'poMore',
      ];
      for (final e in expected.entries) {
        final hasMessage = ![
          'PENDING_APPROVAL',
          'APPROVED',
          'REJECTED',
        ].contains(e.key);
        final api = FakePurchaseApi(
          order: orderJson(
            status: e.key,
            message: hasMessage
                ? messageJson(
                    actionable: !['CLOSED', 'CANCELED'].contains(e.key),
                  )
                : null,
          ),
        );
        await _pump(tester, _details(api));
        for (final k in all) {
          expect(
            _key(k),
            e.value.contains(k) ? findsOneWidget : findsNothing,
            reason: '${e.key}: $k',
          );
        }
        expect(
          _key('poFinal'),
          ['CLOSED', 'REJECTED', 'CANCELED'].contains(e.key)
              ? findsOneWidget
              : findsNothing,
        );
        if (e.key == 'CLOSED') {
          expect(_key('poMessageReadOnly'), findsOneWidget);
        }
        // never a generic "Send" button
        expect(find.widgetWithText(FilledButton, 'Send'), findsNothing);
      }
    },
  );

  testWidgets(
    'details: approval blockers disable Approve; costs say they are never in the message',
    (tester) async {
      final api = FakePurchaseApi(
        order: orderJson(
          blockers: ['MESSAGE_TOO_LONG'],
          messageLength: {
            'ar': {
              'language': 'ar',
              'length': 2300,
              'maxPossibleLength': 2380,
              'limit': 2000,
              'fits': false,
              'alwaysFits': false,
              'longestLines': [
                {
                  'productId': 1,
                  'productName': 'Very long name',
                  'length': 120,
                },
              ],
              'minLinesToRemove': 4,
            },
            'en': {
              'language': 'en',
              'length': 2350,
              'maxPossibleLength': 2430,
              'limit': 2000,
              'fits': false,
              'alwaysFits': false,
              'longestLines': [],
              'minLinesToRemove': 5,
            },
          },
        ),
      );
      await _pump(tester, _details(api));
      expect(_enabled(tester, 'poApprove'), isFalse);
      expect(find.textContaining('too long for WhatsApp'), findsOneWidget);
      expect(find.textContaining('move at least'), findsWidgets);
      expect(find.textContaining('Very long name'), findsOneWidget);
      expect(_key('poCostNote'), findsOneWidget);
    },
  );

  // ── stale version: never replayed ───────────────────────────────────────────────────────
  testWidgets(
    'stale version: the page reloads and asks again; nothing is resent',
    (tester) async {
      final api = FakePurchaseApi()..failNext['approve'] = orderChanged;
      await _pump(tester, _details(api));
      await _tap(tester, _key('poApprove'));
      await _tap(tester, _key('confirmActionButton'));
      expect(api.count('approve'), 1);
      expect(api.count('get'), 2); // first load + reload after the conflict
      expect(
        find.textContaining('Another manager changed this order'),
        findsOneWidget,
      );
    },
  );

  // ── unknown outcome: checked before saying anything ───────────────────────────────────────
  testWidgets('unknown outcome: the stored state decides what is said', (
    tester,
  ) async {
    final api = FakePurchaseApi(
      order: orderJson(
        status: 'MESSAGE_GENERATED',
        version: 2,
        message: messageJson(),
      ),
    );
    api.lostAnswer['markSent'] = () => api.order = orderJson(
      status: 'SENT',
      version: 3,
      message: messageJson(),
    );
    await _pump(tester, _details(api));
    await _tap(tester, _key('poMarkSent'));
    await _tap(tester, _key('checkDialogBox'));
    await _tap(tester, _key('checkDialogConfirm'));
    expect(
      find.textContaining('Checked: the change had been saved'),
      findsOneWidget,
    );
    expect(api.count('markSent'), 1);

    final api2 = FakePurchaseApi(
      order: orderJson(
        status: 'MESSAGE_GENERATED',
        version: 2,
        message: messageJson(),
      ),
    )..failNext['markSent'] = databaseBusy;
    await _pump(tester, _details(api2));
    await _tap(tester, _key('poMarkSent'));
    await _tap(tester, _key('checkDialogBox'));
    await _tap(tester, _key('checkDialogConfirm'));
    expect(find.textContaining('not saved at this moment'), findsOneWidget);
    expect(find.textContaining('could not be confirmed'), findsOneWidget);
    expect(api2.count('markSent'), 1, reason: 'never retried automatically');
  });

  // ── cancellation ──────────────────────────────────────────────────────────────────────────
  testWidgets(
    'cancel after the message: an answer is required, nothing preselected; UNKNOWN never records sending',
    (tester) async {
      final api = FakePurchaseApi(
        order: orderJson(
          status: 'MESSAGE_GENERATED',
          version: 2,
          message: messageJson(),
        ),
      );
      await _pump(tester, _details(api));
      await _tap(tester, _key('poCancel'));
      final group = tester.widget<RadioGroup<ContactStatus>>(
        find.byType(RadioGroup<ContactStatus>),
      );
      expect(group.groupValue, isNull);
      await tester.enterText(_key('cancelReason'), 'Plan changed');
      await tester.pumpAndSettle();
      expect(_enabled(tester, 'cancelConfirm'), isFalse);
      await _tap(tester, _key('cancel-NOT_SENT'));
      expect(
        _enabled(tester, 'cancelConfirm'),
        isFalse,
        reason: 'NOT_SENT needs the confirmation box',
      );
      await _tap(tester, _key('cancelConfirmNotSent'));
      expect(_enabled(tester, 'cancelConfirm'), isTrue);
      await _tap(tester, _key('cancel-UNKNOWN'));
      expect(_key('cancelUnknownExplain'), findsOneWidget);
      await _tap(tester, _key('cancelConfirm'));
      expect(api.bodies[api.calls.indexOf('cancel')], {
        'version': 2,
        'reason': 'Plan changed',
        'contactStatus': 'UNKNOWN',
        'confirmNotSent': null,
        'acknowledgeSupplierNotNotified': null,
      });
      expect(api.count('markSent'), 0);
    },
  );

  testWidgets(
    'cancel: "Yes, I sent it" leads to recording the sending, never to a cancellation',
    (tester) async {
      final api = FakePurchaseApi(
        order: orderJson(
          status: 'MESSAGE_GENERATED',
          version: 2,
          message: messageJson(),
        ),
      );
      await _pump(tester, _details(api));
      await _tap(tester, _key('poCancel'));
      await _tap(tester, _key('cancel-SENT'));
      expect(_key('cancelSentExplain'), findsOneWidget);
      await _tap(tester, _key('cancelGoRecordSending'));
      expect(_key('markSentDialog'), findsOneWidget);
      expect(api.count('cancel'), 0);
    },
  );

  testWidgets(
    'cancel after contact needs the acknowledgment that the supplier is not notified',
    (tester) async {
      final api = FakePurchaseApi(
        order: orderJson(status: 'SENT', version: 3, message: messageJson()),
      );
      await _pump(tester, _details(api));
      await _tap(tester, _key('poCancel'));
      expect(_key('cancelAfterContactWarning'), findsOneWidget);
      await tester.enterText(_key('cancelReason'), 'Too slow');
      await tester.pumpAndSettle();
      expect(_enabled(tester, 'cancelConfirm'), isFalse);
      await _tap(tester, _key('cancelAckNotNotified'));
      await _tap(tester, _key('cancelConfirm'));
      expect(
        api.bodies[api.calls.indexOf(
          'cancel',
        )]['acknowledgeSupplierNotNotified'],
        isTrue,
      );
      expect(api.bodies[api.calls.indexOf('cancel')]['contactStatus'], isNull);
    },
  );

  // ── WhatsApp ──────────────────────────────────────────────────────────────────────────────
  testWidgets(
    'open WhatsApp: confirm first, then a separate click; the result is reported; never marks sent',
    (tester) async {
      final api = FakePurchaseApi(
        order: orderJson(
          status: 'MESSAGE_GENERATED',
          version: 2,
          message: messageJson(),
        ),
      );
      final opener = FakeUrlOpener();
      await _pump(tester, _details(api, opener: opener));
      await _tap(tester, _key('poOpenWhatsapp'));
      expect(find.text('+970 599 000 001'), findsWidgets);
      expect(_enabled(tester, 'openConfirm'), isFalse);
      expect(api.count('openRequested'), 0);
      await _tap(tester, _key('openReviewed'));
      await _tap(tester, _key('openConfirm'));
      expect(api.count('openRequested'), 1);
      expect(
        opener.opened,
        isEmpty,
        reason: 'nothing opens before the separate click',
      );
      await _tap(tester, _key('openNow'));
      expect(opener.opened.single, startsWith('https://wa.me/970599000001'));
      expect(api.bodies[api.calls.indexOf('openResult')]['opened'], isTrue);
      expect(
        api.bodies[api.calls.indexOf('openResult')]['clientActionId'],
        api.bodies[api.calls.indexOf('openRequested')]['clientActionId'],
      );
      expect(_key('openDone'), findsOneWidget);
      expect(api.count('markSent'), 0);
    },
  );

  testWidgets(
    'open WhatsApp: a refused link reports FAILED and offers copying; a failed request retries with the same id',
    (tester) async {
      final api = FakePurchaseApi(
        order: orderJson(
          status: 'MESSAGE_GENERATED',
          version: 2,
          message: messageJson(),
        ),
      )..failNext['openRequested'] = const ApiException.network('down');
      final opener = FakeUrlOpener(result: false);
      await _pump(tester, _details(api, opener: opener));
      await _tap(tester, _key('poOpenWhatsapp'));
      await _tap(tester, _key('openReviewed'));
      await _tap(tester, _key('openConfirm'));
      expect(_key('purchaseError'), findsOneWidget);
      await _tap(tester, _key('openConfirm'));
      final ids = [
        for (var i = 0; i < api.calls.length; i++)
          if (api.calls[i] == 'openRequested') api.bodies[i]['clientActionId'],
      ];
      expect(ids.length, 2);
      expect(ids.toSet().length, 1, reason: 'same clientActionId on retry');
      await _tap(tester, _key('openNow'));
      expect(_key('openFailed'), findsOneWidget);
      expect(api.bodies[api.calls.indexOf('openResult')]['opened'], isFalse);
    },
  );

  testWidgets(
    'a changed supplier number needs an extra acknowledgment; the saved number is shown',
    (tester) async {
      final api = FakePurchaseApi(
        order: orderJson(
          status: 'MESSAGE_GENERATED',
          version: 2,
          message: messageJson(changed: true),
        ),
      );
      await _pump(tester, _details(api));
      expect(_key('poNumberChanged'), findsOneWidget);
      await _tap(tester, _key('poOpenWhatsapp'));
      expect(_key('openNumberChanged'), findsOneWidget);
      await _tap(tester, _key('openReviewed'));
      expect(_enabled(tester, 'openConfirm'), isFalse);
      await _tap(tester, _key('openChangedAck'));
      expect(_enabled(tester, 'openConfirm'), isTrue);
    },
  );

  testWidgets(
    'after SENT, opening again is only under More, with the duplicate warning and an extra acknowledgment',
    (tester) async {
      final api = FakePurchaseApi(
        order: orderJson(status: 'SENT', version: 3, message: messageJson()),
      );
      await _pump(tester, _details(api));
      expect(_key('poOpenWhatsapp'), findsNothing);
      await _tap(tester, _key('poMore'));
      await _tap(tester, _key('poOpenAgain'));
      expect(_key('openAgainWarning'), findsOneWidget);
      await _tap(tester, _key('openReviewed'));
      expect(_enabled(tester, 'openConfirm'), isFalse);
      await _tap(tester, _key('openAgainAck'));
      expect(_enabled(tester, 'openConfirm'), isTrue);
    },
  );

  testWidgets(
    'copy: recorded only after the clipboard succeeded; a failed record is retried with the same id',
    (tester) async {
      final api = FakePurchaseApi(
        order: orderJson(
          status: 'MESSAGE_GENERATED',
          version: 2,
          message: messageJson(),
        ),
      );
      final clipboard = FakeClipboard(ok: false);
      await _pump(tester, _details(api, clipboard: clipboard));
      await _tap(tester, _key('poCopy'));
      expect(api.count('copied'), 0);
      expect(
        find.textContaining('Copying automatically was not allowed'),
        findsOneWidget,
      );

      clipboard.ok = true;
      api.failNext['copied'] = const ApiException.network('down');
      await _tap(tester, _key('poCopy'));
      expect(clipboard.copied.single, messageJson()['text']);
      expect(_key('purchaseErrorAction'), findsOneWidget);
      await _tap(tester, _key('purchaseErrorAction'));
      final ids = [
        for (var i = 0; i < api.calls.length; i++)
          if (api.calls[i] == 'copied') api.bodies[i]['clientActionId'],
      ];
      expect(ids.length, 2);
      expect(ids.toSet().length, 1);
    },
  );

  testWidgets(
    'preview: Arabic first, English on request, PREVIEW_CHANGED shows the new text without saving',
    (tester) async {
      final api =
          FakePurchaseApi(order: orderJson(status: 'APPROVED', version: 1))
            ..failNext['generate'] = ApiException(
              statusCode: 409,
              code: 'PREVIEW_CHANGED',
              message: 'changed',
              details: {
                'preview': {
                  'orderId': 1,
                  'version': 1,
                  'language': 'ar',
                  'text': 'NEW TEXT',
                  'supplierName': 'Dairy Co',
                  'whatsappNumber': '970599000009',
                  'length': {
                    'language': 'ar',
                    'length': 8,
                    'maxPossibleLength': 80,
                    'limit': 2000,
                    'fits': true,
                    'alwaysFits': true,
                    'longestLines': [],
                    'minLinesToRemove': 0,
                  },
                  'previewToken': 'b' * 64,
                },
              },
            );
      await _pump(tester, _details(api));
      await _tap(tester, _key('poPrepare'));
      expect(api.bodies[api.calls.indexOf('preview')]['language'], 'ar');
      await _tap(tester, find.text('English').last);
      expect(api.bodies[api.calls.lastIndexOf('preview')]['language'], 'en');
      await _tap(tester, find.text('Arabic').last);
      await _tap(tester, _key('previewPrepare'));
      expect(_key('previewChanged'), findsOneWidget);
      expect(find.text('NEW TEXT'), findsOneWidget);
      expect(find.text('+970 599 000 009'), findsOneWidget);
      expect(api.count('generate'), 1);
      await _tap(tester, _key('previewPrepare'));
      expect(
        api.bodies[api.calls.lastIndexOf('generate')]['previewToken'],
        'b' * 64,
      );
    },
  );

  // ── form ──────────────────────────────────────────────────────────────────────────────────
  testWidgets(
    'form: an unknown outcome locks the form; the retry sends exactly the same request',
    (tester) async {
      final api = FakePurchaseApi()
        ..candidatesJson = {
          'supplierId': 7,
          'supplierName': 'Dairy Co',
          'active': true,
          'hasWhatsapp': true,
          'products': [
            {
              'productId': 101,
              'name': 'Labneh',
              'barcode': '111',
              'quantity': 2,
              'minStock': 5,
              'stockStatus': 'LOW_STOCK',
              'expectedUnitCost': 9.5,
              'available': true,
              'cautions': [],
            },
          ],
        };
      api.lostAnswer['create'] = () {};
      PurchaseOrder? saved;
      await _pump(
        tester,
        PurchaseOrderFormPage(
          api: api,
          supplierApi: FakeSupplierApi(),
          onSaved: (o) => saved = o,
          onCancel: () {},
          seed: const PurchaseOrderSeed(
            supplierId: 7,
            source: 'MANUAL',
            lines: [OrderLine(101, 4)],
          ),
        ),
      );
      await tester.enterText(_key('po-qty-101'), '0');
      await tester.pumpAndSettle();
      expect(
        _enabled(tester, 'poFormSave'),
        isFalse,
        reason: 'quantity 0 is refused',
      );
      await tester.enterText(_key('po-qty-101'), '6');
      await tester.pumpAndSettle();
      await _tap(tester, _key('poFormSave'));
      expect(_key('poUnresolved'), findsOneWidget);
      expect(_enabled(tester, 'poFormSave'), isFalse);
      expect(tester.widget<TextField>(_key('po-qty-101')).enabled, isFalse);
      await _tap(tester, _key('poCheckRetry'));
      expect(api.count('create'), 2);
      expect(
        api.bodies[api.calls.lastIndexOf('create')],
        api.bodies[api.calls.indexOf('create')],
        reason: 'identical request',
      );
      expect(saved?.orderNumber, 'PO-000005');
    },
  );

  testWidgets(
    'form: cautions from the server must be ticked; the next request carries the acknowledgment',
    (tester) async {
      final api = FakePurchaseApi()
        ..candidatesJson = {
          'supplierId': 7,
          'supplierName': 'Dairy Co',
          'active': true,
          'hasWhatsapp': true,
          'products': [
            {
              'productId': 101,
              'name': 'Labneh',
              'barcode': '111',
              'quantity': 2,
              'minStock': 5,
              'stockStatus': 'LOW_STOCK',
              'expectedUnitCost': null,
              'available': true,
              'cautions': [],
            },
          ],
        }
        ..failNext['create'] = ApiException(
          statusCode: 409,
          code: 'REORDER_CAUTION_ACK_REQUIRED',
          message: 'm',
          details: {
            'cautions': [
              {
                'productId': 101,
                'productName': 'Labneh',
                'orderId': 9,
                'orderNumber': 'PO-000009',
                'type': 'CONTACT_UNKNOWN',
                'at': '2026-11-20T10:00:00',
                'quantity': 3,
              },
            ],
          },
        );
      await _pump(
        tester,
        PurchaseOrderFormPage(
          api: api,
          supplierApi: FakeSupplierApi(),
          onSaved: (_) {},
          onCancel: () {},
          seed: const PurchaseOrderSeed(
            supplierId: 7,
            source: 'MANUAL',
            lines: [OrderLine(101, 4)],
          ),
        ),
      );
      await _tap(tester, _key('poFormSave'));
      expect(_key('po-ack-101-9'), findsOneWidget);
      expect(_enabled(tester, 'poFormSave'), isFalse);
      expect(
        find.textContaining('unknown whether the supplier got the message'),
        findsWidgets,
      );
      await _tap(tester, _key('po-ack-101-9'));
      await _tap(tester, _key('poFormSave'));
      final second = api.bodies[api.calls.lastIndexOf('create')];
      expect(second['acknowledgedCautions'], [
        {'productId': 101, 'orderId': 9},
      ]);
      expect(
        second['clientRequestId'],
        api.bodies[api.calls.indexOf('create')]['clientRequestId'],
        reason:
            'nothing was created by the refused request: the same logical order keeps its id',
      );
    },
  );

  // ── recommendations ──────────────────────────────────────────────────────────────────────
  testWidgets(
    'suggestions: Guardian off shows nothing fabricated; one supplier at a time; the seed has the server quantities',
    (tester) async {
      final off = FakePurchaseApi();
      var manual = 0;
      await _pump(
        tester,
        PurchaseRecommendationsPage(
          api: off,
          onCreateOrder: (_) {},
          onManualOrder: () => manual++,
          onOpenOrder: (_) {},
        ),
      );
      expect(_key('recGuardianOff'), findsOneWidget);
      await _tap(tester, find.text('Create a manual order').last);
      expect(manual, 1);

      Map<String, dynamic> item(int id, String name, int? q) => {
        'productId': id,
        'name': name,
        'barcode': '$id',
        'quantity': 0,
        'minStock': 5,
        'classification': 'OUT_OF_STOCK',
        'basis': 'MIN_STOCK_ONLY',
        'suggestedQuantity': q,
        'targetStock': 10,
        'observedDays': 3,
        'netUnits': 0,
        'available': true,
        'cautions': [],
        'explanation': 'x',
      };
      final on = FakePurchaseApi()
        ..recommendationsJson = {
          'guardianEnabled': true,
          'engine': 'RULES',
          'suppliers': [
            {
              'supplierId': 7,
              'supplierName': 'Dairy Co',
              'active': true,
              'hasWhatsapp': true,
              'items': [item(1, 'Labneh', 10), item(2, 'Milk', 6)],
            },
            {
              'supplierId': 8,
              'supplierName': 'Bakery',
              'active': true,
              'hasWhatsapp': false,
              'items': [item(3, 'Bread', 12)],
            },
          ],
          'inconsistent': [],
        };
      PurchaseOrderSeed? seed;
      await _pump(
        tester,
        PurchaseRecommendationsPage(
          api: on,
          onCreateOrder: (s) => seed = s,
          onManualOrder: () {},
          onOpenOrder: (_) {},
        ),
      );
      expect(find.textContaining('not an AI model'), findsOneWidget);
      await _tap(tester, _key('rec-select-1'));
      await _tap(
        tester,
        _key('rec-select-3'),
      ); // another supplier: replaces the selection
      expect(tester.widget<Checkbox>(_key('rec-select-1')).value, isFalse);
      await _tap(tester, _key('rec-select-1'));
      await _tap(tester, _key('rec-select-2'));
      await _tap(tester, _key('recCreateOrder'));
      expect(seed!.supplierId, 7);
      expect(seed!.source, 'GUARDIAN');
      expect(seed!.lines.map((l) => [l.productId, l.quantity]), [
        [1, 10],
        [2, 6],
      ]);
    },
  );

  // ── roles, layout ────────────────────────────────────────────────────────────────────────
  test('only the Store Admin has the purchasing pages', () {
    const purchasing = [
      SidebarDestination.purchaseOrders,
      SidebarDestination.purchaseRecommendations,
      SidebarDestination.purchaseOrderDetails,
      SidebarDestination.purchaseOrderForm,
    ];
    for (final d in purchasing) {
      expect(UserRole.storeAdmin.destinations, contains(d));
      expect(UserRole.cashier.destinations, isNot(contains(d)));
      expect(UserRole.systemAdmin.destinations, isNot(contains(d)));
    }
  });

  for (final size in const [Size(1440, 900), Size(1024, 768)]) {
    for (final locale in const [Locale('en'), Locale('ar')]) {
      testWidgets(
        'no overflow at $size (${locale.languageCode}): list, details with message, form',
        (tester) async {
          final api =
              FakePurchaseApi(
                  order: orderJson(
                    status: 'MESSAGE_GENERATED',
                    version: 2,
                    message: messageJson(changed: true),
                    overdue: true,
                  ),
                )
                ..listRows = [
                  {
                    'orderId': 1,
                    'orderNumber': 'PO-000001',
                    'status': 'MESSAGE_GENERATED',
                    'source': 'GUARDIAN',
                    'supplierId': 7,
                    'supplierName': 'A supplier with a rather long name Ltd.',
                    'itemCount': 12,
                    'createdBy': {'userId': 1, 'fullName': 'Ahmad Odeh'},
                    'createdAt': '2026-11-20T09:00:00',
                    'overdue': true,
                    'version': 2,
                  },
                ]
                ..listTotal = 1;
          await _pump(
            tester,
            PurchaseOrdersPage(
              api: api,
              onOpenOrder: (_) {},
              onNewOrder: () {},
            ),
            locale: locale,
            size: size,
          );
          expect(tester.takeException(), isNull);
          await _pump(tester, _details(api), locale: locale, size: size);
          expect(tester.takeException(), isNull);
          await _tap(tester, _key('poOpenWhatsapp'));
          expect(tester.takeException(), isNull);
        },
      );
    }
  }
}

import 'dart:async';
import 'dart:convert';

import 'package:clixsell_pos/models/app_user.dart';
import 'package:clixsell_pos/models/product.dart';
import 'package:clixsell_pos/screens/ai/ai_identify_page.dart';
import 'package:clixsell_pos/screens/ai/product_camera.dart';
import 'package:clixsell_pos/screens/app_shell.dart';
import 'package:clixsell_pos/services/api_http.dart';
import 'package:clixsell_pos/services/barcode_lookup_client.dart';
import 'package:clixsell_pos/services/product_api_client.dart';
import 'package:clixsell_pos/state/pos_cart.dart';
import 'package:clixsell_pos/state/product_store.dart';
import 'package:clixsell_pos/theme/app_theme.dart';
import 'package:clixsell_pos/widgets/app_sidebar.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

/// AI Product Identification, Phase 1 (Figure 3.10): the screen with a fake
/// camera and a fake inventory lookup, the real lookup client against a mock
/// HTTP server, and the shell / sidebar rules (Android only, store roles only).

// A real 1x1 PNG, so Image.memory decodes it.
final _png = base64Decode(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
);
final _png2 = Uint8List.fromList([..._png]);

class FakeCamera implements ProductCamera {
  void Function(ScannedCode)? onCode;
  CameraProblem? problem;
  int starts = 0, stops = 0, disposes = 0, photos = 0;
  final List<Object?> photoResults =
      []; // Uint8List, null (cancel) or Exception
  Uint8List? lost;

  void read(String value, [String format = 'EAN_13']) =>
      onCode!(ScannedCode(value, format));

  @override
  Widget preview({
    required void Function(ScannedCode code) onCode,
    required Widget Function(BuildContext, CameraProblem) onProblem,
  }) {
    this.onCode = onCode;
    return Builder(
      builder: (context) => problem == null
          ? const SizedBox.expand(key: Key('fakePreview'))
          : onProblem(context, problem!),
    );
  }

  @override
  Future<void> start() async => starts++;
  @override
  Future<void> stop() async => stops++;
  @override
  Future<void> dispose() async => disposes++;

  @override
  Future<Uint8List?> takePhoto() async {
    photos++;
    final r = photoResults.isEmpty ? null : photoResults.removeAt(0);
    if (r is Exception) throw r;
    return r as Uint8List?;
  }

  @override
  Future<Uint8List?> recoverPhoto() async => lost;
}

class FakeLookup implements BarcodeLookupApi {
  final Map<String, BarcodeLookupResult> results = {};
  final List<String> calls = [];
  Object? error;
  Completer<void>? gate;

  @override
  Future<BarcodeLookupResult> lookup(String code, {String? format}) async {
    calls.add('$code/$format');
    if (gate != null) await gate!.future;
    if (error != null) throw error!;
    if (!BarcodeLookupClient.isProductBarcode(code)) {
      return BarcodeLookupResult.invalid(code);
    }
    return results[code] ?? BarcodeLookupResult.notFound(code);
  }
}

Product _product({int qty = 5, String barcode = '4006381333931'}) =>
    Product.fromJson({
      'productId': 11,
      'name': 'Milk 1L',
      'barcode': barcode,
      'category': 'dairy',
      'sellingPrice': 4.5,
      'quantity': qty,
      'minStock': 2,
      'supplierName': 'Dairy Co',
      'createdAt': '2026-11-20T09:00:00',
      'updatedAt': '2026-11-20T09:00:00',
    });

BarcodeLookupResult _found(Product p, {String? scanned, bool exact = true}) =>
    BarcodeLookupResult.found(
      scannedCode: scanned ?? p.barcode,
      product: p,
      matchedBarcode: p.barcode,
      matchType: exact
          ? BarcodeMatchType.exact
          : BarcodeMatchType.gtinEquivalent,
    );

class _Harness {
  final camera = FakeCamera();
  final lookup = FakeLookup();
  final cart = PosCart();
  int openSale = 0, searched = 0;
}

Future<_Harness> _pump(
  WidgetTester tester, {
  Locale locale = const Locale('en'),
  bool canSell = true,
  void Function(_Harness h)? setup,
}) async {
  tester.view.physicalSize = const Size(412, 915);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final h = _Harness();
  setup?.call(h);
  await tester.pumpWidget(
    MaterialApp(
      theme: AppTheme.light(),
      locale: locale,
      supportedLocales: const [Locale('en'), Locale('ar')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      home: PosCartScope(
        cart: h.cart,
        child: Scaffold(
          body: AiIdentifyPage(
            api: h.lookup,
            createCamera: () => h.camera,
            canSell: canSell,
            onOpenSale: () => h.openSale++,
            onSearchManually: () => h.searched++,
          ),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return h;
}

Future<void> _tap(WidgetTester tester, Finder f) async {
  await tester.ensureVisible(f);
  await tester.pumpAndSettle();
  await tester.tap(f);
  await tester.pumpAndSettle();
}

Finder _k(String key) => find.byKey(Key(key));

void main() {
  for (final locale in const [Locale('en'), Locale('ar')]) {
    final lang = locale.languageCode;

    testWidgets('[$lang] found: real product, confirm, add to this sale', (
      tester,
    ) async {
      final h = await _pump(
        tester,
        locale: locale,
        setup: (h) => h.lookup.results['4006381333931'] = _found(_product()),
      );
      expect(h.camera.starts, 1);
      expect(_k('fakePreview'), findsOneWidget);
      expect(
        find.text(
          lang == 'ar'
              ? 'التعرف على المنتج بالذكاء الاصطناعي'
              : 'AI Product Identification',
        ),
        findsOneWidget,
      );

      h.camera.read('4006381333931');
      await tester.pumpAndSettle();
      expect(h.lookup.calls, ['4006381333931/EAN_13']);
      expect(h.camera.stops, greaterThanOrEqualTo(1)); // scanner paused
      expect(_k('aiFound'), findsOneWidget);
      expect(find.text('Milk 1L'), findsOneWidget);
      expect(_k('aiProductBarcode'), findsOneWidget);
      expect(find.text('4006381333931'), findsOneWidget);
      expect(find.textContaining('4.50'), findsOneWidget);
      // nothing is added before the cashier confirms
      expect(_k('aiAddToSale'), findsNothing);
      expect(h.cart.lines, isEmpty);

      await _tap(tester, _k('aiConfirm'));
      expect(_k('aiConfirmed'), findsOneWidget);
      expect(_k('aiOpenSale'), findsNothing); // only after a real add
      expect(_k('aiBarcodeQr'), findsOneWidget);
      expect(_k('aiBarcodeDigits'), findsOneWidget);

      await _tap(tester, _k('aiAddToSale'));
      expect(h.cart.lines, hasLength(1));
      expect(h.cart.lines.single.product.id, '11');
      expect(h.cart.lines.single.quantity, 1);
      expect(_k('aiSaleMessage'), findsOneWidget);
      await _tap(tester, _k('aiOpenSale'));
      expect(h.openSale, 1);

      // RTL in Arabic
      final dir = Directionality.of(tester.element(_k('aiFound')));
      expect(dir, lang == 'ar' ? TextDirection.rtl : TextDirection.ltr);
      expect(tester.takeException(), isNull);
    });

    testWidgets('[$lang] not found: code kept, photo, retake, back', (
      tester,
    ) async {
      final h = await _pump(tester, locale: locale);
      h.camera.read('96385074', 'EAN_8');
      await tester.pumpAndSettle();
      expect(_k('aiNotFound'), findsOneWidget);
      expect(find.textContaining('96385074'), findsOneWidget);
      expect(h.cart.lines, isEmpty);
      // Phase 2 is shown disabled
      await tester.ensureVisible(_k('aiIdentifyByPhoto'));
      await tester.pumpAndSettle();
      final ai = tester.widget<ButtonStyleButton>(_k('aiIdentifyByPhoto'));
      expect(ai.onPressed, isNull);

      h.camera.photoResults.addAll([_png, _png2]);
      await _tap(tester, _k('aiCapturePhoto'));
      expect(h.camera.photos, 1);
      expect(_k('aiPhotoPreview'), findsOneWidget);
      expect(find.textContaining('96385074'), findsOneWidget); // still kept
      var img = tester.widget<Image>(_k('aiPhotoPreview'));
      expect((img.image as MemoryImage).bytes, same(_png));

      await _tap(tester, _k('aiRetake'));
      expect(h.camera.photos, 2);
      img = tester.widget<Image>(_k('aiPhotoPreview'));
      expect((img.image as MemoryImage).bytes, same(_png2));

      await _tap(tester, _k('aiBack'));
      expect(_k('aiNotFound'), findsOneWidget);
      await _tap(tester, _k('aiSearchManually'));
      expect(h.searched, 1);
      expect(tester.takeException(), isNull);
    });
  }

  for (final locale in const [Locale('en'), Locale('ar')]) {
    testWidgets('[${locale.languageCode}] small phone (320x568): no overflow', (
      tester,
    ) async {
      final h = await _pump(
        tester,
        locale: locale,
        setup: (h) => h.lookup.results['4006381333931'] = _found(_product()),
      );
      tester.view.physicalSize = const Size(320, 568);
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      h.camera.read('4006381333931');
      await tester.pumpAndSettle();
      await _tap(tester, _k('aiConfirm'));
      await _tap(tester, _k('aiAddToSale'));
      expect(tester.takeException(), isNull);
      await _tap(tester, _k('aiScanAgain'));
      h.camera.read('96385074', 'EAN_8');
      await tester.pumpAndSettle();
      expect(_k('aiNotFound'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('duplicate reads during a lookup make one request', (
    tester,
  ) async {
    final h = await _pump(
      tester,
      setup: (h) {
        h.lookup.gate = Completer<void>();
        h.lookup.results['4006381333931'] = _found(_product());
      },
    );
    h.camera.read('4006381333931');
    await tester.pump();
    expect(_k('aiLookingUp'), findsOneWidget);
    h.camera.read('4006381333931');
    h.camera.read('96385074', 'EAN_8');
    await tester.pump();
    h.lookup.gate!.complete();
    await tester.pumpAndSettle();
    expect(h.lookup.calls, hasLength(1));
    expect(_k('aiFound'), findsOneWidget);
    // reads while a result is open are ignored too
    h.camera.read('96385074', 'EAN_8');
    await tester.pumpAndSettle();
    expect(h.lookup.calls, hasLength(1));
  });

  testWidgets('scan again restarts the scanner and allows the next code', (
    tester,
  ) async {
    final h = await _pump(tester);
    h.camera.read('96385074', 'EAN_8');
    await tester.pumpAndSettle();
    final starts = h.camera.starts;
    await _tap(tester, _k('aiScanAgain'));
    expect(h.camera.starts, starts + 1);
    expect(_k('fakePreview'), findsOneWidget);
    h.camera.read('12345670', 'EAN_8');
    await tester.pumpAndSettle();
    expect(h.lookup.calls, hasLength(2));
  });

  testWidgets('a non-product code (letters / QR-like) is not a product', (
    tester,
  ) async {
    final h = await _pump(tester);
    h.camera.read('ABC-123', 'CODE_128');
    await tester.pumpAndSettle();
    expect(_k('aiInvalid'), findsOneWidget);
    expect(find.textContaining('ABC-123'), findsOneWidget);
    expect(_k('aiAddToSale'), findsNothing);
    expect(h.cart.lines, isEmpty);
  });

  testWidgets('GTIN equivalent shows the stored barcode and a note', (
    tester,
  ) async {
    final p = _product(barcode: '0036000291452');
    final h = await _pump(
      tester,
      setup: (h) => h.lookup.results['036000291452'] = _found(
        p,
        scanned: '036000291452',
        exact: false,
      ),
    );
    h.camera.read('036000291452', 'UPC_A');
    await tester.pumpAndSettle();
    expect(h.lookup.calls, ['036000291452/UPC_A']);
    expect(find.text('0036000291452'), findsOneWidget);
    expect(find.textContaining('036000291452'), findsWidgets);
  });

  testWidgets('out of stock: never added, no "open sale"', (tester) async {
    final h = await _pump(
      tester,
      setup: (h) =>
          h.lookup.results['4006381333931'] = _found(_product(qty: 0)),
    );
    h.camera.read('4006381333931');
    await tester.pumpAndSettle();
    await _tap(tester, _k('aiConfirm'));
    await _tap(tester, _k('aiAddToSale'));
    expect(h.cart.lines, isEmpty);
    expect(find.textContaining('out of stock'), findsWidgets);
    expect(_k('aiOpenSale'), findsNothing);
  });

  testWidgets('stock limit: the second add is refused', (tester) async {
    final h = await _pump(
      tester,
      setup: (h) =>
          h.lookup.results['4006381333931'] = _found(_product(qty: 1)),
    );
    h.camera.read('4006381333931');
    await tester.pumpAndSettle();
    await _tap(tester, _k('aiConfirm'));
    await _tap(tester, _k('aiAddToSale'));
    await _tap(tester, _k('aiAddToSale'));
    expect(h.cart.lines.single.quantity, 1);
    expect(find.textContaining('Only'), findsOneWidget);
  });

  testWidgets('a role without POS sees the barcode but no add button', (
    tester,
  ) async {
    final h = await _pump(
      tester,
      canSell: false,
      setup: (h) => h.lookup.results['4006381333931'] = _found(_product()),
    );
    h.camera.read('4006381333931');
    await tester.pumpAndSettle();
    await _tap(tester, _k('aiConfirm'));
    expect(_k('aiAddToSale'), findsNothing);
    expect(_k('aiOtherDevice'), findsOneWidget);
  });

  testWidgets('network error keeps the code; retry looks it up again', (
    tester,
  ) async {
    final h = await _pump(
      tester,
      setup: (h) => h.lookup.error = const ApiException(message: 'offline'),
    );
    h.camera.read('4006381333931');
    await tester.pumpAndSettle();
    expect(_k('aiError'), findsOneWidget);
    expect(find.textContaining('Cannot reach'), findsOneWidget);
    expect(find.textContaining('4006381333931'), findsOneWidget);
    h.lookup.error = null;
    h.lookup.results['4006381333931'] = _found(_product());
    await _tap(tester, _k('aiRetry'));
    expect(h.lookup.calls, hasLength(2));
    expect(_k('aiFound'), findsOneWidget);
  });

  testWidgets('server error has its own message', (tester) async {
    final h = await _pump(
      tester,
      setup: (h) =>
          h.lookup.error = const ApiException(message: 'x', statusCode: 500),
    );
    h.camera.read('4006381333931');
    await tester.pumpAndSettle();
    expect(find.textContaining('could not search'), findsOneWidget);
  });

  for (final problem in CameraProblem.values) {
    testWidgets('camera problem ${problem.name} is explained, retry starts', (
      tester,
    ) async {
      final h = await _pump(tester, setup: (h) => h.camera.problem = problem);
      expect(_k('aiCameraProblem-${problem.name}'), findsOneWidget);
      final before = h.camera.starts;
      await _tap(tester, _k('aiCameraRetry'));
      expect(h.camera.starts, before + 1);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('photo cancelled / failed: message, scanner restarted', (
    tester,
  ) async {
    final h = await _pump(tester);
    h.camera.photoResults.addAll([null, Exception('camera')]);
    final starts = h.camera.starts;
    await _tap(tester, _k('aiCapturePhoto'));
    expect(find.text('No photo was taken.'), findsOneWidget);
    expect(h.camera.starts, starts + 1);
    await tester.pump(const Duration(seconds: 5));
    await tester.pumpAndSettle();
    await _tap(tester, _k('aiCapturePhoto'));
    expect(find.text('The camera could not take a photo.'), findsOneWidget);
    expect(_k('aiPhotoPreview'), findsNothing);
  });

  testWidgets('photo lost by Android process death is recovered', (
    tester,
  ) async {
    await _pump(tester, setup: (h) => h.camera.lost = _png);
    expect(_k('aiPhotoPreview'), findsOneWidget);
    await _tap(tester, _k('aiBack'));
    expect(_k('fakePreview'), findsOneWidget);
  });

  testWidgets('app lifecycle: camera released in background, reopened', (
    tester,
  ) async {
    final h = await _pump(tester);
    final stops = h.camera.stops, starts = h.camera.starts;
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    expect(h.camera.stops, greaterThan(stops));
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    expect(h.camera.starts, starts + 1);
    await tester.pumpWidget(const SizedBox());
    expect(h.camera.disposes, 1);
  });

  group('BarcodeLookupClient (mock HTTP)', () {
    late List<Uri> requests;
    BarcodeLookupClient client(http.Response Function(http.Request) h) {
      requests = [];
      return BarcodeLookupClient(
        ApiHttp(
          baseUrl: 'http://x/api',
          client: MockClient((r) async {
            requests.add(r.url);
            return h(r);
          }),
        )..token = 't',
      );
    }

    http.Response json(Object body, int status) => http.Response(
      jsonEncode(body),
      status,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );

    test('a non-product code is invalid without any request', () async {
      final c = client((_) => json({}, 200));
      for (final v in ['https://example.com', 'ABC123', '1234567', '']) {
        expect((await c.lookup(v, format: 'CODE_128')).invalid, isTrue);
      }
      expect(requests, isEmpty);
    });

    test('200: the store product, the stored barcode and match type', () async {
      final c = client(
        (_) => json({
          'scannedCode': '036000291452',
          'matchedBarcode': '0036000291452',
          'matchType': 'GTIN_EQUIVALENT',
          'product': {
            'productId': 5,
            'name': 'Tea',
            'barcode': '0036000291452',
            'category': 'beverages',
            'sellingPrice': 3,
            'quantity': 2,
            'minStock': 1,
            'supplierName': 'S',
            'createdAt': '2026-11-20T09:00:00',
            'updatedAt': '2026-11-20T09:00:00',
          },
        }, 200),
      );
      final r = await c.lookup('036000291452', format: 'UPC_A');
      expect(r.found, isTrue);
      expect(r.product!.name, 'Tea');
      expect(r.matchedBarcode, '0036000291452');
      expect(r.matchType, BarcodeMatchType.gtinEquivalent);
      expect(requests.single.path, '/api/products/by-barcode');
      expect(requests.single.queryParameters, {
        'code': '036000291452',
        'format': 'UPC_A',
      });
    });

    test('404 is "not found"; 400 is "invalid"; 500 is an error', () async {
      expect(
        (await client(
          (_) => json({'code': 'PRODUCT_NOT_FOUND', 'message': 'x'}, 404),
        ).lookup('4006381333931')).found,
        isFalse,
      );
      expect(
        (await client(
          (_) => json({'code': 'VALIDATION_ERROR', 'message': 'x'}, 400),
        ).lookup('4006381333931')).invalid,
        isTrue,
      );
      expect(
        client((_) => json({'message': 'x'}, 500)).lookup('4006381333931'),
        throwsA(isA<ApiException>()),
      );
    });
  });

  group('shell and sidebar', () {
    // flutter_test_config.dart turns the camera page off for every test.
    tearDown(() => debugAiCameraAvailable = false);

    AppUser user(UserRole role) => AppUser(
      userId: 1,
      username: 'u',
      fullName: 'Lina',
      role: role,
      isActive: true,
      storeId: role == UserRole.systemAdmin ? null : 1,
      storeName: role == UserRole.systemAdmin ? null : 'Shini',
    );

    Future<void> pumpShell(
      WidgetTester tester,
      UserRole role, {
      Size size = const Size(412, 915),
    }) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      final api = ApiHttp(
        baseUrl: 'http://x/api',
        client: MockClient((r) async {
          final body = r.url.path == '/api/guardian/status'
              ? {'enabled': true}
              : r.url.path == '/api/store'
              ? {
                  'storeId': 1,
                  'name': 'Shini',
                  'status': 'ACTIVE',
                  'taxRate': 16,
                }
              : r.url.path.startsWith('/api/products') ||
                    r.url.path.contains('stores')
              ? []
              : {};
          return http.Response(
            jsonEncode(body),
            200,
            headers: {'content-type': 'application/json; charset=utf-8'},
          );
        }),
      )..token = 't';
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.light(),
          supportedLocales: const [Locale('en'), Locale('ar')],
          localizationsDelegates: GlobalMaterialLocalizations.delegates,
          home: ProductStoreScope(
            store: ProductStore(api: ProductApiClient(api)),
            child: PosCartScope(
              cart: PosCart(),
              child: AppShell(
                user: user(role),
                http: api,
                createCamera: FakeCamera.new,
                onToggleLanguage: () {},
                onSetLanguage: (_) {},
                onLogout: () {},
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
    }

    test('only store roles get the page, and only with a camera', () {
      debugAiCameraAvailable = true;
      expect(UserRole.cashier.home, SidebarDestination.aiIdentify);
      expect(UserRole.storeAdmin.home, SidebarDestination.aiIdentify);
      expect(
        UserRole.systemAdmin.destinations,
        isNot(contains(SidebarDestination.aiIdentify)),
      );
      debugAiCameraAvailable = false;
      for (final r in UserRole.values) {
        expect(r.destinations, isNot(contains(SidebarDestination.aiIdentify)));
      }
      expect(UserRole.cashier.home, SidebarDestination.pos);
      expect(UserRole.storeAdmin.home, SidebarDestination.products);
    });

    test('the camera is supported on Android only (not desktop / iOS)', () {
      for (final p in TargetPlatform.values) {
        debugDefaultTargetPlatformOverride = p;
        expect(
          ProductCamera.supported,
          p == TargetPlatform.android,
          reason: '$p',
        );
      }
      debugDefaultTargetPlatformOverride = null;
    });

    for (final role in const [UserRole.cashier, UserRole.storeAdmin]) {
      testWidgets('Android phone, ${role.name}: opens on the camera page', (
        tester,
      ) async {
        debugAiCameraAvailable = true;
        await pumpShell(tester, role);
        expect(_k('aiIdentifyPage'), findsOneWidget);
        expect(_k('fakePreview'), findsOneWidget);
        // phone menu in a drawer
        await tester.tap(find.byTooltip('Open navigation menu'));
        await tester.pumpAndSettle();
        expect(_k('phoneMenu'), findsOneWidget);
        expect(_k('nav-aiIdentify'), findsOneWidget);
        await tester.tap(_k('nav-pos'));
        await tester.pumpAndSettle();
        expect(_k('aiIdentifyPage'), findsNothing);
        expect(tester.takeException(), isNull);
      });
    }

    testWidgets('web / desktop: no camera entry, layout unchanged', (
      tester,
    ) async {
      debugAiCameraAvailable = false;
      await pumpShell(tester, UserRole.cashier, size: const Size(1440, 1000));
      expect(_k('nav-aiIdentify'), findsNothing);
      expect(_k('phoneMenu'), findsNothing);
      expect(_k('aiIdentifyPage'), findsNothing);
    });

    testWidgets('System Admin never gets the camera page', (tester) async {
      debugAiCameraAvailable = true;
      await pumpShell(
        tester,
        UserRole.systemAdmin,
        size: const Size(1440, 1000),
      );
      expect(_k('nav-aiIdentify'), findsNothing);
      expect(_k('aiIdentifyPage'), findsNothing);
    });
  });

  test('tests run with the camera page off unless a test turns it on', () {
    expect(debugAiCameraAvailable, isFalse);
    expect(aiCameraAvailable, isFalse);
    expect(kIsWeb, isFalse);
  });
}

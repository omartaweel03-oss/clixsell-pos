import 'package:clixsell_pos/main.dart';
import 'package:clixsell_pos/models/supplier.dart';
import 'package:clixsell_pos/screens/purchasing/suppliers_page.dart';
import 'package:clixsell_pos/services/api_http.dart';
import 'package:clixsell_pos/services/supplier_api_client.dart';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_product_backend.dart';

/// In-memory Supplier API with the backend's rules (duplicates, WhatsApp
/// digits, no delete). Records every call so tests can check what was sent.
class FakeSupplierApi implements SupplierApi {
  FakeSupplierApi([List<Supplier>? seed]) : suppliers = [...?seed];

  final List<Supplier> suppliers;
  final List<String> calls = [];
  ApiException? failNext;
  int _nextId = 100;

  final Map<int, List<SupplierProduct>> products = {
    1: const [
      SupplierProduct(
        productId: 7,
        name: 'Milk 1L',
        barcode: '11111111',
        quantity: 20,
        minStock: 5,
      ),
      SupplierProduct(
        productId: 8,
        name: 'Labneh',
        barcode: '22222222',
        quantity: 3,
        minStock: 4,
      ),
    ],
  };

  ApiException? _takeFailure() {
    final f = failNext;
    failNext = null;
    return f;
  }

  static String _key(String n) =>
      n.trim().replaceAll(RegExp(r'\s+'), ' ').toLowerCase();

  static String? _digits(String? w) {
    if (w == null || w.trim().isEmpty) return null;
    var d = w.replaceAll(RegExp(r'[\s\-.()]'), '');
    if (d.startsWith('+')) d = d.substring(1);
    return d;
  }

  void _checkDuplicate(String name, int? except) {
    for (final s in suppliers) {
      if (s.supplierId != except && _key(s.name) == _key(name)) {
        throw ApiException(
          statusCode: 409,
          code: 'DUPLICATE_SUPPLIER',
          message: "A supplier named '${s.name}' already exists",
          fieldErrors: {'name': "A supplier named '${s.name}' already exists"},
        );
      }
    }
  }

  @override
  Future<List<Supplier>> list() async {
    calls.add('list');
    final f = _takeFailure();
    if (f != null) throw f;
    return [...suppliers];
  }

  @override
  Future<Supplier> get(int supplierId) async {
    calls.add('get $supplierId');
    final s = suppliers.firstWhere((x) => x.supplierId == supplierId);
    final p = products[supplierId] ?? const [];
    return Supplier(
      supplierId: s.supplierId,
      name: s.name,
      phone: s.phone,
      whatsappNumber: s.whatsappNumber,
      address: s.address,
      active: s.active,
      productCount: p.length,
      products: p,
    );
  }

  @override
  Future<Supplier> create({
    required String name,
    String? phone,
    String? whatsappNumber,
    String? address,
  }) async {
    calls.add('create $name|$phone|$whatsappNumber|$address');
    final f = _takeFailure();
    if (f != null) throw f;
    _checkDuplicate(name, null);
    final s = Supplier(
      supplierId: _nextId++,
      name: name.trim().replaceAll(RegExp(r'\s+'), ' '),
      phone: phone,
      whatsappNumber: _digits(whatsappNumber),
      address: address,
      active: true,
      productCount: 0,
    );
    suppliers.add(s);
    return s;
  }

  @override
  Future<Supplier> update(
    int supplierId, {
    required String name,
    String? phone,
    String? whatsappNumber,
    String? address,
  }) async {
    calls.add('update $supplierId $name|$phone|$whatsappNumber|$address');
    final f = _takeFailure();
    if (f != null) throw f;
    _checkDuplicate(name, supplierId);
    final i = suppliers.indexWhere((x) => x.supplierId == supplierId);
    final old = suppliers[i];
    final s = Supplier(
      supplierId: supplierId,
      name: name,
      phone: phone,
      whatsappNumber: _digits(whatsappNumber),
      address: address,
      active: old.active,
      productCount: old.productCount,
    );
    suppliers[i] = s;
    return s;
  }

  @override
  Future<Supplier> setActive(int supplierId, bool active) async {
    calls.add('setActive $supplierId $active');
    final f = _takeFailure();
    if (f != null) throw f;
    final i = suppliers.indexWhere((x) => x.supplierId == supplierId);
    final old = suppliers[i];
    final s = Supplier(
      supplierId: supplierId,
      name: old.name,
      phone: old.phone,
      whatsappNumber: old.whatsappNumber,
      address: old.address,
      active: active,
      productCount: old.productCount,
    );
    suppliers[i] = s;
    return s;
  }
}

List<Supplier> _seed() => const [
  Supplier(
    supplierId: 1,
    name: 'Al-Quds Dairy',
    phone: '02-2345678',
    whatsappNumber: '970599123456',
    address: 'Nablus',
    active: true,
    productCount: 2,
  ),
  Supplier(
    supplierId: 2,
    name: 'Ramallah Bakery',
    active: true,
    productCount: 1,
  ),
  Supplier(
    supplierId: 3,
    name: 'Old Traders',
    whatsappNumber: '972501234567',
    active: false,
    productCount: 0,
  ),
];

Future<void> _pump(
  WidgetTester tester,
  SupplierApi api, {
  Locale locale = const Locale('en'),
  Size size = const Size(1440, 900),
  String? fontFamily,
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(
    MaterialApp(
      locale: locale,
      supportedLocales: const [Locale('en'), Locale('ar')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      theme: fontFamily == null ? null : ThemeData(fontFamily: fontFamily),
      home: Scaffold(body: SuppliersPage(api: api)),
    ),
  );
  await tester.pumpAndSettle();
}

Finder _row(int id, String text) => find.descendant(
  of: find.byKey(ValueKey('supplier-$id')),
  matching: find.text(text),
);

void main() {
  testWidgets('lists suppliers with WhatsApp, products and status', (
    tester,
  ) async {
    await _pump(tester, FakeSupplierApi(_seed()));
    expect(find.text('Suppliers'), findsWidgets);
    expect(_row(1, 'Al-Quds Dairy'), findsOneWidget);
    expect(_row(1, '+970 599 123 456'), findsOneWidget);
    expect(_row(1, '2 products'), findsOneWidget);
    expect(_row(2, 'No WhatsApp number'), findsOneWidget);
    expect(_row(3, 'Inactive'), findsOneWidget);
    expect(
      find.descendant(
        of: find.byKey(const Key('supplier-toggle-3')),
        matching: find.text('Activate'),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: find.byKey(const Key('supplier-toggle-1')),
        matching: find.text('Deactivate'),
      ),
      findsOneWidget,
    );
    // nothing about purchase orders or messages in Phase 1
    expect(find.textContaining('Purchase'), findsNothing);
    expect(find.textContaining('Send'), findsNothing);
  });

  testWidgets('empty list, load error and retry', (tester) async {
    final api = FakeSupplierApi();
    api.failNext = const ApiException.network('offline');
    await _pump(tester, api);
    expect(find.byIcon(Icons.cloud_off_rounded), findsOneWidget);
    await tester.tap(find.text('Try again'));
    await tester.pumpAndSettle();
    expect(find.text('No suppliers yet'), findsOneWidget);
    expect(api.calls.where((c) => c == 'list'), hasLength(2));
  });

  testWidgets('adds a supplier with client-side and server-side validation', (
    tester,
  ) async {
    final api = FakeSupplierApi(_seed());
    await _pump(tester, api);
    await tester.tap(find.byKey(const Key('addSupplierButton')));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('supplierSaveButton')));
    await tester.pumpAndSettle();
    expect(find.text('Supplier name is required'), findsOneWidget);

    await tester.enterText(
      find.byKey(const Key('supplierName')),
      'Fresh Farms',
    );
    await tester.enterText(
      find.byKey(const Key('supplierWhatsapp')),
      '0599123456',
    ); // local: refused
    await tester.tap(find.byKey(const Key('supplierSaveButton')));
    await tester.pumpAndSettle();
    expect(
      find.textContaining('international format with the country code'),
      findsOneWidget,
    );
    expect(api.calls.where((c) => c.startsWith('create')), isEmpty);

    // a duplicate name is reported by the backend under the name field
    await tester.enterText(
      find.byKey(const Key('supplierName')),
      'al-quds   dairy',
    );
    await tester.enterText(
      find.byKey(const Key('supplierWhatsapp')),
      '+970 59 912 3457',
    );
    await tester.tap(find.byKey(const Key('supplierSaveButton')));
    await tester.pumpAndSettle();
    expect(
      find.text('A supplier named "Al-Quds Dairy" already exists'),
      findsOneWidget,
    );

    await tester.enterText(
      find.byKey(const Key('supplierName')),
      'Fresh Farms',
    );
    await tester.enterText(
      find.byKey(const Key('supplierPhone')),
      '02 2981234',
    );
    await tester.tap(find.byKey(const Key('supplierSaveButton')));
    await tester.pumpAndSettle();
    expect(find.byType(AlertDialog), findsNothing);
    expect(find.text('Supplier "Fresh Farms" added'), findsOneWidget);
    expect(find.text('+970 599 123 457'), findsOneWidget);
    expect(
      api.calls.last,
      'create Fresh Farms|02 2981234|+970 59 912 3457|null',
    );
  });

  testWidgets('edits a supplier and sends the name exactly as typed', (
    tester,
  ) async {
    final api = FakeSupplierApi(_seed());
    await _pump(tester, api);
    await tester.tap(find.byKey(const Key('supplier-edit-2')));
    await tester.pumpAndSettle();
    expect(
      find.widgetWithText(TextFormField, 'Ramallah Bakery'),
      findsOneWidget,
    );
    await tester.enterText(
      find.byKey(const Key('supplierWhatsapp')),
      '970598000111',
    );
    await tester.tap(find.byKey(const Key('supplierSaveButton')));
    await tester.pumpAndSettle();
    expect(api.calls.last, 'update 2 Ramallah Bakery|null|970598000111|null');
    expect(_row(2, '+970 598 000 111'), findsOneWidget);
    expect(find.text('Supplier "Ramallah Bakery" saved'), findsOneWidget);
  });

  testWidgets(
    'deactivation asks first and explains that products keep selling',
    (tester) async {
      final api = FakeSupplierApi(_seed());
      await _pump(tester, api);
      await tester.tap(find.byKey(const Key('supplier-toggle-1')));
      await tester.pumpAndSettle();
      expect(find.text('Deactivate supplier?'), findsOneWidget);
      expect(find.textContaining('keep selling in the POS'), findsOneWidget);
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      expect(api.calls.where((c) => c.startsWith('setActive')), isEmpty);

      await tester.tap(find.byKey(const Key('supplier-toggle-1')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('confirmActionButton')));
      await tester.pumpAndSettle();
      expect(api.calls.last, 'setActive 1 false');
      expect(_row(1, 'Inactive'), findsOneWidget);
      expect(_row(1, '2 products'), findsOneWidget);

      // activating needs no confirmation
      await tester.tap(find.byKey(const Key('supplier-toggle-3')));
      await tester.pumpAndSettle();
      expect(api.calls.last, 'setActive 3 true');
      expect(_row(3, 'Active'), findsOneWidget);
    },
  );

  testWidgets('a failed status change shows an error and changes nothing', (
    tester,
  ) async {
    final api = FakeSupplierApi(_seed());
    await _pump(tester, api);
    api.failNext = const ApiException(
      statusCode: 403,
      code: 'FORBIDDEN',
      message: 'no',
    );
    await tester.tap(find.byKey(const Key('supplier-toggle-3')));
    await tester.pumpAndSettle();
    expect(_row(3, 'Inactive'), findsOneWidget);
    expect(find.byType(SnackBar), findsOneWidget);
  });

  testWidgets('filters by status and searches by name or number', (
    tester,
  ) async {
    await _pump(tester, FakeSupplierApi(_seed()));
    await tester.tap(find.text('Inactive').first);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('supplier-3')), findsOneWidget);
    expect(find.byKey(const ValueKey('supplier-1')), findsNothing);
    await tester.tap(find.text('All'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('supplierSearch')), '599 123');
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('supplier-1')), findsOneWidget);
    expect(find.byKey(const ValueKey('supplier-2')), findsNothing);
    await tester.enterText(find.byKey(const Key('supplierSearch')), 'bakery');
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('supplier-2')), findsOneWidget);
    await tester.enterText(find.byKey(const Key('supplierSearch')), 'zzz');
    await tester.pumpAndSettle();
    expect(find.text('No matching suppliers'), findsOneWidget);
    // the search field keeps its accessible label while text is typed
    final field = tester.widget<TextField>(
      find.byKey(const Key('supplierSearch')),
    );
    expect(field.decoration!.labelText, isNotEmpty);
  });

  testWidgets('details show contact data and the supplier products', (
    tester,
  ) async {
    final api = FakeSupplierApi(_seed());
    await _pump(tester, api);
    await tester.tap(find.byKey(const Key('supplier-details-1')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('supplierDetailsDialog')), findsOneWidget);
    expect(find.text('Milk 1L'), findsOneWidget);
    expect(find.text('11111111 · Stock 20 · min 5'), findsOneWidget);
    await tester.tap(find.byKey(const Key('supplierDetailsClose')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('supplierDetailsDialog')), findsNothing);
  });

  testWidgets('Arabic: right-to-left, translated, numbers stay left-to-right', (
    tester,
  ) async {
    await _pump(tester, FakeSupplierApi(_seed()), locale: const Locale('ar'));
    expect(find.text('الموردون'), findsWidgets);
    expect(find.text('إضافة مورد'), findsOneWidget);
    expect(_row(1, '+970 599 123 456'), findsOneWidget);
    expect(_row(2, 'لا يوجد رقم WhatsApp'), findsOneWidget);
    expect(_row(1, '2 منتجات'), findsOneWidget);
    expect(
      Directionality.of(
        tester.element(find.byKey(const ValueKey('supplier-1'))),
      ),
      TextDirection.rtl,
    );
    // Numbers read left-to-right but sit at the START of their cell (the right
    // side in Arabic), so the phone and WhatsApp numbers never run together.
    // (Measured in real fonts by the browser test supplier_ui.js.)
    for (final number in ['+970 599 123 456', '02-2345678']) {
      final align = tester.widget<Align>(
        find.ancestor(of: _row(1, number), matching: find.byType(Align)).first,
      );
      expect(align.alignment, AlignmentDirectional.centerStart, reason: number);
    }
    await tester.tap(find.byKey(const Key('supplier-toggle-1')));
    await tester.pumpAndSettle();
    expect(find.text('إيقاف المورد؟'), findsOneWidget);
  });

  testWidgets('Arabic with a real font: phone and WhatsApp numbers stay apart', (
    tester,
  ) async {
    // The default test font draws every character as wide as the font size,
    // so text widths are not realistic. Load Roboto from the Flutter SDK.
    final fonts =
        '${Platform.environment['FLUTTER_ROOT']}/bin/cache/artifacts/material_fonts';
    final loader = FontLoader('RealRoboto')
      ..addFont(
        Future.value(
          ByteData.sublistView(
            File('$fonts/Roboto-Regular.ttf').readAsBytesSync(),
          ),
        ),
      )
      ..addFont(
        Future.value(
          ByteData.sublistView(
            File('$fonts/Roboto-Bold.ttf').readAsBytesSync(),
          ),
        ),
      );
    await loader.load();
    await _pump(
      tester,
      FakeSupplierApi(_seed()),
      locale: const Locale('ar'),
      fontFamily: 'RealRoboto',
    );
    final whatsapp = tester.getRect(_row(1, '+970 599 123 456'));
    final phone = tester.getRect(_row(1, '02-2345678'));
    // Arabic: the WhatsApp column is to the right of the phone column.
    expect(whatsapp.left - phone.right, greaterThan(16));
  });

  for (final size in const [
    Size(1024, 768),
    Size(1280, 800),
    Size(1920, 1080),
  ]) {
    testWidgets('desktop layout without overflow at $size', (tester) async {
      await _pump(tester, FakeSupplierApi(_seed()), size: size);
      expect(tester.takeException(), isNull);
      await tester.tap(find.byKey(const Key('addSupplierButton')));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });
  }

  group('in the app', () {
    Future<void> login(
      WidgetTester tester,
      FakeProductBackend backend,
      String user,
      String password,
    ) async {
      tester.view.physicalSize = const Size(1440, 1000);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(ClixSellApp(http: backend.newHttp()));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('loginUsername')), user);
      await tester.enterText(find.byKey(const Key('loginPassword')), password);
      await tester.tap(find.byKey(const Key('loginButton')));
      await tester.pumpAndSettle();
    }

    testWidgets('Store Admins see Suppliers in the menu; Cashiers do not', (
      tester,
    ) async {
      await login(tester, FakeProductBackend(), 'shini.admin', 'Admin-Pass-1');
      expect(find.byKey(const Key('nav-suppliers')), findsOneWidget);
    });

    testWidgets('Cashier menu has no Suppliers', (tester) async {
      await login(
        tester,
        FakeProductBackend(),
        'shini.cashier',
        'Cashier-Pass-1',
      );
      expect(find.byKey(const Key('nav-suppliers')), findsNothing);
    });

    testWidgets(
      'the product form explains an inactive supplier in the user language',
      (tester) async {
        final backend = FakeProductBackend()
          ..inactiveSuppliers.add('old traders');
        await login(tester, backend, 'shini.admin', 'Admin-Pass-1');
        await tester.tap(find.byKey(const Key('nav-addProduct')));
        await tester.pumpAndSettle();
        await tester.enterText(
          find.byKey(const Key('nameField')),
          'Orange Juice',
        );
        await tester.enterText(
          find.byKey(const Key('barcodeField')),
          '44444444',
        );
        await tester.tap(find.byKey(const Key('categoryField')));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Beverages').last);
        await tester.pumpAndSettle();
        await tester.enterText(find.byKey(const Key('priceField')), '8.5');
        await tester.enterText(find.byKey(const Key('quantityField')), '4');
        await tester.enterText(find.byKey(const Key('minStockField')), '6');
        await tester.enterText(
          find.byKey(const Key('supplierField')),
          'Old Traders',
        );
        await tester.pumpAndSettle();
        await tester.ensureVisible(find.byKey(const Key('saveButton')));
        await tester.tap(find.byKey(const Key('saveButton')));
        await tester.pumpAndSettle();
        expect(
          find.textContaining('This supplier is inactive'),
          findsOneWidget,
        );
        expect(backend.rows.where((r) => r['barcode'] == '44444444'), isEmpty);
      },
    );
  });
}

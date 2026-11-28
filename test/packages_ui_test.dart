import 'dart:convert';

import 'package:clixsell_pos/models/product.dart';
import 'package:clixsell_pos/models/product_category.dart';
import 'package:clixsell_pos/models/product_package.dart';
import 'package:clixsell_pos/models/sale.dart';
import 'package:clixsell_pos/screens/inventory/product_packages_card.dart';
import 'package:clixsell_pos/screens/pos/package_choice_dialog.dart';
import 'package:clixsell_pos/services/api_http.dart';
import 'package:clixsell_pos/services/package_api_client.dart';
import 'package:clixsell_pos/state/pos_cart.dart';
import 'package:clixsell_pos/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

Product _water({int qty = 30}) => Product(
  id: '7',
  name: 'Water',
  barcode: '4006381333931',
  category: ProductCategory.values.first,
  price: 2,
  quantity: qty,
  minStockLevel: 2,
  supplier: 'S',
  createdAt: DateTime(2026),
  updatedAt: DateTime(2026),
);

const _carton = ProductPackage(
  packageId: 3,
  productId: '7',
  name: 'Carton 24',
  unitsPerPackage: 24,
  price: 40,
  active: true,
  barcode: '7290000000244',
);

Future<void> _pump(WidgetTester tester, Widget w, {Locale locale = const Locale('en')}) async {
  tester.view.physicalSize = const Size(1280, 900);
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

void main() {
  group('cart with pieces and cartons', () {
    test('a carton is its own line; stock is shared in pieces; totals per package price', () {
      final cart = PosCart()..taxRate = 0;
      final water = _water();
      expect(cart.add(water, package: _carton), AddResult.added);
      for (var i = 0; i < 6; i++) {
        expect(cart.add(water), AddResult.added);
      }
      expect(cart.lines.map((l) => l.key), ['7@3', '7']);
      expect(cart.piecesOf('7'), 30);
      expect(cart.add(water), AddResult.stockLimit); // 31 > 30
      expect(cart.increment('7@3'), AddResult.stockLimit); // +24
      expect(cart.totalCents, 4000 + 6 * 200);
      expect(cart.lines.first.displayName, 'Water · Carton 24');
    });

    test('the draft sends packageId only for package lines', () {
      final cart = PosCart();
      cart.add(_water(), package: _carton);
      cart.add(_water());
      final items = cart.toDraft().toJson()['items'] as List;
      expect(items, [
        {'productId': 7, 'quantity': 1, 'packageId': 3},
        {'productId': 7, 'quantity': 1},
      ]);
    });

    test('a carton bigger than the stock is refused', () {
      final cart = PosCart();
      expect(cart.add(_water(qty: 10), package: _carton), AddResult.stockLimit);
      expect(cart.isEmpty, isTrue);
    });

    test('resuming an invoice with a carton: the line comes back; an inactive package is reported', () {
      final sale = Sale.fromJson({
        'saleId': 5, 'invoiceNumber': 'INV-5', 'status': 'SUSPENDED', 'cashierName': 'A', 'itemCount': 2,
        'subtotal': 42, 'discount': 0, 'taxRate': 0, 'tax': 0, 'total': 42, 'createdAt': '2026-11-28T10:00:00',
        'items': [
          {'saleItemId': 1, 'productId': 7, 'productName': 'Water', 'quantity': 1, 'unitPrice': 40, 'lineTotal': 40,
           'returnedQuantity': 0, 'returnableQuantity': 1, 'packageId': 3, 'packageName': 'Carton 24', 'unitsPerPackage': 24},
          {'saleItemId': 2, 'productId': 7, 'productName': 'Water', 'quantity': 1, 'unitPrice': 2, 'lineTotal': 2,
           'returnedQuantity': 0, 'returnableQuantity': 1, 'unitsPerPackage': 1},
        ],
      });
      final cart = PosCart();
      expect(cart.resume(sale, [_water()], packages: const [_carton]), isEmpty);
      expect(cart.lines.map((l) => l.key), ['7@3', '7']);
      expect(cart.hasUnsavedChanges, isFalse);
      final missing = PosCart().resume(sale, [_water()]);
      expect(missing, ['Water · Carton 24']);
    });
  });

  group('cash rounding on the POS (display; the backend decides)', () {
    Product item(double price) => Product(
      id: '9', name: 'Oil', barcode: '4006381333931', category: ProductCategory.values.first, price: price,
      quantity: 10, minStockLevel: 1, supplier: 'S', createdAt: DateTime(2026), updatedAt: DateTime(2026));

    test('19.97 cash at 0.10 → 20.00 due; card is never rounded; none by default', () {
      final cart = PosCart()..taxRate = 0;
      cart.add(item(19.97));
      expect(cart.roundingCents, 0);
      cart.cashRoundingMinor = 10;
      expect(cart.roundingCents, 3);
      expect(cart.amountDueCents, 2000);
      expect(cart.totalCents, 1997); // the total itself never changes
      cart.setPaymentMethod(PaymentMethod.card);
      expect(cart.roundingCents, 0);
      expect(cart.amountDueCents, 1997);
    });

    test('midpoint up, other steps', () {
      final cart = PosCart()..taxRate = 0..cashRoundingMinor = 10;
      cart.add(item(19.95));
      expect(cart.amountDueCents, 2000);
      cart.cashRoundingMinor = 50;
      expect(cart.amountDueCents, 2000);
      cart.cashRoundingMinor = 5;
      expect(cart.amountDueCents, 1995);
    });
  });

  testWidgets('POS: "piece or carton?" with both prices', (tester) async {
    Object? chosen = 'none';
    await _pump(tester, Builder(builder: (ctx) => TextButton(
      onPressed: () async => chosen = await showDialog<Object>(
        context: ctx,
        builder: (_) => PackageChoiceDialog(product: _water(), packages: const [_carton]),
      ),
      child: const Text('tap'),
    )), locale: const Locale('ar'));
    await tester.tap(find.text('tap'));
    await tester.pumpAndSettle();
    expect(find.text('حبة'), findsOneWidget);
    expect(find.text('24 حبة'), findsOneWidget);
    await tester.tap(find.byKey(const Key('choosePackage-3')));
    await tester.pumpAndSettle();
    expect(chosen, _carton);
  });

  testWidgets('Store Admin adds a carton: exact request; the size cannot be edited later', (tester) async {
    final sent = <http.Request>[];
    final rows = <Map<String, dynamic>>[];
    final api = PackageApiClient(ApiHttp(baseUrl: 'http://x/api', client: MockClient((r) async {
      sent.add(r);
      if (r.method == 'POST') {
        final b = jsonDecode(r.body) as Map<String, dynamic>;
        rows.add({'packageId': 3, 'productId': 7, 'active': true, ...b});
        return http.Response(jsonEncode(rows.last), 201, headers: {'content-type': 'application/json'});
      }
      return http.Response(jsonEncode(rows), 200, headers: {'content-type': 'application/json'});
    })));
    await _pump(tester, SingleChildScrollView(child: ProductPackagesCard(api: api, productId: '7')));
    expect(find.text('No cartons or boxes yet.'), findsOneWidget);
    await tester.tap(find.byKey(const Key('addPackage')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('packageName')), 'Carton 24');
    await tester.enterText(find.byKey(const Key('packageUnits')), '1');
    await tester.enterText(find.byKey(const Key('packagePrice')), '40');
    await tester.tap(find.byKey(const Key('packageSave')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('packageError')), findsOneWidget); // 1 piece is not a package
    await tester.enterText(find.byKey(const Key('packageUnits')), '24');
    await tester.enterText(find.byKey(const Key('packageBarcode')), '7290000000244');
    await tester.tap(find.byKey(const Key('packageSave')));
    await tester.pumpAndSettle();
    final post = sent.where((r) => r.method == 'POST').single;
    expect(post.url.path, '/api/products/7/packages');
    expect(jsonDecode(post.body), {'name': 'Carton 24', 'unitsPerPackage': 24, 'price': 40, 'barcode': '7290000000244'});
    expect(find.text('Carton 24 · 24 pieces'), findsOneWidget);
    await tester.tap(find.byKey(const Key('package-3')));
    await tester.pumpAndSettle();
    expect(tester.widget<TextField>(find.byKey(const Key('packageUnits'))).enabled, isFalse);
  });
}

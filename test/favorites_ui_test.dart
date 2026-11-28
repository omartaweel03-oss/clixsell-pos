import 'dart:convert';

import 'package:clixsell_pos/models/product.dart';
import 'package:clixsell_pos/models/product_category.dart';
import 'package:clixsell_pos/screens/favorites/favorites_dialog.dart';
import 'package:clixsell_pos/services/api_http.dart';
import 'package:clixsell_pos/services/favorite_api_client.dart';
import 'package:clixsell_pos/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

Product _p(String id, String name, {int qty = 10}) => Product(
  id: id,
  name: name,
  barcode: '4006381333$id',
  category: ProductCategory.values.first,
  price: 5,
  quantity: qty,
  minStockLevel: 2,
  supplier: 'S',
  createdAt: DateTime(2026),
  updatedAt: DateTime(2026),
);

final _products = [_p('1', 'Milk'), _p('2', 'Bread'), _p('3', 'Eggs', qty: 0)];

class _Server {
  List<int> saved = [1, 2];
  final puts = <List<dynamic>>[];
  int failPut = 0;

  Future<http.Response> handle(http.Request r) async {
    if (r.method == 'PUT') {
      if (failPut > 0) {
        failPut--;
        return http.Response(jsonEncode({'status': 404, 'code': 'PRODUCT_NOT_FOUND', 'message': 'Product 9 is not available', 'fieldErrors': {}}), 404,
            headers: {'content-type': 'application/json'});
      }
      final ids = (jsonDecode(r.body)['productIds'] as List).cast<int>();
      puts.add(ids);
      saved = ids;
    }
    return http.Response(
      jsonEncode([for (var i = 0; i < saved.length; i++) {'productId': saved[i], 'name': 'x', 'position': i}]),
      200,
      headers: {'content-type': 'application/json'},
    );
  }

  FavoriteApiClient client() => FavoriteApiClient(ApiHttp(baseUrl: 'http://f/api', client: MockClient(handle)));
}

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

Finder _k(String k) => find.byKey(Key(k));

Future<void> _tap(WidgetTester tester, Finder f) async {
  await tester.ensureVisible(f);
  await tester.tap(f);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('Store Admin reorders, adds and removes; the complete list is saved in order', (tester) async {
    final server = _Server();
    bool? result;
    await _pump(tester, Builder(builder: (context) => TextButton(
      onPressed: () async => result = await FavoritesDialog.show(context, server.client(), _products),
      child: const Text('open'),
    )));
    await _tap(tester, find.text('open'));
    expect(find.text('1. Milk'), findsOneWidget);
    expect(find.text('2. Bread'), findsOneWidget);
    await _tap(tester, _k('favoriteUp-2')); // Bread first
    expect(find.text('1. Bread'), findsOneWidget);
    await tester.tap(_k('favoriteAddField'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Eggs').last);
    await tester.pumpAndSettle();
    await _tap(tester, _k('favoriteAddButton'));
    await _tap(tester, _k('favoriteRemove-1')); // drop Milk
    await _tap(tester, _k('favoritesSave'));
    expect(server.puts, [
      [2, 3],
    ]);
    expect(result, isTrue);
  });

  testWidgets('a refused save keeps the dialog open with the reason; nothing is lost', (tester) async {
    final server = _Server()..failPut = 1;
    await _pump(tester, Builder(builder: (context) => TextButton(
      onPressed: () => FavoritesDialog.show(context, server.client(), _products),
      child: const Text('open'),
    )));
    await _tap(tester, find.text('open'));
    await _tap(tester, _k('favoritesSave'));
    expect(_k('favoritesError'), findsOneWidget);
    expect(_k('favoritesDialog'), findsOneWidget);
    expect(find.text('1. Milk'), findsOneWidget);
  });

  testWidgets('POS strip: favorites in order, one tap adds; Arabic right to left', (tester) async {
    final added = <String>[];
    await _pump(
      tester,
      PosFavoritesStrip(products: [_products[1], _products[0]], onAdd: (p) => added.add(p.name)),
      locale: const Locale('ar'),
    );
    expect(find.text('المفضلة'), findsOneWidget);
    final bread = tester.getCenter(_k('posFavorite-2'));
    final milk = tester.getCenter(_k('posFavorite-1'));
    expect(bread.dx, greaterThan(milk.dx)); // first favorite on the right (RTL)
    await _tap(tester, _k('posFavorite-2'));
    expect(added, ['Bread']);
  });

  testWidgets('no favorites: nothing is shown', (tester) async {
    await _pump(tester, PosFavoritesStrip(products: const [], onAdd: (_) {}));
    expect(_k('posFavorites'), findsNothing);
  });
}

import 'dart:convert';
import 'dart:typed_data';

import 'package:clixsell_pos/models/product_file.dart';
import 'package:clixsell_pos/screens/inventory/inventory_page.dart';
import 'package:clixsell_pos/services/api_http.dart';
import 'package:clixsell_pos/services/product_file_client.dart';
import 'package:clixsell_pos/state/product_store.dart';
import 'package:clixsell_pos/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'support/fake_product_backend.dart';

/// Product import / export on the Products screen (UR-M11, Figure 3.3):
/// buttons only for Store Admins, export saved as a file, import previewed by
/// the server, confirmed explicitly, sent once, and reconciled after a lost
/// answer. Plus the real API client's request / response contract.

Map<String, dynamic> _row(
  int line,
  String name,
  String barcode, {
  Map<String, String> errors = const {},
  String? supplierStatus = 'EXISTING',
}) => {
  'line': line,
  'values': {
    'name': name,
    'barcode': barcode,
    'category': 'dairy',
    'sellingPrice': '5',
    'costPrice': '',
    'quantity': '10',
    'minStock': '2',
    'supplier': 'Dairy Co',
  },
  'supplierStatus': supplierStatus,
  'errors': errors,
};

Map<String, dynamic> _preview({
  List<Map<String, dynamic>>? rows,
  List<String> fileErrors = const [],
  List<String> newSuppliers = const [],
}) {
  final r =
      rows ??
      [_row(2, 'Imported Milk', '61000001'), _row(3, 'لبنة', '61000002')];
  final valid = r.where((x) => (x['errors'] as Map).isEmpty).length;
  return {
    'fileName': 'products.csv',
    'fileHash': 'a' * 64,
    'totalRows': r.length,
    'validRows': valid,
    'invalidRows': r.length - valid,
    'fileErrors': fileErrors,
    'newSuppliers': newSuppliers,
    'rows': fileErrors.isEmpty ? r : const [],
    'canImport': fileErrors.isEmpty && valid == r.length,
    'maxRows': 5000,
    'maxBytes': 2097152,
  };
}

class _FakeFiles implements ProductFileApi {
  _FakeFiles(this.backend);

  final FakeProductBackend backend;
  Map<String, dynamic> previewJson = _preview();
  final List<String> calls = [];
  ApiException? importFails;
  bool importLostButSaved = false;

  @override
  Future<ProductExport> export() async {
    calls.add('export');
    return ProductExport(
      fileName: 'clixsell-products-2026-11-25.csv',
      bytes: utf8.encode('name,barcode\n'),
      count: 3,
    );
  }

  @override
  Future<ImportPreview> preview(String fileName, Uint8List bytes) async {
    calls.add('preview:$fileName:${bytes.length}');
    return ImportPreview.fromJson(previewJson);
  }

  @override
  Future<ImportResult> importFile(
    String fileName,
    Uint8List bytes,
    String previewHash,
  ) async {
    calls.add('import:$previewHash');
    void create() {
      for (final r in previewJson['rows'] as List) {
        final v = (r as Map)['values'] as Map;
        backend.seed(
          name: v['name'] as String,
          barcode: v['barcode'] as String,
          price: 5,
          quantity: 10,
          minStock: 2,
        );
      }
    }

    if (importLostButSaved) {
      create();
      throw const ApiException.network('The server did not respond in time');
    }
    final f = importFails;
    if (f != null) throw f;
    create();
    return ImportResult(
      created: (previewJson['rows'] as List).length,
      suppliersCreated: const [],
    );
  }
}

class _FakeIO implements ProductFileIO {
  PickedFile? next = (
    name: 'products.csv',
    bytes: Uint8List.fromList(utf8.encode('name,barcode\nx,1\n')),
  );
  final List<String> saved = [];
  bool cancelSave = false;

  @override
  Future<PickedFile?> pickCsv() async => next;

  SaveOutcome outcome = SaveOutcome.saved;

  @override
  Future<SaveOutcome> save(String fileName, Uint8List bytes) async {
    if (cancelSave) return SaveOutcome.cancelled;
    saved.add('$fileName:${bytes.length}');
    return outcome;
  }
}

Future<ProductStore> _pump(
  WidgetTester tester,
  FakeProductBackend backend, {
  _FakeFiles? files,
  _FakeIO? io,
  bool readOnly = false,
  Locale locale = const Locale('en'),
}) async {
  tester.view.physicalSize = const Size(1440, 1000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final store = ProductStore(api: backend.apiClient());
  await store.load();
  await tester.pumpWidget(
    MaterialApp(
      theme: AppTheme.light(),
      locale: locale,
      supportedLocales: const [Locale('en'), Locale('ar')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      home: Scaffold(
        body: ProductStoreScope(
          store: store,
          child: InventoryPage(
            onAddProduct: () {},
            onEditProduct: (_) {},
            readOnly: readOnly,
            productFiles: files,
            fileIO: io ?? _FakeIO(),
          ),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return store;
}

Finder _k(String k) => find.byKey(Key(k));

Future<void> _tap(WidgetTester tester, String k) async {
  await tester.ensureVisible(_k(k));
  await tester.tap(_k(k));
  await tester.pumpAndSettle();
}

bool _enabled(WidgetTester tester, String k) =>
    tester.widget<ButtonStyleButton>(_k(k)).onPressed != null;

FakeProductBackend _backend() => FakeProductBackend()
  ..seed(
    name: 'Milk',
    barcode: '11111111',
    price: 5,
    quantity: 24,
    minStock: 5,
  );

void main() {
  testWidgets('cashier: no import or export buttons', (tester) async {
    await _pump(tester, _backend(), readOnly: true);
    expect(_k('headerImportButton'), findsNothing);
    expect(_k('headerExportButton'), findsNothing);
  });

  testWidgets('export: the server file is saved; cancel says nothing saved', (
    tester,
  ) async {
    final backend = _backend();
    final files = _FakeFiles(backend);
    final io = _FakeIO();
    await _pump(tester, backend, files: files, io: io);
    expect(find.text('Import'), findsOneWidget);
    expect(find.text('Export'), findsOneWidget);
    await _tap(tester, 'headerExportButton');
    expect(files.calls, ['export']);
    expect(io.saved.single, startsWith('clixsell-products-2026-11-25.csv'));
    expect(find.textContaining('Exported'), findsOneWidget);
    expect(find.textContaining('3'), findsWidgets);
    // web: handed to the browser as a download — never reported as "cancelled"
    io.outcome = SaveOutcome.downloaded;
    await _tap(tester, 'headerExportButton');
    expect(find.textContaining('the browser is downloading'), findsOneWidget);
    expect(find.textContaining('no file was saved'), findsNothing);
    io.cancelSave = true;
    await _tap(tester, 'headerExportButton');
    expect(find.textContaining('no file was saved'), findsOneWidget);
  });

  testWidgets(
    'import: preview first (nothing saved), confirmation required, sent once, list reloaded',
    (tester) async {
      final backend = _backend();
      final files = _FakeFiles(backend)
        ..previewJson = _preview(newSuppliers: ['New Farm']);
      final store = await _pump(tester, backend, files: files);
      expect(store.products.length, 1);
      await _tap(tester, 'headerImportButton');
      expect(files.calls.single, startsWith('preview:products.csv:'));
      expect(_k('importDialog'), findsOneWidget);
      expect(find.textContaining('Nothing has been saved yet'), findsOneWidget);

      expect(_k('importSummary'), findsOneWidget);
      expect(find.textContaining('New Farm'), findsOneWidget);
      expect(
        find.textContaining('Only NEW products are created'),
        findsOneWidget,
      );
      expect(find.text('\u2068Imported Milk\u2069'), findsOneWidget);
      expect(
        _enabled(tester, 'importConfirmButton'),
        isFalse,
        reason: 'needs the confirmation box',
      );
      await _tap(tester, 'importConfirmBox');
      expect(_enabled(tester, 'importConfirmButton'), isTrue);
      await tester.tap(_k('importConfirmButton'));
      await tester.tap(_k('importConfirmButton'), warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(files.calls.where((c) => c.startsWith('import:')), [
        'import:${'a' * 64}',
      ], reason: 'one import even with a double click');
      expect(_k('importResult'), findsOneWidget);
      expect(
        find.textContaining(RegExp(r'2\u2069? new products were created')),
        findsOneWidget,
      );
      await _tap(tester, 'importCloseButton');
      expect(store.products.length, 3, reason: 'list reloaded from the server');
    },
  );

  testWidgets(
    'import with row errors: errors shown per row (EN), import impossible',
    (tester) async {
      final backend = _backend();
      final files = _FakeFiles(backend)
        ..previewJson = _preview(
          rows: [
            _row(2, 'Good', '61000001'),
            _row(
              3,
              'Dup',
              '61000001',
              errors: {'barcode': 'DUPLICATE_IN_FILE:2'},
            ),
            _row(4, 'Old', '11111111', errors: {'barcode': 'EXISTS_IN_STORE'}),
            _row(
              5,
              'Bad',
              '61000005',
              errors: {
                'sellingPrice': 'NOT_A_NUMBER',
                'quantity': 'Quantity cannot be negative',
              },
            ),
            _row(
              6,
              'Off',
              '61000006',
              errors: {'supplier': 'SUPPLIER_INACTIVE'},
              supplierStatus: 'INACTIVE',
            ),
          ],
        );
      await _pump(tester, backend, files: files);
      await _tap(tester, 'headerImportButton');
      expect(
        find.textContaining(RegExp(r'5\u2069? products in the file')),
        findsOneWidget,
      );
      expect(_k('importFixHint'), findsOneWidget);
      expect(_k('importConfirmBox'), findsNothing);
      expect(_enabled(tester, 'importConfirmButton'), isFalse);
      String errs(int line) =>
          tester.widget<Text>(_k('importRowErrors-$line')).data!;
      expect(errs(3), contains('Same barcode as line'));
      expect(errs(4), contains('already exists'));
      expect(errs(5), contains('Not a number'));
      expect(errs(5), contains('cannot be negative'));
      expect(errs(6), contains('inactive'));
      // values are isolated: "-3" can never be shown as "3-" in a right-to-left cell
      expect(find.text('\u2068Bad\u2069'), findsOneWidget);
      expect(_k('importRowErrors-2'), findsNothing);
      // rows with errors are listed first
      final first = tester.getTopLeft(_k('importRow-3')).dy;
      final good = tester.getTopLeft(_k('importRow-2')).dy;
      expect(first, lessThan(good));
      await _tap(tester, 'importCancelButton');
      expect(files.calls.where((c) => c.startsWith('import:')), isEmpty);
    },
  );

  testWidgets('import, Arabic: file errors and row errors in Arabic, RTL', (
    tester,
  ) async {
    final backend = _backend();
    final files = _FakeFiles(backend)
      ..previewJson = _preview(
        fileErrors: ['MISSING_COLUMN:barcode', 'NOT_UTF8'],
      );
    await _pump(tester, backend, files: files, locale: const Locale('ar'));
    expect(find.text('استيراد'), findsOneWidget);
    expect(find.text('تصدير'), findsOneWidget);
    await _tap(tester, 'headerImportButton');
    expect(find.textContaining('عمود ناقص'), findsOneWidget);
    expect(find.textContaining('UTF-8'), findsOneWidget);
    expect(_enabled(tester, 'importConfirmButton'), isFalse);
    expect(
      Directionality.of(tester.element(_k('importDialog'))),
      TextDirection.rtl,
    );
    expect(tester.takeException(), isNull);
    await _tap(tester, 'importCancelButton');

    files.previewJson = _preview(
      rows: [
        _row(
          2,
          'سعر',
          '61000001',
          errors: {'sellingPrice': 'Price must be greater than 0'},
        ),
      ],
    );
    await _tap(tester, 'headerImportButton');
    expect(
      tester.widget<Text>(_k('importRowErrors-2')).data,
      contains('أكبر من 0'),
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'lost answer: the store is checked; saved → success, not saved → safe retry',
    (tester) async {
      final backend = _backend();
      final files = _FakeFiles(backend)..importLostButSaved = true;
      final store = await _pump(tester, backend, files: files);
      await _tap(tester, 'headerImportButton');
      await _tap(tester, 'importConfirmBox');
      await _tap(tester, 'importConfirmButton');
      expect(find.textContaining('the import was completed'), findsOneWidget);
      expect(_k('importResult'), findsOneWidget);
      await _tap(tester, 'importCloseButton');
      expect(store.products.length, 3);

      final backend2 = _backend();
      final files2 = _FakeFiles(backend2)
        ..importFails = const ApiException.network('timeout');
      await _pump(tester, backend2, files: files2);
      await _tap(tester, 'headerImportButton');
      await _tap(tester, 'importConfirmBox');
      await _tap(tester, 'importConfirmButton');
      expect(find.textContaining('nothing was imported'), findsOneWidget);
      expect(find.textContaining('Trying again is safe'), findsOneWidget);
      expect(
        _enabled(tester, 'importConfirmButton'),
        isTrue,
        reason: 'checked: nothing saved, so a new attempt is allowed',
      );
    },
  );

  testWidgets(
    'refused at import time: the new row errors replace the preview',
    (tester) async {
      final backend = _backend();
      final refusal = _preview(
        rows: [
          _row(
            2,
            'Taken now',
            '61000001',
            errors: {'barcode': 'EXISTS_IN_STORE'},
          ),
        ],
      );
      final files = _FakeFiles(backend)
        ..importFails = ApiException(
          statusCode: 422,
          code: 'IMPORT_INVALID',
          message: 'invalid',
          details: refusal,
        );
      await _pump(tester, backend, files: files);
      await _tap(tester, 'headerImportButton');
      await _tap(tester, 'importConfirmBox');
      await _tap(tester, 'importConfirmButton');
      expect(find.textContaining('Fix the rows marked in red'), findsWidgets);
      expect(
        tester.widget<Text>(_k('importRowErrors-2')).data,
        contains('already exists'),
      );
      expect(_enabled(tester, 'importConfirmButton'), isFalse);
    },
  );

  group('ProductFileApiClient', () {
    test('sends base64 content, the preview hash and the confirmation; '
        'reads the export file name and count', () async {
      final sent = <http.Request>[];
      final client = ProductFileApiClient(
        ApiHttp(
          baseUrl: 'http://x/api',
          client: MockClient((r) async {
            sent.add(r);
            if (r.url.path.endsWith('/export')) {
              return http.Response.bytes(
                utf8.encode('﻿name\r\n'),
                200,
                headers: {
                  'content-type': 'text/csv; charset=UTF-8',
                  'content-disposition':
                      'attachment; filename="clixsell-products-2026-11-25.csv"',
                  'x-product-count': '7',
                },
              );
            }
            if (r.url.path.endsWith('/preview')) {
              return http.Response(
                jsonEncode(_preview()),
                200,
                headers: {'content-type': 'application/json'},
              );
            }
            return http.Response(
              jsonEncode({
                'fileName': 'p.csv',
                'created': 2,
                'suppliersCreated': ['New Farm'],
                'productIds': [5, 6],
              }),
              201,
              headers: {'content-type': 'application/json'},
            );
          }),
        ),
      );
      final ex = await client.export();
      expect(ex.fileName, 'clixsell-products-2026-11-25.csv');
      expect(ex.count, 7);
      final bytes = Uint8List.fromList(utf8.encode('name,barcode\nلبنة,1\n'));
      final p = await client.preview('p.csv', bytes);
      expect(p.validRows, 2);
      expect(jsonDecode(sent[1].body), {
        'fileName': 'p.csv',
        'content': base64Encode(bytes),
      });
      final r = await client.importFile('p.csv', bytes, 'b' * 64);
      expect(r.created, 2);
      expect(r.suppliersCreated, ['New Farm']);
      expect(jsonDecode(sent[2].body), {
        'fileName': 'p.csv',
        'content': base64Encode(bytes),
        'previewHash': 'b' * 64,
        'confirmImport': true,
      });
    });
  });
}

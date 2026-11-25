import 'dart:convert';
import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';

import '../models/product_file.dart';
import 'api_http.dart';

export 'api_http.dart' show ApiException;

/// Product files of the logged-in Store Admin's store (UR-M11, SR24):
///
/// | Method     | HTTP                                        |
/// |------------|---------------------------------------------|
/// | export     | GET  /api/product-files/export              |
/// | preview    | POST /api/product-files/import/preview      |
/// | importFile | POST /api/product-files/import              |
abstract class ProductFileApi {
  Future<ProductExport> export();

  /// Checks the file on the server; nothing is saved.
  Future<ImportPreview> preview(String fileName, Uint8List bytes);

  /// Creates all products of the previewed file (same [previewHash]) in one
  /// transaction, or none.
  Future<ImportResult> importFile(
    String fileName,
    Uint8List bytes,
    String previewHash,
  );
}

class ProductFileApiClient implements ProductFileApi {
  ProductFileApiClient(this._http);

  final ApiHttp _http;

  @override
  Future<ProductExport> export() async {
    final r = await _http.getBytes('/product-files/export');
    final disposition = r.headers['content-disposition'] ?? '';
    final match = RegExp(r'filename="?([^";]+)"?').firstMatch(disposition);
    final today = DateTime.now().toIso8601String().substring(0, 10);
    return ProductExport(
      fileName: match?.group(1) ?? 'clixsell-products-$today.csv',
      bytes: r.bytes,
      count: int.tryParse(r.headers['x-product-count'] ?? ''),
    );
  }

  @override
  Future<ImportPreview> preview(String fileName, Uint8List bytes) async =>
      ImportPreview.fromJson(
        await _http.post('/product-files/import/preview', {
              'fileName': fileName,
              'content': base64Encode(bytes),
            })
            as Map<String, dynamic>,
      );

  @override
  Future<ImportResult> importFile(
    String fileName,
    Uint8List bytes,
    String previewHash,
  ) async => ImportResult.fromJson(
    await _http.post('/product-files/import', {
          'fileName': fileName,
          'content': base64Encode(bytes),
          'previewHash': previewHash,
          'confirmImport': true,
        })
        as Map<String, dynamic>,
  );
}

/// A picked file.
typedef PickedFile = ({String name, Uint8List bytes});

/// Choosing a CSV file and saving the export (replaceable in tests).
abstract class ProductFileIO {
  Future<PickedFile?> pickCsv();

  /// Saves (desktop: save dialog; web: browser download). False if cancelled.
  Future<bool> save(String fileName, Uint8List bytes);
}

class PickerProductFileIO implements ProductFileIO {
  const PickerProductFileIO();

  @override
  Future<PickedFile?> pickCsv() async {
    final file = await FilePicker.pickFile(
      type: FileType.custom,
      allowedExtensions: const ['csv'],
    );
    if (file == null) return null;
    return (name: file.name, bytes: await file.readAsBytes());
  }

  @override
  Future<bool> save(String fileName, Uint8List bytes) async {
    final uri = await FilePicker.saveFile(
      fileName: fileName,
      bytes: bytes,
      mimeType: 'text/csv',
    );
    return uri != null;
  }
}

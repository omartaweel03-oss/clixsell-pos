import '../models/product.dart';
import 'api_http.dart';

export 'api_http.dart' show ApiException;

/// How the scanned code matched the stored barcode.
enum BarcodeMatchType { exact, gtinEquivalent }

/// Result of a camera barcode lookup in the signed-in user's store.
class BarcodeLookupResult {
  const BarcodeLookupResult.found({
    required this.scannedCode,
    required Product this.product,
    required String this.matchedBarcode,
    required BarcodeMatchType this.matchType,
  }) : invalid = false;

  const BarcodeLookupResult.notFound(this.scannedCode)
    : product = null,
      matchedBarcode = null,
      matchType = null,
      invalid = false;

  /// The value cannot be a ClixSell product barcode (8–14 digits).
  const BarcodeLookupResult.invalid(this.scannedCode)
    : product = null,
      matchedBarcode = null,
      matchType = null,
      invalid = true;

  final String scannedCode;
  final Product? product;
  final String? matchedBarcode;
  final BarcodeMatchType? matchType;
  final bool invalid;

  bool get found => product != null;
}

abstract class BarcodeLookupApi {
  /// [format]: EAN_13, EAN_8, UPC_A, UPC_E or CODE_128 (as read by the scanner).
  Future<BarcodeLookupResult> lookup(String code, {String? format});
}

/// GET /api/products/by-barcode — read only; the backend takes the store
/// from the session and never returns the cost price to Cashiers.
class BarcodeLookupClient implements BarcodeLookupApi {
  BarcodeLookupClient(this._http);

  final ApiHttp _http;

  /// Same rule as the backend (Product.validate): digits only, 8–14 long.
  static bool isProductBarcode(String code) =>
      RegExp(r'^\d{8,14}$').hasMatch(code);

  @override
  Future<BarcodeLookupResult> lookup(String code, {String? format}) async {
    if (!isProductBarcode(code)) return BarcodeLookupResult.invalid(code);
    final query = StringBuffer('code=${Uri.encodeQueryComponent(code)}');
    if (format != null) query.write('&format=${Uri.encodeQueryComponent(format)}');
    try {
      final json =
          await _http.get('/products/by-barcode?$query') as Map<String, dynamic>;
      return BarcodeLookupResult.found(
        scannedCode: code,
        product: Product.fromJson(json['product'] as Map<String, dynamic>),
        matchedBarcode: json['matchedBarcode'] as String,
        matchType: json['matchType'] == 'EXACT'
            ? BarcodeMatchType.exact
            : BarcodeMatchType.gtinEquivalent,
      );
    } on ApiException catch (e) {
      if (e.isNotFound) return BarcodeLookupResult.notFound(code);
      if (e.statusCode == 400) return BarcodeLookupResult.invalid(code);
      rethrow;
    }
  }
}

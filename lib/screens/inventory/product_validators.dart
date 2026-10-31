import '../../l10n/app_strings.dart';

/// Validation rules for the Add / Edit Product form.
///
/// Each method returns `null` when the value is valid, or a localized error.
class ProductValidators {
  const ProductValidators(this.s);

  final AppStrings s;

  static const int maxNameLength = 80;
  static const int maxQuantity = 1000000;
  static const double maxPrice = 1000000;
  static const int maxImageBytes = 5 * 1024 * 1024;
  static const Set<String> allowedImageExtensions = {
    'png',
    'jpg',
    'jpeg',
    'gif',
    'webp',
  };

  String? name(String? value) {
    final v = value?.trim() ?? '';
    if (v.isEmpty) return s.vRequired;
    if (v.length < 2) return s.vNameShort;
    if (v.length > maxNameLength) return s.vNameLong;
    return null;
  }

  String? barcode(String? value, {required bool Function(String) isTaken}) {
    final v = value?.trim() ?? '';
    if (v.isEmpty) return s.vRequired;
    if (!RegExp(r'^\d+$').hasMatch(v)) return s.vBarcodeDigits;
    if (v.length < 8 || v.length > 14) return s.vBarcodeLength;
    if (isTaken(v)) return s.vBarcodeTaken;
    return null;
  }

  String? category(Object? value) => value == null ? s.vCategory : null;

  String? price(String? value) {
    final v = value?.trim() ?? '';
    if (v.isEmpty) return s.vRequired;
    if (!RegExp(r'^\d+(\.\d+)?$').hasMatch(v)) return s.vPriceInvalid;
    final parsed = double.tryParse(v);
    if (parsed == null) return s.vPriceInvalid;
    if (parsed <= 0) return s.vPricePositive;
    if (parsed > maxPrice) return s.vTooLarge;
    final dot = v.indexOf('.');
    if (dot != -1 && v.length - dot - 1 > 2) return s.vPriceDecimals;
    return null;
  }

  String? wholeNumber(String? value) {
    final v = value?.trim() ?? '';
    if (v.isEmpty) return s.vRequired;
    if (!RegExp(r'^\d+$').hasMatch(v)) return s.vIntInvalid;
    final parsed = int.tryParse(v);
    if (parsed == null) return s.vIntInvalid;
    if (parsed > maxQuantity) return s.vTooLarge;
    return null;
  }

  String? supplier(String? value) {
    final v = value?.trim() ?? '';
    if (v.isEmpty) return s.vRequired;
    if (v.length < 2) return s.vSupplierShort;
    return null;
  }

  /// Validates a picked image's extension and size.
  String? image({required String? extension, required int sizeInBytes}) {
    final ext = extension?.toLowerCase();
    if (ext == null || !allowedImageExtensions.contains(ext)) {
      return s.vImageType;
    }
    if (sizeInBytes > maxImageBytes) return s.vImageTooLarge;
    return null;
  }
}

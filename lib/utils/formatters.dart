/// Lightweight number formatting helpers (no extra package needed).
class Formatters {
  Formatters._();

  static const String currencySymbol = '₪';

  /// 85000 -> "85,000.00"
  static String number(num value, {int decimals = 2}) {
    final negative = value < 0;
    final fixed = value.abs().toStringAsFixed(decimals);
    final parts = fixed.split('.');
    final intPart = parts[0];
    final buffer = StringBuffer();
    for (var i = 0; i < intPart.length; i++) {
      final fromEnd = intPart.length - i;
      buffer.write(intPart[i]);
      if (fromEnd > 1 && fromEnd % 3 == 1) buffer.write(',');
    }
    final decimalsPart = parts.length > 1 ? '.${parts[1]}' : '';
    return '${negative ? '-' : ''}$buffer$decimalsPart';
  }

  static String integer(int value) => number(value, decimals: 0);

  /// 5 -> "₪ 5.00"
  static String currency(num value) => '$currencySymbol ${number(value)}';

  /// Compact form for large stats on small cards: 1250000 -> "₪ 1.25M"
  static String compactCurrency(num value) {
    final abs = value.abs();
    if (abs >= 1000000) {
      return '$currencySymbol ${(value / 1000000).toStringAsFixed(2)}M';
    }
    return currency(value);
  }

  static String date(DateTime d) {
    String two(int n) => n.toString().padLeft(2, '0');
    return '${d.year}-${two(d.month)}-${two(d.day)}  ${two(d.hour)}:${two(d.minute)}';
  }

  static String fileSize(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
}

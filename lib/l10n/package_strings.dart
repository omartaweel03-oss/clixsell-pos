import 'package:flutter/widgets.dart';

/// Piece / carton / box texts (feature 6).
class PackageStrings {
  const PackageStrings._(this.isArabic);

  final bool isArabic;

  static PackageStrings of(BuildContext context) =>
      PackageStrings._(Localizations.localeOf(context).languageCode == 'ar');

  String _t(String en, String ar) => isArabic ? ar : en;

  String get piece => _t('Piece', 'حبة');
  String piecesEach(int n) => _t('$n pieces', '$n حبة');
  String get title => _t('Cartons and boxes', 'الكراتين والعلب');
  String get description => _t(
    'Sell this product by the carton or box. Stock is always counted in pieces.',
    'بيع هذا المنتج بالكرتونة أو العلبة. المخزون يُحسب دائماً بالحبة.',
  );
  String get add => _t('Add package', 'إضافة عبوة');
  String get name => _t('Name (e.g. Carton 24)', 'الاسم (مثلاً كرتونة 24)');
  String get units => _t('Pieces per package', 'عدد الحبات في العبوة');
  String get price => _t('Package price', 'سعر العبوة');
  String get barcode => _t('Barcode (optional)', 'الباركود (اختياري)');
  String get active => _t('Sold', 'متاحة للبيع');
  String get none => _t('No cartons or boxes yet.', 'لا توجد كراتين أو علب بعد.');
  String get save => _t('Save', 'حفظ');
  String get cancel => _t('Cancel', 'إلغاء');
  String get sizeFixed => _t(
    'The number of pieces cannot change later (past invoices keep it). For another size, add a new package.',
    'لا يمكن تغيير عدد الحبات لاحقاً (الفواتير السابقة تحتفظ به). لحجم آخر أضف عبوة جديدة.',
  );
  String get invalid => _t(
    'Enter a name, 2–10,000 pieces and a price above 0 with at most 2 decimals.',
    'أدخل اسماً، و 2–10,000 حبة، وسعراً أكبر من 0 بخانتين عشريتين على الأكثر.',
  );
}

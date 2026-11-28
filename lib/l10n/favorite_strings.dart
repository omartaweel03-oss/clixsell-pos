import 'package:flutter/widgets.dart';

/// POS favorites texts (feature 5).
class FavoriteStrings {
  const FavoriteStrings._(this.isArabic);

  final bool isArabic;

  static FavoriteStrings of(BuildContext context) =>
      FavoriteStrings._(Localizations.localeOf(context).languageCode == 'ar');

  String _t(String en, String ar) => isArabic ? ar : en;

  String get button => _t('POS favorites', 'مفضلة نقطة البيع');
  String get title => _t('POS favorites', 'مفضلة نقطة البيع');
  String get description => _t(
    'Products shown first on every cashier\'s POS screen, in this order. Drag to reorder.',
    'منتجات تظهر أولاً في شاشة البيع لكل كاشير، بهذا الترتيب. اسحب لإعادة الترتيب.',
  );
  String get add => _t('Add a product', 'إضافة منتج');
  String get empty => _t('No favorites yet.', 'لا توجد منتجات مفضلة بعد.');
  String get save => _t('Save', 'حفظ');
  String get cancel => _t('Cancel', 'إلغاء');
  String get remove => _t('Remove', 'إزالة');
  String get saved => _t('POS favorites saved', 'حُفظت مفضلة نقطة البيع');
  String get strip => _t('Favorites', 'المفضلة');
  String max(int n) => _t('At most $n favorites.', 'الحد الأقصى $n منتجاً.');
  String get moveUp => _t('Move up', 'تحريك للأعلى');
  String get moveDown => _t('Move down', 'تحريك للأسفل');
}

import 'package:flutter/widgets.dart';

/// Arabic / English text of Supplier Management (Purchase Orders, Phase 1).
///
/// Phase 1 manages suppliers only: no text here offers purchase orders,
/// recommendations or WhatsApp messages (those are later phases).
class PurchaseStrings {
  const PurchaseStrings._(this.isArabic);

  final bool isArabic;

  static PurchaseStrings of(BuildContext context) =>
      PurchaseStrings._(Localizations.localeOf(context).languageCode == 'ar');

  String _t(String en, String ar) => isArabic ? ar : en;

  /// Keeps a name in its own direction inside Arabic text.
  String _iso(String v) => isArabic ? '\u2068$v\u2069' : v;

  // Navigation and page
  String get navSuppliers => _t('Suppliers', 'الموردون');
  String get suppliersTitle => _t('Suppliers', 'الموردون');
  String get suppliersDescription => _t(
    'Supplier contact details and WhatsApp numbers. Suppliers are never deleted: deactivate the ones you no longer use.',
    'بيانات التواصل مع الموردين وأرقام WhatsApp. الموردون لا يُحذفون: أوقف المورد الذي لم تعد تتعامل معه.',
  );
  String get addSupplier => _t('Add Supplier', 'إضافة مورد');
  String get editSupplier => _t('Edit Supplier', 'تعديل المورد');
  String get searchSuppliers =>
      _t('Search suppliers by name or number', 'ابحث عن مورد بالاسم أو الرقم');

  // Filters
  String get filterAll => _t('All', 'الكل');
  String get filterActive => _t('Active', 'النشطون');
  String get filterInactive => _t('Inactive', 'الموقوفون');

  // Table
  String get colSupplier => _t('Supplier', 'المورد');
  String get colWhatsapp => _t('WhatsApp', 'WhatsApp');
  String get colPhone => _t('Phone', 'الهاتف');
  String get colProducts => _t('Products', 'المنتجات');
  String get noWhatsapp => _t('No WhatsApp number', 'لا يوجد رقم WhatsApp');
  String get none => '—';
  String productsCount(int n) => isArabic
      ? (n == 0
            ? 'لا منتجات'
            : n == 1
            ? 'منتج واحد'
            : '$n منتجات')
      : (n == 1 ? '1 product' : '$n products');
  String get details => _t('Details', 'التفاصيل');
  String detailsTooltip(String name) =>
      _t('Details of $name', 'تفاصيل ${_iso(name)}');
  String editTooltip(String name) => _t('Edit $name', 'تعديل ${_iso(name)}');
  String get edit => _t('Edit', 'تعديل');

  // Empty / no match
  String get noSuppliersTitle => _t('No suppliers yet', 'لا يوجد موردون بعد');
  String get noSuppliersBody => _t(
    'Add a supplier here, or type a supplier name when you add a product.',
    'أضف مورداً من هنا، أو اكتب اسم المورد عند إضافة منتج.',
  );
  String get noMatchTitle =>
      _t('No matching suppliers', 'لا يوجد موردون مطابقون');
  String get noMatchBody =>
      _t('Change the search or the filter.', 'غيّر البحث أو الفلتر.');

  // Form
  String get nameLabel => _t('Supplier name', 'اسم المورد');
  String get whatsappLabel => _t('WhatsApp number', 'رقم WhatsApp');
  String get whatsappHelper => _t(
    'International format with country code, e.g. 970599123456 or +970 59 912 3456',
    'بالصيغة الدولية مع رمز الدولة، مثل 970599123456 أو \u200E+970 59 912 3456',
  );
  String get phoneLabel => _t('Phone (optional)', 'الهاتف (اختياري)');
  String get addressLabel => _t('Address (optional)', 'العنوان (اختياري)');
  String get vNameRequired =>
      _t('Supplier name is required', 'اسم المورد مطلوب');
  String get vNameShort => _t('At least 2 characters', 'حرفان على الأقل');
  String get vNameLong => _t('80 characters or fewer', '80 حرفاً أو أقل');
  String get vWhatsapp => _t(
    'Use the international format with the country code (no leading 0), 8–15 digits',
    'استعمل الصيغة الدولية مع رمز الدولة (بدون 0 في البداية)، من 8 إلى 15 رقماً',
  );
  String get vPhone => _t('Phone number is not valid', 'رقم الهاتف غير صحيح');
  String get vAddressLong => _t('200 characters or fewer', '200 حرف أو أقل');
  String duplicateName(String existing) => _t(
    'A supplier named "$existing" already exists',
    'يوجد مورد باسم "${_iso(existing)}" مسبقاً',
  );
  String get save => _t('Save', 'حفظ');

  // Toasts
  String supplierAdded(String n) =>
      _t('Supplier "$n" added', 'تمت إضافة المورد "${_iso(n)}"');
  String supplierSaved(String n) =>
      _t('Supplier "$n" saved', 'تم حفظ المورد "${_iso(n)}"');
  String supplierActivated(String n) =>
      _t('Supplier "$n" activated', 'تم تفعيل المورد "${_iso(n)}"');
  String supplierDeactivated(String n) =>
      _t('Supplier "$n" deactivated', 'تم إيقاف المورد "${_iso(n)}"');

  // Deactivation
  String get deactivateTitle => _t('Deactivate supplier?', 'إيقاف المورد؟');
  String deactivateBody(String name, int products) => _t(
    '"$name" will no longer be available for new products. '
        '${products == 0 ? 'No product uses it.' : '$products product(s) keep this supplier and keep selling in the POS as before.'} '
        'You can activate it again at any time.',
    'لن يكون "${_iso(name)}" متاحاً للمنتجات الجديدة. '
        '${products == 0 ? 'لا يوجد منتج يستعمله.' : 'المنتجات المرتبطة به ($products) تبقى مرتبطة وتُباع في نقطة البيع كالمعتاد.'} '
        'يمكنك تفعيله مرة أخرى في أي وقت.',
  );

  // Details dialog
  String get contact => _t('Contact', 'التواصل');
  String get productsOfSupplier =>
      _t('Products from this supplier', 'منتجات هذا المورد');
  String get noProductsOfSupplier => _t(
    'No product uses this supplier yet.',
    'لا يوجد منتج مرتبط بهذا المورد بعد.',
  );
  String stockLine(int qty, int min) =>
      _t('Stock $qty · min $min', 'المخزون $qty · الحد الأدنى $min');
  String get close => _t('Close', 'إغلاق');

  // Product form errors from the backend (D16 / D26)
  String get productSupplierInactive => _t(
    'This supplier is inactive. Activate it in Suppliers or choose another supplier.',
    'هذا المورد موقوف. فعّله من صفحة الموردين أو اختر مورداً آخر.',
  );
  String get productSupplierAmbiguous => _t(
    'Several suppliers have this name with different spaces. Type the exact name from the Suppliers page.',
    'يوجد أكثر من مورد بهذا الاسم مع اختلاف في المسافات. اكتب الاسم كما هو في صفحة الموردين.',
  );
}

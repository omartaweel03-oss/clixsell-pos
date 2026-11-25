import 'package:flutter/widgets.dart';

/// Arabic / English text of AI Product Identification (report Figure 3.10),
/// Phase 1: camera, barcode scanning, inventory lookup and product photo.
class AiStrings {
  const AiStrings._(this.isArabic);

  final bool isArabic;

  static AiStrings of(BuildContext context) =>
      AiStrings._(Localizations.localeOf(context).languageCode == 'ar');

  String _t(String en, String ar) => isArabic ? ar : en;

  /// Keeps a value (barcode, price) in its own direction inside the other language.
  String iso(String v) => '\u2068$v\u2069';

  String get navTitle => _t('Identify Product', 'التعرف على المنتج');
  String get title =>
      _t('AI Product Identification', 'التعرف على المنتج بالذكاء الاصطناعي');
  String get subtitle => _t(
    'Scan the barcode, or photograph the product',
    'امسح الباركود، أو صوّر المنتج',
  );
  String get scanHint => _t(
    'Point the camera at the product barcode',
    'وجّه الكاميرا نحو باركود المنتج',
  );
  String get capturePhoto => _t('Capture Product Photo', 'التقاط صورة المنتج');
  String get retakePhoto => _t('Retake Photo', 'إعادة التقاط الصورة');
  String get scanAgain => _t('Scan Again', 'مسح مرة أخرى');
  String get scanBarcodeInstead =>
      _t('Scan a barcode instead', 'مسح الباركود بدلاً من ذلك');
  String get searchManually => _t('Search manually', 'البحث اليدوي');
  String get retry => _t('Try again', 'إعادة المحاولة');
  String get back => _t('Back', 'رجوع');

  // Lookup
  String get lookingUp => _t('Searching inventory…', 'جاري البحث في المخزون…');
  String scanned(String code) =>
      _t('Scanned barcode: ${iso(code)}', 'الباركود الممسوح: ${iso(code)}');
  String get resultTitle =>
      _t('Product found in inventory', 'تم العثور على المنتج في المخزون');
  String get name => _t('Product name', 'اسم المنتج');
  String get barcode => _t('Barcode', 'الباركود');
  String get category => _t('Category', 'الفئة');
  String get price => _t('Selling price', 'سعر البيع');
  String get stock => _t('In stock', 'الكمية المتوفرة');
  String get outOfStock => _t('Out of stock', 'نفد من المخزون');
  String sameNumber(String scanned, String stored) => _t(
    'The camera read ${iso(scanned)}; the product is saved as ${iso(stored)} (the same GS1 number).',
    'قرأت الكاميرا ${iso(scanned)}، والمنتج محفوظ بالرقم ${iso(stored)} (نفس رقم GS1).',
  );
  String get sourceBarcode => _t(
    'Exact match from the store inventory (barcode)',
    'مطابقة مؤكدة من مخزون المتجر (باركود)',
  );
  String get confirmProduct => _t('Confirm product', 'تأكيد المنتج');
  String get confirmed => _t('Product confirmed', 'تم تأكيد المنتج');
  String get addToSale =>
      _t('Add to current sale', 'إضافة إلى الفاتورة الحالية');
  String added(String name) => _t(
    '${iso(name)} was added to the current sale.',
    'تمت إضافة ${iso(name)} إلى الفاتورة الحالية.',
  );
  String get openSale => _t('Open the sale', 'فتح الفاتورة');
  String outOfStockMsg(String name) => _t(
    '${iso(name)} is out of stock and cannot be sold.',
    '${iso(name)} نفد من المخزون ولا يمكن بيعه.',
  );
  String stockLimit(String name, int qty) => _t(
    'Only ${iso('$qty')} of ${iso(name)} in stock — already in the sale.',
    'المتوفر من ${iso(name)} ${iso('$qty')} فقط، وهي كلها في الفاتورة.',
  );
  String get otherDevice => _t(
    'Using the POS on another computer? Type this barcode in its search box (or scan the QR code with a 2D scanner). The phone does not change the other computer\'s sale.',
    'تستخدم نقطة البيع على حاسوب آخر؟ اكتب هذا الباركود في خانة البحث فيه (أو امسح رمز QR بماسح ثنائي الأبعاد). الهاتف لا يغيّر فاتورة الحاسوب الآخر.',
  );

  // Not found / invalid
  String get notFoundTitle =>
      _t('Product not found in inventory', 'المنتج غير موجود في المخزون');
  String get notFoundBody => _t(
    'No product in your store has this barcode. Nothing was created or changed.',
    'لا يوجد منتج في متجرك بهذا الباركود. لم يُنشأ أو يُغيَّر أي شيء.',
  );
  String get invalidTitle => _t('Not a product barcode', 'هذا ليس باركود منتج');
  String get invalidBody => _t(
    'ClixSell product barcodes have 8 to 14 digits. This code cannot belong to a product.',
    'باركود منتجات ClixSell من 8 إلى 14 رقماً. هذا الرمز لا يمكن أن يخص منتجاً.',
  );
  String get phase2 => _t(
    'Identifying a product from its photo (AI) comes in Phase 2. For now: scan its barcode or search manually.',
    'التعرف على المنتج من صورته (بالذكاء الاصطناعي) سيأتي في المرحلة 2. حالياً: امسح الباركود أو ابحث يدوياً.',
  );
  String get identifyByPhoto =>
      _t('Identify by photo (Phase 2)', 'التعرف من الصورة (المرحلة 2)');

  // Photo
  String get photoTitle => _t('Product photo', 'صورة المنتج');
  String get photoNotSaved => _t(
    'The photo stays on this phone; it is not uploaded or saved.',
    'الصورة تبقى على هذا الهاتف، ولا تُرفع ولا تُحفظ.',
  );
  String get photoCancelled => _t('No photo was taken.', 'لم تُلتقط أي صورة.');
  String get photoFailed =>
      _t('The camera could not take a photo.', 'تعذر التقاط الصورة.');

  // Errors
  String get networkError => _t(
    'Cannot reach the ClixSell server. Check the phone is on the store network, then try again.',
    'تعذر الوصول إلى خادم ClixSell. تأكد أن الهاتف على شبكة المتجر، ثم أعد المحاولة.',
  );
  String get serverError => _t(
    'The server could not search the inventory. Try again.',
    'تعذر على الخادم البحث في المخزون. أعد المحاولة.',
  );
  String get permissionTitle =>
      _t('Camera permission needed', 'يلزم إذن الكاميرا');
  String get permissionBody => _t(
    'ClixSell needs the camera to scan barcodes. Tap Try again. If Android no longer asks, allow it in Settings > Apps > ClixSell > Permissions > Camera.',
    'يحتاج ClixSell الكاميرا لمسح الباركود. اضغط إعادة المحاولة. إن لم يعد Android يسأل، اسمح بها من الإعدادات > التطبيقات > ClixSell > الأذونات > الكاميرا.',
  );
  String get unavailableTitle =>
      _t('Camera not available', 'الكاميرا غير متاحة');
  String get unavailableBody => _t(
    'This device has no usable camera, or another app is using it.',
    'لا توجد كاميرا صالحة على هذا الجهاز، أو يستخدمها تطبيق آخر.',
  );
  String get cameraErrorTitle => _t('Camera error', 'خطأ في الكاميرا');
}

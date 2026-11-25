import 'package:flutter/widgets.dart';

/// Arabic / English text of product import / export (UR-M11, Figure 3.3).
class ProductFileStrings {
  const ProductFileStrings._(this.isArabic);

  final bool isArabic;

  static ProductFileStrings of(BuildContext context) => ProductFileStrings._(
    Localizations.localeOf(context).languageCode == 'ar',
  );

  String _t(String en, String ar) => isArabic ? ar : en;

  /// Keeps a value in its own direction inside the other language.
  String iso(String v) => '\u2068$v\u2069';

  String get importButton => _t('Import', 'استيراد');
  String get exportButton => _t('Export', 'تصدير');
  String get importTooltip => _t(
    'Create new products from a CSV file (checked before anything is saved)',
    'إنشاء منتجات جديدة من ملف CSV (يُفحص قبل حفظ أي شيء)',
  );
  String get exportTooltip => _t(
    'Download all products of this store as a CSV file',
    'تنزيل كل منتجات هذا المتجر كملف CSV',
  );

  // ── export ─────────────────────────────────────────────────────────────────
  String exported(int? count, String file) => count == null
      ? _t('Exported to ${iso(file)}.', 'تم التصدير إلى ${iso(file)}.')
      : _t(
          'Exported ${iso('$count')} products to ${iso(file)}.',
          'تم تصدير ${iso('$count')} منتجاً إلى ${iso(file)}.',
        );
  String get exportCancelled => _t(
    'Export cancelled: no file was saved.',
    'أُلغي التصدير: لم يُحفظ أي ملف.',
  );
  String get exportFailed => _t(
    'The export failed. Nothing was changed. Try again.',
    'فشل التصدير. لم يتغير شيء. حاول مرة أخرى.',
  );

  // ── preview dialog ─────────────────────────────────────────────────────────
  String get previewTitle =>
      _t('Import products – check', 'استيراد المنتجات – فحص');
  String get checking => _t('Checking the file…', 'جارٍ فحص الملف…');
  String get readFailed => _t(
    'The file could not be read. Choose a CSV file.',
    'تعذّرت قراءة الملف. اختر ملف CSV.',
  );
  String get nothingSaved => _t(
    'Nothing has been saved yet. The file was only checked.',
    'لم يُحفظ أي شيء بعد. تم فحص الملف فقط.',
  );
  String rowsSummary(int total, int valid, int invalid) => _t(
    '${iso('$total')} products in the file · ${iso('$valid')} valid · ${iso('$invalid')} with errors',
    '${iso('$total')} منتجاً في الملف · ${iso('$valid')} صالحة · ${iso('$invalid')} فيها أخطاء',
  );
  String newSuppliers(List<String> names) => _t(
    'New suppliers that will be created: ${names.map(iso).join('، ')}',
    'موردون جدد سيتم إنشاؤهم: ${names.map(iso).join('، ')}',
  );
  String get onlyNew => _t(
    'Only NEW products are created. A barcode that already exists in this store is an error: existing products, stock, prices and suppliers are never changed.',
    'تُنشأ منتجات جديدة فقط. الباركود الموجود مسبقاً في هذا المتجر خطأ: لا تتغير المنتجات الموجودة ولا المخزون ولا الأسعار ولا الموردون.',
  );
  String get allOrNothing => _t(
    'All products are created together, or none.',
    'تُنشأ كل المنتجات معاً، أو لا يُنشأ أي منها.',
  );
  String get fixAndRetry => _t(
    'Fix the rows marked in red in the file, then import it again. Nothing will be saved while any row has an error.',
    'صحّح الأسطر المعلّمة بالأحمر في الملف ثم استورده مرة أخرى. لن يُحفظ أي شيء طالما يوجد سطر فيه خطأ.',
  );
  String confirmBox(int n) => _t(
    'I checked the preview: create these ${iso('$n')} new products.',
    'راجعت المعاينة: أنشئ هذه المنتجات الجديدة وعددها ${iso('$n')}.',
  );
  String importNow(int n) =>
      _t('Import ${iso('$n')} products', 'استيراد ${iso('$n')} منتجاً');
  String get cancel => _t('Cancel', 'إلغاء');
  String get close => _t('Close', 'إغلاق');
  String get importing => _t('Importing…', 'جارٍ الاستيراد…');
  String get line => _t('Line', 'السطر');
  String get status => _t('Status', 'الحالة');
  String get ok => _t('OK', 'صالح');
  String showingFirst(int shown, int total) => _t(
    'Showing ${iso('$shown')} of ${iso('$total')} rows (rows with errors first).',
    'عرض ${iso('$shown')} من ${iso('$total')} سطراً (الأسطر التي فيها أخطاء أولاً).',
  );

  // ── result ─────────────────────────────────────────────────────────────────
  String get successTitle => _t('Import completed', 'اكتمل الاستيراد');
  String created(int n) => _t(
    '${iso('$n')} new products were created.',
    'تم إنشاء ${iso('$n')} منتجاً جديداً.',
  );
  String suppliersCreated(List<String> names) => _t(
    'New suppliers: ${names.map(iso).join('، ')}',
    'موردون جدد: ${names.map(iso).join('، ')}',
  );
  String get refusedTitle => _t('Nothing was imported', 'لم يُستورد أي شيء');
  String get fileChanged => _t(
    'This is not the file that was checked. Nothing was imported: check the file again.',
    'هذا ليس الملف الذي تم فحصه. لم يُستورد أي شيء: افحص الملف مرة أخرى.',
  );
  String get importFailed => _t(
    'The import could not be completed and nothing was saved. Check the file and try again.',
    'تعذّر إكمال الاستيراد ولم يُحفظ أي شيء. راجع الملف وحاول مرة أخرى.',
  );
  String get unknownOutcomeImported => _t(
    'The server did not answer in time. Checked: the products are in the store now, so the import was completed.',
    'لم يرد الخادم في الوقت المحدد. تم التحقق: المنتجات موجودة الآن في المتجر، فالاستيراد قد اكتمل.',
  );
  String get unknownOutcomeNotImported => _t(
    'The server did not answer in time. Checked: the products are not in the store, so nothing was imported. Trying again is safe (existing barcodes are refused, nothing is created twice).',
    'لم يرد الخادم في الوقت المحدد. تم التحقق: المنتجات غير موجودة في المتجر، فلم يُستورد شيء. إعادة المحاولة آمنة (الباركود الموجود يُرفض، ولا يُنشأ شيء مرتين).',
  );

  // ── columns ────────────────────────────────────────────────────────────────
  String column(String key) => switch (key) {
    'name' => _t('Name', 'الاسم'),
    'barcode' => _t('Barcode', 'الباركود'),
    'category' => _t('Category', 'التصنيف'),
    'sellingPrice' => _t('Price', 'السعر'),
    'costPrice' => _t('Cost', 'التكلفة'),
    'quantity' => _t('Quantity', 'الكمية'),
    'minStock' => _t('Min. stock', 'الحد الأدنى'),
    'supplier' => _t('Supplier', 'المورد'),
    'row' => _t('Row', 'السطر'),
    _ => key,
  };

  String supplierStatus(String? s) => switch (s) {
    'NEW' => _t('new', 'جديد'),
    'INACTIVE' => _t('inactive', 'موقوف'),
    _ => '',
  };

  // ── errors ─────────────────────────────────────────────────────────────────
  /// A file-level problem (code[:argument]).
  String fileError(String code) {
    final i = code.indexOf(':');
    final c = i < 0 ? code : code.substring(0, i);
    final arg = i < 0 ? '' : code.substring(i + 1);
    return switch (c) {
      'EMPTY_FILE' => _t('The file is empty.', 'الملف فارغ.'),
      'NO_PRODUCTS' => _t(
        'The file has the header row but no products.',
        'الملف فيه سطر العناوين فقط بدون منتجات.',
      ),
      'FILE_TOO_LARGE' => _t(
        'The file is larger than 2 MB.',
        'الملف أكبر من 2 ميغابايت.',
      ),
      'TOO_MANY_ROWS' => _t(
        'The file has more than 5000 products. Split it into smaller files.',
        'الملف فيه أكثر من 5000 منتج. قسّمه إلى ملفات أصغر.',
      ),
      'NOT_UTF8' => _t(
        'The file is not saved as UTF-8. In Excel choose "CSV UTF-8 (comma delimited)".',
        'الملف غير محفوظ بترميز UTF-8. في Excel اختر "CSV UTF-8 (محدد بفاصلة)".',
      ),
      'UNTERMINATED_QUOTE' => _t(
        'A quote (") opened on line ${iso(arg)} is never closed.',
        'علامة اقتباس (") في السطر ${iso(arg)} لم تُغلق.',
      ),
      'MISSING_COLUMN' => _t(
        'Missing column: ${iso(arg)}.',
        'عمود ناقص: ${iso(arg)}.',
      ),
      'UNKNOWN_COLUMN' => _t(
        'Unknown column: ${iso(arg)}. Use the columns of an exported file.',
        'عمود غير معروف: ${iso(arg)}. استخدم أعمدة ملف مُصدَّر.',
      ),
      'DUPLICATE_COLUMN' => _t(
        'Column ${iso(arg)} appears twice.',
        'العمود ${iso(arg)} مكرر.',
      ),
      _ => code,
    };
  }

  /// A row problem: a server code, or a product-form message (translated).
  String rowError(String field, String message) {
    final i = message.indexOf(':');
    final code = i < 0 ? message : message.substring(0, i);
    final arg = i < 0 ? '' : message.substring(i + 1);
    switch (code) {
      case 'DUPLICATE_IN_FILE':
        return _t(
          'Same barcode as line ${iso(arg)} of the file',
          'نفس باركود السطر ${iso(arg)} في الملف',
        );
      case 'EXISTS_IN_STORE':
        return _t(
          'A product with this barcode already exists (not changed)',
          'يوجد منتج بهذا الباركود (لن يتغير)',
        );
      case 'NOT_A_NUMBER':
        return _t(
          'Not a number (use digits and a dot, e.g. 12.5)',
          'ليس رقماً (استخدم أرقاماً ونقطة، مثل 12.5)',
        );
      case 'NOT_A_WHOLE_NUMBER':
        return _t('Must be a whole number', 'يجب أن يكون عدداً صحيحاً');
      case 'SUPPLIER_INACTIVE':
        return _t(
          'This supplier is inactive (activate it in Suppliers first)',
          'هذا المورد موقوف (فعّله من صفحة الموردين أولاً)',
        );
      case 'SUPPLIER_NAME_AMBIGUOUS':
        return _t(
          'Several suppliers match this name: type the exact name',
          'أكثر من مورد يطابق هذا الاسم: اكتب الاسم بالضبط',
        );
      case 'COLUMN_COUNT':
        final parts = arg.split('/');
        return _t(
          'Has ${iso(parts.first)} values instead of ${iso(parts.last)} (a comma inside a value needs quotes)',
          'فيه ${iso(parts.first)} قيم بدلاً من ${iso(parts.last)} (الفاصلة داخل القيمة تحتاج علامات اقتباس)',
        );
      case 'MALFORMED_QUOTES':
        return _t(
          'Quotes are not used correctly in this row',
          'علامات الاقتباس غير صحيحة في هذا السطر',
        );
    }
    if (!isArabic) {
      return message.toLowerCase().startsWith('unknown category')
          ? '$message (use: dairy, bakery, beverages, snacks, groceries, freshProduce, frozen, cleaning, personalCare, other)'
          : message;
    }
    // Product-form rules (English messages from the server) → Arabic.
    final m = message.toLowerCase();
    if (m.contains('is required')) return 'هذا الحقل مطلوب';
    if (m.contains('cannot be negative')) return 'لا يمكن أن يكون سالباً';
    if (m.contains('greater than 0')) return 'يجب أن يكون أكبر من 0';
    if (m.contains('too large')) return 'القيمة كبيرة جداً';
    if (m.contains('2 decimal')) return 'خانتان عشريتان كحد أقصى';
    if (m.contains('digits only')) return 'الباركود أرقام فقط';
    if (m.contains('8 to 14')) return 'الباركود من 8 إلى 14 رقماً';
    if (m.contains('at least 2')) return 'حرفان على الأقل';
    if (m.contains('characters or fewer')) return '80 حرفاً كحد أقصى';
    if (m.contains('unknown category')) {
      return 'تصنيف غير معروف (استخدم: dairy، bakery، beverages، snacks، groceries، freshProduce، frozen، cleaning، personalCare، other)';
    }
    if (m.contains('must be a number')) return 'يجب أن يكون رقماً';
    return message;
  }
}

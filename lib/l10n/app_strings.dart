import 'package:flutter/widgets.dart';

/// All user-facing text for the Inventory module in English and Arabic.
///
/// The language is taken from the active [Locale]; Arabic automatically puts
/// the whole app in right-to-left (RTL) layout via flutter_localizations.
class AppStrings {
  const AppStrings._(this.isArabic);

  final bool isArabic;

  static const AppStrings english = AppStrings._(false);
  static const AppStrings arabic = AppStrings._(true);

  static AppStrings of(BuildContext context) {
    final code = Localizations.localeOf(context).languageCode;
    return AppStrings._(code == 'ar');
  }

  String _t(String en, String ar) => isArabic ? ar : en;

  // ── App / sidebar ─────────────────────────────────────────────────────────
  String get appName => 'ClixSell';
  String get appSubtitle => _t('POS System', 'نظام نقاط البيع');
  String get navInventory => _t('Products', 'المنتجات');
  String get navAddProduct => _t('Add Product', 'إضافة منتج');
  String get navEditProduct => _t('Edit Product', 'تعديل منتج');
  String get navSectionInventory => _t('INVENTORY', 'المخزون');
  String get languageToggle => _t('العربية', 'English');
  String get languageToggleTooltip =>
      _t('Switch to Arabic (RTL)', 'التبديل إلى الإنجليزية');
  String get menu => _t('Menu', 'القائمة');
  String get backToInventory => _t('Back to inventory', 'العودة إلى المخزون');

  // ── Inventory page header ─────────────────────────────────────────────────
  String get inventoryTitle => _t('Inventory Management', 'إدارة المخزون');
  String get inventoryDescription => _t(
    'Manage your products, quantities, prices and stock levels in one place.',
    'إدارة المنتجات والكميات والأسعار ومتابعة مستويات المخزون في مكان واحد.',
  );

  // ── Stats cards ───────────────────────────────────────────────────────────
  String get statTotalProducts => _t('Total Products', 'إجمالي المنتجات');
  String get statLowStock => _t('Low Stock', 'منخفضة المخزون');
  String get statOutOfStock => _t('Out of Stock', 'نفدت بالكامل');
  String get statInventoryValue => _t('Inventory Value', 'قيمة المخزون');
  String get statTotalHint => _t('Registered items', 'منتجات مسجلة');
  String get statLowHint => _t('At or below minimum', 'عند الحد الأدنى أو أقل');
  String get statOutHint => _t('Quantity is zero', 'الكمية صفر');
  String get statValueHint => _t('Price × quantity', 'السعر × الكمية');

  // ── Toolbar / filters ─────────────────────────────────────────────────────
  String get searchHint => _t(
    'Search by name, barcode or supplier…',
    'ابحث باسم المنتج أو الباركود أو المورد…',
  );
  String get addProduct => _t('Add Product', 'إضافة منتج');
  String get allCategories => _t('All categories', 'كل التصنيفات');
  String get allStatuses => _t('All statuses', 'كل الحالات');
  String get clearFilters => _t('Clear filters', 'مسح الفلاتر');

  // ── Table ─────────────────────────────────────────────────────────────────
  String get colProduct => _t('Product', 'المنتج');
  String get colBarcode => _t('Barcode', 'الباركود');
  String get colCategory => _t('Category', 'التصنيف');
  String get colPrice => _t('Price', 'السعر');
  String get colQuantity => _t('Quantity', 'الكمية');
  String get colMinStock => _t('Min. Stock', 'الحد الأدنى');
  String get colSupplier => _t('Supplier', 'المورد');
  String get colStatus => _t('Status', 'الحالة');
  String get colActions => _t('Actions', 'الإجراءات');
  String get edit => _t('Edit', 'تعديل');
  String get delete => _t('Delete', 'حذف');
  String showingRange(int from, int to, int total) => _t(
    'Showing $from–$to of $total products',
    'عرض $from - $to من $total منتج',
  );
  String get previousPage => _t('Previous page', 'الصفحة السابقة');
  String get nextPage => _t('Next page', 'الصفحة التالية');

  // ── Status labels ─────────────────────────────────────────────────────────
  String get statusInStock => _t('In Stock', 'متوفر');
  String get statusLowStock => _t('Low Stock', 'منخفض');
  String get statusOutOfStock => _t('Out of Stock', 'نفد');

  // ── Empty states ──────────────────────────────────────────────────────────
  String get emptyTitle => _t('No products yet', 'لا توجد منتجات بعد');
  String get emptyBody => _t(
    'Start building your inventory by adding your first product.',
    'ابدأ ببناء مخزونك بإضافة أول منتج.',
  );
  String get noResultsTitle =>
      _t('No matching products', 'لا توجد نتائج مطابقة');
  String get noResultsBody => _t(
    'Try a different search term or clear the filters.',
    'جرّب كلمة بحث مختلفة أو امسح الفلاتر.',
  );

  // ── Form ──────────────────────────────────────────────────────────────────
  String get addProductTitle => _t('Add New Product', 'إضافة منتج جديد');
  String get editProductTitle => _t('Edit Product', 'تعديل المنتج');
  String get addProductSubtitle => _t(
    'Fill in the product details below. Fields marked * are required.',
    'أدخل تفاصيل المنتج أدناه. الحقول المميزة بـ * مطلوبة.',
  );
  String editProductSubtitle(String name) =>
      _t('Update the information for "$name".', 'تحديث معلومات "$name".');
  String get sectionBasicInfo => _t('Basic Information', 'المعلومات الأساسية');
  String get sectionPricingStock => _t('Pricing & Stock', 'السعر والمخزون');
  String get sectionImage => _t('Product Image', 'صورة المنتج');
  String get fieldName => _t('Product name', 'اسم المنتج');
  String get fieldNameHint => _t('e.g. Fresh Milk 1L', 'مثال: حليب طازج 1 لتر');
  String get fieldBarcode => _t('Barcode', 'الباركود');
  String get fieldBarcodeHint => _t('8–14 digits', '8 إلى 14 رقمًا');
  String get generateBarcode => _t('Generate barcode', 'توليد باركود');
  String get fieldCategory => _t('Category', 'التصنيف');
  String get fieldCategoryHint => _t('Select a category', 'اختر التصنيف');
  String get fieldPrice => _t('Price (₪)', 'السعر (₪)');
  String get fieldQuantity => _t('Quantity', 'الكمية');
  String get fieldMinStock => _t('Minimum stock level', 'الحد الأدنى للمخزون');
  String get fieldMinStockHelper => _t(
    'You will be alerted when quantity reaches this level.',
    'سيتم تنبيهك عندما تصل الكمية إلى هذا الحد.',
  );
  String get fieldSupplier => _t('Supplier', 'المورد');
  String get fieldSupplierHint => _t('Supplier name', 'اسم المورد');
  String get uploadImage => _t('Upload image', 'رفع صورة');
  String get changeImage => _t('Change image', 'تغيير الصورة');
  String get removeImage => _t('Remove', 'إزالة');
  String get imageHint => _t(
    'PNG, JPG, GIF or WEBP · max 5 MB (optional)',
    'PNG أو JPG أو GIF أو WEBP · بحد أقصى 5 ميغابايت (اختياري)',
  );
  String get noImage => _t('No image selected', 'لم يتم اختيار صورة');
  String get previewStatus => _t('Status preview', 'معاينة الحالة');
  String get save => _t('Save Product', 'حفظ المنتج');
  String get saveChanges => _t('Save Changes', 'حفظ التعديلات');
  String get cancel => _t('Cancel', 'إلغاء');
  String get createdAt => _t('Created', 'تاريخ الإضافة');
  String get updatedAt => _t('Last updated', 'آخر تحديث');

  // ── Validation ────────────────────────────────────────────────────────────
  String get vRequired => _t('This field is required', 'هذا الحقل مطلوب');
  String get vNameShort => _t(
    'Name must be at least 2 characters',
    'يجب أن يتكون الاسم من حرفين على الأقل',
  );
  String get vNameLong => _t(
    'Name must be 80 characters or fewer',
    'يجب ألا يتجاوز الاسم 80 حرفًا',
  );
  String get vBarcodeDigits => _t(
    'Barcode must contain digits only',
    'يجب أن يحتوي الباركود على أرقام فقط',
  );
  String get vBarcodeLength => _t(
    'Barcode must be 8 to 14 digits',
    'يجب أن يتكون الباركود من 8 إلى 14 رقمًا',
  );
  String get vBarcodeTaken => _t(
    'Another product already uses this barcode',
    'هذا الباركود مستخدم لمنتج آخر',
  );
  String get vCategory => _t('Please select a category', 'يرجى اختيار التصنيف');
  String get vPriceInvalid => _t('Enter a valid price', 'أدخل سعرًا صحيحًا');
  String get vPricePositive =>
      _t('Price must be greater than 0', 'يجب أن يكون السعر أكبر من 0');
  String get vPriceDecimals =>
      _t('Use at most 2 decimal places', 'استخدم خانتين عشريتين كحد أقصى');
  String get vIntInvalid =>
      _t('Enter a whole number (0 or more)', 'أدخل عددًا صحيحًا (0 أو أكثر)');
  String get vTooLarge => _t('Value is too large', 'القيمة كبيرة جدًا');
  String get vSupplierShort => _t(
    'Supplier must be at least 2 characters',
    'يجب أن يتكون اسم المورد من حرفين على الأقل',
  );
  String get vImageTooLarge =>
      _t('Image is larger than 5 MB', 'حجم الصورة أكبر من 5 ميغابايت');
  String get vImageType => _t(
    'Unsupported image type. Use PNG, JPG, GIF or WEBP.',
    'نوع الصورة غير مدعوم. استخدم PNG أو JPG أو GIF أو WEBP.',
  );
  String get vImageFailed =>
      _t('Could not read the selected image', 'تعذّرت قراءة الصورة المحددة');
  String get vFixErrors =>
      _t('Please fix the highlighted fields', 'يرجى تصحيح الحقول المحددة');

  // ── Feedback ──────────────────────────────────────────────────────────────
  String productAdded(String name) =>
      _t('"$name" was added to inventory', 'تمت إضافة "$name" إلى المخزون');
  String productUpdated(String name) =>
      _t('"$name" was updated', 'تم تحديث "$name"');
  String productDeleted(String name) =>
      _t('"$name" was deleted', 'تم حذف "$name"');
  String get undo => _t('Undo', 'تراجع');
  String get productNotFound =>
      _t('This product no longer exists.', 'هذا المنتج لم يعد موجودًا.');

  // ── Server / API ──────────────────────────────────────────────────────────
  String get loadingProducts =>
      _t('Loading products from the server…', 'جارٍ تحميل المنتجات من الخادم…');
  String get serverUnreachableTitle =>
      _t("Can't reach the server", 'تعذّر الاتصال بالخادم');
  String serverUnreachableBody(String url) => _t(
    'Make sure the ClixSell backend is running at $url, then try again.',
    'تأكد من تشغيل خادم ClixSell على $url ثم أعد المحاولة.',
  );
  String get loadFailedTitle =>
      _t('Could not load products', 'تعذّر تحميل المنتجات');
  String get retry => _t('Try again', 'إعادة المحاولة');
  String get networkError => _t(
    "Can't reach the server. Check that the backend is running.",
    'تعذّر الاتصال بالخادم. تأكد من أن الخادم يعمل.',
  );
  String saveFailed(String reason) =>
      _t('Could not save the product: $reason', 'تعذّر حفظ المنتج: $reason');
  String deleteFailed(String reason) =>
      _t('Could not delete the product: $reason', 'تعذّر حذف المنتج: $reason');
  String restoreFailed(String reason) => _t(
    'Could not restore the product: $reason',
    'تعذّرت استعادة المنتج: $reason',
  );

  // ── Login ─────────────────────────────────────────────────────────────────
  String get loginTitle => _t('Log in', 'تسجيل الدخول');
  String get loginSubtitle => _t(
    'Use the account your store or system administrator gave you.',
    'استخدم الحساب الذي حصلت عليه من متجرك أو من مدير النظام.',
  );
  String get loginTagline => _t(
    'Smart point of sale for small and medium businesses',
    'نظام نقاط بيع ذكي للمشاريع الصغيرة والمتوسطة',
  );
  String get usernameLabel => _t('Username', 'اسم المستخدم');
  String get passwordLabel => _t('Password', 'كلمة المرور');
  String get loginButton => _t('Log in', 'دخول');
  String get showPassword => _t('Show password', 'إظهار كلمة المرور');
  String get hidePassword => _t('Hide password', 'إخفاء كلمة المرور');
  String get loginInvalid => _t(
    'Invalid username or password.',
    'اسم المستخدم أو كلمة المرور غير صحيحة.',
  );
  String get loginDisabled => _t(
    'This account or its store has been deactivated. Contact your manager.',
    'تم إيقاف هذا الحساب أو متجره. تواصل مع مديرك.',
  );
  String get loginLocked => _t(
    'Too many wrong attempts. Try again in 5 minutes.',
    'محاولات خاطئة كثيرة. حاول مرة أخرى بعد 5 دقائق.',
  );
  String get sessionEnded => _t(
    'Your session has ended. Please log in again.',
    'انتهت جلستك. يرجى تسجيل الدخول مرة أخرى.',
  );
  String get logout => _t('Log out', 'تسجيل الخروج');

  // ── Roles ─────────────────────────────────────────────────────────────────
  String get roleSystemAdmin => _t('System Admin', 'مدير النظام');
  String get roleStoreAdmin => _t('Store Admin', 'مدير المتجر');
  String get roleCashier => _t('Cashier', 'كاشير');

  // ── Navigation (multi-store) ──────────────────────────────────────────────
  String get navSectionAdmin => _t('ADMINISTRATION', 'الإدارة');
  String get navSectionStaff => _t('STAFF', 'الموظفون');
  String get navStores => _t('Stores', 'المتاجر');
  String get navCashiers => _t('Cashiers', 'الكاشير');
  String get inventoryDescriptionCashier => _t(
    'Look up products, prices and stock levels in your store.',
    'ابحث عن المنتجات والأسعار والكميات المتوفرة في متجرك.',
  );
  String get viewOnly => _t('View only', 'عرض فقط');

  // ── Common ────────────────────────────────────────────────────────────────
  String get back => _t('Back', 'رجوع');
  String get create => _t('Create', 'إنشاء');
  String get statusActive => _t('Active', 'نشط');
  String get statusInactive => _t('Inactive', 'موقوف');
  String get activate => _t('Activate', 'تفعيل');
  String get deactivate => _t('Deactivate', 'إيقاف');
  String get notAllowed =>
      _t('You are not allowed to do this.', 'غير مسموح لك بهذا الإجراء.');
  String requestFailed(String reason) =>
      _t('Something went wrong: $reason', 'حدث خطأ: $reason');

  // ── Stores (System Admin) ─────────────────────────────────────────────────
  String get storesTitle => _t('Stores', 'المتاجر');
  String get storesDescription => _t(
    'Create stores and manage the Store Admins of each one.',
    'أنشئ المتاجر وأدر مديري كل متجر.',
  );
  String get addStore => _t('Add Store', 'إضافة متجر');
  String get editStore => _t('Edit Store', 'تعديل المتجر');
  String get storeNameLabel => _t('Store name', 'اسم المتجر');
  String get addressLabel => _t('Address', 'العنوان');
  String get phoneLabel => _t('Phone', 'الهاتف');
  String get optional => _t('optional', 'اختياري');
  String get manageStoreAdmins => _t('Store Admins', 'مديرو المتجر');
  String adminsCount(int n) =>
      _t('$n admin${n == 1 ? '' : 's'}', 'المديرون: $n');
  String cashiersCount(int n) =>
      _t('$n cashier${n == 1 ? '' : 's'}', 'الكاشير: $n');
  String get noStoresTitle => _t('No stores yet', 'لا توجد متاجر بعد');
  String get noStoresBody =>
      _t('Add the first store to get started.', 'أضف أول متجر للبدء.');
  String storeCreated(String name) =>
      _t('Store "$name" was created', 'تم إنشاء المتجر "$name"');
  String storeUpdated(String name) =>
      _t('Store "$name" was updated', 'تم تحديث المتجر "$name"');
  String storeActivated(String name) =>
      _t('Store "$name" is active again', 'تم تفعيل المتجر "$name"');
  String storeDeactivated(String name) =>
      _t('Store "$name" was deactivated', 'تم إيقاف المتجر "$name"');
  String get deactivateStoreTitle => _t('Deactivate store?', 'إيقاف المتجر؟');
  String deactivateStoreBody(String name) => _t(
    'All users of "$name" will be logged out and cannot log in until the store is activated again. Its data is kept.',
    'سيتم تسجيل خروج جميع مستخدمي "$name" ولن يستطيعوا الدخول حتى يُعاد تفعيل المتجر. تبقى بياناته محفوظة.',
  );
  String get vStoreNameShort => _t(
    'Store name must be at least 2 characters',
    'يجب أن يتكون اسم المتجر من حرفين على الأقل',
  );
  String get vPhone => _t(
    'Use digits, spaces, +, - and brackets only',
    'استخدم الأرقام والمسافات و + و - والأقواس فقط',
  );

  // ── Staff accounts (Store Admins / Cashiers) ──────────────────────────────
  String get cashiersTitle => _t('Cashiers', 'الكاشير');
  String get cashiersDescription => _t(
    'Create and manage the cashier accounts of your store.',
    'أنشئ حسابات الكاشير في متجرك وأدرها.',
  );
  String storeAdminsTitle(String store) =>
      _t('Store Admins – $store', 'مديرو المتجر – $store');
  String get storeAdminsDescription => _t(
    "These accounts manage the store's products and cashiers.",
    'هذه الحسابات تدير منتجات المتجر والكاشير.',
  );
  String get addCashier => _t('Add Cashier', 'إضافة كاشير');
  String get addStoreAdmin => _t('Add Store Admin', 'إضافة مدير متجر');
  String get fullNameLabel => _t('Full name', 'الاسم الكامل');
  String get colUsername => _t('Username', 'اسم المستخدم');
  String get colFullName => _t('Full name', 'الاسم الكامل');
  String get colCreated => _t('Created', 'تاريخ الإنشاء');
  String get resetPassword => _t('Reset password', 'إعادة تعيين كلمة المرور');
  String resetPasswordFor(String username) =>
      _t('New password for "$username"', 'كلمة مرور جديدة لـ "$username"');
  String get newPasswordLabel => _t('New password', 'كلمة المرور الجديدة');
  String get passwordHint => _t('At least 8 characters', '8 أحرف على الأقل');
  String get usernameHint => _t('e.g. shini.ahmad', 'مثال: shini.ahmad');
  String get usernameHelper => _t(
    'Must be unique in the whole system. Letters, digits, ".", "_" or "-".',
    'يجب أن يكون فريدًا في النظام كله. أحرف إنجليزية وأرقام و "." و "_" و "-".',
  );
  String get noStaffTitle => _t('No accounts yet', 'لا توجد حسابات بعد');
  String get noStaffBody => _t('Add the first account.', 'أضف أول حساب.');
  String accountCreated(String u) =>
      _t('Account "$u" was created', 'تم إنشاء الحساب "$u"');
  String accountActivated(String u) =>
      _t('Account "$u" is active again', 'تم تفعيل الحساب "$u"');
  String accountDeactivated(String u) =>
      _t('Account "$u" was deactivated', 'تم إيقاف الحساب "$u"');
  String passwordWasReset(String u) =>
      _t('Password of "$u" was changed', 'تم تغيير كلمة مرور "$u"');
  String get deactivateUserTitle => _t('Deactivate account?', 'إيقاف الحساب؟');
  String deactivateUserBody(String u) => _t(
    '"$u" will be logged out at once and cannot log in until the account is activated again.',
    'سيتم تسجيل خروج "$u" فورًا ولن يستطيع الدخول حتى يُعاد تفعيل الحساب.',
  );
  String get usernameTaken =>
      _t('This username is already taken', 'اسم المستخدم هذا مستخدم من قبل');
  String get vUsernameFormat => _t(
    '3–40 characters: English letters, digits, ".", "_" or "-"',
    'من 3 إلى 40 حرفًا: أحرف إنجليزية وأرقام و "." و "_" و "-"',
  );
  String get vPasswordShort => _t(
    'Password must be at least 8 characters',
    'يجب أن تتكون كلمة المرور من 8 أحرف على الأقل',
  );
  String get vFullNameShort => _t(
    'Full name must be at least 2 characters',
    'يجب أن يتكون الاسم من حرفين على الأقل',
  );

  // ── Store POS settings ────────────────────────────────────────────────────
  String get taxRateLabel => _t('Tax rate', 'نسبة الضريبة');
  String get maxDiscountLabel => _t('Max. discount', 'أقصى خصم');
  String get vPercent => _t('Enter 0 – 100', 'أدخل رقمًا من 0 إلى 100');
  String taxAndDiscount(double tax, double maxDiscount) => _t(
    'Tax ${_pct(tax)}% · max. discount ${_pct(maxDiscount)}%',
    'الضريبة ${_pct(tax)}% · أقصى خصم ${_pct(maxDiscount)}%',
  );
  static String _pct(double v) =>
      v == v.roundToDouble() ? v.toInt().toString() : v.toString();

  // ── POS (Cashier Dashboard, report Figure 3.2) ────────────────────────────
  String get navPos => _t('Point of Sale', 'نقطة البيع');
  String get navSectionSales => _t('SALES', 'المبيعات');
  String get posTitle => _t('Point of Sale (POS)', 'نقطة البيع (POS)');
  String get posSubtitle => _t(
    'Add products and complete the sale',
    'أضف المنتجات وأكمل عملية البيع',
  );
  String get posSearchHint =>
      _t('Search by name or barcode…', 'ابحث بالاسم أو الباركود…');
  String get posSearchTooltip => _t(
    'Type or scan a barcode and press Enter to add it',
    'اكتب الباركود أو امسحه ثم اضغط Enter لإضافته',
  );
  String get categoriesProducts =>
      _t('Categories / Products', 'الأصناف / المنتجات');
  String get allTab => _t('All', 'الكل');
  String get noProductsFound =>
      _t('No matching products', 'لا توجد منتجات مطابقة');
  String get outOfStockShort => _t('Out of stock', 'نفد');
  String leftInStock(int n) => _t('$n left', 'متبقٍ $n');
  String get currentInvoice => _t('Current Invoice', 'الفاتورة الحالية');
  String get newInvoiceChip => _t('New invoice', 'فاتورة جديدة');
  String get colLineTotal => _t('Total', 'الإجمالي');
  String get emptyInvoiceTitle => _t('No products yet', 'لا توجد منتجات بعد');
  String get emptyInvoiceBody => _t(
    'Tap a product or scan its barcode to add it.',
    'اضغط على منتج أو امسح الباركود لإضافته.',
  );
  String get clearAll => _t('Clear all', 'مسح الكل');
  String get applyDiscount => _t('Apply discount', 'تطبيق خصم');
  String get removeItem => _t('Remove', 'حذف');
  String get decreaseQty => _t('Decrease', 'إنقاص');
  String get increaseQty => _t('Increase', 'زيادة');
  String get invoiceSummary => _t('Invoice Summary', 'ملخص الفاتورة');
  String get itemsCount => _t('Items', 'عدد الأصناف');
  String get subtotalLabel => _t('Subtotal', 'المجموع الفرعي');
  String get discountLabel => _t('Discount', 'الخصم');
  String taxLabel(double rate) =>
      _t('Tax (${_pct(rate)}%)', 'الضريبة (${_pct(rate)}%)');
  String get totalLabel => _t('Total', 'الإجمالي');
  String get paymentMethods => _t('Payment Methods', 'طرق الدفع');
  String get payCash => _t('Cash', 'دفع نقدي');
  String get payCard => _t('Credit card', 'بطاقة ائتمان');
  String get payWallet => _t('E-wallet', 'محفظة إلكترونية');
  String get payBank => _t('Bank transfer', 'تحويل بنكي');
  String get completeSale => _t('Complete Sale', 'إتمام البيع');
  String get newInvoice => _t('New Invoice', 'فاتورة جديدة');
  String get suspendInvoice => _t('Suspend Invoice', 'تعليق الفاتورة');
  String get suspendedInvoices => _t('Suspended', 'المعلقة');
  String get returnLabel => _t('Return', 'مرتجع');
  String get cancelInvoice => _t('Cancel Invoice', 'إلغاء الفاتورة');
  String get productsTab => _t('Products', 'المنتجات');
  String invoiceTab(int n) => _t('Invoice ($n)', 'الفاتورة ($n)');

  // POS messages
  String stockLimit(String name, int n) =>
      _t('Only $n of "$name" in stock', 'المتوفر من "$name" هو $n فقط');
  String outOfStockMsg(String name) =>
      _t('"$name" is out of stock', '"$name" غير متوفر في المخزون');
  String barcodeNotFound(String code) =>
      _t('No product with barcode $code', 'لا يوجد منتج بالباركود $code');
  String saleCompleted(String inv) =>
      _t('Sale $inv completed', 'تم إتمام البيع $inv');
  String invoiceSuspended(String inv) =>
      _t('Invoice $inv was suspended', 'تم تعليق الفاتورة $inv');
  String invoiceResumed(String inv) =>
      _t('Invoice $inv resumed', 'تم استئناف الفاتورة $inv');
  String invoiceCanceled(String inv) =>
      _t('Invoice $inv was canceled', 'تم إلغاء الفاتورة $inv');
  String get invoiceCleared => _t('Invoice cleared', 'تم مسح الفاتورة');
  String skippedProducts(String names) => _t(
    'No longer available and left out: $names',
    'لم تعد متوفرة وتم استبعادها: $names',
  );
  String get notEnoughStockTitle => _t('Not enough stock', 'المخزون غير كافٍ');
  String get confirmNewInvoiceTitle =>
      _t('Start a new invoice?', 'بدء فاتورة جديدة؟');
  String get confirmNewInvoiceBody => _t(
    'The products on the current invoice will be removed.',
    'سيتم حذف منتجات الفاتورة الحالية.',
  );
  String get confirmCancelTitle =>
      _t('Cancel this invoice?', 'إلغاء هذه الفاتورة؟');
  String get confirmCancelBody => _t(
    'All products on the invoice will be removed.',
    'سيتم حذف جميع منتجات الفاتورة.',
  );
  String confirmCancelSuspendedBody(String inv) => _t(
    'Suspended invoice $inv will be marked as canceled.',
    'سيتم تعليم الفاتورة المعلقة $inv كملغاة.',
  );
  String get keepInvoice => _t('Keep invoice', 'إبقاء الفاتورة');
  String get yesContinue => _t('Yes, continue', 'نعم، متابعة');

  // Discount dialog
  String get discountTitle => _t('Invoice discount', 'خصم الفاتورة');
  String get discountAmount => _t('Amount (₪)', 'مبلغ (₪)');
  String get discountPercent => _t('Percent (%)', 'نسبة (%)');
  String get discountValueLabel => _t('Discount', 'الخصم');
  String maxDiscountHint(double pct, String amount) => _t(
    'Maximum ${_pct(pct)}% of the subtotal ($amount)',
    'الحد الأقصى ${_pct(pct)}% من المجموع الفرعي ($amount)',
  );
  String get discountTooHigh =>
      _t('Discount is above the allowed maximum', 'الخصم أعلى من الحد المسموح');
  String get removeDiscount => _t('Remove discount', 'إزالة الخصم');
  String get apply => _t('Apply', 'تطبيق');

  // Suspended invoices dialog
  String get suspendedTitle => _t('Suspended Invoices', 'الفواتير المعلقة');
  String get noSuspended =>
      _t('There are no suspended invoices.', 'لا توجد فواتير معلقة.');
  String get resume => _t('Resume', 'استئناف');
  String itemsAndCashier(int items, String cashier) =>
      _t('$items items · $cashier', '$items أصناف · $cashier');
  String get replaceInvoiceTitle =>
      _t('Replace the current invoice?', 'استبدال الفاتورة الحالية؟');
  String get replaceInvoiceBody => _t(
    'The current invoice has products. Resuming will remove them.',
    'الفاتورة الحالية فيها منتجات، وسيتم حذفها عند الاستئناف.',
  );

  // Return dialog
  String get returnTitle => _t('Return Products', 'إرجاع منتجات');
  String get returnCodeLabel =>
      _t('Invoice number or receipt code', 'رقم الفاتورة أو رمز الإيصال');
  String get returnCodeHint => _t('e.g. INV-000012', 'مثال: INV-000012');
  String get find => _t('Find', 'بحث');
  String get chooseProduct =>
      _t('Choose the product to return', 'اختر المنتج المراد إرجاعه');
  String soldReturnable(int sold, int returnable) => _t(
    'Sold $sold · can return $returnable',
    'المباع $sold · يمكن إرجاع $returnable',
  );
  String get returnQuantity => _t('Quantity', 'الكمية');
  String get returnReason => _t('Reason', 'السبب');
  String get returnReasonHint =>
      _t('e.g. Damaged package', 'مثال: العبوة تالفة');
  String get confirmReturn => _t('Confirm Return', 'تأكيد الإرجاع');
  String get returnDone => _t('Return completed', 'تم الإرجاع');
  String refundInfo(String amount, String method) =>
      _t('Refund $amount by $method', 'استرداد $amount عن طريق $method');
  String get stockRestored => _t(
    'The units were put back into stock.',
    'تمت إعادة الكمية إلى المخزون.',
  );
  String get nothingToReturn => _t(
    'Everything on this invoice was already returned.',
    'تم إرجاع كل منتجات هذه الفاتورة.',
  );
  String get searchAgain => _t('Another invoice', 'فاتورة أخرى');
  String get done => _t('Done', 'تم');

  // Receipt dialog
  String get receiptTitle => _t('Digital Receipt', 'الإيصال الرقمي');
  String get scanReceipt => _t(
    'Scan the QR code to keep the receipt. Use this code for returns.',
    'امسح رمز QR للاحتفاظ بالإيصال، واستخدم الرمز عند الإرجاع.',
  );
  String get cashierLabel => _t('Cashier', 'الكاشير');
  String get paidBy => _t('Paid by', 'طريقة الدفع');
  String get close => _t('Close', 'إغلاق');

  // ── Delete dialog ─────────────────────────────────────────────────────────
  String get deleteTitle => _t('Delete product?', 'حذف المنتج؟');
  String deleteBody(String name) => _t(
    'Are you sure you want to delete "$name"? This will remove it from the inventory and update the statistics.',
    'هل أنت متأكد من حذف "$name"؟ سيتم إزالته من المخزون وتحديث الإحصائيات.',
  );
  String get deleteConfirm => _t('Delete', 'حذف');

  // ── Unsaved changes dialog ────────────────────────────────────────────────
  String get discardTitle => _t('Discard changes?', 'تجاهل التغييرات؟');
  String get discardBody => _t(
    'You have unsaved changes. If you leave now they will be lost.',
    'لديك تغييرات غير محفوظة. ستفقدها إذا غادرت الآن.',
  );
  String get discard => _t('Discard', 'تجاهل');
  String get keepEditing => _t('Keep editing', 'متابعة التعديل');

  // ── Stock Alerts (report Figure 3.5, UR-M9 / SR22) ─────────────────────────
  String get navStockAlerts => _t('Stock Alerts', 'تنبيهات المخزون');
  String get alertsTitle => _t('Stock Alerts', 'تنبيهات المخزون');
  String get alertsDescription => _t(
    'Products that need to be reordered',
    'المنتجات التي تحتاج إلى إضافة طلب',
  );
  String get alertsTotal => _t('Total alerts', 'إجمالي التنبيهات');
  String get alertsOut => _t('Out of stock', 'نفدت بالفعل');
  String get alertsLow => _t('Low stock', 'منخفضة المخزون');
  String get alertsTotalHint => _t('Low + out of stock', 'المنخفضة + النافدة');
  String get alertsOutHint => _t('Quantity is zero', 'الكمية صفر');
  String get alertsLowHint =>
      _t('At or below the alert level', 'عند الحد التنبيهي أو أقل');
  String get colCurrent => _t('Current stock', 'الحد الحالي');
  String get colAlertLevel => _t('Alert level', 'الحد التنبيهي');
  String get updateStock => _t('Update stock', 'تحديث المخزون');
  String get noAlerts => _t(
    'No alerts: every product is above its alert level.',
    'لا توجد تنبيهات: كل المنتجات فوق الحد التنبيهي.',
  );
  String get createPurchaseRequest =>
      _t('Create purchase request', 'إنشاء طلب شراء');
  String get purchaseNotAvailable => _t(
    'Purchase requests are not available yet: purchase orders (approval and sending to '
        'suppliers by WhatsApp, SR26–SR29) are not built.',
    'طلبات الشراء غير متاحة بعد: لم يتم بناء أوامر الشراء (الموافقة والإرسال '
        'للمورّد عبر واتساب، SR26–SR29).',
  );
  String get refresh => _t('Refresh', 'تحديث');
  String alertsShowing(int n, int total) =>
      _t('Showing $n of $total alerts', 'عرض $n من $total تنبيه');
  String get showAll => _t('Show all', 'عرض الكل');

  // ── Purchase cost (profit) ────────────────────────────────────────────────
  String get fieldCostPrice => _t('Cost price (₪)', 'سعر التكلفة (₪)');
  String get fieldCostPriceHelper => _t(
    'Optional. Used for profit reports; cashiers never see it.',
    'اختياري. يُستخدم لتقارير الأرباح ولا يظهر للكاشير.',
  );
  String get costAbovePrice => _t(
    'Cost is higher than the selling price (sold at a loss).',
    'التكلفة أعلى من سعر البيع (بيع بخسارة).',
  );

  // ── Reports & Sales Analytics (report Figures 3.7 / 3.8) ──────────────────
  String get navSectionReports => _t('REPORTS', 'التقارير');
  String get navReports => _t('Reports', 'التقارير');
  String get navSalesAnalytics => _t('Sales Analytics', 'المبيعات');
  String get reportsTitle => _t('Reports', 'التقارير');
  String get reportsDescription =>
      _t('Sales and profit report', 'تقرير المبيعات والأرباح');
  String get analyticsTitle => _t('Sales', 'المبيعات');
  String get analyticsDescription =>
      _t('Complete analysis of sales performance', 'تحليل شامل لأداء المبيعات');
  String get monthLabel => _t('Month', 'الشهر');
  String get yearLabel => _t('Year', 'السنة');
  List<String> get monthNames => isArabic
      ? const [
          'يناير',
          'فبراير',
          'مارس',
          'أبريل',
          'مايو',
          'يونيو',
          'يوليو',
          'أغسطس',
          'سبتمبر',
          'أكتوبر',
          'نوفمبر',
          'ديسمبر',
        ]
      : const [
          'January',
          'February',
          'March',
          'April',
          'May',
          'June',
          'July',
          'August',
          'September',
          'October',
          'November',
          'December',
        ];
  List<String> get monthShort => isArabic
      ? const [
          'ينا',
          'فبر',
          'مار',
          'أبر',
          'ماي',
          'يون',
          'يول',
          'أغس',
          'سبت',
          'أكت',
          'نوف',
          'ديس',
        ]
      : const [
          'Jan',
          'Feb',
          'Mar',
          'Apr',
          'May',
          'Jun',
          'Jul',
          'Aug',
          'Sep',
          'Oct',
          'Nov',
          'Dec',
        ];
  String get scopeMonth => _t('Selected month', 'الشهر المحدد');
  String get scopeYear => _t('Whole year', 'السنة كاملة');
  String get generateReport => _t('Generate report', 'توليد تقرير');
  String get dailySales => _t('Daily sales', 'المبيعات اليومية');
  String get monthlySales => _t('Monthly sales', 'المبيعات الشهرية');
  String get bestSelling => _t('Best-selling products', 'أكثر المنتجات مبيعاً');
  String get nearReorder =>
      _t('Products running out', 'الأصناف التي أوشكت على النفاد');
  String get featuredProducts => _t('Featured products', 'المنتجات المميزة');
  // Card labels (Figure 3.8: إجمالي مبيعات · إجمالي القيمة · عدد الفواتير · إجمالي الأرباح)
  String get cardNetUnits => _t('Net units sold', 'صافي الوحدات المباعة');
  String get cardNetRevenue => _t('Net revenue', 'صافي الإيراد');
  String get cardInvoices => _t('Invoice count', 'عدد الفواتير');
  String get cardProfit => _t('Profit', 'الربح');
  String get netUnitsHint => _t('Sold − returned', 'المباع − المرتجع');
  String get netRevenueHint => _t(
    'Incl. tax, after discounts and returns',
    'شامل الضريبة بعد الخصم والمرتجعات',
  );
  String get profitPartialTitle => _t('Profit (partial)', 'الربح (جزئي)');
  String profitPartialNote(String pct) => _t(
    'Only $pct of revenue has a known cost – not a complete total',
    'يشمل $pct فقط من الإيراد (له تكلفة معروفة) – ليس إجمالياً كاملاً',
  );
  String get profitPartialNoteNoPct => _t(
    'Some sales have no known cost – not a complete total',
    'بعض المبيعات بدون تكلفة معروفة – ليس إجمالياً كاملاً',
  );
  String get profitCompleteNote =>
      _t('Revenue without tax − cost', 'الإيراد بدون ضريبة − التكلفة');
  String get vsLastMonth => _t('vs last month', 'من الشهر الماضي');
  String get vsLastYear => _t('vs last year', 'من السنة الماضية');
  String get noPreviousData =>
      _t('No sales in the previous period', 'لا مبيعات في الفترة السابقة');
  String get profitUnavailable => _t('Not available', 'غير متاح');
  String get profitNeedsCost =>
      _t('Needs the purchase cost of products', 'يحتاج سعر تكلفة المنتجات');
  String inventoryValueHint(int products) =>
      _t('Stock value · $products products', 'قيمة المخزون · $products منتج');
  String returnsHint(String amount) =>
      _t('After returns of $amount', 'بعد مرتجعات بقيمة $amount');
  String get noSalesPeriod =>
      _t('No sales in this period', 'لا توجد مبيعات في هذه الفترة');
  String get noReorder => _t(
    'All products are above their minimum stock level',
    'كل المنتجات فوق الحد الأدنى للمخزون',
  );
  String get othersLabel => _t('Others', 'أخرى');
  String unitsSold(int n) => _t('$n sold', '$n مباع');
  String stockOfMin(int q, int min) =>
      _t('$q in stock · min $min', 'المتوفر $q · الحد الأدنى $min');
  String get lowStockShort => _t('Low stock', 'منخفض');
  String get reportsLoadError =>
      _t('Could not load the report.', 'تعذّر تحميل التقرير.');
  String get inventoryReport => _t('Inventory report', 'تقرير المخزون');
  String get invProducts => _t('Products', 'المنتجات');
  String get invUnits => _t('Units in stock', 'الوحدات المتوفرة');
  String get invValue => _t('Stock value', 'قيمة المخزون');
  String get invInStock => _t('In stock', 'متوفر');
  String get invLow => _t('Low stock', 'منخفض');
  String get invOut => _t('Out of stock', 'نفد');
  String get categoryColumn => _t('Category', 'التصنيف');
  String get productsColumn => _t('Products', 'المنتجات');
  String get unitsColumn => _t('Units', 'الوحدات');
  String get valueColumn => _t('Value', 'القيمة');
  String get salesValueNote => _t(
    'Amounts include tax, after discounts and returns.',
    'المبالغ شاملة الضريبة بعد الخصم والمرتجعات.',
  );
  String get reportDialogTitle => _t('Sales report', 'تقرير المبيعات');
  String get copyReport => _t('Copy', 'نسخ');
  String get reportCopied => _t('Report copied', 'تم نسخ التقرير');
  String get copyFailed => _t(
    'The browser did not allow copying. Select the text and copy it.',
    'المتصفح لم يسمح بالنسخ. حدّد النص وانسخه يدوياً.',
  );
  String get rPeriod => _t('Period', 'الفترة');
  String get rGross =>
      _t('Sales (completed invoices)', 'المبيعات (فواتير مكتملة)');
  String get rDiscounts => _t('Discounts given', 'الخصومات');
  String get rTax => _t('Tax on sales', 'الضريبة على المبيعات');
  String get rRefunds => _t('Refunds (returns)', 'المبالغ المرتجعة');
  String get rNet => _t('Net sales', 'صافي المبيعات');
  String get rInvoices => _t('Completed invoices', 'الفواتير المكتملة');
  String get rAverage => _t('Average invoice', 'متوسط الفاتورة');
  String get rUnits =>
      _t('Units sold / returned', 'الوحدات المباعة / المرتجعة');
  String get rReturns => _t('Returns', 'المرتجعات');
  String get rProfit => _t('Profit', 'الربح');
  String get rRevenueExTax => _t('Revenue without tax', 'الإيراد بدون ضريبة');
  String get rCost => _t('Cost of goods sold', 'تكلفة البضاعة المباعة');
  String get rNetUnits => _t('Net units sold', 'صافي الوحدات المباعة');
  String rProfitPartial(String amount, String pct) => _t(
    '$amount (partial: only $pct of revenue has a known cost)',
    '$amount (جزئي: $pct فقط من الإيراد له تكلفة معروفة)',
  );
  String get rTop => _t('Best-selling products', 'أكثر المنتجات مبيعاً');
}

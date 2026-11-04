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
}

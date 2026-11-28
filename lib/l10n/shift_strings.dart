import 'package:flutter/widgets.dart';

/// Cash drawer and shift texts (store upgrade, features 1 and 2).
class ShiftStrings {
  const ShiftStrings._(this.isArabic);

  final bool isArabic;

  static ShiftStrings of(BuildContext context) =>
      ShiftStrings._(Localizations.localeOf(context).languageCode == 'ar');

  static ShiftStrings forLanguage({required bool arabic}) =>
      ShiftStrings._(arabic);

  String _t(String en, String ar) => isArabic ? ar : en;

  // ── POS shift bar ──
  String get noShift => _t('No open shift', 'لا توجد وردية مفتوحة');
  String shiftOpenOn(String drawer) =>
      _t('Shift open · $drawer', 'الوردية مفتوحة · $drawer');
  String get openShift => _t('Open shift', 'فتح وردية');
  String get closeShift => _t('Close shift', 'إغلاق الوردية');
  String get shiftRequiredBanner => _t(
    'This store requires an open cash shift before selling.',
    'هذا المتجر يتطلب وردية نقدية مفتوحة قبل البيع.',
  );
  String get noDrawers => _t(
    'No cash drawer is set up yet. Ask your Store Admin to add one.',
    'لا يوجد درج نقدي بعد. اطلب من مدير المتجر إضافة درج.',
  );

  // ── open ──
  String get drawer => _t('Cash drawer', 'الدرج النقدي');
  String get drawerBusy => _t('in use', 'مستخدم');
  String get openingCash => _t('Opening cash (counted)', 'النقد الافتتاحي (المعدود)');
  String get open => _t('Open', 'فتح');
  String get logicalDrawerNote => _t(
    'This records the cash for the drawer; it does not open a physical drawer.',
    'هذا يسجّل نقد الدرج؛ ولا يفتح درجاً فعلياً.',
  );

  // ── close ──
  String get countedCash => _t('Counted cash', 'النقد المعدود');
  String get expectedCash => _t('Expected cash', 'النقد المتوقع');
  String get difference => _t('Difference', 'الفرق');
  String get note => _t('Note', 'ملاحظة');
  String get noteRequired => _t(
    'The counted cash differs: explain the difference (3–300 characters).',
    'النقد المعدود مختلف: اشرح سبب الفرق (3–300 حرف).',
  );
  String get reason => _t('Reason', 'السبب');
  String get reasonRequired =>
      _t('Enter the reason (3–300 characters).', 'أدخل السبب (3–300 حرف).');
  String get confirmClose => _t('Close the shift', 'إغلاق الوردية');
  String shiftClosed(String diff) =>
      _t('Shift closed. Difference: $diff', 'أُغلقت الوردية. الفرق: $diff');
  String shiftOpened(String drawer) =>
      _t('Shift opened on $drawer', 'فُتحت الوردية على $drawer');

  // ── summary lines ──
  String get opening => _t('Opening cash', 'النقد الافتتاحي');
  String get cashSales => _t('Cash sales', 'المبيعات النقدية');
  String get cashRefunds => _t('Cash refunds', 'المرتجعات النقدية');
  String get cashIn => _t('Cash in', 'إيداع نقدي');
  String get cashOut => _t('Cash out', 'سحب نقدي');
  String get otherSales =>
      _t('Card / wallet / transfer sales (not in drawer)', 'مبيعات البطاقة / المحفظة / التحويل (ليست في الدرج)');

  // ── Store Admin page ──
  String get navShifts => _t('Cash shifts', 'الورديات النقدية');
  String get pageSubtitle => _t(
    'Drawers, shifts, cash in / out and closing differences of your store',
    'الأدراج والورديات والإيداع والسحب وفروقات الإغلاق في متجرك',
  );
  String get requireShifts => _t('Require a cash shift for every sale', 'إلزام وردية نقدية لكل عملية بيع');
  String get requireShiftsHint => _t(
    'When on, cashiers must open a shift before selling or giving a cash refund.',
    'عند التفعيل، يجب على الكاشير فتح وردية قبل البيع أو إرجاع مبلغ نقدي.',
  );
  String get drawers => _t('Cash drawers', 'الأدراج النقدية');
  String get cashRounding => _t('Cash rounding', 'تقريب الدفع النقدي');
  String get cashRoundingHint => _t(
    'Cash invoices are rounded to the nearest step (half up). The invoice total, card payments and reports are not changed.',
    'تُقرَّب الفواتير النقدية إلى أقرب خطوة (النصف للأعلى). لا يتغير إجمالي الفاتورة ولا الدفع بالبطاقة ولا التقارير.',
  );
  String get noRounding => _t('None', 'بدون');
  String get addDrawer => _t('Add drawer', 'إضافة درج');
  String get drawerName => _t('Drawer name', 'اسم الدرج');
  String get active => _t('Active', 'فعّال');
  String get inactive => _t('Not in use', 'غير مستخدم');
  String get save => _t('Save', 'حفظ');
  String get cancel => _t('Cancel', 'إلغاء');
  String get shifts => _t('Shifts', 'الورديات');
  String get all => _t('All', 'الكل');
  String get statusOpen => _t('Open', 'مفتوحة');
  String get statusClosed => _t('Closed', 'مغلقة');
  String get cashier => _t('Cashier', 'الكاشير');
  String get openedAt => _t('Opened', 'الفتح');
  String get closedAt => _t('Closed', 'الإغلاق');
  String get counted => _t('Counted', 'المعدود');
  String get noShifts => _t('No shifts yet.', 'لا توجد ورديات بعد.');
  String get details => _t('Shift details', 'تفاصيل الوردية');
  String get adminClose => _t('Close as Store Admin', 'إغلاق بصلاحية المدير');
  String get adminCloseKind => _t('Closed by Store Admin', 'أغلقها مدير المتجر');
  String get movements => _t('Cash movements', 'الحركات النقدية');
  String get sales => _t('Sales', 'المبيعات');
  String get amount => _t('Amount', 'المبلغ');
  String get noMovements => _t('No cash movements.', 'لا توجد حركات نقدية.');
  String get movementSaved => _t('Cash movement recorded', 'سُجّلت الحركة النقدية');
  String get closedBy => _t('Closed by', 'أغلقها');
  String get invalidAmount => _t(
    'Enter an amount of 0 or more with at most 2 decimals.',
    'أدخل مبلغاً 0 أو أكثر بخانتين عشريتين على الأكثر.',
  );
  String get positiveAmount =>
      _t('Enter an amount greater than 0.', 'أدخل مبلغاً أكبر من 0.');

  /// Localized messages for the backend's shift codes (null = use the backend's message).
  String? forCode(String? code) => switch (code) {
    'SHIFT_REQUIRED' => _t(
      'Open a cash shift first: this store requires one for every sale and cash refund.',
      'افتح وردية نقدية أولاً: هذا المتجر يتطلب وردية لكل بيع وكل إرجاع نقدي.',
    ),
    'SHIFT_CLOSED' => _t('This shift is already closed.', 'هذه الوردية مغلقة بالفعل.'),
    'SHIFT_CLOSED_RECORD' => _t(
      'This record belongs to a closed shift and can no longer be changed.',
      'هذا السجل تابع لوردية مغلقة ولا يمكن تعديله.',
    ),
    'SHIFT_EXPECTED_MISMATCH' || 'SHIFT_CHANGED' => _t(
      'The cash in this shift changed while closing. Review and try again.',
      'تغيّر النقد في الوردية أثناء الإغلاق. راجع المبالغ وحاول مجدداً.',
    ),
    'SHIFT_ALREADY_OPEN' => _t('You already have an open shift.', 'لديك وردية مفتوحة بالفعل.'),
    'DRAWER_IN_USE' => _t('This drawer already has an open shift.', 'هذا الدرج عليه وردية مفتوحة.'),
    'DRAWER_INACTIVE' => _t('This drawer is not in use.', 'هذا الدرج غير مستخدم.'),
    'DRAWER_NAME_TAKEN' => _t('A drawer with this name already exists.', 'يوجد درج بهذا الاسم.'),
    'CASH_OUT_TOO_LARGE' => _t(
      'The drawer does not hold that much cash.',
      'الدرج لا يحتوي على هذا المبلغ.',
    ),
    _ => null,
  };
}

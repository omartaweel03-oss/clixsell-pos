import 'package:flutter/widgets.dart';

import '../models/purchase_order.dart';
import '../services/api_http.dart';

/// Arabic / English text of the Purchase Orders screens (Phase 4).
///
/// Wording rule: nothing here says ClixSell sent, delivered or confirmed a
/// message. ClixSell prepares the message; the manager sends it and records it.
class PurchaseOrderStrings {
  const PurchaseOrderStrings._(this.isArabic);

  final bool isArabic;

  static PurchaseOrderStrings of(BuildContext context) =>
      PurchaseOrderStrings._(
        Localizations.localeOf(context).languageCode == 'ar',
      );

  String _t(String en, String ar) => isArabic ? ar : en;

  /// Keeps a name / number in its own direction inside the other language.
  String iso(String v) => '\u2068$v\u2069';

  // ── navigation ─────────────────────────────────────────────────────────────
  String get navSection => _t('PURCHASING', 'المشتريات');
  String get navOrders => _t('Purchase Orders', 'أوامر الشراء');
  String get navRecommendations => _t('Reorder Suggestions', 'اقتراحات الطلب');

  // ── common ─────────────────────────────────────────────────────────────────
  String get retry => _t('Try again', 'إعادة المحاولة');
  String get cancel => _t('Cancel', 'إلغاء');
  String get close => _t('Close', 'إغلاق');
  String get back => _t('Back', 'رجوع');
  String get refresh => _t('Refresh', 'تحديث');
  String get loading => _t('Loading…', 'جارٍ التحميل…');
  String get notVerified => _t(
    'Reported by this app; ClixSell cannot verify it.',
    'حسب التطبيق؛ لا يستطيع ClixSell التحقق منه.',
  );
  String get manualRecord =>
      _t('Recorded manually by a manager', 'تسجيل يدوي من المدير');
  String by(String name, String? at) => _t(
    'by ${iso(name)}${at == null ? '' : ' · ${iso(at)}'}',
    'بواسطة ${iso(name)}${at == null ? '' : ' · ${iso(at)}'}',
  );
  String get noCostInMessage => _t(
    'For your information only — never shown in the supplier message.',
    'للعلم فقط — لا تظهر في رسالة المورد أبداً.',
  );
  String get unknownCost =>
      _t('Unknown (no cost recorded)', 'غير معروفة (لا توجد تكلفة مسجلة)');

  // ── statuses ───────────────────────────────────────────────────────────────
  String status(PurchaseOrderStatus s) => switch (s) {
    PurchaseOrderStatus.pendingApproval => _t(
      'Pending approval',
      'بانتظار الموافقة',
    ),
    PurchaseOrderStatus.approved => _t('Approved', 'موافق عليه'),
    PurchaseOrderStatus.messageGenerated => _t(
      'Message prepared (not sent)',
      'الرسالة جاهزة (لم تُرسل)',
    ),
    PurchaseOrderStatus.sent => _t('Recorded as sent', 'سُجّل كمُرسل'),
    PurchaseOrderStatus.confirmed => _t(
      'Supplier confirmation recorded',
      'سُجّل تأكيد المورد',
    ),
    PurchaseOrderStatus.closed => _t(
      'Closed (no receiving)',
      'مغلق (بدون استلام)',
    ),
    PurchaseOrderStatus.rejected => _t('Rejected', 'مرفوض'),
    PurchaseOrderStatus.canceled => _t('Canceled', 'ملغى'),
  };

  String statusShort(PurchaseOrderStatus s) => switch (s) {
    PurchaseOrderStatus.pendingApproval => _t('Pending', 'بانتظار الموافقة'),
    PurchaseOrderStatus.approved => _t('Approved', 'موافق عليه'),
    PurchaseOrderStatus.messageGenerated => _t(
      'Message prepared',
      'الرسالة جاهزة',
    ),
    PurchaseOrderStatus.sent => _t('Recorded as sent', 'مسجّل كمُرسل'),
    PurchaseOrderStatus.confirmed => _t(
      'Supplier confirmed',
      'تأكيد المورد مسجّل',
    ),
    PurchaseOrderStatus.closed => _t('Closed', 'مغلق'),
    PurchaseOrderStatus.rejected => _t('Rejected', 'مرفوض'),
    PurchaseOrderStatus.canceled => _t('Canceled', 'ملغى'),
  };

  String source(String s) =>
      s == 'GUARDIAN' ? _t('Guardian', 'Guardian') : _t('Manual', 'يدوي');

  // ── list (S1) ──────────────────────────────────────────────────────────────
  String get ordersTitle => _t('Purchase Orders', 'أوامر الشراء');
  String get ordersDescription => _t(
    'Orders to your suppliers: approval, the supplier message and what you recorded. ClixSell never sends messages itself.',
    'طلبات الشراء من الموردين: الموافقة، رسالة المورد، وما سجّلته. ClixSell لا يرسل الرسائل بنفسه.',
  );
  String get newOrder => _t('New Purchase Order', 'أمر شراء جديد');
  String get searchOrders =>
      _t('Search by order number or supplier', 'ابحث برقم الأمر أو اسم المورد');
  String get filterAll => _t('All', 'الكل');
  String get cardPending => _t('Waiting for approval', 'بانتظار الموافقة');
  String get cardToContact =>
      _t('To contact supplier', 'بانتظار التواصل مع المورد');
  String get cardAwaitingSupplier =>
      _t('Waiting for supplier', 'بانتظار تأكيد المورد');
  String get cardOverdue => _t('Overdue', 'متأخرة');
  String get cardOpenHint => _t('open orders', 'أوامر مفتوحة');
  String get colNumber => _t('Order', 'الأمر');
  String get colSupplier => _t('Supplier', 'المورد');
  String get colItems => _t('Items', 'البنود');
  String get colSource => _t('Source', 'المصدر');
  String get colStatus => _t('Status', 'الحالة');
  String get colCreated => _t('Created', 'أُنشئ');
  String get colActions => _t('Actions', 'إجراءات');
  String get open => _t('Open', 'فتح');
  String openOrderTooltip(String n) => _t('Open $n', 'فتح ${iso(n)}');
  String get overdue => _t('Overdue', 'متأخر');
  String get noOrdersTitle =>
      _t('No purchase orders yet', 'لا توجد أوامر شراء بعد');
  String get noOrdersBody => _t(
    'Create an order manually or from the reorder suggestions.',
    'أنشئ أمراً يدوياً أو من اقتراحات الطلب.',
  );
  String get noMatchTitle => _t('No matching orders', 'لا توجد أوامر مطابقة');
  String get noMatchBody =>
      _t('Try another search or filter.', 'جرّب بحثاً أو فلتراً آخر.');
  String showing(int from, int to, int total) => _t(
    'Showing ${iso('$from–$to')} of ${iso('$total')}',
    'عرض ${iso('$from–$to')} من ${iso('$total')}',
  );
  String get couldNotLoad =>
      _t('Could not load the orders', 'تعذّر تحميل الأوامر');

  // ── recommendations (S2) ──────────────────────────────────────────────────
  String get recTitle => _t('Reorder Suggestions', 'اقتراحات الطلب');
  String get recDescription => _t(
    'Products to reorder by supplier, calculated by fixed rules from stock and recent sales (not an AI model).',
    'منتجات يُقترح طلبها حسب المورد، محسوبة بقواعد ثابتة من المخزون والمبيعات الأخيرة (ليست نموذج ذكاء اصطناعي).',
  );
  String get guardianOffTitle =>
      _t('Guardian Mode is off', 'وضع Guardian مطفأ');
  String get guardianOffBody => _t(
    'Reorder suggestions are calculated only while Guardian Mode is on. Nothing is shown instead of them. You can still create a manual purchase order.',
    'اقتراحات الطلب تُحسب فقط عندما يكون وضع Guardian مفعّلاً. لا نعرض أي اقتراحات بديلة. يمكنك إنشاء أمر شراء يدوي.',
  );
  String get manualOrder => _t('Create a manual order', 'إنشاء أمر يدوي');
  String get noRecommendations =>
      _t('Nothing needs reordering now', 'لا يوجد ما يحتاج طلباً الآن');
  String get noRecommendationsBody => _t(
    'No product is out of stock, at its minimum or expected to run out within 7 days.',
    'لا يوجد منتج نفد أو وصل للحد الأدنى أو يُتوقع نفاده خلال 7 أيام.',
  );
  String get rulesNote => _t(
    'Rules: urgent = runs out within 3 days, soon = 4–7 days. Suggested quantities are calculated by the server; you can change them before saving.',
    'القواعد: عاجل = ينفد خلال 3 أيام، قريب = خلال 4–7 أيام. الكميات المقترحة يحسبها الخادم؛ ويمكنك تغييرها قبل الحفظ.',
  );
  String createFromSelected(int n) => _t(
    'Create order from $n selected',
    'إنشاء أمر من ${iso('$n')} محدد',
  );
  String selectProduct(String name) => _t('Select $name', 'تحديد ${iso(name)}');
  String get selectOneSupplier => _t(
    'Select products of one supplier to create an order.',
    'حدّد منتجات مورد واحد لإنشاء أمر.',
  );
  String get supplierInactive => _t('Supplier inactive', 'المورد موقوف');
  String get noWhatsapp => _t('No WhatsApp number', 'لا يوجد رقم WhatsApp');
  String get inconsistentTitle => _t(
    'Products with an invalid stock quantity',
    'منتجات بكمية مخزون غير صالحة',
  );
  String get inconsistentBody => _t(
    'These products cannot be ordered until their stock is corrected in Products.',
    'لا يمكن طلب هذه المنتجات حتى تُصحّح كميتها في صفحة المنتجات.',
  );
  String classification(String c) => switch (c) {
    'OUT_OF_STOCK' => _t('Out of stock', 'نفد'),
    'URGENT' => _t('Urgent (≤ 3 days)', 'عاجل (≤ 3 أيام)'),
    'SOON' => _t('Soon (4–7 days)', 'قريب (4–7 أيام)'),
    'LOW_STOCK' => _t('At / below minimum', 'عند الحد الأدنى أو أقل'),
    'INCONSISTENT' => _t('Invalid stock', 'مخزون غير صالح'),
    _ => c,
  };
  String unavailable(String? r) => switch (r) {
    'SUPPLIER_INACTIVE' => _t('Supplier inactive', 'المورد موقوف'),
    'IN_OPEN_ORDER' => _t('Already in an open order', 'موجود في أمر مفتوح'),
    'INCONSISTENT_STOCK' => _t(
      'Invalid stock quantity',
      'كمية مخزون غير صالحة',
    ),
    _ => _t('Not available', 'غير متاح'),
  };
  String inOpenOrder(String number, int qty) => _t(
    'In open order ${iso(number)} (${iso('$qty')})',
    'في الأمر المفتوح ${iso(number)} (${iso('$qty')})',
  );
  String get colProduct => _t('Product', 'المنتج');
  String get colStock => _t('Stock / Min', 'المخزون / الحد الأدنى');
  String get colUrgency => _t('Urgency', 'الأولوية');
  String get colSuggested => _t('Suggested', 'المقترح');
  String get colQuantity => _t('Quantity', 'الكمية');
  String get colWhy => _t('How it was calculated', 'طريقة الحساب');
  String get noSuggestedQty =>
      _t('No basis — choose yourself', 'لا يوجد أساس — اختر بنفسك');

  /// The rule explanation in the UI language, from the server's numbers.
  String explanation(RecommendationItem r) {
    switch (r.basis) {
      case 'SALES_RATE':
        return _t(
          '${iso('${r.netUnits}')} net units sold in ${iso('${r.observedDays}')} days (${iso('${r.unitsPerDay ?? 0}')}/day). Target ${iso('${r.targetStock}')} = minimum ${iso('${r.minStock}')} + 14 days of sales. Suggested = ${iso('${r.targetStock}')} − ${iso('${r.quantity}')} in stock = ${iso('${r.suggestedQuantity}')}.',
          'بيع ${iso('${r.netUnits}')} وحدة صافية خلال ${iso('${r.observedDays}')} يوماً (${iso('${r.unitsPerDay ?? 0}')} يومياً). الهدف ${iso('${r.targetStock}')} = الحد الأدنى ${iso('${r.minStock}')} + مبيعات 14 يوماً. المقترح = ${iso('${r.targetStock}')} − ${iso('${r.quantity}')} في المخزون = ${iso('${r.suggestedQuantity}')}.',
        );
      case 'MIN_STOCK_ONLY':
        return _t(
          'Not enough sales history (${iso('${r.netUnits}')} units in ${iso('${r.observedDays}')} days). Based on the minimum only, not a forecast: target = 2 × ${iso('${r.minStock}')} = ${iso('${r.targetStock}')}. Suggested = ${iso('${r.suggestedQuantity}')}.',
          'سجل المبيعات غير كافٍ (${iso('${r.netUnits}')} وحدة خلال ${iso('${r.observedDays}')} يوماً). الحساب من الحد الأدنى فقط وليس توقعاً: الهدف = 2 × ${iso('${r.minStock}')} = ${iso('${r.targetStock}')}. المقترح = ${iso('${r.suggestedQuantity}')}.',
        );
      default:
        return r.classification == 'INCONSISTENT'
            ? _t(
                'The stock quantity is not valid (${iso('${r.quantity}')}). Correct the product first.',
                'كمية المخزون غير صالحة (${iso('${r.quantity}')}). صحّح المنتج أولاً.',
              )
            : _t(
                'Not enough sales history and no minimum stock: no quantity can be suggested.',
                'لا يوجد سجل مبيعات كافٍ ولا حد أدنى: لا يمكن اقتراح كمية.',
              );
    }
  }

  // ── cautions ───────────────────────────────────────────────────────────────
  String cautionTitle(String type) => switch (type) {
    'RECENTLY_CLOSED' => _t(
      'Ordered recently (closed, receiving not recorded)',
      'طُلب مؤخراً (أُغلق، ولم يُسجّل استلام)',
    ),
    'CANCELED_AFTER_CONTACT' => _t(
      'Canceled after the supplier was contacted',
      'أُلغي بعد التواصل مع المورد',
    ),
    'CONTACT_UNKNOWN' => _t(
      'Canceled — unknown whether the supplier got the message',
      'أُلغي — غير معروف إن وصلت الرسالة للمورد',
    ),
    'DECLARED_NOT_SENT' => _t(
      'Canceled as "not sent" after the message was copied/opened',
      'أُلغي كـ"لم تُرسل" بعد نسخ/فتح الرسالة',
    ),
    _ => type,
  };
  String cautionBody(PurchaseCaution c) => _t(
    'Order ${iso(c.orderNumber)} (${iso('${c.quantity}')}) ${c.at == null ? '' : 'on ${iso(c.at!.substring(0, 10))} '}may still arrive from the supplier.',
    'الأمر ${iso(c.orderNumber)} (${iso('${c.quantity}')}) ${c.at == null ? '' : 'بتاريخ ${iso(c.at!.substring(0, 10))} '}قد يصل من المورد.',
  );
  String get ackCaution => _t(
    'I checked: order this product anyway',
    'تحققت: اطلب هذا المنتج على أي حال',
  );
  String get cautionsTitle =>
      _t('Recently ordered products', 'منتجات طُلبت مؤخراً');
  String get cautionsBody => _t(
    'These products are in recent orders that may still arrive. Confirm each one to continue.',
    'هذه المنتجات في أوامر حديثة قد تصل. أكّد كل واحد منها للمتابعة.',
  );

  // ── form (S3) ──────────────────────────────────────────────────────────────
  String get newOrderTitle => _t('New Purchase Order', 'أمر شراء جديد');
  String editOrderTitle(String n) => _t('Edit ${iso(n)}', 'تعديل ${iso(n)}');
  String get formDescription => _t(
    'Choose a supplier and the products to order. The order is saved as pending approval.',
    'اختر المورد والمنتجات المطلوبة. يُحفظ الأمر بانتظار الموافقة.',
  );
  String get stepSupplier => _t('1. Supplier', '1. المورد');
  String get stepProducts =>
      _t('2. Products and quantities', '2. المنتجات والكميات');
  String get stepReview => _t('3. Note and review', '3. الملاحظة والمراجعة');
  String get chooseSupplier => _t('Choose a supplier', 'اختر المورد');
  String get inactiveSupplierOption => _t('(inactive)', '(موقوف)');
  String get supplierNoWhatsappWarning => _t(
    'This supplier has no WhatsApp number: you will not be able to prepare the supplier message until you add one in Suppliers.',
    'هذا المورد ليس لديه رقم WhatsApp: لن تستطيع تجهيز رسالة المورد حتى تضيفه من صفحة الموردين.',
  );
  String get fromSuggestions =>
      _t('From reorder suggestions (Guardian)', 'من اقتراحات الطلب (Guardian)');
  String get manualSource => _t('Manual order', 'أمر يدوي');
  String get noProducts =>
      _t('This supplier has no products.', 'لا توجد منتجات لهذا المورد.');
  String get addProductToOrder => _t('Add', 'إضافة');
  String get removeLine => _t('Remove', 'إزالة');
  String get quantityError =>
      _t('Whole number 1–100000', 'رقم صحيح من 1 إلى 100000');
  String get noteLabel =>
      _t('Internal note (optional)', 'ملاحظة داخلية (اختيارية)');
  String get noteHelp => _t(
    'For your team only. It is not included in the supplier message.',
    'لفريقك فقط. لا تُضاف إلى رسالة المورد.',
  );
  String get noteTooLong => _t('At most 500 characters', '500 حرف كحد أقصى');
  String get expectedCost => _t('Expected cost', 'التكلفة المتوقعة');
  String get selectedItems => _t('Order lines', 'بنود الأمر');
  String get noLines =>
      _t('Add at least one product.', 'أضف منتجاً واحداً على الأقل.');
  String get save => _t('Save order', 'حفظ الأمر');
  String get saveChanges => _t('Save changes', 'حفظ التغييرات');
  String get changedQuantity =>
      _t('changed from the suggestion', 'معدّلة عن الاقتراح');
  String get deletedLineWillBeRemoved => _t(
    'The product of this line was deleted; saving removes the line.',
    'منتج هذا البند حُذف؛ الحفظ يزيله من الأمر.',
  );
  String get unresolvedTitle =>
      _t('Saving could not be confirmed', 'تعذّر التأكد من الحفظ');
  String get unresolvedBody => _t(
    'The server did not answer. The order may or may not have been saved. The form is locked; "Check and retry" sends exactly the same request again, so it can never create a second order.',
    'لم يرد الخادم. قد يكون الأمر حُفظ أو لم يُحفظ. النموذج مقفل؛ "تحقق وأعد المحاولة" يرسل نفس الطلب بالضبط، فلا يمكن أن يُنشئ أمراً ثانياً.',
  );
  String get checkAndRetry => _t('Check and retry', 'تحقق وأعد المحاولة');
  String get savedReplayed =>
      _t('The order had already been saved.', 'الأمر كان قد حُفظ مسبقاً.');
  String get leaveTitle => _t('Discard changes?', 'تجاهل التغييرات؟');
  String get leaveBody => _t(
    'Your changes to this order are not saved.',
    'تغييراتك على هذا الأمر غير محفوظة.',
  );
  String get discard => _t('Discard', 'تجاهل');
  String get keepEditing => _t('Keep editing', 'متابعة التعديل');

  // ── details (S4) ───────────────────────────────────────────────────────────
  String get tabItems => _t('Items', 'البنود');
  String get tabHistory => _t('History', 'السجل');
  String get stepsTitle => _t('Progress', 'المراحل');
  String get approvalBlockersTitle =>
      _t('Why it cannot be approved now', 'لماذا لا يمكن الموافقة الآن');
  String blocker(String b) => switch (b) {
    'SUPPLIER_INACTIVE' => _t('The supplier is inactive.', 'المورد موقوف.'),
    'ORDER_EMPTY' => _t('The order has no products.', 'الأمر لا يحتوي منتجات.'),
    'ITEM_PRODUCT_DELETED' => _t(
      'A product of the order was deleted: edit the order.',
      'أحد منتجات الأمر حُذف: عدّل الأمر.',
    ),
    'PRODUCT_SUPPLIER_CHANGED' => _t(
      'A product now has another supplier: edit the order.',
      'أحد المنتجات أصبح لمورد آخر: عدّل الأمر.',
    ),
    'MESSAGE_TOO_LONG' => _t(
      'The supplier message would be too long for WhatsApp: move some lines to another order.',
      'رسالة المورد ستكون أطول من المسموح: انقل بعض البنود إلى أمر آخر.',
    ),
    _ => b,
  };
  String get lengthTitle => _t('Supplier message length', 'طول رسالة المورد');
  String lengthLine(String lang, int len, int max, int limit) => _t(
    '${lang == 'ar' ? 'Arabic' : 'English'}: ${iso('$len')} characters now, at most ${iso('$max')} (limit ${iso('$limit')})',
    '${lang == 'ar' ? 'العربية' : 'الإنجليزية'}: ${iso('$len')} حرفاً الآن، وحتى ${iso('$max')} (الحد ${iso('$limit')})',
  );
  String moveLines(int n) => _t(
    'Too long: move at least ${iso('$n')} line(s) to another order. Longest lines:',
    'طويلة جداً: انقل ${iso('$n')} بند/بنود على الأقل إلى أمر آخر. أطول البنود:',
  );
  String get fitsOk =>
      _t('Fits in one WhatsApp message.', 'تتسع في رسالة WhatsApp واحدة.');
  String get colBarcode => _t('Barcode', 'الباركود');
  String get colBasis => _t('Basis', 'الأساس');
  String get colUnitCost => _t('Expected unit cost', 'تكلفة الوحدة المتوقعة');
  String get reserved => _t('Reserved', 'محجوز');
  String get productDeleted => _t('Product deleted', 'المنتج حُذف');
  String basis(String b) => switch (b) {
    'SALES_RATE' => _t('Sales rate', 'معدل المبيعات'),
    'MIN_STOCK_ONLY' => _t('Minimum only', 'الحد الأدنى فقط'),
    _ => _t('Manual', 'يدوي'),
  };
  String get totalExpected =>
      _t('Expected total cost', 'إجمالي التكلفة المتوقعة');
  String get noteTitle => _t('Internal note', 'الملاحظة الداخلية');
  String get createdLabel => _t('Created', 'أُنشئ');
  String get approvedLabel => _t('Approved', 'الموافقة');
  String get rejectedLabel => _t('Rejected', 'الرفض');
  String get canceledLabel => _t('Canceled', 'الإلغاء');
  String get readOnlyFinal => _t(
    'This order is final: it can no longer be changed.',
    'هذا الأمر نهائي: لا يمكن تغييره.',
  );
  String get overdueExplain => _t(
    'Overdue: the next step has waited longer than usual (pending ≥ 3 days, approved or message prepared ≥ 1 day, sent ≥ 3 days, confirmed ≥ 14 days).',
    'متأخر: الخطوة التالية تنتظر أكثر من المعتاد (بانتظار الموافقة ≥ 3 أيام، موافق عليه أو الرسالة جاهزة ≥ يوم، مُرسل ≥ 3 أيام، مؤكد ≥ 14 يوماً).',
  );
  String get supplierInactiveBanner =>
      _t('This supplier is inactive.', 'هذا المورد موقوف.');

  // message card
  String get messageTitle => _t('Supplier message', 'رسالة المورد');
  String get savedDestination =>
      _t('Saved WhatsApp number', 'رقم WhatsApp المحفوظ');
  String preparedBy(String who, String? at) => _t(
    'Prepared by ${iso(who)}${at == null ? '' : ' · ${iso(at)}'} — not sent by ClixSell',
    'جهّزها ${iso(who)}${at == null ? '' : ' · ${iso(at)}'} — ClixSell لم يرسلها',
  );
  String numberChanged(String saved, String? current) => _t(
    'The supplier\'s number changed after this message was prepared. This order stays bound to ${iso(saved)}${current == null ? '' : ' (current number: ${iso(current)})'}. ClixSell never redirects it. To use the new number before sending, cancel this order ("not sent") and create a new one.',
    'تغيّر رقم المورد بعد تجهيز هذه الرسالة. هذا الأمر يبقى مرتبطاً بالرقم ${iso(saved)}${current == null ? '' : ' (الرقم الحالي: ${iso(current)})'}. ClixSell لا يحوّله أبداً. لاستعمال الرقم الجديد قبل الإرسال: ألغِ هذا الأمر ("لم تُرسل") وأنشئ أمراً جديداً.',
  );
  String get messageReadOnly => _t(
    'Read only: this order is final; the message must not be sent.',
    'للقراءة فقط: هذا الأمر نهائي؛ ويجب ألا تُرسل الرسالة.',
  );

  // communication card
  String get communicationTitle =>
      _t('Supplier communication', 'التواصل مع المورد');
  String copies(int n) => _t(
    'Message copied: ${iso('$n')} time(s)',
    'نُسخت الرسالة: ${iso('$n')} مرة',
  );
  String opens(int n, int reports) => _t(
    'WhatsApp opening requested: ${iso('$n')} time(s) (results reported: ${iso('$reports')})',
    'طُلب فتح WhatsApp: ${iso('$n')} مرة (نتائج مُبلّغ عنها: ${iso('$reports')})',
  );
  String get sentNotRecorded =>
      _t('Sending: not recorded', 'الإرسال: غير مسجّل');
  String sentRecorded(String who, String? at) => _t(
    'Sending recorded by ${iso(who)}${at == null ? '' : ' · ${iso(at)}'} (manual record)',
    'سجّل ${iso(who)} الإرسال${at == null ? '' : ' · ${iso(at)}'} (تسجيل يدوي)',
  );
  String confirmationRecorded(String who, String? at) => _t(
    'Supplier confirmation recorded by ${iso(who)}${at == null ? '' : ' · ${iso(at)}'} (manual record)',
    'سجّل ${iso(who)} تأكيد المورد${at == null ? '' : ' · ${iso(at)}'} (تسجيل يدوي)',
  );
  String closedRecorded(String who, String? at) => _t(
    'Closed by ${iso(who)}${at == null ? '' : ' · ${iso(at)}'} — goods receiving is not recorded; stock did not change',
    'أغلقه ${iso(who)}${at == null ? '' : ' · ${iso(at)}'} — لم يُسجّل استلام البضاعة؛ والمخزون لم يتغير',
  );
  String contactAtCancel(String? c) => switch (c) {
    'NOT_SENT' => _t(
      'Canceled — the manager declared the message was not sent',
      'أُلغي — صرّح المدير أن الرسالة لم تُرسل',
    ),
    'UNKNOWN' => _t(
      'Canceled — unknown whether the supplier got the message',
      'أُلغي — غير معروف إن وصلت الرسالة للمورد',
    ),
    'MANAGER_RECORDED_SENT' => _t(
      'Canceled after the supplier was contacted',
      'أُلغي بعد التواصل مع المورد',
    ),
    _ => _t(
      'Canceled before any supplier message',
      'أُلغي قبل أي رسالة للمورد',
    ),
  };
  String get cancelNotTold => _t(
    'Cancelling in ClixSell does not mean the supplier was told or agreed.',
    'الإلغاء في ClixSell لا يعني أن المورد أُبلغ أو وافق.',
  );
  String get supplierAcceptanceRecorded => _t(
    'Supplier acceptance of the cancellation: recorded (manual record)',
    'قبول المورد للإلغاء: مسجّل (تسجيل يدوي)',
  );
  String get supplierAcceptanceMissing => _t(
    'Supplier acceptance of the cancellation: not recorded — the products show a reorder warning for 30 days.',
    'قبول المورد للإلغاء: غير مسجّل — تظهر المنتجات بتحذير عند إعادة الطلب لمدة 30 يوماً.',
  );

  // ── actions ────────────────────────────────────────────────────────────────
  String get actEdit => _t('Edit', 'تعديل');
  String get actApprove => _t('Approve', 'موافقة');
  String get actReject => _t('Reject', 'رفض');
  String get actCancel => _t('Cancel order', 'إلغاء الأمر');
  String get actPrepare => _t('Prepare Message', 'تجهيز الرسالة');
  String get actOpen => _t('Open WhatsApp', 'فتح WhatsApp');
  String get actCopy => _t('Copy Message', 'نسخ الرسالة');
  String get actMarkSent =>
      _t('I Have Sent This Message', 'لقد أرسلت هذه الرسالة');
  String get actConfirm =>
      _t('Record Supplier Confirmation', 'تسجيل تأكيد المورد');
  String get actClose =>
      _t('Close Order (no receiving)', 'إغلاق الأمر (بدون استلام)');
  String get actSupplierCancel => _t(
    'Record Supplier Acceptance of Cancellation',
    'تسجيل قبول المورد للإلغاء',
  );
  String get actMore => _t('More', 'المزيد');
  String get actOpenAgain =>
      _t('Open WhatsApp again (follow-up)…', 'فتح WhatsApp مرة أخرى (متابعة)…');

  // ── dialogs ────────────────────────────────────────────────────────────────
  String get approveTitle =>
      _t('Approve this order?', 'الموافقة على هذا الأمر؟');
  String get approveBody => _t(
    'After approval you can prepare the supplier message. Nothing is sent to the supplier, and stock does not change.',
    'بعد الموافقة يمكنك تجهيز رسالة المورد. لا يُرسل أي شيء للمورد، ولا يتغير المخزون.',
  );
  String get rejectTitle => _t('Reject this order?', 'رفض هذا الأمر؟');
  String get reasonLabel => _t('Reason (required)', 'السبب (إلزامي)');
  String get reasonError => _t('3–300 characters', 'من 3 إلى 300 حرف');
  String get cancelTitle => _t('Cancel this order?', 'إلغاء هذا الأمر؟');
  String get cancelBeforeMessage => _t(
    'No supplier message was prepared: the supplier does not know about this order. The products are released for new orders.',
    'لم تُجهّز رسالة للمورد: المورد لا يعرف بهذا الأمر. تتحرر المنتجات لأوامر جديدة.',
  );
  String get wasItSent => _t(
    'Did you send this message to the supplier?',
    'هل أرسلت هذه الرسالة إلى المورد؟',
  );
  String get wasItSentHelp => _t(
    'The message was prepared and may have been copied or sent by hand. ClixSell cannot know. Choose what is true:',
    'الرسالة جُهّزت وربما نُسخت أو أُرسلت يدوياً. ClixSell لا يستطيع أن يعرف. اختر ما هو صحيح:',
  );
  String get optNotSent =>
      _t('No — I am sure it was NOT sent', 'لا — أنا متأكد أنها لم تُرسل');
  String get optUnknown => _t('I do not know', 'لا أعرف');
  String get optSent => _t('Yes — I sent it', 'نعم — أرسلتها');
  String get confirmNotSentLabel => _t(
    'I confirm this message was not sent to the supplier',
    'أؤكد أن هذه الرسالة لم تُرسل إلى المورد',
  );
  String priorActions(int copies, int opens) => _t(
    'Recorded before: message copied ${iso('$copies')} time(s), WhatsApp opening requested ${iso('$opens')} time(s). The products will show a "declared not sent" warning for 30 days.',
    'سُجّل سابقاً: نسخ الرسالة ${iso('$copies')} مرة، وطلب فتح WhatsApp ${iso('$opens')} مرة. ستظهر المنتجات بتحذير "صُرّح بعدم الإرسال" لمدة 30 يوماً.',
  );
  String get unknownExplain => _t(
    'The order is canceled WITHOUT recording any sending. Because the supplier may have the message, its products show a "contact unknown" warning for 30 days when reordered. If you later reach the supplier, record their acceptance of the cancellation.',
    'يُلغى الأمر بدون تسجيل أي إرسال. ولأن المورد ربما وصلته الرسالة، تظهر منتجاته بتحذير "التواصل غير معروف" لمدة 30 يوماً عند إعادة الطلب. إذا تواصلت مع المورد لاحقاً، سجّل قبوله للإلغاء.',
  );
  String get sentExplain => _t(
    'Then record the sending first ("I Have Sent This Message"). After that you can cancel the order as canceled after supplier contact.',
    'إذن سجّل الإرسال أولاً ("لقد أرسلت هذه الرسالة"). بعدها يمكنك إلغاء الأمر كإلغاء بعد التواصل مع المورد.',
  );
  String get goRecordSending =>
      _t('Record the sending first', 'سجّل الإرسال أولاً');
  String get afterContactWarning => _t(
    'The supplier was contacted. Cancelling in ClixSell does NOT notify the supplier: tell them yourself.',
    'تم التواصل مع المورد. الإلغاء في ClixSell لا يُبلغ المورد: أبلغه بنفسك.',
  );
  String get ackNotNotifiedLabel => _t(
    'I understand ClixSell will not notify the supplier',
    'أفهم أن ClixSell لن يُبلغ المورد',
  );
  String get confirmCancel => _t('Cancel the order', 'إلغاء الأمر');
  String get keepOrder => _t('Keep the order', 'إبقاء الأمر');

  String get previewTitle =>
      _t('Prepare the supplier message', 'تجهيز رسالة المورد');
  String get previewOnly => _t(
    'Preview only: nothing is saved or sent. ClixSell never sends messages itself.',
    'معاينة فقط: لا يُحفظ أو يُرسل أي شيء. ClixSell لا يرسل الرسائل بنفسه.',
  );
  String get languageLabel => _t('Message language', 'لغة الرسالة');
  String get arabic => _t('Arabic', 'العربية');
  String get english => _t('English', 'الإنجليزية');
  String get toNumber => _t('To WhatsApp number', 'إلى رقم WhatsApp');
  String lengthOf(int len, int limit) => _t(
    '${iso('$len')} / ${iso('$limit')} characters',
    '${iso('$len')} / ${iso('$limit')} حرف',
  );
  String get prepareAndSave =>
      _t('Prepare and save the message', 'تجهيز الرسالة وحفظها');
  String get previewChanged => _t(
    'Something changed since your preview (the supplier number, a name or the date). Review the new message before saving.',
    'تغيّر شيء منذ المعاينة (رقم المورد أو اسم أو التاريخ). راجع الرسالة الجديدة قبل الحفظ.',
  );
  String get prepared => _t(
    'The message is ready and was NOT sent. Next: open WhatsApp or copy the message, send it yourself, then press "I Have Sent This Message".',
    'الرسالة جاهزة ولم تُرسل. التالي: افتح WhatsApp أو انسخ الرسالة، وأرسلها بنفسك، ثم اضغط "لقد أرسلت هذه الرسالة".',
  );
  String get goSuppliers => _t('Open Suppliers', 'فتح صفحة الموردين');

  String get openTitle => _t('Open WhatsApp', 'فتح WhatsApp');
  String get openStep1 => _t(
    'Check the number and the full message. Opening WhatsApp does not send anything and does not record sending.',
    'راجع الرقم والرسالة كاملة. فتح WhatsApp لا يرسل شيئاً ولا يسجّل الإرسال.',
  );
  String get reviewedLabel =>
      _t('I checked the number and the message', 'راجعت الرقم والرسالة');
  String get changedReviewedLabel => _t(
    'I understand this message goes to the SAVED number above, not the supplier\'s new number',
    'أفهم أن هذه الرسالة ستذهب إلى الرقم المحفوظ أعلاه، وليس رقم المورد الجديد',
  );
  String get confirmOpen => _t('Confirm', 'تأكيد');
  String get openNow => _t('Open WhatsApp Now', 'افتح WhatsApp الآن');
  String get openReady => _t(
    'Ready. Press "Open WhatsApp Now" to open the link in a new window.',
    'جاهز. اضغط "افتح WhatsApp الآن" لفتح الرابط في نافذة جديدة.',
  );
  String get longLink => _t(
    'This message is long: some devices may not open such a long link. If it does not open, copy the message instead.',
    'هذه الرسالة طويلة: بعض الأجهزة قد لا تفتح رابطاً بهذا الطول. إذا لم يُفتح، انسخ الرسالة بدلاً من ذلك.',
  );
  String get opened => _t(
    'The link was handed to the browser / system. Nothing was recorded as sent. After you send the message in WhatsApp, press "I Have Sent This Message".',
    'سُلّم الرابط للمتصفح/النظام. لم يُسجّل أي إرسال. بعد أن ترسل الرسالة في WhatsApp، اضغط "لقد أرسلت هذه الرسالة".',
  );
  String notOpened(String number) => _t(
    'The link did not open. Copy the message and send it yourself to ${iso(number)}.',
    'لم يُفتح الرابط. انسخ الرسالة وأرسلها بنفسك إلى ${iso(number)}.',
  );
  String get resultNotSaved => _t(
    'The opening result could not be saved in the history (it does not affect the order).',
    'تعذّر حفظ نتيجة الفتح في السجل (لا يؤثر على الأمر).',
  );
  String againWarning(String? sentAt, int opens) => _t(
    'This order was already recorded as sent${sentAt == null ? '' : ' on ${iso(sentAt)}'} and WhatsApp opening was requested ${iso('$opens')} time(s). Opening it again may send the same order to the supplier twice.',
    'هذا الأمر مسجّل كمُرسل${sentAt == null ? '' : ' في ${iso(sentAt)}'}، وطُلب فتح WhatsApp ${iso('$opens')} مرة. فتحه مرة أخرى قد يرسل نفس الطلب للمورد مرتين.',
  );
  String get againAck => _t(
    'I want to contact the supplier again about this order (follow-up), not send a new order',
    'أريد التواصل مع المورد مجدداً بخصوص هذا الأمر (متابعة)، وليس إرسال طلب جديد',
  );

  String get copied =>
      _t('Message copied to the clipboard.', 'نُسخت الرسالة إلى الحافظة.');
  String get copyFailed => _t(
    'Copying automatically was not allowed. Select the message text and copy it with Ctrl+C.',
    'لم يُسمح بالنسخ التلقائي. حدّد نص الرسالة وانسخه بـ Ctrl+C.',
  );
  String get copyNotLogged => _t(
    'Copied, but the history could not be updated yet. Retrying safely…',
    'نُسخت، لكن تعذّر تحديث السجل بعد. نعيد المحاولة بأمان…',
  );

  String get sentTitle =>
      _t('Record that you sent the message', 'تسجيل أنك أرسلت الرسالة');
  String sentCheck(String number) => _t(
    'I sent this message myself to ${iso(number)} (WhatsApp or another way)',
    'أرسلت هذه الرسالة بنفسي إلى ${iso(number)} (عبر WhatsApp أو طريقة أخرى)',
  );
  String get sentNote => _t(
    'This is a manual record. ClixSell does not know whether the message was delivered or read.',
    'هذا تسجيل يدوي. ClixSell لا يعرف إن وصلت الرسالة أو قُرئت.',
  );
  String get recordSending => _t('Record sending', 'تسجيل الإرسال');

  String get confirmTitle =>
      _t('Record supplier confirmation', 'تسجيل تأكيد المورد');
  String get confirmCheck =>
      _t('The supplier confirmed this order', 'المورد أكّد هذا الطلب');
  String get confirmNoteLabel => _t(
    'Note (optional), e.g. "phone call"',
    'ملاحظة (اختيارية)، مثل "اتصال هاتفي"',
  );
  String get confirmNoteError =>
      _t('At most 300 characters', '300 حرف كحد أقصى');
  String get recordConfirmation => _t('Record confirmation', 'تسجيل التأكيد');

  String get closeTitle => _t('Close this order?', 'إغلاق هذا الأمر؟');
  String get closeCheck => _t(
    'I understand closing does not record goods receiving and stock will not change',
    'أفهم أن الإغلاق لا يسجّل استلام البضاعة وأن المخزون لن يتغير',
  );
  String get closeBody => _t(
    'Closing ends the supplier communication. Its products show a "recently ordered" warning for 14 days.',
    'الإغلاق ينهي التواصل مع المورد. تظهر منتجاته بتحذير "طُلب مؤخراً" لمدة 14 يوماً.',
  );
  String get closeOrder => _t('Close order', 'إغلاق الأمر');

  String get supplierCancelTitle => _t(
    'Record supplier acceptance of the cancellation',
    'تسجيل قبول المورد للإلغاء',
  );
  String get supplierCancelCheck => _t(
    'The supplier told me they accept the cancellation',
    'أخبرني المورد أنه يقبل الإلغاء',
  );
  String get channelLabel =>
      _t('How did you reach the supplier?', 'كيف تواصلت مع المورد؟');
  String channel(String c) => switch (c) {
    'PHONE' => _t('Phone', 'هاتف'),
    'WHATSAPP' => _t('WhatsApp', 'WhatsApp'),
    'IN_PERSON' => _t('In person', 'شخصياً'),
    _ => _t('Other', 'أخرى'),
  };
  String get supplierCancelNote =>
      _t('What did the supplier say? (required)', 'ماذا قال المورد؟ (إلزامي)');
  String get supplierCancelWarning => _t(
    'This is a manual record that cannot be undone. It ends the reorder warning of this order only.',
    'هذا تسجيل يدوي لا يمكن التراجع عنه. يُنهي تحذير إعادة الطلب لهذا الأمر فقط.',
  );
  String get recordAcceptance => _t('Record acceptance', 'تسجيل القبول');

  // ── history ────────────────────────────────────────────────────────────────
  String event(PurchaseOrderEvent e) {
    final phase = e.details['phase'];
    return switch (e.type) {
      'CREATED' => _t('Order created', 'أُنشئ الأمر'),
      'EDITED' => _t('Order edited', 'عُدّل الأمر'),
      'APPROVED' => _t('Approved', 'تمت الموافقة'),
      'REJECTED' => _t('Rejected', 'رُفض'),
      'CANCELED' => _t('Canceled', 'أُلغي'),
      'MESSAGE_GENERATED' => _t(
        'Supplier message prepared (not sent)',
        'جُهّزت رسالة المورد (لم تُرسل)',
      ),
      'MESSAGE_COPIED' => _t(
        'Message copied (reported by the app)',
        'نُسخت الرسالة (حسب التطبيق)',
      ),
      'WHATSAPP_OPEN_REQUESTED' =>
        phase == 'CLIENT_REPORTED'
            ? (e.details['result'] == 'OPENED'
                  ? _t(
                      'Link handed to the browser (reported by the app)',
                      'سُلّم الرابط للمتصفح (حسب التطبيق)',
                    )
                  : _t(
                      'Link did not open (reported by the app)',
                      'لم يُفتح الرابط (حسب التطبيق)',
                    ))
            : _t(
                'WhatsApp opening requested (confirmed by the manager)',
                'طُلب فتح WhatsApp (بتأكيد المدير)',
              ),
      'MARKED_SENT' => _t(
        'Manager recorded that they sent the message',
        'سجّل المدير أنه أرسل الرسالة',
      ),
      'SUPPLIER_CONFIRMATION_RECORDED' => _t(
        'Manager recorded the supplier\'s confirmation',
        'سجّل المدير تأكيد المورد',
      ),
      'CLOSED' => _t(
        'Closed (no receiving recorded)',
        'أُغلق (بدون تسجيل استلام)',
      ),
      'SUPPLIER_CANCELLATION_RECORDED' => _t(
        'Manager recorded the supplier accepted the cancellation',
        'سجّل المدير قبول المورد للإلغاء',
      ),
      _ => e.type,
    };
  }

  String eventDetail(PurchaseOrderEvent e) {
    final d = e.details;
    final parts = <String>[];
    if (d['reason'] is String) {
      parts.add(_t('Reason: ', 'السبب: ') + iso(d['reason'] as String));
    }
    if (d['note'] is String) {
      parts.add(_t('Note: ', 'ملاحظة: ') + iso(d['note'] as String));
    }
    if (d['channel'] is String) {
      parts.add(_t('Channel: ', 'القناة: ') + channel(d['channel'] as String));
    }
    if (d['contactStatus'] is String) {
      parts.add(contactAtCancel(d['contactStatus'] as String));
    }
    if (d['language'] is String) {
      parts.add(d['language'] == 'ar' ? arabic : english);
    }
    if (d['whatsappNumber'] is String) {
      parts.add(iso('+${d['whatsappNumber']}'));
    }
    final items = d['items'];
    if (items is List && items.isNotEmpty) {
      parts.add(
        _t('Lines: ', 'البنود: ') +
            items
                .map(
                  (i) =>
                      '${iso('${(i as Map)['productName']}')} × ${iso('${i['quantity']}')}',
                )
                .join('، '),
      );
    }
    if (d['verified'] == false &&
        (e.type == 'MESSAGE_COPIED' || e.type == 'WHATSAPP_OPEN_REQUESTED')) {
      parts.add(_t('not verified', 'غير مُتحقق منه'));
    }
    if (d['recordedManually'] == true) {
      parts.add(_t('manual record', 'تسجيل يدوي'));
    }
    return parts.join(' · ');
  }

  String get noHistory => _t('No history yet', 'لا يوجد سجل بعد');

  // ── errors ─────────────────────────────────────────────────────────────────
  /// A clear sentence for a backend error code (the technical text is shown
  /// separately under "details").
  String error(ApiException e) {
    if (e.isNetworkError) {
      return _t(
        'The server could not be reached. If you were saving something, we check whether it was saved before you retry.',
        'تعذّر الوصول للخادم. إذا كنت تحفظ شيئاً، نتحقق إن كان قد حُفظ قبل إعادة المحاولة.',
      );
    }
    return switch (e.code) {
      'ORDER_CHANGED' || 'INVALID_TRANSITION' => _t(
        'Another manager changed this order meanwhile. It was reloaded: review it and choose again.',
        'غيّر مدير آخر هذا الأمر للتو. أُعيد تحميله: راجعه واختر من جديد.',
      ),
      'DATABASE_BUSY' => _t(
        'The database was busy and the change could not be confirmed. We check the saved state before you retry.',
        'كانت قاعدة البيانات مشغولة ولم يُتأكد من التغيير. نتحقق من الحالة المحفوظة قبل إعادة المحاولة.',
      ),
      'REORDER_CAUTION_ACK_REQUIRED' => cautionsBody,
      'PRODUCT_IN_OPEN_ORDER' => _t(
        'A product is already in another open purchase order.',
        'أحد المنتجات موجود في أمر شراء مفتوح آخر.',
      ),
      'PREVIEW_CHANGED' => previewChanged,
      'MESSAGE_TOO_LONG' => _t(
        'The supplier message would be longer than WhatsApp allows for one order. Nothing was changed.',
        'رسالة المورد ستكون أطول من المسموح لأمر واحد. لم يتغير شيء.',
      ),
      'SUPPLIER_NO_WHATSAPP' => _t(
        'This supplier has no WhatsApp number. Add it in Suppliers, then try again.',
        'هذا المورد ليس لديه رقم WhatsApp. أضفه من صفحة الموردين ثم أعد المحاولة.',
      ),
      'SUPPLIER_INACTIVE' => _t(
        'The supplier is inactive. Activate it in Suppliers first.',
        'المورد موقوف. فعّله من صفحة الموردين أولاً.',
      ),
      'CLIENT_ACTION_CONFLICT' => _t(
        'This action was already recorded with a different result; the first record is kept.',
        'هذا الإجراء سُجّل سابقاً بنتيجة مختلفة؛ يبقى التسجيل الأول.',
      ),
      'ORDER_FINAL' => readOnlyFinal,
      'CONTACT_STATUS_REQUIRED' => wasItSent,
      'RECORD_SENT_FIRST' => sentExplain,
      'ACK_SUPPLIER_NOT_NOTIFIED' => afterContactWarning,
      'ALREADY_RECORDED' => _t(
        'This was already recorded.',
        'هذا مسجّل مسبقاً.',
      ),
      'NOT_ELIGIBLE' => _t(
        'Not possible for this order.',
        'غير ممكن لهذا الأمر.',
      ),
      'GUARDIAN_DISABLED' => guardianOffBody,
      'NOT_A_CURRENT_RECOMMENDATION' => _t(
        'A product is no longer a current suggestion. Reload the suggestions or create a manual order.',
        'أحد المنتجات لم يعد ضمن الاقتراحات الحالية. أعد تحميل الاقتراحات أو أنشئ أمراً يدوياً.',
      ),
      'PURCHASE_ORDER_NOT_FOUND' => _t(
        'This order was not found.',
        'لم يُعثر على هذا الأمر.',
      ),
      'IDEMPOTENCY_KEY_REUSED' => _t(
        'This save request was already used for a different order. Start a new order.',
        'طلب الحفظ هذا استُعمل لأمر مختلف. ابدأ أمراً جديداً.',
      ),
      _ when e.isForbidden => _t(
        'Only the Store Admin can do this.',
        'هذا للمدير فقط.',
      ),
      _ when e.isNotFound => _t('Not found.', 'غير موجود.'),
      _ when e.statusCode == 400 => _t(
        'Some values are not valid.',
        'بعض القيم غير صالحة.',
      ),
      _ => _t('The request failed.', 'فشل الطلب.'),
    };
  }

  String get technicalDetails => _t('Details', 'تفاصيل');
  String get savedAfterCheck => _t(
    'Checked: the change had been saved.',
    'تم التحقق: التغيير كان قد حُفظ.',
  );
  String get notSavedAfterCheck => _t(
    'Checked: the change is not saved at this moment. If the server was busy it may still finish — press Refresh before trying again.',
    'تم التحقق: التغيير غير محفوظ في هذه اللحظة. إذا كان الخادم مشغولاً فقد يكتمل لاحقاً — اضغط تحديث قبل إعادة المحاولة.',
  );
}

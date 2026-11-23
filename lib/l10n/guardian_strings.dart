import 'package:flutter/widgets.dart';

import '../models/guardian.dart';
import '../models/product_category.dart';
import '../utils/formatters.dart';

/// Arabic / English text of the Guardian Mode screen (report Figure 3.1).
///
/// Every alert sentence is built here from the CODE and the NUMBERS the
/// backend sends (never from ready-made server text), so each explanation
/// shows the real values and the limit they were compared with.
class GuardianStrings {
  const GuardianStrings._(this.isArabic);

  final bool isArabic;

  static GuardianStrings of(BuildContext context) =>
      GuardianStrings._(Localizations.localeOf(context).languageCode == 'ar');

  String _t(String en, String ar) => isArabic ? ar : en;

  /// Keeps a product name or invoice number in its own direction inside
  /// Arabic text (Unicode first-strong isolate).
  String _iso(Object? v) => isArabic ? '\u2068${v ?? ''}\u2069' : '${v ?? ''}';

  /// English count with the right singular / plural: "1 product", "3 products".
  static String en(Object? n, String one, String many) {
    final v = (n as num?) ?? 0;
    return '${num_(v)} ${v == 1 ? one : many}';
  }

  /// Keeps a date (2026-11-23) left-to-right inside Arabic text.
  String _ltr(Object? v) => isArabic ? '\u2066${v ?? ''}\u2069' : '${v ?? ''}';

  static String money(Object? v) => Formatters.currency((v as num?) ?? 0);

  /// 7.5 → "7.5", 10.0 → "10".
  static String num_(Object? v) {
    final d = (v as num?)?.toDouble() ?? 0;
    return d == d.roundToDouble() ? d.toInt().toString() : d.toString();
  }

  static String date(DateTime d) =>
      '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  static String time(DateTime d) =>
      '${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';

  String category(String? key) {
    for (final c in ProductCategory.values) {
      if (c.name == key) return c.label(arabic: isArabic);
    }
    return key ?? '';
  }

  // ── Navigation / header ──────────────────────────────────────────────────
  String get navGuardian => _t('Guardian Mode', 'وضع الحارس');
  String get navSectionOverview => _t('OVERVIEW', 'نظرة عامة');
  String get title => _t('Smart Guardian Mode', 'وضع الحارس الذكي');
  String get subtitle => _t(
    'Protects your store from losses and suggests actions to take.',
    'يحمي متجرك من الخسائر ويقترح لك أفضل القرارات',
  );
  String get ruleBasedNote => _t(
    'Rule-based analysis of your store\'s real records (not a trained AI model).',
    'تحليل مبني على قواعد واضحة من سجلات متجرك الحقيقية (ليس نموذج ذكاء اصطناعي مدرّب).',
  );
  String get on => _t('On', 'مفعّل');
  String get off => _t('Off', 'متوقف');
  String get switchTooltip =>
      _t('Turn Guardian Mode on or off', 'تفعيل أو إيقاف وضع الحارس');
  String get refresh => _t('Refresh', 'تحديث');
  String lastChange(String who, DateTime when) => _t(
    'Last changed by $who on ${date(when)} ${time(when)}',
    'آخر تغيير: $who في ${_ltr('${date(when)} ${time(when)}')}',
  );
  String get pastPeriodNote => _t(
    'Past month: alerts and activity are for that month. Stock, prices, recommendations and '
        'alerts marked "Now" show the CURRENT state.',
    'شهر سابق: التنبيهات والنشاط لذلك الشهر. المخزون والأسعار والتوصيات والتنبيهات '
        'المكتوب عليها "حالياً" تعرض الوضع الحالي.',
  );
  String get now => _t('Now', 'حالياً');

  // ── Off state / activation ──────────────────────────────────────────────
  String get offTitle => _t('Guardian Mode is off', 'وضع الحارس متوقف');
  String get offBody => _t(
    'When it is on, Guardian Mode checks your store\'s real sales, returns, canceled '
        'invoices, prices, costs and stock with clear rules, and shows alerts, risks '
        'and suggestions. It never changes a price, stock or discount and never sends anything.',
    'عند التفعيل، يفحص وضع الحارس مبيعات متجرك ومرتجعاته وفواتيره الملغاة وأسعاره '
        'وتكاليفه ومخزونه الحقيقية بقواعد واضحة، ويعرض التنبيهات والمخاطر والتوصيات. '
        'لا يغيّر أي سعر أو مخزون أو خصم، ولا يرسل أي شيء.',
  );
  String trackedSince(DateTime d) => _t(
    'The activity log has been recording since ${date(d)} (also while Guardian Mode is off).',
    'سجل النشاط يعمل منذ ${_ltr(date(d))} (حتى عندما يكون وضع الحارس متوقفاً).',
  );
  String get enable => _t('Turn on Guardian Mode', 'تفعيل وضع الحارس');
  String get enableConfirmTitle =>
      _t('Turn on Guardian Mode?', 'تفعيل وضع الحارس؟');
  String get enableConfirmBody => _t(
    'Alerts, store health and suggestions will be calculated for this store.',
    'سيتم حساب التنبيهات وصحة المتجر والتوصيات لهذا المتجر.',
  );
  String get disableConfirmTitle =>
      _t('Turn off Guardian Mode?', 'إيقاف وضع الحارس؟');
  String get disableConfirmBody => _t(
    'Alerts and suggestions stop. No data is deleted, and the activity log keeps recording.',
    'ستتوقف التنبيهات والتوصيات. لا يتم حذف أي بيانات، ويستمر سجل النشاط في التسجيل.',
  );
  String get turnOn => _t('Turn on', 'تفعيل');
  String get turnOff => _t('Turn off', 'إيقاف');
  String get cancel => _t('Cancel', 'إلغاء');
  String get close => _t('Close', 'إغلاق');

  // ── Cards ───────────────────────────────────────────────────────────────
  String get todaySales => _t('Today\'s sales', 'مبيعات اليوم');
  String changeVsYesterday(double p) {
    final sign = p > 0 ? '+' : '';
    return _t(
      '$sign${num_(p)}% vs yesterday (same time)',
      '$sign${num_(p)}% عن أمس (لنفس الساعة)',
    );
  }

  String get noYesterday =>
      _t('No sales yesterday to compare', 'لا توجد مبيعات أمس للمقارنة');
  String get netSalesHint =>
      _t('Paid − refunds, like Sales Analytics', 'المدفوع − المرتجعات');
  String get critical => _t('Critical alerts', 'تنبيهات حرجة');

  /// Decision U8: alerts about the same invoice or product count once.
  String separateIssues(int n) =>
      _t(en(n, 'separate issue', 'separate issues'), 'حالات منفصلة: $n');
  String get profitRisks => _t('Profit risks', 'مخاطر الربح');

  /// G2c on the Profit Risks card: a separate line, never part of its number.
  String marginRisks(int n) => _t(
    '+ ${en(n, 'margin risk', 'margin risks')} after discount sharing, not included in this number · View',
    'مخاطر هامش بعد توزيع الخصم، غير محسوبة في هذا الرقم: $n · عرض',
  );

  /// The Profit Risks number is 0 but margin risks exist: never a plain "None".
  String get noneCountedSeeBelow => _t(
    'None counted here – see below',
    'لا شيء ضمن هذا الرقم – انظر بالأسفل',
  );
  String get needAttention => _t('Need attention', 'تحتاج انتباه');
  String get none => _t('None', 'لا توجد');
  String get viewAll => _t('View all', 'عرض الكل');
  String incompleteCost(int n) => _t(
    n == 1
        ? '1 invoice with a missing cost: its profit is not judged'
        : '$n invoices with a missing cost: their profit is not judged',
    'فواتير تكلفتها ناقصة (لا يُحكم على ربحها): $n',
  );
  String get health => _t('Store health index', 'مؤشر صحة المتجر');
  String get notEnoughData => _t('Not enough data', 'بيانات غير كافية');
  String partsUsed(int n) =>
      _t('Based on $n of 4 parts', 'مبني على $n من 4 أجزاء');
  String band(HealthBand? b) => switch (b) {
    HealthBand.good => _t('Good', 'جيد'),
    HealthBand.attention => _t('Needs follow-up', 'يحتاج متابعة'),
    HealthBand.risk => _t('Risk', 'خطر'),
    null => notEnoughData,
  };
  String get healthDetails => _t('How is it calculated?', 'كيف يُحسب؟');

  /// Decision U3 (P1): the number is a general measure, open critical alerts
  /// are named next to it.
  String healthCritical(int n) => _t(
    'The score measures general store health · '
        '${en(n, 'critical alert needs', 'critical alerts need')} review',
    'النسبة تقيس الوضع العام · تنبيهات حرجة تحتاج مراجعة: $n',
  );

  /// Decision U3 (P2).
  String get bandLimited => _t(
    'Shown as "Needs follow-up", not "Good", while critical alerts are open. '
        'The score itself is not changed.',
    'يظهر "يحتاج متابعة" وليس "جيد" ما دامت هناك تنبيهات حرجة مفتوحة. الرقم نفسه لا يتغير.',
  );

  // ── Health breakdown ────────────────────────────────────────────────────
  String get healthFormula => _t(
    'Score = points of the available parts ÷ (25 × number of available parts) × 100. '
        'A number is shown only when at least 3 of the 4 parts have enough data. '
        '80+ Good · 60–79 Needs follow-up · below 60 Risk. '
        'The score is a general measure of the period (ratios): it does not mean there are no risks. '
        'While a critical alert is open, it is never shown as Good.',
    'النتيجة = مجموع نقاط الأجزاء المتوفرة ÷ (25 × عددها) × 100. '
        'يظهر الرقم فقط إذا توفرت بيانات كافية لـ 3 أجزاء من 4 على الأقل. '
        '80 فأكثر جيد · 60–79 يحتاج متابعة · أقل من 60 خطر. '
        'النتيجة مقياس عام للفترة (نِسَب): لا تعني عدم وجود مخاطر. '
        'ما دام هناك تنبيه حرج مفتوح، لا تظهر أبداً كـ"جيد".',
  );
  String partName(String p) => switch (p) {
    'STOCK' => _t('Stock', 'المخزون'),
    'PROFIT' => _t('Profit', 'الربح'),
    'DISCOUNTS' => _t('Discounts', 'الخصومات'),
    _ => _t('Activity', 'النشاط'),
  };
  String partNumbers(HealthPart p) {
    final n = p.numbers;
    return switch (p.part) {
      'STOCK' => _t(
        '${n['healthyProducts']} of ${en(n['products'], 'product', 'products')} not low / out of stock',
        'منتجات غير منخفضة ولا نافدة: ${n['healthyProducts']} من أصل ${n['products']}',
      ),
      'PROFIT' => _t(
        '${en(n['confirmedLossInvoices'], 'confirmed loss', 'confirmed losses')} (at the time of sale) in '
            '${en(n['completeCostInvoices'], 'invoice', 'invoices')} with every cost recorded (of ${n['invoices']})',
        'خسائر مؤكدة وقت البيع: ${n['confirmedLossInvoices']} · فواتير تكلفتها كاملة: '
            '${n['completeCostInvoices']} (من أصل ${n['invoices']})',
      ),
      'DISCOUNTS' => _t(
        '${en(n['highDiscountInvoices'], 'high discount', 'high discounts')} in '
            '${en(n['invoices'], 'invoice', 'invoices')}',
        'خصومات عالية: ${n['highDiscountInvoices']} من أصل ${n['invoices']} فاتورة',
      ),
      _ => _t(
        '${en(n['daysWithAlert'], 'day', 'days')} with an activity alert of '
            '${en(n['activeDays'], 'active day', 'active days')}',
        'أيام فيها تنبيه نشاط: ${n['daysWithAlert']} من أصل الأيام النشطة: ${n['activeDays']}',
      ),
    };
  }

  String partReason(HealthPart p) {
    final n = p.numbers;
    return switch (p.reason) {
      'PAST_PERIOD' => _t(
        'Not available: past stock levels are not stored',
        'غير متوفر: المخزون التاريخي غير محفوظ',
      ),
      'NO_PRODUCTS' => _t(
        'Not available: no products',
        'غير متوفر: لا توجد منتجات',
      ),
      'NOT_ENOUGH_INVOICES' => _t(
        'Not available: fewer than ${n['minInvoices']} completed invoices',
        'غير متوفر: أقل من ${n['minInvoices']} فواتير مكتملة',
      ),
      'COST_COVERAGE_LOW' => _t(
        'Not available: only ${n['coveragePercent'] ?? 0}% of invoices have every cost '
            '(needs ${n['minCoveragePercent']}% and ${n['minInvoices']} invoices)',
        'غير متوفر: ${n['coveragePercent'] ?? 0}% فقط من الفواتير تكلفتها كاملة '
            '(المطلوب ${n['minCoveragePercent']}% و${n['minInvoices']} فواتير)',
      ),
      _ => '',
    };
  }

  String deletionsSince(String d) => _t(
    'Product deletions are recorded since $d',
    'حذف المنتجات مسجّل منذ ${_ltr(d)}',
  );

  // ── Smart alerts ────────────────────────────────────────────────────────
  String smartAlerts(int n) => _t('Smart alerts ($n)', 'تنبيهات ذكية ($n)');
  String get viewAllAlerts => _t('View all alerts', 'عرض كل التنبيهات');
  String get noAlerts =>
      _t('No alerts for this period.', 'لا توجد تنبيهات في هذه الفترة.');
  String get allAlertsTitle => _t('All alerts', 'كل التنبيهات');
  String get filterAll => _t('All', 'الكل');
  String severity(GuardianSeverity s) => switch (s) {
    GuardianSeverity.high => _t('Critical', 'حرج'),
    GuardianSeverity.medium => _t('Needs attention', 'يحتاج انتباه'),
    GuardianSeverity.low => _t('Info', 'معلومة'),
  };
  String get notRecorded => _t(
    'User not recorded (before the activity log started)',
    'المستخدم غير مسجّل (قبل بدء سجل النشاط)',
  );

  String alertTitle(GuardianAlert a) => switch (a.code) {
    'HIGH_DISCOUNT' => _t('High discount', 'خصم عالٍ'),
    'HIGH_DISCOUNT_REPEATED' => _t(
      'Repeated high discounts',
      'خصومات عالية متكررة',
    ),
    'BELOW_COST_SALE' => _t('Sold below cost', 'بيع بأقل من التكلفة'),
    'CONFIRMED_LOSS' => _t(
      'Confirmed loss at the time of sale',
      'خسارة مؤكدة وقت البيع',
    ),
    'DISCOUNT_SHARE_BELOW_COST' => _t(
      'Below cost after its share of the discount',
      'أقل من التكلفة بعد حصته من الخصم',
    ),
    'PRICE_BELOW_COST' => _t('Price below cost', 'سعر أقل من التكلفة'),
    'PRICE_EQUALS_COST' => _t('Price equals cost', 'سعر يساوي التكلفة'),
    'MAX_DISCOUNT_RISK' => _t('Profit protection', 'حماية الربح'),
    'OUT_OF_STOCK' => _t('Out of stock', 'منتجات نفدت'),
    'LOW_STOCK' => _t('Low stock products', 'منتجات منخفضة المخزون'),
    'STOCKOUT_SOON' => _t('Running out soon', 'نفاد قريب'),
    'CANCELED_INVOICES' => _t(
      'Canceled invoices above the limit',
      'إلغاء فواتير فوق الحد',
    ),
    'RETURNS_SPIKE' => _t('High returns', 'مرتجعات مرتفعة'),
    'PRODUCT_RETURNS_REPEATED' => _t(
      'Same product returned again',
      'مرتجعات متكررة لنفس المنتج',
    ),
    'PRODUCT_DELETIONS' => _t('Repeated product deletions', 'حذف منتجات متكرر'),
    _ => a.code,
  };

  String alertText(GuardianAlert a) {
    final p = a.params;
    String m(String k) => money(p[k]);
    String n(String k) => num_(p[k]);
    return switch (a.code) {
      'HIGH_DISCOUNT' => _t(
        '${n('percent')}% discount (${m('discount')}) on invoice ${_iso(p['invoiceNumber'])} '
            '(${m('subtotal')}). Limit: ${n('thresholdPercent')}% = 75% of the '
            '${p['maxSource'] == 'CURRENT' ? 'current ' : ''}maximum ${n('maxPercent')}%, '
            'and at least ${m('minAmount')}.',
        'خصم ${n('percent')}% (${m('discount')}) على الفاتورة ${_iso(p['invoiceNumber'])} '
            '(${m('subtotal')}). الحد: ${n('thresholdPercent')}% = 75% من أعلى خصم '
            '${n('maxPercent')}%${p['maxSource'] == 'CURRENT' ? ' (الحد الحالي للمتجر)' : ''}، '
            'و${m('minAmount')} على الأقل.',
      ),
      'HIGH_DISCOUNT_REPEATED' => _t(
        '${en(p['count'], 'invoice', 'invoices')} with a high discount by the same user in one day '
            '(limit: ${p['repeatLimit']}). Total discount ${m('totalDiscount')}.',
        'فواتير بخصم عالٍ من نفس المستخدم في يوم واحد: ${p['count']} '
            '(الحد: ${p['repeatLimit']}). مجموع الخصم ${m('totalDiscount')}.',
      ),
      'BELOW_COST_SALE' => _t(
        '${_iso(p['productName'])} sold ${en(p['times'], 'time', 'times')} '
            '(${en(p['units'], 'unit', 'units')}) at ${m('unitPrice')}; its cost at sale time was '
            '${m('unitCost')}. Loss when it was sold, before the invoice discount: '
            '${m('lossBeforeDiscount')}.${returnedNote(p['unitsReturned'], null)}',
        '${_iso(p['productName'])} بيع بسعر ${m('unitPrice')} وتكلفته وقت البيع '
            '${m('unitCost')} (عدد المرات: ${p['times']}، القطع: ${p['units']}). '
            'الخسارة وقت البيع قبل خصم الفاتورة: ${m('lossBeforeDiscount')}.${returnedNote(p['unitsReturned'], null)}',
      ),
      'CONFIRMED_LOSS' => _t(
        'Invoice ${_iso(p['invoiceNumber'])} when it was sold: net ${m('netRevenue')} '
            '(after a ${m('discount')} discount, tax excluded), cost ${m('cost')} → loss '
            '${m('loss')}. Every product on it has a recorded cost.${returnedNote(p['unitsReturned'], p['unitsSold'])}',
        'الفاتورة ${_iso(p['invoiceNumber'])} وقت البيع: الصافي ${m('netRevenue')} '
            '(بعد خصم ${m('discount')}، بدون الضريبة) والتكلفة ${m('cost')} ← خسارة '
            '${m('loss')}. كل منتجاتها لها تكلفة مسجلة.${returnedNote(p['unitsReturned'], p['unitsSold'])}',
      ),
      'DISCOUNT_SHARE_BELOW_COST' => _t(
        '${_iso(p['productName'])}: after its share of the invoice discount (${n('discountPercent')}%), '
            'a unit brought ${m('allocatedUnitRevenue')}, below its sale-time cost ${m('unitCost')} '
            '(price ${m('unitPrice')}; ${en(p['times'], 'time', 'times')}, ${en(p['units'], 'unit', 'units')}; '
            'short by ${m('shortfall')}). Allocation-based product margin risk, NOT a confirmed invoice '
            'loss.${saleTimeNote(p['unitsReturned'])}',
        '${_iso(p['productName'])}: بعد حصته من خصم الفاتورة (${n('discountPercent')}%) صار نصيب القطعة '
            '${m('allocatedUnitRevenue')}، أقل من تكلفتها وقت البيع ${m('unitCost')} (السعر ${m('unitPrice')}؛ '
            'عدد المرات: ${p['times']}، القطع: ${p['units']}؛ الفرق: ${m('shortfall')}). '
            'خطر على هامش المنتج حسب توزيع الخصم، وليس خسارة فاتورة مؤكدة.${saleTimeNote(p['unitsReturned'])}',
      ),
      'PRICE_BELOW_COST' => _t(
        '${_iso(p['productName'])}: price ${m('price')}, cost ${m('cost')}. Every sale loses money.',
        '${_iso(p['productName'])}: السعر ${m('price')} والتكلفة ${m('cost')}. كل بيع له بخسارة.',
      ),
      'PRICE_EQUALS_COST' => _t(
        '${_iso(p['productName'])}: price ${m('price')} equals its cost. No profit.',
        '${_iso(p['productName'])}: السعر ${m('price')} يساوي التكلفة. بدون ربح.',
      ),
      'MAX_DISCOUNT_RISK' => maxDiscountText(
        p['productName'] as String? ?? '',
        p['maxPercent'],
        p['priceAfterMax'],
        p['cost'],
        p['safePercent'],
      ),
      'OUT_OF_STOCK' => _t(
        p['count'] == 1
            ? '1 product is out of stock.'
            : '${p['count']} products are out of stock.',
        'منتجات نفدت من المخزون: ${p['count']}.',
      ),
      'LOW_STOCK' => _t(
        '${en(p['count'], 'product', 'products')} reached the reorder level.',
        'منتجات وصلت حد إعادة الطلب: ${p['count']}.',
      ),
      'STOCKOUT_SOON' => _t(
        '${en(p['count'], 'product', 'products')} will run out within ${p['days']} days at '
            '${p['count'] == 1 ? 'its' : 'their'} sales rate of the last ${p['windowDays']} days.',
        'منتجات ستنفد خلال ${p['days']} أيام حسب معدل بيعها في آخر '
            '${p['windowDays']} يوماً: ${p['count']}.',
      ),
      'CANCELED_INVOICES' => _t(
        '${p['count']} suspended invoices canceled on ${_ltr(p['day'])} of '
            '${(p['count'] as num) + (p['completed'] as num)} (${n('percent')}%). '
            'Limit: ${p['minCount']} and ${p['minPercent']}%.',
        'فواتير معلّقة أُلغيت في ${_ltr(p['day'])}: ${p['count']} من أصل '
            '${(p['count'] as num) + (p['completed'] as num)} (${n('percent')}%). '
            'الحد: ${p['minCount']} و${p['minPercent']}%.',
      ),
      'RETURNS_SPIKE' =>
        p['percent'] == null
            ? _t(
                'Refunds of ${m('refunds')} on ${_ltr(p['day'])}, a day without sales '
                    '(limit ${m('minAmount')}).',
                'مرتجعات ${m('refunds')} في ${_ltr(p['day'])}، يوم بلا مبيعات '
                    '(الحد ${m('minAmount')}).',
              )
            : _t(
                'Refunds on ${_ltr(p['day'])}: ${m('refunds')} = ${n('percent')}% of that day\'s '
                    'sales ${m('sales')} (limit: ${m('minAmount')} and ${p['mediumPercent']}%).',
                'مرتجعات ${_ltr(p['day'])}: ${m('refunds')} = ${n('percent')}% من مبيعات اليوم '
                    '${m('sales')} (الحد: ${m('minAmount')} و${p['mediumPercent']}%).',
              ),
      'PRODUCT_RETURNS_REPEATED' => _t(
        '${_iso(p['productName'])} was returned ${p['count']} times within ${p['days']} days. '
            'There may be a problem with the product.',
        'عدد مرات إرجاع ${_iso(p['productName'])} خلال ${p['days']} أيام: ${p['count']}. '
            'قد تكون هناك مشكلة في المنتج.',
      ),
      'PRODUCT_DELETIONS' => _t(
        '${p['count']} products deleted on ${_ltr(p['day'])} (limit: ${p['mediumLimit']}).',
        'منتجات حُذفت في ${_ltr(p['day'])}: ${p['count']} (الحد: ${p['mediumLimit']}).',
      ),
      _ => '',
    };
  }

  String maxDiscountText(
    String name,
    Object? max,
    Object? after,
    Object? cost,
    Object? safe,
  ) {
    final who = name.isEmpty ? '' : '${_iso(name)}: ';
    return _t(
      '${who}with the maximum allowed discount (${num_(max)}%) its price becomes '
          '${money(after)}, below its cost ${money(cost)}. Safe discount: up to ${num_(safe)}%.',
      '$whoبأعلى خصم مسموح (${num_(max)}%) يصبح سعره ${money(after)} وهو أقل '
          'من تكلفته ${money(cost)}. أعلى خصم آمن: ${num_(safe)}%.',
    );
  }

  /// " Later returned: …" – the alert is a SALE-TIME fact; returns never undo
  /// the judgement, so this says so instead of hiding it.
  String returnedNote(Object? returned, Object? sold) {
    final r = (returned as num?)?.toInt() ?? 0;
    if (r <= 0) return '';
    final en_ = sold == null
        ? en(r, 'unit', 'units')
        : '$r of ${en(sold, 'unit', 'units')}';
    final ar = sold == null ? '$r' : '$r من أصل $sold';
    return _t(
      ' Later returned: $en_ – this is the loss when it was sold, not the result after the return.',
      ' قطع أُرجعت لاحقاً: $ar. هذه خسارة وقت البيع، وليست النتيجة بعد الإرجاع.',
    );
  }

  /// G2c: " Later returned: …" – the numbers are from the time of sale.
  String saleTimeNote(Object? returned) {
    final r = (returned as num?)?.toInt() ?? 0;
    if (r <= 0) return '';
    return _t(
      ' Later returned: ${en(r, 'unit', 'units')} – these are the numbers of the time of sale.',
      ' قطع أُرجعت لاحقاً: $r. هذه أرقام وقت البيع.',
    );
  }

  /// Short name of a rule, for "also reported as …" (decision U8).
  String ruleName(String rule) => switch (rule) {
    'G1' => _t('High discount', 'خصم عالٍ'),
    'G2a' => _t('Sold below cost', 'بيع بأقل من التكلفة'),
    'G2b' => _t('Confirmed loss', 'خسارة مؤكدة'),
    'G2c' => _t(
      'Below cost after the discount share',
      'أقل من التكلفة بعد حصته من الخصم',
    ),
    'G3' => _t('Price at or below cost', 'سعر عند التكلفة أو أقل'),
    'G4' => _t('Profit protection', 'حماية الربح'),
    'G8b' => _t('Same product returned again', 'مرتجعات متكررة لنفس المنتج'),
    _ => rule,
  };

  /// Decision U8: other alerts about the same invoice or product, or null.
  String? relatedLine(GuardianAlert a) {
    final rules = a.relatedRules;
    if (rules.isEmpty) return null;
    final names = [for (final r in rules) ruleName(r)];
    return _t(
      'Also reported as: ${names.join(', ')} (same invoice or product – one issue, not separate problems).',
      'مُبلَّغ عنها أيضاً في: ${names.join('، ')} (نفس الفاتورة أو المنتج – حالة واحدة وليست مشاكل منفصلة).',
    );
  }

  /// STORE_ADMIN / CASHIER as recorded by the backend (decision U2), or null.
  String? role(Object? r) => switch (r) {
    'STORE_ADMIN' => _t('Store Admin', 'مدير المتجر'),
    'CASHIER' => _t('Cashier', 'كاشير'),
    _ => null,
  };

  /// "Name (Role)" – the role only when it was recorded.
  String _who(String name, Object? r) {
    final label = role(r);
    return label == null ? name : '$name ($label)';
  }

  /// Who did it, from the backend's records only. The role is shown, so an
  /// action of a Store Admin is never read as a cashier's (decision U2).
  String? actorLine(GuardianAlert a) {
    final p = a.params;
    final byUser = p['byUser'] as List<dynamic>?;
    if (byUser != null) {
      final parts = [
        for (final u in byUser.cast<Map<String, dynamic>>())
          role(u['role']) == null
              ? '${u['name']} (${u['count']})'
              : '${u['name']} – ${role(u['role'])} (${u['count']})',
      ];
      final missing = (p['notRecorded'] as num?)?.toInt() ?? 0;
      // Nobody recorded at all (before the activity log): say so plainly.
      if (parts.isEmpty) return missing > 0 ? notRecorded : null;
      if (missing > 0) {
        parts.add(_t('$missing not recorded', 'غير مسجّل: $missing'));
      }
      return _t('By: ${parts.join(', ')}', 'بواسطة: ${parts.join('، ')}');
    }
    if (a.actorName != null) {
      final actor = _who(a.actorName!, a.actorRole);
      final started = p['startedBy'] as String?;
      final startedBy = started == null
          ? null
          : _who(started, p['startedByRole']);
      return switch (a.code) {
        'HIGH_DISCOUNT' || 'CONFIRMED_LOSS' =>
          _t('Completed by: $actor', 'أكملها: $actor') +
              (startedBy == null
                  ? ''
                  : _t(' · started by: $startedBy', ' · بدأها: $startedBy')),
        _ => _t('By: $actor', 'بواسطة: $actor'),
      };
    }
    if (a.actorNote == 'NOT_RECORDED') return notRecorded;
    return null;
  }

  String actionLabel(GuardianAction a) => switch (a.type) {
    'INVOICE' => _t('View invoice', 'عرض الفاتورة'),
    'PRODUCT' => _t('View product', 'عرض المنتج'),
    'STOCK_ALERTS' => _t('View products', 'عرض المنتجات'),
    _ => _t('View activity report', 'عرض تقرير النشاط'),
  };

  String get productGone =>
      _t('This product no longer exists.', 'هذا المنتج لم يعد موجوداً.');

  // ── Recommendations ─────────────────────────────────────────────────────
  String get recommendations => _t('Smart recommendations', 'التوصيات الذكية');
  String get recommendationsNote => _t(
    'Rule-based suggestions with their reason; nothing is changed automatically.',
    'اقتراحات مبنية على قواعد مع سببها؛ لا يتم تغيير أي شيء تلقائياً.',
  );
  String recTitle(String code) => switch (code) {
    'REORDER_SOON' => _t(
      'Order stock before it runs out',
      'اطلب مخزون قبل نفاده',
    ),
    'LOW_MARGIN' => _t('Increase profit', 'زيادة الربح'),
    _ => _t('Slow-moving products', 'منتجات بطيئة البيع'),
  };
  String recText(GuardianRecommendation r) => switch (r.code) {
    'REORDER_SOON' => _t(
      '${en(r.count, 'product', 'products')} will run out within 3 days at '
          '${r.count == 1 ? 'its' : 'their'} sales rate.',
      'منتجات ستنفد خلال 3 أيام حسب معدل بيعها: ${r.count}.',
    ),
    'LOW_MARGIN' => _t(
      r.count == 1
          ? '1 product earns less than 10% of its price. Review its price.'
          : '${r.count} products earn less than 10% of their price. Review their price.',
      'منتجات ربحها أقل من 10% من سعرها: ${r.count}. راجع السعر.',
    ),
    _ => _t(
      '${en(r.count, 'product', 'products')} not sold for 30 days with stock above the '
          'minimum. Review the quantity or an offer.',
      'منتجات لم يُبع منها شيء منذ 30 يوماً ومخزونها فوق الحد: ${r.count}. '
          'راجع الكمية أو فكّر بعرض.',
    ),
  };
  String get recNone => _t('No products right now.', 'لا توجد منتجات حالياً.');
  String get openStockAlerts => _t('Stock alerts', 'تنبيهات المخزون');
  String get showProducts => _t('Show products', 'عرض المنتجات');
  String recProductLine(String code, Map<String, dynamic> p) => switch (code) {
    'REORDER_SOON' => _t(
      '${p['quantity']} left · about ${en(p['daysLeft'], 'day', 'days')} · ${p['expectedStockout']}',
      'المتبقي ${p['quantity']} · حوالي ${num_(p['daysLeft'])} يوم · ${_ltr(p['expectedStockout'])}',
    ),
    'LOW_MARGIN' => _t(
      'Price ${money(p['price'])} · cost ${money(p['cost'])} · margin ${num_(p['marginPercent'])}%',
      'السعر ${money(p['price'])} · التكلفة ${money(p['cost'])} · الهامش ${num_(p['marginPercent'])}%',
    ),
    _ => _t(
      '${p['quantity']} in stock (minimum ${p['minStock']}) · added ${p['ageDays']} days ago',
      'الموجود ${p['quantity']} (الحد ${p['minStock']}) · عمر المنتج بالأيام: ${p['ageDays']}',
    ),
  };

  // ── Store map ───────────────────────────────────────────────────────────
  String get storeMap => _t('Live store map', 'خريطة المتجر الحية');
  String get storeMapNote => _t(
    'By product category (the store layout is not recorded). Categories: current stock · '
        'Cashier area: the chosen period\'s POS alerts (high discounts, canceled invoices, returns) '
        'by any user, not only cashiers.',
    'حسب تصنيف المنتجات (مخطط المتجر غير مسجّل). التصنيفات: المخزون الحالي · '
        'منطقة الكاشير: تنبيهات نقطة البيع للفترة المختارة (خصومات عالية، إلغاء فواتير، مرتجعات) '
        'من أي مستخدم، وليس الكاشير فقط.',
  );
  String stockHealth(int p) => _t('Stock health $p%', 'صحة المخزون $p%');
  String get cashierArea => _t('Cashier area', 'منطقة الكاشير');
  String get noProblems => _t('No problems', 'لا توجد مشاكل');
  String cashierAlerts(int high, int medium) => _t(
    '${en(high + medium, 'POS alert', 'POS alerts')} this period, by any user',
    'تنبيهات نقطة البيع هذه الفترة (من أي مستخدم): ${high + medium}',
  );
  String get legendGood => _t('Good', 'جيد');
  String get legendAttention => _t('Needs follow-up', 'يحتاج متابعة');
  String get legendRisk => _t('Risk', 'خطر');
  String get noProductsYet => _t('No products yet.', 'لا توجد منتجات بعد.');

  // ── Profit protection ───────────────────────────────────────────────────
  String get profitProtection => _t('Profit protection', 'حماية الربح');
  String profitItemText(ProfitProtectionItem i) => switch (i.status) {
    'PRICE_BELOW_COST' => _t(
      'Price ${money(i.price)} is below its cost ${money(i.cost)}: every sale loses money.',
      'السعر ${money(i.price)} أقل من التكلفة ${money(i.cost)}: كل بيع بخسارة.',
    ),
    'PRICE_EQUALS_COST' => _t(
      'Price ${money(i.price)} equals its cost: no profit.',
      'السعر ${money(i.price)} يساوي التكلفة: بدون ربح.',
    ),
    _ => maxDiscountText(
      '',
      i.maxDiscountPercent,
      i.priceAfterMaxDiscount,
      i.cost,
      i.safeDiscountPercent,
    ),
  };
  String costPrice(double c) => _t('Cost ${money(c)}', 'التكلفة ${money(c)}');
  String currentPrice(double c) =>
      _t('Price ${money(c)}', 'السعر الحالي ${money(c)}');
  String get noProfitRisks => _t(
    'No product would be sold below its recorded cost.',
    'لا يوجد منتج سيُباع بأقل من تكلفته المسجلة.',
  );
  String withoutCost(int n) => _t(
    n == 1
        ? '1 product has no recorded cost and is not checked.'
        : '$n products have no recorded cost and are not checked.',
    'منتجات بدون سعر تكلفة ولا يتم فحصها: $n.',
  );

  // ── At-risk products ────────────────────────────────────────────────────
  String get atRisk =>
      _t('Products most at risk', 'المنتجات الأكثر عرضة للخطر');
  String get colProduct => _t('Product', 'المنتج');
  String get colStock => _t('Current stock', 'المخزون الحالي');
  String get colStockout => _t('Expected stockout', 'تاريخ النفاد المتوقع');
  String get colRisk => _t('Risk', 'الخطر');
  String risk(GuardianSeverity s) => switch (s) {
    GuardianSeverity.high => _t('High', 'عالي'),
    GuardianSeverity.medium => _t('Medium', 'متوسط'),
    GuardianSeverity.low => _t('Low', 'منخفض'),
  };
  String get soldOut => _t('Out of stock', 'نفد');
  String get noForecast => _t('Not enough data', 'بيانات غير كافية');
  String get viewAllProducts => _t('View all products', 'عرض جميع المنتجات');
  String get noAtRisk =>
      _t('No products at risk.', 'لا توجد منتجات معرضة للخطر.');

  // ── Unusual activity ────────────────────────────────────────────────────
  String get unusual => _t('Unusual activity', 'نشاط غير طبيعي');
  String perDay(double v) => _t(
    v == 1 ? '1 case / day' : '${num_(v)} cases / day',
    'حالات يومياً: ${num_(v)}',
  );
  String get typeDiscounts => _t('High discounts', 'خصومات عالية');
  String get typeCancels => _t('Canceled invoices', 'إلغاء فواتير');
  String get typeReturns => _t('Returns', 'مرتجعات');
  String get typeDeletions => _t('Product deletions', 'حذف منتجات');
  String get unusualNote => _t(
    'Only cases that are part of an alert are counted.',
    'تُحسب فقط الحالات التي هي جزء من تنبيه.',
  );
  String get noUnusual => _t(
    'No unusual activity in this period.',
    'لا يوجد نشاط غير طبيعي في هذه الفترة.',
  );
  String get viewActivity => _t('View activity report', 'عرض تقرير النشاط');

  // ── Activity report ─────────────────────────────────────────────────────
  String get activityTitle => _t('Activity report', 'تقرير النشاط');
  String get allTypes => _t('All types', 'كل الأنواع');
  String eventLabel(String type) => switch (type) {
    'DISCOUNT_APPLIED' => _t('Discount on a sale', 'خصم على فاتورة'),
    'INVOICE_CANCELED' => _t(
      'Suspended invoice canceled',
      'إلغاء فاتورة معلّقة',
    ),
    'PRODUCT_DELETED' => _t('Product deleted', 'حذف منتج'),
    'PRICE_CHANGED' => _t('Price changed', 'تغيير السعر'),
    'COST_CHANGED' => _t('Cost changed', 'تغيير التكلفة'),
    'STOCK_ADJUSTED' => _t('Stock changed by hand', 'تعديل المخزون يدوياً'),
    'GUARDIAN_ENABLED' => _t('Guardian Mode turned on', 'تفعيل وضع الحارس'),
    'GUARDIAN_DISABLED' => _t('Guardian Mode turned off', 'إيقاف وضع الحارس'),
    _ => type,
  };
  static const eventTypes = [
    'DISCOUNT_APPLIED',
    'INVOICE_CANCELED',
    'PRODUCT_DELETED',
    'PRICE_CHANGED',
    'COST_CHANGED',
    'STOCK_ADJUSTED',
    'GUARDIAN_ENABLED',
    'GUARDIAN_DISABLED',
  ];
  String eventDetails(ActivityEntry e) {
    final d = e.details;
    String v(Object? x) => x == null ? '—' : money(x);
    return switch (e.type) {
      'DISCOUNT_APPLIED' =>
        '${e.entityName ?? ''} · ${num_(d['percent'])}% (${money(d['discount'])})',
      'INVOICE_CANCELED' => '${e.entityName ?? ''} · ${money(d['total'])}',
      'PRODUCT_DELETED' => _t(
        '${e.entityName ?? ''} · quantity ${d['quantity']} · price ${money(d['sellingPrice'])}',
        '${e.entityName ?? ''} · الكمية ${d['quantity']} · السعر ${money(d['sellingPrice'])}',
      ),
      'PRICE_CHANGED' || 'COST_CHANGED' =>
        '${e.entityName ?? ''} · ${v(d['old'])} → ${v(d['new'])}',
      'STOCK_ADJUSTED' =>
        '${e.entityName ?? ''} · ${d['old']} → ${d['new']} (${(d['change'] as num? ?? 0) > 0 ? '+' : ''}${d['change']})',
      _ => e.entityName ?? '',
    };
  }

  String get noActivity => _t(
    'No recorded activity in this period.',
    'لا يوجد نشاط مسجّل في هذه الفترة.',
  );
  String pageOf(int page, int pages) =>
      _t('Page $page of $pages', 'صفحة $page من $pages');
  String get previous => _t('Previous', 'السابق');
  String get next => _t('Next', 'التالي');
  String get colTime => _t('Time', 'الوقت');
  String get colUser => _t('User', 'المستخدم');
  String get colEvent => _t('Event', 'الحدث');
  String get colDetails => _t('Details', 'التفاصيل');
}

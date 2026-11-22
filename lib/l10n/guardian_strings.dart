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
    'آخر تغيير: $who في ${date(when)} ${time(when)}',
  );
  String get pastPeriodNote => _t(
    'Past month: alerts and activity are for that month; stock sections show the CURRENT stock.',
    'شهر سابق: التنبيهات والنشاط لذلك الشهر، وأقسام المخزون تعرض المخزون الحالي.',
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
    'سجل النشاط يعمل منذ ${date(d)} (حتى عندما يكون وضع الحارس متوقفاً).',
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
  String get profitRisks => _t('Profit risks', 'مخاطر الربح');
  String get needAttention => _t('Need attention', 'تحتاج انتباه');
  String get none => _t('None', 'لا توجد');
  String get viewAll => _t('View all', 'عرض الكل');
  String incompleteCost(int n) => _t(
    '$n invoice(s) with missing cost: profit not judged',
    '$n فاتورة تكلفتها ناقصة: لا يُحكم على ربحها',
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

  // ── Health breakdown ────────────────────────────────────────────────────
  String get healthFormula => _t(
    'Score = points of the available parts ÷ (25 × number of available parts) × 100. '
        'A number is shown only when at least 3 of the 4 parts have enough data. '
        '80+ Good · 60–79 Needs follow-up · below 60 Risk.',
    'النتيجة = مجموع نقاط الأجزاء المتوفرة ÷ (25 × عددها) × 100. '
        'يظهر الرقم فقط إذا توفرت بيانات كافية لـ 3 أجزاء من 4 على الأقل. '
        '80 فأكثر جيد · 60–79 يحتاج متابعة · أقل من 60 خطر.',
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
        '${n['healthyProducts']} of ${n['products']} products not low / out of stock',
        '${n['healthyProducts']} من ${n['products']} منتج غير منخفض أو نافد',
      ),
      'PROFIT' => _t(
        '${n['confirmedLossInvoices']} confirmed loss(es) in ${n['completeCostInvoices']} '
            'invoices with every cost recorded (of ${n['invoices']})',
        '${n['confirmedLossInvoices']} خسارة مؤكدة من ${n['completeCostInvoices']} فاتورة '
            'تكلفتها كاملة (من أصل ${n['invoices']})',
      ),
      'DISCOUNTS' => _t(
        '${n['highDiscountInvoices']} high discount(s) in ${n['invoices']} invoices',
        '${n['highDiscountInvoices']} خصم عالٍ من ${n['invoices']} فاتورة',
      ),
      _ => _t(
        '${n['daysWithAlert']} day(s) with an activity alert of ${n['activeDays']} active days',
        '${n['daysWithAlert']} يوم فيه تنبيه نشاط من ${n['activeDays']} يوم نشط',
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
    'حذف المنتجات مسجّل منذ $d',
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
      'Invoice with a confirmed loss',
      'فاتورة بخسارة مؤكدة',
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
        '${n('percent')}% discount (${m('discount')}) on invoice ${p['invoiceNumber']} '
            '(${m('subtotal')}). Limit: ${n('thresholdPercent')}% = 75% of the '
            '${p['maxSource'] == 'CURRENT' ? 'current ' : ''}maximum ${n('maxPercent')}%, '
            'and at least ${m('minAmount')}.',
        'خصم ${n('percent')}% (${m('discount')}) على الفاتورة ${p['invoiceNumber']} '
            '(${m('subtotal')}). الحد: ${n('thresholdPercent')}% = 75% من أعلى خصم '
            '${n('maxPercent')}%${p['maxSource'] == 'CURRENT' ? ' (الحد الحالي للمتجر)' : ''}، '
            'و${m('minAmount')} على الأقل.',
      ),
      'HIGH_DISCOUNT_REPEATED' => _t(
        '${p['count']} invoices with a high discount by the same user in one day '
            '(limit: ${p['repeatLimit']}). Total discount ${m('totalDiscount')}.',
        '${p['count']} فواتير بخصم عالٍ من نفس المستخدم في يوم واحد '
            '(الحد: ${p['repeatLimit']}). مجموع الخصم ${m('totalDiscount')}.',
      ),
      'BELOW_COST_SALE' => _t(
        '${p['productName']} sold ${p['times']} time(s) (${p['units']} units) at '
            '${m('unitPrice')}; its cost at sale time was ${m('unitCost')}. '
            'Loss before the invoice discount: ${m('lossBeforeDiscount')}.',
        '${p['productName']} بيع ${p['times']} مرة (${p['units']} قطعة) بسعر '
            '${m('unitPrice')} وتكلفته وقت البيع ${m('unitCost')}. '
            'الخسارة قبل خصم الفاتورة: ${m('lossBeforeDiscount')}.',
      ),
      'CONFIRMED_LOSS' => _t(
        'Invoice ${p['invoiceNumber']}: net ${m('netRevenue')} (after a ${m('discount')} '
            'discount, tax excluded), cost ${m('cost')} → loss ${m('loss')}. '
            'Every product on it has a recorded cost.',
        'الفاتورة ${p['invoiceNumber']}: الصافي ${m('netRevenue')} (بعد خصم ${m('discount')}، '
            'بدون الضريبة) والتكلفة ${m('cost')} ← خسارة ${m('loss')}. '
            'كل منتجاتها لها تكلفة مسجلة.',
      ),
      'PRICE_BELOW_COST' => _t(
        '${p['productName']}: price ${m('price')}, cost ${m('cost')}. Every sale loses money.',
        '${p['productName']}: السعر ${m('price')} والتكلفة ${m('cost')}. كل بيع له بخسارة.',
      ),
      'PRICE_EQUALS_COST' => _t(
        '${p['productName']}: price ${m('price')} equals its cost. No profit.',
        '${p['productName']}: السعر ${m('price')} يساوي التكلفة. بدون ربح.',
      ),
      'MAX_DISCOUNT_RISK' => maxDiscountText(
        p['productName'] as String? ?? '',
        p['maxPercent'],
        p['priceAfterMax'],
        p['cost'],
        p['safePercent'],
      ),
      'OUT_OF_STOCK' => _t(
        '${p['count']} product(s) are out of stock.',
        '${p['count']} منتجات نفدت من المخزون.',
      ),
      'LOW_STOCK' => _t(
        '${p['count']} product(s) reached the reorder level.',
        '${p['count']} منتجات وصلت حد إعادة الطلب.',
      ),
      'STOCKOUT_SOON' => _t(
        '${p['count']} product(s) will run out within ${p['days']} days at their sales '
            'rate of the last ${p['windowDays']} days.',
        '${p['count']} منتجات ستنفد خلال ${p['days']} أيام حسب معدل بيعها في آخر '
            '${p['windowDays']} يوماً.',
      ),
      'CANCELED_INVOICES' => _t(
        '${p['count']} suspended invoices canceled on ${p['day']} of '
            '${(p['count'] as num) + (p['completed'] as num)} (${n('percent')}%). '
            'Limit: ${p['minCount']} and ${p['minPercent']}%.',
        '${p['count']} فواتير معلّقة أُلغيت في ${p['day']} من أصل '
            '${(p['count'] as num) + (p['completed'] as num)} (${n('percent')}%). '
            'الحد: ${p['minCount']} و${p['minPercent']}%.',
      ),
      'RETURNS_SPIKE' =>
        p['percent'] == null
            ? _t(
                'Refunds of ${m('refunds')} on ${p['day']}, a day without sales '
                    '(limit ${m('minAmount')}).',
                'مرتجعات ${m('refunds')} في ${p['day']}، يوم بلا مبيعات '
                    '(الحد ${m('minAmount')}).',
              )
            : _t(
                'Refunds on ${p['day']}: ${m('refunds')} = ${n('percent')}% of that day\'s '
                    'sales ${m('sales')} (limit: ${m('minAmount')} and ${p['mediumPercent']}%).',
                'مرتجعات ${p['day']}: ${m('refunds')} = ${n('percent')}% من مبيعات اليوم '
                    '${m('sales')} (الحد: ${m('minAmount')} و${p['mediumPercent']}%).',
              ),
      'PRODUCT_RETURNS_REPEATED' => _t(
        '${p['productName']} was returned ${p['count']} times within ${p['days']} days. '
            'There may be a problem with the product.',
        '${p['productName']} أُرجع ${p['count']} مرات خلال ${p['days']} أيام. '
            'قد تكون هناك مشكلة في المنتج.',
      ),
      'PRODUCT_DELETIONS' => _t(
        '${p['count']} products deleted on ${p['day']} (limit: ${p['mediumLimit']}).',
        '${p['count']} منتجات حُذفت في ${p['day']} (الحد: ${p['mediumLimit']}).',
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
  ) => _t(
    '$name: with the maximum allowed discount (${num_(max)}%) its price becomes '
        '${money(after)}, below its cost ${money(cost)}. Safe discount: up to ${num_(safe)}%.',
    '$name: بأعلى خصم مسموح (${num_(max)}%) يصبح سعره ${money(after)} وهو أقل '
        'من تكلفته ${money(cost)}. أعلى خصم آمن: ${num_(safe)}%.',
  );

  /// Who did it, from the backend's records only.
  String? actorLine(GuardianAlert a) {
    final p = a.params;
    final byUser = p['byUser'] as List<dynamic>?;
    if (byUser != null) {
      final parts = [
        for (final u in byUser.cast<Map<String, dynamic>>())
          '${u['name']} (${u['count']})',
      ];
      final missing = (p['notRecorded'] as num?)?.toInt() ?? 0;
      if (missing > 0) {
        parts.add(_t('$missing not recorded', '$missing غير مسجّل'));
      }
      if (parts.isEmpty) return null;
      return _t('By: ${parts.join(', ')}', 'بواسطة: ${parts.join('، ')}');
    }
    if (a.actorName != null) {
      final started = p['startedBy'] as String?;
      return switch (a.code) {
        'HIGH_DISCOUNT' || 'CONFIRMED_LOSS' =>
          _t('Completed by: ${a.actorName}', 'أكملها: ${a.actorName}') +
              (started == null
                  ? ''
                  : _t(' · started by: $started', ' · بدأها: $started')),
        _ => _t('By: ${a.actorName}', 'بواسطة: ${a.actorName}'),
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
      '${r.count} product(s) will run out within 3 days at their sales rate.',
      '${r.count} منتجات ستنفد خلال 3 أيام حسب معدل بيعها.',
    ),
    'LOW_MARGIN' => _t(
      '${r.count} product(s) earn less than 10% of their price. Review their price.',
      '${r.count} منتجات ربحها أقل من 10% من سعرها. راجع السعر.',
    ),
    _ => _t(
      '${r.count} product(s) not sold for 30 days with stock above the minimum. '
          'Review the quantity or an offer.',
      '${r.count} منتجات لم يُبع منها شيء منذ 30 يوماً ومخزونها فوق الحد. '
          'راجع الكمية أو فكّر بعرض.',
    ),
  };
  String get recNone => _t('No products right now.', 'لا توجد منتجات حالياً.');
  String get openStockAlerts => _t('Stock alerts', 'تنبيهات المخزون');
  String get showProducts => _t('Show products', 'عرض المنتجات');
  String recProductLine(String code, Map<String, dynamic> p) => switch (code) {
    'REORDER_SOON' => _t(
      '${p['quantity']} left · about ${num_(p['daysLeft'])} day(s) · ${p['expectedStockout']}',
      'المتبقي ${p['quantity']} · حوالي ${num_(p['daysLeft'])} يوم · ${p['expectedStockout']}',
    ),
    'LOW_MARGIN' => _t(
      'Price ${money(p['price'])} · cost ${money(p['cost'])} · margin ${num_(p['marginPercent'])}%',
      'السعر ${money(p['price'])} · التكلفة ${money(p['cost'])} · الهامش ${num_(p['marginPercent'])}%',
    ),
    _ => _t(
      '${p['quantity']} in stock (minimum ${p['minStock']}) · added ${p['ageDays']} days ago',
      'الموجود ${p['quantity']} (الحد ${p['minStock']}) · أضيف قبل ${p['ageDays']} يوم',
    ),
  };

  // ── Store map ───────────────────────────────────────────────────────────
  String get storeMap => _t('Live store map', 'خريطة المتجر الحية');
  String get storeMapNote => _t(
    'By product category (the store layout is not recorded).',
    'حسب تصنيف المنتجات (مخطط المتجر غير مسجّل).',
  );
  String stockHealth(int p) => _t('Stock health $p%', 'صحة المخزون $p%');
  String get cashierArea => _t('Cashier area', 'منطقة الكاشير');
  String get noProblems => _t('No problems', 'لا توجد مشاكل');
  String cashierAlerts(int high, int medium) => _t(
    '${high + medium} alert(s) this period',
    '${high + medium} تنبيه في هذه الفترة',
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
    ).replaceFirst(RegExp(r'^: '), ''),
  };
  String costPrice(double c) => _t('Cost ${money(c)}', 'التكلفة ${money(c)}');
  String currentPrice(double c) =>
      _t('Price ${money(c)}', 'السعر الحالي ${money(c)}');
  String get noProfitRisks => _t(
    'No product would be sold below its recorded cost.',
    'لا يوجد منتج سيُباع بأقل من تكلفته المسجلة.',
  );
  String withoutCost(int n) => _t(
    '$n product(s) have no recorded cost and are not checked.',
    '$n منتجات بدون سعر تكلفة ولا يتم فحصها.',
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
  String perDay(double v) =>
      _t('${num_(v)} case(s) / day', '${num_(v)} حالة يومياً');
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

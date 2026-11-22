/// Guardian Mode data (report Figure 3.1), exactly as calculated by the
/// backend (`/api/guardian/...`). Rule-based: the app only displays it.
library;

double _d(Object? v) => (v as num?)?.toDouble() ?? 0;
double? _dn(Object? v) => (v as num?)?.toDouble();
int _i(Object? v) => (v as num?)?.toInt() ?? 0;
int? _in(Object? v) => (v as num?)?.toInt();
DateTime? _dt(Object? v) => v == null ? null : DateTime.parse(v as String);
List<Map<String, dynamic>> _maps(Object? v) => [
  for (final m in (v as List<dynamic>? ?? const [])) m as Map<String, dynamic>,
];

enum GuardianSeverity { high, medium, low }

GuardianSeverity severityOf(Object? v) => switch (v) {
  'HIGH' => GuardianSeverity.high,
  'MEDIUM' => GuardianSeverity.medium,
  _ => GuardianSeverity.low,
};

/// GOOD (≥ 80), ATTENTION (60–79) or RISK (< 60).
enum HealthBand { good, attention, risk }

HealthBand? bandOf(Object? v) => switch (v) {
  'GOOD' => HealthBand.good,
  'ATTENTION' => HealthBand.attention,
  'RISK' => HealthBand.risk,
  _ => null,
};

class GuardianStatus {
  const GuardianStatus({
    required this.enabled,
    this.changedAt,
    this.changedBy,
    this.activityTrackedSince,
  });

  factory GuardianStatus.fromJson(Map<String, dynamic> j) => GuardianStatus(
    enabled: j['enabled'] == true,
    changedAt: _dt(j['changedAt']),
    changedBy: j['changedBy'] as String?,
    activityTrackedSince: _dt(j['activityTrackedSince']),
  );

  final bool enabled;
  final DateTime? changedAt;
  final String? changedBy;
  final DateTime? activityTrackedSince;
}

/// The screen an alert or recommendation can open:
/// INVOICE (id = sale), PRODUCT (id = product), STOCK_ALERTS or ACTIVITY.
class GuardianAction {
  const GuardianAction(this.type, this.id);

  static GuardianAction? fromJson(Object? v) {
    if (v == null) return null;
    final j = v as Map<String, dynamic>;
    return GuardianAction(j['type'] as String, _in(j['id']));
  }

  final String type;
  final int? id;
}

class GuardianRef {
  const GuardianRef(this.type, this.id, this.label);

  factory GuardianRef.fromJson(Map<String, dynamic> j) =>
      GuardianRef(j['type'] as String, _in(j['id']), j['label'] as String?);

  final String type;
  final int? id;
  final String? label;
}

class GuardianAlert {
  const GuardianAlert({
    required this.key,
    required this.rule,
    required this.code,
    required this.severity,
    required this.category,
    required this.params,
    required this.evidence,
    this.actorName,
    this.actorNote,
    this.occurredAt,
    this.action,
  });

  factory GuardianAlert.fromJson(Map<String, dynamic> j) => GuardianAlert(
    key: j['key'] as String,
    rule: j['rule'] as String,
    code: j['code'] as String,
    severity: severityOf(j['severity']),
    category: j['category'] as String? ?? '',
    params: (j['params'] as Map<String, dynamic>?) ?? const {},
    evidence: [for (final r in _maps(j['evidence'])) GuardianRef.fromJson(r)],
    actorName: (j['actor'] as Map<String, dynamic>?)?['name'] as String?,
    actorNote: j['actorNote'] as String?,
    occurredAt: _dt(j['occurredAt']),
    action: GuardianAction.fromJson(j['action']),
  );

  final String key;
  final String rule;
  final String code;
  final GuardianSeverity severity;
  final String category;
  final Map<String, dynamic> params;
  final List<GuardianRef> evidence;

  /// The user recorded by the backend (activity log), or null.
  final String? actorName;

  /// NOT_RECORDED when the user is unknown (before the activity log started).
  final String? actorNote;

  /// Null for a "current state" alert (stock, prices).
  final DateTime? occurredAt;
  final GuardianAction? action;
}

class TodaySales {
  const TodaySales({
    required this.today,
    required this.yesterdaySameTime,
    required this.changePercent,
    required this.last7Days,
  });

  factory TodaySales.fromJson(Map<String, dynamic> j) => TodaySales(
    today: _d(j['today']),
    yesterdaySameTime: _d(j['yesterdaySameTime']),
    changePercent: _dn(j['changePercent']),
    last7Days: [for (final p in _maps(j['last7Days'])) _d(p['netSales'])],
  );

  final double today;
  final double yesterdaySameTime;

  /// Null when there were no sales yesterday to compare with.
  final double? changePercent;
  final List<double> last7Days;
}

class CostData {
  const CostData({
    required this.invoices,
    required this.completeCostInvoices,
    required this.incompleteCostInvoices,
    required this.confirmedLossInvoices,
    required this.productsWithoutCost,
  });

  factory CostData.fromJson(Map<String, dynamic>? j) => CostData(
    invoices: _i(j?['invoices']),
    completeCostInvoices: _i(j?['completeCostInvoices']),
    incompleteCostInvoices: _i(j?['incompleteCostInvoices']),
    confirmedLossInvoices: _i(j?['confirmedLossInvoices']),
    productsWithoutCost: _i(j?['productsWithoutCost']),
  );

  final int invoices;
  final int completeCostInvoices;
  final int incompleteCostInvoices;
  final int confirmedLossInvoices;
  final int productsWithoutCost;
}

class HealthPart {
  const HealthPart({
    required this.part,
    required this.available,
    required this.points,
    required this.reason,
    required this.numbers,
  });

  factory HealthPart.fromJson(Map<String, dynamic> j) => HealthPart(
    part: j['part'] as String,
    available: j['available'] == true,
    points: _dn(j['points']),
    reason: j['reason'] as String?,
    numbers: (j['numbers'] as Map<String, dynamic>?) ?? const {},
  );

  /// STOCK, PROFIT, DISCOUNTS or ACTIVITY.
  final String part;
  final bool available;
  final double? points;
  final String? reason;
  final Map<String, dynamic> numbers;
}

class StoreHealth {
  const StoreHealth({
    required this.score,
    required this.band,
    required this.partsUsed,
    required this.reason,
    required this.parts,
  });

  factory StoreHealth.fromJson(Map<String, dynamic> j) => StoreHealth(
    score: _in(j['score']),
    band: bandOf(j['band']),
    partsUsed: _i(j['partsUsed']),
    reason: j['reason'] as String?,
    parts: [for (final p in _maps(j['parts'])) HealthPart.fromJson(p)],
  );

  /// Null when fewer than 3 parts have enough data.
  final int? score;
  final HealthBand? band;
  final int partsUsed;
  final String? reason;
  final List<HealthPart> parts;
}

class MapTile {
  const MapTile({
    required this.kind,
    required this.category,
    required this.percent,
    required this.band,
    required this.products,
    required this.healthyProducts,
    required this.highAlerts,
    required this.mediumAlerts,
  });

  factory MapTile.fromJson(Map<String, dynamic> j) => MapTile(
    kind: j['kind'] as String,
    category: j['category'] as String?,
    percent: _in(j['percent']),
    band: bandOf(j['band']) ?? HealthBand.good,
    products: _i(j['products']),
    healthyProducts: _i(j['healthyProducts']),
    highAlerts: _i(j['highAlerts']),
    mediumAlerts: _i(j['mediumAlerts']),
  );

  /// CATEGORY or CASHIER_AREA.
  final String kind;
  final String? category;
  final int? percent;
  final HealthBand band;
  final int products;
  final int healthyProducts;
  final int highAlerts;
  final int mediumAlerts;

  bool get cashierArea => kind == 'CASHIER_AREA';
}

class AtRiskProduct {
  const AtRiskProduct({
    required this.productId,
    required this.name,
    required this.quantity,
    required this.minStock,
    required this.expectedStockout,
    required this.daysLeft,
    required this.unitsPerDay,
    required this.prediction,
    required this.risk,
  });

  factory AtRiskProduct.fromJson(Map<String, dynamic> j) => AtRiskProduct(
    productId: _i(j['productId']),
    name: j['name'] as String,
    quantity: _i(j['quantity']),
    minStock: _i(j['minStock']),
    expectedStockout: _dt(j['expectedStockout']),
    daysLeft: _dn(j['daysLeft']),
    unitsPerDay: _dn(j['unitsPerDay']),
    prediction: j['prediction'] as String? ?? 'INSUFFICIENT_DATA',
    risk: severityOf(j['risk']),
  );

  final int productId;
  final String name;
  final int quantity;
  final int minStock;
  final DateTime? expectedStockout;
  final double? daysLeft;
  final double? unitsPerDay;

  /// OK, INSUFFICIENT_DATA or OUT_OF_STOCK.
  final String prediction;
  final GuardianSeverity risk;
}

class ProfitProtectionItem {
  const ProfitProtectionItem({
    required this.productId,
    required this.name,
    required this.cost,
    required this.price,
    required this.maxDiscountPercent,
    required this.priceAfterMaxDiscount,
    required this.safeDiscountPercent,
    required this.status,
  });

  factory ProfitProtectionItem.fromJson(Map<String, dynamic> j) =>
      ProfitProtectionItem(
        productId: _i(j['productId']),
        name: j['name'] as String,
        cost: _d(j['cost']),
        price: _d(j['price']),
        maxDiscountPercent: _d(j['maxDiscountPercent']),
        priceAfterMaxDiscount: _d(j['priceAfterMaxDiscount']),
        safeDiscountPercent: _d(j['safeDiscountPercent']),
        status: j['status'] as String,
      );

  final int productId;
  final String name;
  final double cost;
  final double price;
  final double maxDiscountPercent;
  final double priceAfterMaxDiscount;
  final double safeDiscountPercent;

  /// PRICE_BELOW_COST, PRICE_EQUALS_COST or MAX_DISCOUNT_RISK.
  final String status;
}

/// T1 REORDER_SOON, T2 LOW_MARGIN or T3 SLOW_MOVING (rule-based suggestions).
class GuardianRecommendation {
  const GuardianRecommendation({
    required this.rule,
    required this.code,
    required this.count,
    required this.products,
  });

  factory GuardianRecommendation.fromJson(Map<String, dynamic> j) =>
      GuardianRecommendation(
        rule: j['rule'] as String,
        code: j['code'] as String,
        count: _i(j['count']),
        products: _maps(j['products']),
      );

  final String rule;
  final String code;
  final int count;
  final List<Map<String, dynamic>> products;
}

class ActivitySummary {
  const ActivitySummary({
    required this.highDiscounts,
    required this.cancellations,
    required this.returns,
    required this.deletions,
    required this.total,
    required this.days,
    required this.perDay,
    required this.trackedSince,
  });

  factory ActivitySummary.fromJson(Map<String, dynamic> j) => ActivitySummary(
    highDiscounts: _i(j['highDiscounts']),
    cancellations: _i(j['cancellations']),
    returns: _i(j['returns']),
    deletions: _i(j['deletions']),
    total: _i(j['total']),
    days: _i(j['days']),
    perDay: _d(j['perDay']),
    trackedSince: _dt(j['trackedSince']),
  );

  final int highDiscounts;
  final int cancellations;
  final int returns;
  final int deletions;
  final int total;
  final int days;
  final double perDay;
  final DateTime? trackedSince;
}

class GuardianDashboard {
  const GuardianDashboard({
    required this.enabled,
    required this.status,
    this.from,
    this.to,
    this.currentPeriod = false,
    this.todaySales,
    this.criticalCount = 0,
    this.profitRiskCount = 0,
    this.costData,
    this.health,
    this.topAlerts = const [],
    this.alertCount = 0,
    this.recommendations = const [],
    this.storeMap = const [],
    this.atRisk = const [],
    this.profitProtection = const [],
    this.activity,
  });

  factory GuardianDashboard.fromJson(Map<String, dynamic> j) =>
      GuardianDashboard(
        enabled: j['enabled'] == true,
        status: GuardianStatus.fromJson(
          (j['status'] as Map<String, dynamic>?) ?? const {},
        ),
        from: _dt(j['from']),
        to: _dt(j['to']),
        currentPeriod: j['currentPeriod'] == true,
        todaySales: j['todaySales'] == null
            ? null
            : TodaySales.fromJson(j['todaySales'] as Map<String, dynamic>),
        criticalCount: _i(j['criticalCount']),
        profitRiskCount: _i(j['profitRiskCount']),
        costData: j['costData'] == null
            ? null
            : CostData.fromJson(j['costData'] as Map<String, dynamic>),
        health: j['health'] == null
            ? null
            : StoreHealth.fromJson(j['health'] as Map<String, dynamic>),
        topAlerts: [
          for (final a in _maps(j['topAlerts'])) GuardianAlert.fromJson(a),
        ],
        alertCount: _i(j['alertCount']),
        recommendations: [
          for (final r in _maps(j['recommendations']))
            GuardianRecommendation.fromJson(r),
        ],
        storeMap: [for (final t in _maps(j['storeMap'])) MapTile.fromJson(t)],
        atRisk: [
          for (final p in _maps(j['atRiskProducts'])) AtRiskProduct.fromJson(p),
        ],
        profitProtection: [
          for (final p in _maps(j['profitProtection']))
            ProfitProtectionItem.fromJson(p),
        ],
        activity: j['activity'] == null
            ? null
            : ActivitySummary.fromJson(j['activity'] as Map<String, dynamic>),
      );

  final bool enabled;
  final GuardianStatus status;
  final DateTime? from;
  final DateTime? to;
  final bool currentPeriod;
  final TodaySales? todaySales;
  final int criticalCount;
  final int profitRiskCount;
  final CostData? costData;
  final StoreHealth? health;
  final List<GuardianAlert> topAlerts;
  final int alertCount;
  final List<GuardianRecommendation> recommendations;
  final List<MapTile> storeMap;
  final List<AtRiskProduct> atRisk;
  final List<ProfitProtectionItem> profitProtection;
  final ActivitySummary? activity;
}

class ActivityEntry {
  const ActivityEntry({
    required this.logId,
    required this.type,
    required this.createdAt,
    required this.userName,
    required this.entityName,
    required this.details,
  });

  factory ActivityEntry.fromJson(Map<String, dynamic> j) => ActivityEntry(
    logId: _i(j['logId']),
    type: j['type'] as String,
    createdAt: _dt(j['createdAt'])!,
    userName: j['userName'] as String? ?? '',
    entityName: j['entityName'] as String?,
    details: (j['details'] as Map<String, dynamic>?) ?? const {},
  );

  final int logId;
  final String type;
  final DateTime createdAt;
  final String userName;
  final String? entityName;
  final Map<String, dynamic> details;
}

class ActivityPage {
  const ActivityPage({
    required this.enabled,
    required this.page,
    required this.pageSize,
    required this.total,
    required this.trackedSince,
    required this.entries,
  });

  factory ActivityPage.fromJson(Map<String, dynamic> j) => ActivityPage(
    enabled: j['enabled'] == true,
    page: _i(j['page']),
    pageSize: _i(j['pageSize']),
    total: _i(j['total']),
    trackedSince: _dt(j['trackedSince']),
    entries: [for (final e in _maps(j['entries'])) ActivityEntry.fromJson(e)],
  );

  final bool enabled;
  final int page;
  final int pageSize;
  final int total;
  final DateTime? trackedSince;
  final List<ActivityEntry> entries;
}

/// Reports & Sales Analytics data, exactly as calculated by the backend
/// (`/api/reports/...`). The app only displays these figures.
library;

double _d(Object? v) => (v as num?)?.toDouble() ?? 0;
int _i(Object? v) => (v as num?)?.toInt() ?? 0;
DateTime _date(Object? v) => DateTime.parse(v as String);

/// A reporting period: whole days from [from] to [to] (both included).
class ReportPeriod {
  const ReportPeriod(this.from, this.to);

  factory ReportPeriod.month(int year, int month) =>
      ReportPeriod(DateTime(year, month, 1), DateTime(year, month + 1, 0));

  factory ReportPeriod.year(int year) =>
      ReportPeriod(DateTime(year, 1, 1), DateTime(year, 12, 31));

  final DateTime from;
  final DateTime to;

  static String iso(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-'
      '${d.day.toString().padLeft(2, '0')}';

  String get query => 'from=${iso(from)}&to=${iso(to)}';
}

/// Report cards (Figure 3.8).
class SalesSummary {
  const SalesSummary({
    required this.from,
    required this.to,
    required this.grossSales,
    required this.discounts,
    required this.tax,
    required this.refunds,
    required this.netSales,
    required this.invoiceCount,
    required this.returnCount,
    required this.unitsSold,
    required this.unitsReturned,
    required this.averageInvoice,
    required this.previousNetSales,
    required this.previousInvoiceCount,
    required this.profitAvailable,
    this.profit,
  });

  factory SalesSummary.fromJson(Map<String, dynamic> j) {
    final prev = j['previous'] as Map<String, dynamic>? ?? const {};
    final profit = j['profit'] as Map<String, dynamic>? ?? const {};
    return SalesSummary(
      from: _date(j['from']),
      to: _date(j['to']),
      grossSales: _d(j['grossSales']),
      discounts: _d(j['discounts']),
      tax: _d(j['tax']),
      refunds: _d(j['refunds']),
      netSales: _d(j['netSales']),
      invoiceCount: _i(j['invoiceCount']),
      returnCount: _i(j['returnCount']),
      unitsSold: _i(j['unitsSold']),
      unitsReturned: _i(j['unitsReturned']),
      averageInvoice: _d(j['averageInvoice']),
      previousNetSales: _d(prev['netSales']),
      previousInvoiceCount: _i(prev['invoiceCount']),
      profitAvailable: profit['available'] == true,
      profit: (profit['amount'] as num?)?.toDouble(),
    );
  }

  final DateTime from;
  final DateTime to;

  /// Paid totals of completed invoices (after discount, tax included).
  final double grossSales;
  final double discounts;
  final double tax;
  final double refunds;

  /// grossSales − refunds.
  final double netSales;
  final int invoiceCount;
  final int returnCount;
  final int unitsSold;
  final int unitsReturned;
  final double averageInvoice;
  final double previousNetSales;
  final int previousInvoiceCount;

  /// False while products have no purchase cost: profit is then not shown.
  final bool profitAvailable;
  final double? profit;

  /// Percent change against the previous period, or null when it had nothing.
  static double? change(num current, num previous) =>
      previous == 0 ? null : (current - previous) * 100 / previous.abs();
}

/// One point of the daily or monthly chart.
class SalesPoint {
  const SalesPoint({
    required this.label,
    required this.grossSales,
    required this.refunds,
    required this.netSales,
    required this.invoices,
  });

  factory SalesPoint.day(Map<String, dynamic> j) => SalesPoint(
    label: '${_date(j['date']).day}',
    grossSales: _d(j['grossSales']),
    refunds: _d(j['refunds']),
    netSales: _d(j['netSales']),
    invoices: _i(j['invoices']),
  );

  factory SalesPoint.month(Map<String, dynamic> j) => SalesPoint(
    label: '${_i(j['month'])}',
    grossSales: _d(j['grossSales']),
    refunds: _d(j['refunds']),
    netSales: _d(j['netSales']),
    invoices: _i(j['invoices']),
  );

  /// Day of month (1–31) or month (1–12).
  final String label;
  final double grossSales;
  final double refunds;
  final double netSales;
  final int invoices;
}

class TopProduct {
  const TopProduct({
    required this.productId,
    required this.name,
    required this.units,
    required this.salesValue,
    required this.share,
  });

  factory TopProduct.fromJson(Map<String, dynamic> j) => TopProduct(
    productId: (j['productId'] as num?)?.toInt(),
    name: j['name'] as String,
    units: _i(j['units']),
    salesValue: _d(j['salesValue']),
    share: _d(j['share']),
  );

  /// Null when the product was deleted after the sale.
  final int? productId;
  final String name;

  /// Units sold and not returned.
  final int units;

  /// The product's share of what customers paid.
  final double salesValue;

  /// Percent of the period's product sales value.
  final double share;
}

class TopProducts {
  const TopProducts({
    required this.totalSalesValue,
    required this.totalUnits,
    required this.products,
    required this.othersValue,
    required this.othersUnits,
  });

  factory TopProducts.fromJson(Map<String, dynamic> j) => TopProducts(
    totalSalesValue: _d(j['totalSalesValue']),
    totalUnits: _i(j['totalUnits']),
    products: [
      for (final p in j['products'] as List<dynamic>)
        TopProduct.fromJson(p as Map<String, dynamic>),
    ],
    othersValue: _d(j['othersValue']),
    othersUnits: _i(j['othersUnits']),
  );

  final double totalSalesValue;
  final int totalUnits;
  final List<TopProduct> products;
  final double othersValue;
  final int othersUnits;
}

class CategoryLine {
  const CategoryLine(this.category, this.products, this.units, this.value);

  final String category;
  final int products;
  final int units;
  final double value;
}

class ReorderItem {
  const ReorderItem({
    required this.productId,
    required this.name,
    required this.barcode,
    required this.quantity,
    required this.minStock,
    required this.outOfStock,
    this.supplierName,
  });

  final int productId;
  final String name;
  final String barcode;
  final int quantity;
  final int minStock;
  final bool outOfStock;
  final String? supplierName;
}

/// Inventory report (UR-M8 / SR21).
class InventoryReport {
  const InventoryReport({
    required this.totalProducts,
    required this.totalUnits,
    required this.inventoryValue,
    required this.inStock,
    required this.lowStock,
    required this.outOfStock,
    required this.categories,
    required this.reorder,
  });

  factory InventoryReport.fromJson(Map<String, dynamic> j) => InventoryReport(
    totalProducts: _i(j['totalProducts']),
    totalUnits: _i(j['totalUnits']),
    inventoryValue: _d(j['inventoryValue']),
    inStock: _i(j['inStock']),
    lowStock: _i(j['lowStock']),
    outOfStock: _i(j['outOfStock']),
    categories: [
      for (final c in j['categories'] as List<dynamic>)
        CategoryLine(
          (c as Map<String, dynamic>)['category'] as String,
          _i(c['products']),
          _i(c['units']),
          _d(c['value']),
        ),
    ],
    reorder: [
      for (final r in j['reorder'] as List<dynamic>)
        ReorderItem(
          productId: _i((r as Map<String, dynamic>)['productId']),
          name: r['name'] as String,
          barcode: r['barcode'] as String? ?? '',
          quantity: _i(r['quantity']),
          minStock: _i(r['minStock']),
          outOfStock: r['stockStatus'] == 'OUT_OF_STOCK',
          supplierName: r['supplierName'] as String?,
        ),
    ],
  );

  final int totalProducts;
  final int totalUnits;
  final double inventoryValue;
  final int inStock;
  final int lowStock;
  final int outOfStock;
  final List<CategoryLine> categories;

  /// Low-stock and out-of-stock products (existing stock rules).
  final List<ReorderItem> reorder;
}

/// Cash drawers and shifts (store upgrade, features 1 and 2). Amounts come from
/// the backend, which calculates them; the app never computes the expected cash.
library;

double _d(Object? v) => (v as num?)?.toDouble() ?? 0;

class CashDrawer {
  const CashDrawer({
    required this.drawerId,
    required this.name,
    required this.active,
    required this.inUse,
  });

  factory CashDrawer.fromJson(Map<String, dynamic> j) => CashDrawer(
    drawerId: (j['drawerId'] as num).toInt(),
    name: j['name'] as String,
    active: j['active'] == true,
    inUse: j['inUse'] == true,
  );

  final int drawerId;
  final String name;
  final bool active;
  final bool inUse;
}

class CashShift {
  const CashShift({
    required this.shiftId,
    required this.drawerId,
    required this.drawerName,
    required this.cashierId,
    required this.cashierName,
    required this.status,
    required this.openedAt,
    required this.openingCash,
    required this.cashSales,
    required this.cashSalesCount,
    required this.cashRefunds,
    required this.cashRefundsCount,
    required this.cashIn,
    required this.cashOut,
    required this.otherSales,
    required this.otherSalesCount,
    required this.expectedCash,
    required this.version,
    this.closedAt,
    this.closedByName,
    this.closeKind,
    this.closeReason,
    this.countedCash,
    this.difference,
    this.closingNote,
  });

  factory CashShift.fromJson(Map<String, dynamic> j) => CashShift(
    shiftId: (j['shiftId'] as num).toInt(),
    drawerId: (j['drawerId'] as num).toInt(),
    drawerName: j['drawerName'] as String,
    cashierId: (j['cashierId'] as num).toInt(),
    cashierName: j['cashierName'] as String,
    status: j['status'] as String,
    openedAt: DateTime.parse(j['openedAt'] as String),
    openingCash: _d(j['openingCash']),
    cashSales: _d(j['cashSales']),
    cashSalesCount: (j['cashSalesCount'] as num?)?.toInt() ?? 0,
    cashRefunds: _d(j['cashRefunds']),
    cashRefundsCount: (j['cashRefundsCount'] as num?)?.toInt() ?? 0,
    cashIn: _d(j['cashIn']),
    cashOut: _d(j['cashOut']),
    otherSales: _d(j['otherSales']),
    otherSalesCount: (j['otherSalesCount'] as num?)?.toInt() ?? 0,
    expectedCash: _d(j['expectedCash']),
    version: (j['version'] as num?)?.toInt() ?? 0,
    closedAt: j['closedAt'] == null ? null : DateTime.parse(j['closedAt'] as String),
    closedByName: j['closedByName'] as String?,
    closeKind: j['closeKind'] as String?,
    closeReason: j['closeReason'] as String?,
    countedCash: (j['countedCash'] as num?)?.toDouble(),
    difference: (j['difference'] as num?)?.toDouble(),
    closingNote: j['closingNote'] as String?,
  );

  final int shiftId;
  final int drawerId;
  final String drawerName;
  final int cashierId;
  final String cashierName;
  final String status;
  final DateTime openedAt;
  final double openingCash;
  final double cashSales;
  final int cashSalesCount;
  final double cashRefunds;
  final int cashRefundsCount;
  final double cashIn;
  final double cashOut;
  final double otherSales;
  final int otherSalesCount;
  final double expectedCash;
  final int version;
  final DateTime? closedAt;
  final String? closedByName;
  final String? closeKind;
  final String? closeReason;
  final double? countedCash;
  final double? difference;
  final String? closingNote;

  bool get isOpen => status == 'OPEN';
}

/// GET /shifts/current: the user's own open shift (or null), the store rule and the drawers.
class CurrentShift {
  const CurrentShift({
    required this.shift,
    required this.requireCashShift,
    required this.drawers,
  });

  factory CurrentShift.fromJson(Map<String, dynamic> j) => CurrentShift(
    shift: j['shift'] == null
        ? null
        : CashShift.fromJson(j['shift'] as Map<String, dynamic>),
    requireCashShift: j['requireCashShift'] == true,
    drawers: [
      for (final d in (j['drawers'] as List? ?? const []))
        CashDrawer.fromJson(d as Map<String, dynamic>),
    ],
  );

  final CashShift? shift;
  final bool requireCashShift;
  final List<CashDrawer> drawers;
}

class CashMovement {
  const CashMovement({
    required this.movementId,
    required this.type,
    required this.amount,
    required this.reason,
    required this.createdByName,
    required this.createdAt,
  });

  factory CashMovement.fromJson(Map<String, dynamic> j) => CashMovement(
    movementId: (j['movementId'] as num).toInt(),
    type: j['type'] as String,
    amount: ((j['amountMinor'] as num).toInt()) / 100,
    reason: j['reason'] as String,
    createdByName: j['createdByName'] as String,
    createdAt: DateTime.parse(j['createdAt'] as String),
  );

  final int movementId;
  final String type;
  final double amount;
  final String reason;
  final String createdByName;
  final DateTime createdAt;
}

class ShiftSale {
  const ShiftSale({
    required this.saleId,
    required this.invoiceNumber,
    required this.paymentMethod,
    required this.total,
  });

  factory ShiftSale.fromJson(Map<String, dynamic> j) => ShiftSale(
    saleId: (j['saleId'] as num).toInt(),
    invoiceNumber: j['invoiceNumber'] as String,
    paymentMethod: j['paymentMethod'] as String,
    total: _d(j['total']),
  );

  final int saleId;
  final String invoiceNumber;
  final String paymentMethod;
  final double total;
}

class ShiftDetails {
  const ShiftDetails({
    required this.shift,
    required this.sales,
    required this.movements,
  });

  factory ShiftDetails.fromJson(Map<String, dynamic> j) => ShiftDetails(
    shift: CashShift.fromJson(j['shift'] as Map<String, dynamic>),
    sales: [
      for (final s in (j['sales'] as List? ?? const []))
        ShiftSale.fromJson(s as Map<String, dynamic>),
    ],
    movements: [
      for (final m in (j['movements'] as List? ?? const []))
        CashMovement.fromJson(m as Map<String, dynamic>),
    ],
  );

  final CashShift shift;
  final List<ShiftSale> sales;
  final List<CashMovement> movements;
}

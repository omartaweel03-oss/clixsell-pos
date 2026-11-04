/// Payment methods of the POS screen (report SR11, Figure 3.2).
enum PaymentMethod {
  cash('CASH'),
  card('CARD'),
  wallet('WALLET'),
  bankTransfer('BANK_TRANSFER');

  const PaymentMethod(this.apiName);

  final String apiName;

  static PaymentMethod? fromApi(String? value) {
    for (final m in values) {
      if (m.apiName == value) return m;
    }
    return null;
  }
}

/// How the invoice discount was entered.
enum DiscountType {
  amount('AMOUNT'),
  percent('PERCENT');

  const DiscountType(this.apiName);

  final String apiName;
}

enum SaleStatus { suspended, completed, canceled }

/// One line of a saved invoice (SaleResponse.Item).
class SaleLine {
  const SaleLine({
    required this.saleItemId,
    required this.productId,
    required this.productName,
    required this.quantity,
    required this.unitPrice,
    required this.lineTotal,
    required this.returnedQuantity,
    required this.returnableQuantity,
    this.barcode,
  });

  factory SaleLine.fromJson(Map<String, dynamic> j) => SaleLine(
    saleItemId: (j['saleItemId'] as num).toInt(),
    productId: (j['productId'] as num?)?.toInt(),
    productName: j['productName'] as String,
    barcode: j['barcode'] as String?,
    quantity: (j['quantity'] as num).toInt(),
    unitPrice: (j['unitPrice'] as num).toDouble(),
    lineTotal: (j['lineTotal'] as num).toDouble(),
    returnedQuantity: (j['returnedQuantity'] as num).toInt(),
    returnableQuantity: (j['returnableQuantity'] as num).toInt(),
  );

  final int saleItemId;

  /// Null when the product was deleted after the sale.
  final int? productId;
  final String productName;
  final String? barcode;
  final int quantity;
  final double unitPrice;
  final double lineTotal;
  final int returnedQuantity;
  final int returnableQuantity;
}

/// A saved invoice as returned by the backend (SaleResponse).
class Sale {
  const Sale({
    required this.saleId,
    required this.invoiceNumber,
    required this.status,
    required this.cashierName,
    required this.items,
    required this.subtotal,
    required this.discount,
    required this.taxRate,
    required this.tax,
    required this.total,
    required this.createdAt,
    this.paymentMethod,
    this.receiptCode,
    this.completedAt,
  });

  factory Sale.fromJson(Map<String, dynamic> j) => Sale(
    saleId: (j['saleId'] as num).toInt(),
    invoiceNumber: j['invoiceNumber'] as String,
    status: switch (j['status']) {
      'COMPLETED' => SaleStatus.completed,
      'CANCELED' => SaleStatus.canceled,
      _ => SaleStatus.suspended,
    },
    cashierName: j['cashierName'] as String? ?? '',
    items: (j['items'] as List<dynamic>)
        .map((e) => SaleLine.fromJson(e as Map<String, dynamic>))
        .toList(),
    subtotal: (j['subtotal'] as num).toDouble(),
    discount: (j['discount'] as num).toDouble(),
    taxRate: (j['taxRate'] as num).toDouble(),
    tax: (j['tax'] as num).toDouble(),
    total: (j['total'] as num).toDouble(),
    paymentMethod: PaymentMethod.fromApi(j['paymentMethod'] as String?),
    receiptCode: j['receiptCode'] as String?,
    createdAt: DateTime.parse(j['createdAt'] as String),
    completedAt: j['completedAt'] == null
        ? null
        : DateTime.parse(j['completedAt'] as String),
  );

  final int saleId;
  final String invoiceNumber;
  final SaleStatus status;
  final String cashierName;
  final List<SaleLine> items;
  final double subtotal;
  final double discount;
  final double taxRate;
  final double tax;
  final double total;
  final PaymentMethod? paymentMethod;

  /// Value of the receipt's QR code (completed sales only).
  final String? receiptCode;
  final DateTime createdAt;
  final DateTime? completedAt;

  int get itemCount => items.length;
}

/// Confirmation of a product return (ReturnResponse).
class ReturnResult {
  const ReturnResult({
    required this.invoiceNumber,
    required this.productName,
    required this.quantity,
    required this.refundAmount,
    required this.refundMethod,
    required this.stillReturnable,
  });

  factory ReturnResult.fromJson(Map<String, dynamic> j) => ReturnResult(
    invoiceNumber: j['invoiceNumber'] as String,
    productName: j['productName'] as String,
    quantity: (j['quantity'] as num).toInt(),
    refundAmount: (j['refundAmount'] as num).toDouble(),
    refundMethod: PaymentMethod.fromApi(j['refundMethod'] as String?),
    stillReturnable: (j['stillReturnable'] as num).toInt(),
  );

  final String invoiceNumber;
  final String productName;
  final int quantity;
  final double refundAmount;
  final PaymentMethod? refundMethod;
  final int stillReturnable;
}

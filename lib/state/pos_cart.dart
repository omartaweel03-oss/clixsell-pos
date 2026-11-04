import 'package:flutter/widgets.dart';

import '../models/product.dart';
import '../models/sale.dart';
import '../services/sales_api_client.dart';

/// One product on the current invoice.
class CartLine {
  CartLine(this.product, this.quantity);

  Product product;
  int quantity;

  int get unitCents => _cents(product.price);
  int get lineCents => unitCents * quantity;
  double get lineTotal => lineCents / 100;
}

int _cents(double value) => (value * 100).round();

/// Why an item could not be added.
enum AddResult { added, outOfStock, stockLimit }

/// The invoice the cashier is building on the POS screen (Figure 3.2).
///
/// It lives in the app until it is suspended or completed; then the backend
/// saves it. The totals shown here follow the same rules as the backend
/// (subtotal → discount → tax → total, rounded to 2 decimals), but the
/// backend recalculates everything from database prices and decides.
class PosCart extends ChangeNotifier {
  final List<CartLine> _lines = [];

  /// Store settings, loaded from GET /api/store.
  double taxRate = 16;
  double maxDiscountPercent = 10;

  DiscountType _discountType = DiscountType.amount;
  double _discountValue = 0;
  PaymentMethod _paymentMethod = PaymentMethod.cash;

  /// Set while working on a resumed suspended invoice.
  int? _resumedSaleId;
  String? _resumedInvoiceNumber;

  List<CartLine> get lines => List.unmodifiable(_lines);
  bool get isEmpty => _lines.isEmpty;
  int get lineCount => _lines.length;
  int get unitCount => _lines.fold(0, (sum, l) => sum + l.quantity);

  DiscountType get discountType => _discountType;
  double get discountValue => _discountValue;
  PaymentMethod get paymentMethod => _paymentMethod;
  int? get resumedSaleId => _resumedSaleId;
  String? get invoiceNumber => _resumedInvoiceNumber;

  // ── Totals (in cents to avoid floating-point errors) ────────────────────

  int get _subtotalCents => _lines.fold(0, (sum, l) => sum + l.lineCents);

  int get _discountCents {
    if (_discountValue <= 0) return 0;
    final d = _discountType == DiscountType.percent
        ? (_subtotalCents * _discountValue / 100).round()
        : _cents(_discountValue);
    return d > _subtotalCents ? _subtotalCents : d;
  }

  int get _taxCents =>
      ((_subtotalCents - _discountCents) * taxRate / 100).round();

  double get subtotal => _subtotalCents / 100;
  double get discount => _discountCents / 100;
  double get tax => _taxCents / 100;
  double get total => (_subtotalCents - _discountCents + _taxCents) / 100;

  /// Highest discount amount allowed for the current subtotal.
  double get maxDiscountAmount =>
      (_subtotalCents * maxDiscountPercent / 100).floor() / 100;

  // ── Editing ─────────────────────────────────────────────────────────────

  CartLine? lineFor(String productId) {
    for (final l in _lines) {
      if (l.product.id == productId) return l;
    }
    return null;
  }

  /// Adds one unit of [product], never more than its stock.
  AddResult add(Product product) {
    if (product.quantity <= 0) return AddResult.outOfStock;
    final line = lineFor(product.id);
    if (line == null) {
      _lines.add(CartLine(product, 1));
    } else {
      if (line.quantity >= product.quantity) return AddResult.stockLimit;
      line.quantity++;
    }
    notifyListeners();
    return AddResult.added;
  }

  AddResult increment(String productId) {
    final line = lineFor(productId);
    if (line == null) return AddResult.outOfStock;
    if (line.quantity >= line.product.quantity) return AddResult.stockLimit;
    line.quantity++;
    notifyListeners();
    return AddResult.added;
  }

  /// One unit less; the line disappears at zero.
  void decrement(String productId) {
    final line = lineFor(productId);
    if (line == null) return;
    if (line.quantity <= 1) {
      _lines.remove(line);
    } else {
      line.quantity--;
    }
    notifyListeners();
  }

  /// Removes a single product from the invoice (SR7).
  void remove(String productId) {
    _lines.removeWhere((l) => l.product.id == productId);
    notifyListeners();
  }

  /// Empties the lines but keeps working on the same (resumed) invoice.
  void clearLines() {
    _lines.clear();
    _discountValue = 0;
    notifyListeners();
  }

  /// Starts a fresh invoice: no lines, no discount, cash, not resumed.
  void reset() {
    _lines.clear();
    _discountValue = 0;
    _discountType = DiscountType.amount;
    _paymentMethod = PaymentMethod.cash;
    _resumedSaleId = null;
    _resumedInvoiceNumber = null;
    notifyListeners();
  }

  /// Sets the invoice discount. Returns false (and changes nothing) when it is
  /// negative or above the store's maximum.
  bool setDiscount(DiscountType type, double value) {
    if (value < 0) return false;
    final amountCents = type == DiscountType.percent
        ? (_subtotalCents * value / 100).round()
        : _cents(value);
    if (amountCents > (_subtotalCents * maxDiscountPercent / 100).round()) {
      return false;
    }
    _discountType = type;
    _discountValue = value;
    notifyListeners();
    return true;
  }

  /// Uses the store's tax rate and maximum discount (from GET /api/store).
  void applyStoreSettings({
    required double taxRate,
    required double maxDiscountPercent,
  }) {
    this.taxRate = taxRate;
    this.maxDiscountPercent = maxDiscountPercent;
    notifyListeners();
  }

  void setPaymentMethod(PaymentMethod method) {
    _paymentMethod = method;
    notifyListeners();
  }

  /// Keeps the lines in step with the latest product data (price, stock).
  void refreshProducts(List<Product> products) {
    final byId = {for (final p in products) p.id: p};
    for (final l in _lines) {
      final p = byId[l.product.id];
      if (p != null) l.product = p;
    }
    notifyListeners();
  }

  /// Loads a suspended invoice. Lines use the products' CURRENT data; lines
  /// whose product no longer exists are skipped and returned.
  List<String> resume(Sale sale, List<Product> products) {
    final byId = {for (final p in products) p.id: p};
    _lines.clear();
    final missing = <String>[];
    for (final item in sale.items) {
      final p = item.productId == null ? null : byId['${item.productId}'];
      if (p == null) {
        missing.add(item.productName);
      } else {
        _lines.add(CartLine(p, item.quantity));
      }
    }
    _resumedSaleId = sale.saleId;
    _resumedInvoiceNumber = sale.invoiceNumber;
    _discountType = DiscountType.amount;
    _discountValue = sale.discount;
    notifyListeners();
    return missing;
  }

  /// The invoice as sent to the backend.
  SaleDraft toDraft({bool withPayment = false}) => SaleDraft(
    saleId: _resumedSaleId,
    lines: {for (final l in _lines) l.product.id: l.quantity},
    discountType: _discountType,
    discountValue: _discountValue,
    paymentMethod: withPayment ? _paymentMethod : null,
  );
}

/// Makes the cashier's [PosCart] available to the POS screen. It lives as
/// long as the login, so switching pages does not lose the current invoice.
class PosCartScope extends InheritedNotifier<PosCart> {
  const PosCartScope({super.key, required PosCart cart, required super.child})
    : super(notifier: cart);

  static PosCart of(BuildContext context) {
    final scope = context.dependOnInheritedWidgetOfExactType<PosCartScope>();
    assert(scope != null, 'PosCartScope not found');
    return scope!.notifier!;
  }

  static PosCart read(BuildContext context) {
    final scope = context.getInheritedWidgetOfExactType<PosCartScope>();
    assert(scope != null, 'PosCartScope not found');
    return scope!.notifier!;
  }
}

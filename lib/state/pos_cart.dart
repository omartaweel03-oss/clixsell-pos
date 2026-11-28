import 'package:flutter/widgets.dart';

import '../models/product.dart';
import '../models/product_package.dart';
import '../models/sale.dart';
import '../services/sales_api_client.dart';

/// One product on the current invoice: pieces, or packages (carton / box) of it.
class CartLine {
  CartLine(this.product, this.quantity, {this.package});

  Product product;
  int quantity;

  /// Null = pieces; otherwise [quantity] counts packages (feature 6).
  final ProductPackage? package;

  /// Identifies the line: the product id for pieces (as before), "productId@packageId" for a package.
  String get key => keyFor(product.id, package);

  static String keyFor(String productId, ProductPackage? package) =>
      package == null ? productId : '$productId@${package.packageId}';

  /// Pieces this line takes from stock.
  int get pieces => quantity * (package?.unitsPerPackage ?? 1);

  String get displayName => package == null ? product.name : '${product.name} · ${package!.name}';

  int get unitCents => _cents(package?.price ?? product.price);
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

  /// The resumed invoice as it was loaded (= as saved on the server), to know
  /// whether the cashier has changed it since: product id → quantity, and the
  /// discount.
  Map<String, int>? _savedLines;
  DiscountType _savedDiscountType = DiscountType.amount;
  double _savedDiscountValue = 0;

  List<CartLine> get lines => List.unmodifiable(_lines);
  bool get isEmpty => _lines.isEmpty;
  int get lineCount => _lines.length;
  int get unitCount => _lines.fold(0, (sum, l) => sum + l.quantity);

  /// Package data known to the POS (to resume suspended invoices with cartons).
  List<ProductPackage> packages = const [];

  DiscountType get discountType => _discountType;
  double get discountValue => _discountValue;
  PaymentMethod get paymentMethod => _paymentMethod;
  int? get resumedSaleId => _resumedSaleId;
  String? get invoiceNumber => _resumedInvoiceNumber;

  /// True when the resumed invoice was changed after it was loaded (products,
  /// quantities or discount) and the change is not saved yet. The payment
  /// method is not saved with a suspended invoice, so it does not count.
  bool get hasUnsavedChanges {
    final saved = _savedLines;
    if (_resumedSaleId == null || saved == null) return false;
    final sameDiscount =
        _discountValue == _savedDiscountValue &&
        (_discountValue == 0 || _discountType == _savedDiscountType);
    if (!sameDiscount || saved.length != _lines.length) return true;
    for (final l in _lines) {
      if (saved[l.key] != l.quantity) return true;
    }
    return false;
  }

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

  /// The invoice total in cents (agorot): exact, for the cash change.
  int get totalCents => _subtotalCents - _discountCents + _taxCents;

  /// D4: the store's cash rounding step in agorot (0 = none). Display only: the backend rounds.
  int cashRoundingMinor = 0;

  /// Cash rounding of this invoice (0 unless paying cash in a store that rounds).
  int get roundingCents {
    final step = cashRoundingMinor;
    if (_paymentMethod != PaymentMethod.cash || step <= 0) return 0;
    final t = totalCents, r = t % step, q = t ~/ step;
    return (2 * r >= step ? q + 1 : q) * step - t;
  }

  /// What the customer pays: total + cash rounding.
  int get amountDueCents => totalCents + roundingCents;

  /// Highest discount amount allowed for the current subtotal.
  double get maxDiscountAmount =>
      (_subtotalCents * maxDiscountPercent / 100).floor() / 100;

  // ── Editing ─────────────────────────────────────────────────────────────

  /// The line with this key: the product id (pieces) or "productId@packageId".
  CartLine? lineFor(String key) {
    for (final l in _lines) {
      if (l.key == key) return l;
    }
    return null;
  }

  /// Pieces of a product already on the invoice (all its lines).
  int piecesOf(String productId) => _lines
      .where((l) => l.product.id == productId)
      .fold(0, (sum, l) => sum + l.pieces);

  /// Adds one piece of [product] — or one [package] of it — never more than its stock in pieces.
  AddResult add(Product product, {ProductPackage? package}) {
    if (product.quantity <= 0) return AddResult.outOfStock;
    final units = package?.unitsPerPackage ?? 1;
    if (piecesOf(product.id) + units > product.quantity) return AddResult.stockLimit;
    final line = lineFor(CartLine.keyFor(product.id, package));
    if (line == null) {
      _lines.add(CartLine(product, 1, package: package));
    } else {
      line.quantity++;
    }
    notifyListeners();
    return AddResult.added;
  }

  AddResult increment(String key) {
    final line = lineFor(key);
    if (line == null) return AddResult.outOfStock;
    final units = line.package?.unitsPerPackage ?? 1;
    if (piecesOf(line.product.id) + units > line.product.quantity) return AddResult.stockLimit;
    line.quantity++;
    notifyListeners();
    return AddResult.added;
  }

  /// One unit less; the line disappears at zero.
  void decrement(String key) {
    final line = lineFor(key);
    if (line == null) return;
    if (line.quantity <= 1) {
      _lines.remove(line);
    } else {
      line.quantity--;
    }
    notifyListeners();
  }

  /// Removes a single line from the invoice (SR7).
  void remove(String key) {
    _lines.removeWhere((l) => l.key == key);
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
    _savedLines = null;
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
    int? cashRoundingMinor,
  }) {
    this.taxRate = taxRate;
    this.maxDiscountPercent = maxDiscountPercent;
    if (cashRoundingMinor != null) this.cashRoundingMinor = cashRoundingMinor;
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
  List<String> resume(
    Sale sale,
    List<Product> products, {
    List<ProductPackage> packages = const [],
  }) {
    final byId = {for (final p in products) p.id: p};
    final pkgById = {for (final p in packages) p.packageId: p};
    _lines.clear();
    final missing = <String>[];
    for (final item in sale.items) {
      final p = item.productId == null ? null : byId['${item.productId}'];
      final pkg = item.packageId == null ? null : pkgById[item.packageId];
      if (p == null || (item.packageId != null && (pkg == null || !pkg.active))) {
        missing.add(item.packageName == null ? item.productName : '${item.productName} · ${item.packageName}');
      } else {
        _lines.add(CartLine(p, item.quantity, package: pkg));
      }
    }
    _resumedSaleId = sale.saleId;
    _resumedInvoiceNumber = sale.invoiceNumber;
    _discountType = DiscountType.amount;
    _discountValue = sale.discount;
    _savedLines = {for (final l in _lines) l.key: l.quantity};
    _savedDiscountType = _discountType;
    _savedDiscountValue = _discountValue;
    notifyListeners();
    return missing;
  }

  /// The invoice as sent to the backend.
  SaleDraft toDraft({bool withPayment = false}) => SaleDraft(
    saleId: _resumedSaleId,
    lines: {for (final l in _lines) l.key: l.quantity},
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

  /// The cart, or null for a user without a POS (System Admin).
  static PosCart? maybeRead(BuildContext context) =>
      context.getInheritedWidgetOfExactType<PosCartScope>()?.notifier;

  static PosCart read(BuildContext context) {
    final scope = context.getInheritedWidgetOfExactType<PosCartScope>();
    assert(scope != null, 'PosCartScope not found');
    return scope!.notifier!;
  }
}

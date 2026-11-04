import 'package:clixsell_pos/models/product.dart';
import 'package:clixsell_pos/models/product_category.dart';
import 'package:clixsell_pos/models/sale.dart';
import 'package:clixsell_pos/state/pos_cart.dart';
import 'package:flutter_test/flutter_test.dart';

Product _p(String id, double price, int qty) => Product(
      id: id,
      name: 'P$id',
      barcode: '1000000$id',
      category: ProductCategory.dairy,
      price: price,
      quantity: qty,
      minStockLevel: 1,
      supplier: 'Co',
      createdAt: DateTime(2026),
      updatedAt: DateTime(2026),
    );

void main() {
  late PosCart cart;
  final milk = _p('1', 5, 10);
  final bread = _p('2', 2, 3);

  setUp(() => cart = PosCart()..applyStoreSettings(taxRate: 16, maxDiscountPercent: 10));

  test('adding the same product raises the quantity', () {
    cart.add(milk);
    cart.add(milk);
    expect(cart.lineCount, 1);
    expect(cart.lineFor('1')!.quantity, 2);
    expect(cart.lineFor('1')!.lineTotal, 10.0);
  });

  test('never more than the stock, nothing when out of stock', () {
    for (var i = 0; i < 3; i++) {
      expect(cart.add(bread), AddResult.added);
    }
    expect(cart.add(bread), AddResult.stockLimit);
    expect(cart.increment('2'), AddResult.stockLimit);
    expect(cart.add(_p('3', 1, 0)), AddResult.outOfStock);
    expect(cart.lineFor('2')!.quantity, 3);
  });

  test('totals: subtotal → discount → tax → total (same as the backend)', () {
    cart.add(milk);
    cart.add(milk);           // 10.00
    for (var i = 0; i < 3; i++) {
      cart.add(bread);        //  6.00
    }
    expect(cart.subtotal, 16.0);
    expect(cart.setDiscount(DiscountType.amount, 1), isTrue);
    expect(cart.discount, 1.0);
    expect(cart.tax, 2.4);    // 15.00 × 16%
    expect(cart.total, 17.4);

    expect(cart.setDiscount(DiscountType.percent, 5), isTrue);
    expect(cart.discount, 0.8);
    expect(cart.tax, 2.43);   // 15.20 × 16% = 2.432
    expect(cart.total, 17.63);
  });

  test('discount above the store maximum is refused', () {
    cart.add(milk);
    cart.add(milk);           // 10.00 → max 1.00
    expect(cart.setDiscount(DiscountType.amount, 1.01), isFalse);
    expect(cart.setDiscount(DiscountType.percent, 11), isFalse);
    expect(cart.discount, 0);
  });

  test('decrement removes the line at zero; remove and reset', () {
    cart.add(milk);
    cart.add(bread);
    cart.decrement('1');
    expect(cart.lineFor('1'), isNull);
    cart.remove('2');
    expect(cart.isEmpty, isTrue);
    cart.add(milk);
    cart.setPaymentMethod(PaymentMethod.card);
    cart.reset();
    expect(cart.isEmpty, isTrue);
    expect(cart.paymentMethod, PaymentMethod.cash);
  });

  test('draft sends only product ids and quantities', () {
    cart.add(milk);
    cart.add(milk);
    cart.setDiscount(DiscountType.percent, 5);
    cart.setPaymentMethod(PaymentMethod.wallet);
    expect(cart.toDraft(withPayment: true).toJson(), {
      'saleId': null,
      'items': [
        {'productId': 1, 'quantity': 2},
      ],
      'discountType': 'PERCENT',
      'discountValue': 5.0,
      'paymentMethod': 'WALLET',
    });
    expect(cart.toDraft().toJson()['paymentMethod'], isNull);
  });

  test('resume uses current products and skips deleted ones', () {
    final sale = Sale.fromJson({
      'saleId': 7,
      'invoiceNumber': 'INV-000007',
      'status': 'SUSPENDED',
      'cashierName': 'Ahmad',
      'items': [
        {'saleItemId': 1, 'productId': 1, 'productName': 'P1', 'quantity': 2, 'unitPrice': 4.0,
         'lineTotal': 8.0, 'returnedQuantity': 0, 'returnableQuantity': 2},
        {'saleItemId': 2, 'productId': 99, 'productName': 'Gone', 'quantity': 1, 'unitPrice': 1.0,
         'lineTotal': 1.0, 'returnedQuantity': 0, 'returnableQuantity': 1},
      ],
      'subtotal': 9.0, 'discount': 0.5, 'taxRate': 16, 'tax': 1.36, 'total': 9.86,
      'createdAt': '2026-11-04T10:00:00',
    });
    final missing = cart.resume(sale, [milk, bread]);
    expect(missing, ['Gone']);
    expect(cart.invoiceNumber, 'INV-000007');
    expect(cart.resumedSaleId, 7);
    expect(cart.lineFor('1')!.product.price, 5.0); // current price
    expect(cart.discount, 0.5);
    expect(cart.toDraft().saleId, 7);
  });
}

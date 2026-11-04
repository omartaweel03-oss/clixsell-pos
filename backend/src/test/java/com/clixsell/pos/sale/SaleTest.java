package com.clixsell.pos.sale;

import com.clixsell.pos.common.ConflictException;
import com.clixsell.pos.common.ValidationException;
import com.clixsell.pos.product.Product;
import com.clixsell.pos.product.ProductRequest;
import com.clixsell.pos.store.Store;
import com.clixsell.pos.store.StoreRequest;
import com.clixsell.pos.supplier.Supplier;
import com.clixsell.pos.user.Role;
import com.clixsell.pos.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for the Sale business logic (no database). */
class SaleTest {

    private Store store;
    private User cashier;
    private Product milk;   // 5.00, 10 in stock
    private Product bread;  // 2.00, 3 in stock

    private static Product product(Store store, long id, String name, double price, int qty) {
        Product p = Product.create(new ProductRequest(name, "1000000" + id, "dairy", price, qty, 1,
                "Co", null, null), new Supplier("Co", store), store);
        ReflectionTestUtils.setField(p, "productId", id);
        return p;
    }

    @BeforeEach
    void setUp() {
        store = Store.create(new StoreRequest("Shini", null, null, 16.0, 10.0));
        ReflectionTestUtils.setField(store, "storeId", 1L);
        cashier = User.create(Role.CASHIER, store, "shini.ahmad", "Ahmad", "hash");
        milk = product(store, 1, "Milk", 5, 10);
        bread = product(store, 2, "Bread", 2, 3);
    }

    @Test
    void invoiceNumbersCountPerStore() {
        assertThat(Sale.start(store, cashier).getInvoiceNumber()).isEqualTo("INV-000001");
        assertThat(Sale.start(store, cashier).getInvoiceNumber()).isEqualTo("INV-000002");
    }

    @Test
    void sameProductTwiceRaisesTheQuantity() {
        Sale sale = Sale.start(store, cashier);
        sale.addItem(milk, 1);
        sale.addItem(milk, 2);
        assertThat(sale.getItems()).hasSize(1);
        assertThat(sale.getItems().get(0).getQuantity()).isEqualTo(3);
        assertThat(sale.getItems().get(0).getLineTotal()).isEqualTo(15.0);
    }

    @Test
    void totalsFollowSubtotalDiscountTax() {
        Sale sale = Sale.start(store, cashier);
        sale.addItem(milk, 2);   // 10.00
        sale.addItem(bread, 3);  //  6.00
        sale.calculateTotal(DiscountType.AMOUNT, 1.0);
        assertThat(sale.getSubtotal()).isEqualTo(16.0);
        assertThat(sale.getDiscount()).isEqualTo(1.0);
        assertThat(sale.getTax()).isEqualTo(2.4);       // 15.00 × 16%
        assertThat(sale.getTotal()).isEqualTo(17.4);

        sale.calculateTotal(DiscountType.PERCENT, 5.0);
        assertThat(sale.getDiscount()).isEqualTo(0.8);
        assertThat(sale.getTax()).isEqualTo(2.43);      // 15.20 × 16% = 2.432
        assertThat(sale.getTotal()).isEqualTo(17.63);
    }

    @Test
    void discountAboveTheStoreMaximumIsRefused() {
        Sale sale = Sale.start(store, cashier);
        sale.addItem(milk, 2); // 10.00, max 10% = 1.00
        sale.calculateTotal(DiscountType.AMOUNT, 1.0);
        assertThatThrownBy(() -> sale.calculateTotal(DiscountType.AMOUNT, 1.01))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> sale.calculateTotal(DiscountType.PERCENT, 11.0))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> sale.calculateTotal(DiscountType.AMOUNT, -1.0))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void completingReducesStockRecordsPaymentAndIssuesReceipt() {
        Sale sale = Sale.start(store, cashier);
        sale.addItem(milk, 4);
        sale.calculateTotal(null, null);
        sale.completeSale(PaymentMethod.CARD);
        assertThat(milk.getQuantity()).isEqualTo(6);
        assertThat(sale.getStatus()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(sale.getPaymentMethod()).isEqualTo(PaymentMethod.CARD);
        assertThat(sale.getReceipt().getQrCodeValue()).startsWith("CLIXSELL-").hasSize(25);
        assertThat(sale.getCompletedAt()).isNotNull();
    }

    @Test
    void notEnoughStockChangesNothing() {
        Sale sale = Sale.start(store, cashier);
        sale.addItem(milk, 2);
        sale.addItem(bread, 5); // only 3
        sale.calculateTotal(null, null);
        assertThatThrownBy(() -> sale.completeSale(PaymentMethod.CASH))
                .isInstanceOf(InsufficientStockException.class)
                .satisfies(e -> assertThat(((InsufficientStockException) e).getShortages())
                        .containsOnlyKeys("Bread"));
        assertThat(milk.getQuantity()).isEqualTo(10);
        assertThat(bread.getQuantity()).isEqualTo(3);
        assertThat(sale.getStatus()).isNull();
    }

    @Test
    void emptyInvoiceAndMissingPaymentAreRefused() {
        Sale sale = Sale.start(store, cashier);
        assertThatThrownBy(() -> sale.completeSale(PaymentMethod.CASH)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(sale::suspend).isInstanceOf(ValidationException.class);
        sale.addItem(milk, 1);
        sale.calculateTotal(null, null);
        assertThatThrownBy(() -> sale.completeSale(null)).isInstanceOf(ValidationException.class);
    }

    @Test
    void suspendCancelAndClosedRules() {
        Sale sale = Sale.start(store, cashier);
        sale.addItem(milk, 1);
        sale.calculateTotal(null, null);
        sale.suspend();
        assertThat(sale.getStatus()).isEqualTo(SaleStatus.SUSPENDED);
        sale.cancel();
        assertThat(sale.getStatus()).isEqualTo(SaleStatus.CANCELED);
        assertThatThrownBy(() -> sale.addItem(milk, 1)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(sale::cancel).isInstanceOf(ConflictException.class);
    }

    @Test
    void quantityLimitsAndOtherStoresProducts() {
        Sale sale = Sale.start(store, cashier);
        assertThatThrownBy(() -> sale.addItem(milk, 0)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> sale.addItem(milk, 10_001)).isInstanceOf(ValidationException.class);
        Store other = Store.create(new StoreRequest("Neshan", null, null));
        ReflectionTestUtils.setField(other, "storeId", 2L);
        Product foreign = product(other, 9, "Juice", 8, 5);
        assertThatThrownBy(() -> sale.addItem(foreign, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}

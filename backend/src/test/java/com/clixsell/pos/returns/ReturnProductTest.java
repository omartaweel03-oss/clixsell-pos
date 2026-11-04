package com.clixsell.pos.returns;

import com.clixsell.pos.common.ConflictException;
import com.clixsell.pos.common.ValidationException;
import com.clixsell.pos.product.Product;
import com.clixsell.pos.product.ProductRequest;
import com.clixsell.pos.sale.DiscountType;
import com.clixsell.pos.sale.PaymentMethod;
import com.clixsell.pos.sale.Sale;
import com.clixsell.pos.sale.SaleItem;
import com.clixsell.pos.store.Store;
import com.clixsell.pos.store.StoreRequest;
import com.clixsell.pos.supplier.Supplier;
import com.clixsell.pos.user.Role;
import com.clixsell.pos.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for the return business logic (no database). */
class ReturnProductTest {

    private Product milk;
    private User cashier;
    private Sale sale;
    private SaleItem line;

    @BeforeEach
    void completedSale() {
        Store store = Store.create(new StoreRequest("Shini", null, null, 16.0, 10.0));
        ReflectionTestUtils.setField(store, "storeId", 1L);
        cashier = User.create(Role.CASHIER, store, "shini.ahmad", "Ahmad", "hash");
        milk = Product.create(new ProductRequest("Milk", "11111111", "dairy", 5.0, 10, 1, "Co", null, null),
                new Supplier("Co", store), store);
        ReflectionTestUtils.setField(milk, "productId", 1L);
        sale = Sale.start(store, cashier);
        sale.addItem(milk, 3);                          // 15.00
        sale.calculateTotal(DiscountType.PERCENT, 5.0); // -0.75, +2.28 tax = 16.53
        sale.completeSale(PaymentMethod.CASH);
        line = sale.getItems().get(0);
    }

    @Test
    void refundIsTheShareActuallyPaidAndStockComesBack() {
        ReturnProduct r = ReturnProduct.process(sale, line, 1, "Damaged", cashier, LocalDateTime.now(), 14);
        assertThat(r.getRefundAmount()).isEqualTo(5.51);   // 16.53 / 3
        assertThat(r.getRefundMethod()).isEqualTo(PaymentMethod.CASH);
        assertThat(milk.getQuantity()).isEqualTo(8);        // 10 - 3 + 1
        assertThat(line.getReturnedQuantity()).isEqualTo(1);
        assertThat(line.getReturnableQuantity()).isEqualTo(2);
    }

    @Test
    void cannotReturnMoreThanWasBoughtAndNotYetReturned() {
        ReturnProduct.process(sale, line, 2, "Changed mind", cashier, LocalDateTime.now(), 14);
        assertThatThrownBy(() -> ReturnProduct.process(sale, line, 2, "Again", cashier, LocalDateTime.now(), 14))
                .isInstanceOf(ValidationException.class);
        assertThat(milk.getQuantity()).isEqualTo(9);
    }

    @Test
    void reasonIsRequired() {
        assertThatThrownBy(() -> ReturnProduct.process(sale, line, 1, " ", cashier, LocalDateTime.now(), 14))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void returnPeriodIsEnforced() {
        LocalDateTime later = LocalDateTime.now().plusDays(15);
        assertThatThrownBy(() -> ReturnProduct.process(sale, line, 1, "Late", cashier, later, 14))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("14 days");
    }
}

package com.clixsell.pos.product;

import com.clixsell.pos.store.Store;
import com.clixsell.pos.store.StoreRequest;
import com.clixsell.pos.supplier.Supplier;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for the Product business logic (no database, no API). */
class ProductTest {

    private static final Store STORE = Store.create(new StoreRequest("Shini Supermarket", null, null));
    private static final Supplier SUPPLIER = new Supplier("Dairy Co", STORE);

    private static ProductRequest request(int quantity, int minStock) {
        return new ProductRequest("Milk 1L", "7290012345678", "dairy", 5.0,
                quantity, minStock, "Dairy Co", null, null);
    }

    @Test
    void quantityZeroIsOutOfStockEvenWhenMinimumIsZero() {
        assertThat(Product.create(request(0, 5), SUPPLIER, STORE).getStockStatus()).isEqualTo(StockStatus.OUT_OF_STOCK);
        assertThat(Product.create(request(0, 0), SUPPLIER, STORE).getStockStatus()).isEqualTo(StockStatus.OUT_OF_STOCK);
    }

    @Test
    void quantityAboveZeroAndAtOrBelowMinimumIsLowStock() {
        assertThat(Product.create(request(1, 5), SUPPLIER, STORE).isLowStock()).isTrue();
        assertThat(Product.create(request(5, 5), SUPPLIER, STORE).getStockStatus()).isEqualTo(StockStatus.LOW_STOCK);
    }

    @Test
    void quantityAboveMinimumIsInStock() {
        Product p = Product.create(request(6, 5), SUPPLIER, STORE);
        assertThat(p.getStockStatus()).isEqualTo(StockStatus.IN_STOCK);
        assertThat(p.isLowStock()).isFalse();
        assertThat(p.isOutOfStock()).isFalse();
    }

    @Test
    void stockValueIsPriceTimesQuantity() {
        assertThat(Product.create(request(24, 10), SUPPLIER, STORE).getStockValue()).isEqualTo(120.0);
    }

    @Test
    void createTrimsTextAndSetsTimestamps() {
        Product p = Product.create(new ProductRequest("  Milk  ", " 12345678 ", "dairy", 2.5, 1, 0,
                "Dairy Co", null, null), SUPPLIER, STORE);
        assertThat(p.getName()).isEqualTo("Milk");
        assertThat(p.getBarcode()).isEqualTo("12345678");
        assertThat(p.getCreatedAt()).isNotNull().isEqualTo(p.getUpdatedAt());
    }

    @Test
    void updateStockAddsAndRemovesButNeverGoesBelowZero() {
        Product p = Product.create(request(10, 2), SUPPLIER, STORE);
        p.updateStock(5);
        assertThat(p.getQuantity()).isEqualTo(15);
        p.updateStock(-15);
        assertThat(p.isOutOfStock()).isTrue();
        assertThatThrownBy(() -> p.updateStock(-1)).isInstanceOf(ProductValidationException.class);
        assertThat(p.getQuantity()).isZero();
    }

    @Test
    void validateReportsEveryInvalidField() {
        ProductRequest bad = new ProductRequest("A", "12ab", "cars", 1.234, -1, null, "", null, null);
        assertThatThrownBy(() -> Product.validate(bad))
                .isInstanceOf(ProductValidationException.class)
                .satisfies(e -> assertThat(((ProductValidationException) e).getFieldErrors())
                        .containsOnlyKeys("name", "barcode", "category", "sellingPrice",
                                "quantity", "minStock", "supplierName"));
    }

    @Test
    void validateChecksBarcodeLengthPriceAndImage() {
        assertThatThrownBy(() -> Product.validate(new ProductRequest("Milk", "1234567", "dairy", 5.0, 1, 1,
                "Co", null, null))).isInstanceOf(ProductValidationException.class);
        assertThatThrownBy(() -> Product.validate(new ProductRequest("Milk", "12345678", "dairy", 0.0, 1, 1,
                "Co", null, null))).isInstanceOf(ProductValidationException.class);
        assertThatThrownBy(() -> Product.validate(new ProductRequest("Milk", "12345678", "dairy", 5.0, 1, 1,
                "Co", new byte[]{1, 2, 3}, "notes.txt"))).isInstanceOf(ProductValidationException.class);
        // valid, including a 2-decimal price and a PNG image
        Product.validate(new ProductRequest("Milk", "12345678", "dairy", 5.25, 0, 0,
                "Co", new byte[]{1, 2, 3}, "milk.PNG"));
    }

    @Test
    void updateDetailsReplacesValuesAndCanRemoveImage() {
        Product p = Product.create(new ProductRequest("Milk", "12345678", "dairy", 5.0, 1, 1,
                "Co", new byte[]{1}, "a.png"), SUPPLIER, STORE);
        p.updateDetails(new ProductRequest("Bread", "87654321", "bakery", 2.0, 30, 5,
                "Co", null, null), SUPPLIER);
        assertThat(p.getName()).isEqualTo("Bread");
        assertThat(p.getCategory()).isEqualTo("bakery");
        assertThat(p.getImage()).isNull();
        assertThat(p.getStockStatus()).isEqualTo(StockStatus.IN_STOCK);
    }

    @Test
    void supplierMustBelongToTheProductsStore() {
        Store other = Store.create(new StoreRequest("Neshan Supermarket", null, null));
        Supplier otherSupplier = new Supplier("Dairy Co", other);
        assertThatThrownBy(() -> Product.create(request(1, 1), otherSupplier, STORE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generateBarcodeMakesValidEan13() {
        String code = Product.generateBarcode(new Random(42));
        assertThat(code).hasSize(13).startsWith("729").containsOnlyDigits();
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (code.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        assertThat((10 - sum % 10) % 10).isEqualTo(code.charAt(12) - '0');
    }
}

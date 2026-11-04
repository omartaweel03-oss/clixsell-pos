package com.clixsell.pos.product;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Database access for products. Spring Data generates the SQL:
 * save() → INSERT / UPDATE, delete() → DELETE, find…() → SELECT.
 *
 * <p>Every query is limited to ONE store ({@code store_id = ?}); there is
 * deliberately no method that reads products across stores.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    /** The store's products, newest first. */
    List<Product> findAllByStore_StoreIdOrderByProductIdDesc(Long storeId);

    /** The product only if it belongs to this store. */
    Optional<Product> findByProductIdAndStore_StoreId(Long productId, Long storeId);

    boolean existsByStore_StoreIdAndBarcode(Long storeId, String barcode);

    boolean existsByStore_StoreIdAndBarcodeAndProductIdNot(Long storeId, String barcode, Long productId);
}

package com.clixsell.pos.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** Exact barcode within one store (read only; used by the camera lookup). */
    java.util.Optional<Product> findByStore_StoreIdAndBarcode(Long storeId, String barcode);

    boolean existsByStore_StoreIdAndBarcodeAndProductIdNot(Long storeId, String barcode, Long productId);

    /** Products of one supplier of the store (Supplier Management details). */
    List<Product> findAllByStore_StoreIdAndSupplier_SupplierIdOrderByNameAsc(Long storeId, Long supplierId);

    /** [supplierId, number of products] for every supplier of the store that has products. */
    @Query("SELECT p.supplier.supplierId, COUNT(p) FROM Product p WHERE p.store.storeId = :storeId "
            + "GROUP BY p.supplier.supplierId")
    List<Object[]> countProductsBySupplier(@Param("storeId") Long storeId);
}

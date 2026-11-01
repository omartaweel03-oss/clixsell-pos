package com.clixsell.pos.product;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Database access for products. Spring Data generates the SQL:
 * save() → INSERT / UPDATE, delete() → DELETE, find…() → SELECT.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    /** All products, newest first. */
    List<Product> findAllByOrderByProductIdDesc();

    boolean existsByBarcode(String barcode);

    boolean existsByBarcodeAndProductIdNot(String barcode, Long productId);
}

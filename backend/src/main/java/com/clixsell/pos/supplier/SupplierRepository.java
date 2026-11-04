package com.clixsell.pos.supplier;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Database access for suppliers, always within one store. */
public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    Optional<Supplier> findByStore_StoreIdAndNameIgnoreCase(Long storeId, String name);
}

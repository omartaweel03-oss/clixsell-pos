package com.clixsell.pos.supplier;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Database access for suppliers. */
public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    Optional<Supplier> findByNameIgnoreCase(String name);
}

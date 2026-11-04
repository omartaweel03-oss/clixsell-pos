package com.clixsell.pos.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Database access for stores. */
public interface StoreRepository extends JpaRepository<Store, Long> {

    List<Store> findAllByOrderByStoreIdAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndStoreIdNot(String name, Long storeId);
}

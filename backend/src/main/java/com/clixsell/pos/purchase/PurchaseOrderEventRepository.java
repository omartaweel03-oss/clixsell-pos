package com.clixsell.pos.purchase;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Purchase-order history (append only). */
public interface PurchaseOrderEventRepository extends JpaRepository<PurchaseOrderEvent, Long> {

    List<PurchaseOrderEvent> findByOrderIdAndStoreIdOrderByEventId(Long orderId, Long storeId);
}

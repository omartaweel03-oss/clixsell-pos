package com.clixsell.pos.purchase;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Purchase orders, always read within ONE store. */
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    Optional<PurchaseOrder> findByOrderIdAndStoreId(Long orderId, Long storeId);

    Optional<PurchaseOrder> findByStoreIdAndClientRequestId(Long storeId, String clientRequestId);

    Page<PurchaseOrder> findByStoreIdOrderByOrderIdDesc(Long storeId, Pageable page);

    Page<PurchaseOrder> findByStoreIdAndStatusInOrderByOrderIdDesc(Long storeId, Collection<PurchaseOrderStatus> status,
                                                                   Pageable page);

    List<PurchaseOrder> findByStoreIdAndOrderIdIn(Long storeId, Collection<Long> ids);
}

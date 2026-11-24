package com.clixsell.pos.purchase;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** Purchase-order lines. */
public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItem, Long> {

    List<PurchaseOrderItem> findByOrderIdAndStoreIdOrderByItemId(Long orderId, Long storeId);

    /** [orderId, number of lines] for the given orders of one store. */
    @Query("SELECT i.orderId, COUNT(i) FROM PurchaseOrderItem i WHERE i.storeId = :storeId AND i.orderId IN :ids "
            + "GROUP BY i.orderId")
    List<Object[]> countByOrder(@Param("storeId") Long storeId, @Param("ids") Collection<Long> ids);
}

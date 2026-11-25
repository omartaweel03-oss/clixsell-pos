package com.clixsell.pos.purchase;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Search of the whole store (all pages): order number or the supplier name saved on the
     * order, case-insensitive, {@code pattern} already lower-case with LIKE wildcards escaped
     * by '\\'. Limited to the given states.
     */
    @Query("""
            SELECT o FROM PurchaseOrder o
            WHERE o.storeId = :store AND o.status IN :status
              AND (LOWER(o.orderNumber) LIKE :pattern ESCAPE '\\' OR LOWER(o.supplierName) LIKE :pattern ESCAPE '\\')
            ORDER BY o.orderId DESC""")
    Page<PurchaseOrder> search(@Param("store") Long storeId, @Param("status") Collection<PurchaseOrderStatus> status,
                               @Param("pattern") String pattern, Pageable page);

    List<PurchaseOrder> findByStoreIdAndOrderIdIn(Long storeId, Collection<Long> ids);
}

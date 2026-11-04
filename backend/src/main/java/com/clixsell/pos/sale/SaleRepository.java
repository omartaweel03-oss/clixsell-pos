package com.clixsell.pos.sale;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Database access for sales. Every query is limited to ONE store. */
public interface SaleRepository extends JpaRepository<Sale, Long> {

    Optional<Sale> findBySaleIdAndStore_StoreId(Long saleId, Long storeId);

    List<Sale> findAllByStore_StoreIdAndStatusOrderBySaleIdDesc(Long storeId, SaleStatus status);

    /** A completed sale of the store, found by its invoice number or receipt QR code. */
    @Query("""
            select s from Sale s left join s.receipt r
            where s.store.storeId = :storeId and s.status = com.clixsell.pos.sale.SaleStatus.COMPLETED
              and (upper(s.invoiceNumber) = upper(:code) or r.qrCodeValue = :code)""")
    Optional<Sale> findCompletedByCode(@Param("storeId") Long storeId, @Param("code") String code);
}

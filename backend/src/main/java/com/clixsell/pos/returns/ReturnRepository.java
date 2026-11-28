package com.clixsell.pos.returns;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Database access for returns. */
public interface ReturnRepository extends JpaRepository<ReturnProduct, Long> {

    /** The exact shares already refunded for one invoice line (cash rounding excluded; 0 when nothing). */
    @Query("SELECT coalesce(sum(r.refundAmount - r.roundingMinor / 100.0), 0) FROM ReturnProduct r "
            + "WHERE r.saleItem.saleItemId = :saleItemId")
    double refundedForLine(long saleItemId);

    /** What was already paid back for a whole invoice (0 when nothing). */
    @Query("SELECT coalesce(sum(r.refundAmount), 0) FROM ReturnProduct r WHERE r.sale.saleId = :saleId")
    double refundedForSale(long saleId);
}

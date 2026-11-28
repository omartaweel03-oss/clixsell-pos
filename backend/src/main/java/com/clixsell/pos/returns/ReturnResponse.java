package com.clixsell.pos.returns;

import com.clixsell.pos.sale.PaymentMethod;

import java.time.LocalDateTime;

/** Confirmation of a return (Figure 3.15: "Generate Return Confirmation"). */
public record ReturnResponse(
        Long returnId,
        String invoiceNumber,
        String productName,
        int quantity,
        String reason,
        double refundAmount,
        PaymentMethod refundMethod,
        LocalDateTime returnDate,
        int stillReturnable,
        // D4: cash rounding included in refundAmount (refundAmount − exact share)
        double roundingAdjustment
) {
    public static ReturnResponse from(ReturnProduct r) {
        return new ReturnResponse(r.getReturnId(), r.getSale().getInvoiceNumber(),
                r.getSaleItem().getProductName(), r.getQuantity(), r.getReason(), r.getRefundAmount(),
                r.getRefundMethod(), r.getReturnDate(), r.getSaleItem().getReturnableQuantity(),
                java.math.BigDecimal.valueOf(r.getRoundingMinor(), 2).doubleValue());
    }
}

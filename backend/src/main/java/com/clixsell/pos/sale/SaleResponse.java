package com.clixsell.pos.sale;

import java.time.LocalDateTime;
import java.util.List;

/** An invoice as returned by the API, with its lines, totals and (when completed) receipt. */
public record SaleResponse(
        Long saleId,
        String invoiceNumber,
        SaleStatus status,
        String cashierName,
        List<Item> items,
        int itemCount,
        double subtotal,
        double discount,
        double taxRate,
        double tax,
        double total,
        PaymentMethod paymentMethod,
        String receiptCode,
        LocalDateTime createdAt,
        LocalDateTime completedAt,
        // Only in the answer to a cash sale that sent cashReceived.
        Double cashReceived,
        Double changeDue,
        // D4: cash rounding (amountDue − total) and what the customer pays
        double roundingAdjustment,
        double amountDue
) {
    /** The same answer with the cash handed over and the change to give back. */
    public SaleResponse withCash(double received, double change) {
        return new SaleResponse(saleId, invoiceNumber, status, cashierName, items, itemCount, subtotal,
                discount, taxRate, tax, total, paymentMethod, receiptCode, createdAt, completedAt,
                received, change, roundingAdjustment, amountDue);
    }

    public record Item(Long saleItemId, Long productId, String productName, String barcode,
                       int quantity, double unitPrice, double lineTotal,
                       int returnedQuantity, int returnableQuantity,
                       Long packageId, String packageName, int unitsPerPackage) {
    }

    public static SaleResponse from(Sale s) {
        List<Item> items = s.getItems().stream()
                .map(i -> new Item(i.getSaleItemId(),
                        i.getProduct() == null ? null : i.getProduct().getProductId(),
                        i.getProductName(), i.getBarcode(), i.getQuantity(), i.getUnitPrice(),
                        i.getLineTotal(), i.getReturnedQuantity(), i.getReturnableQuantity(),
                        i.getPackageId(), i.getPackageName(), i.getUnitsPerPackage()))
                .toList();
        return new SaleResponse(s.getSaleId(), s.getInvoiceNumber(), s.getStatus(),
                s.getCashier().getFullName(), items, items.size(), s.getSubtotal(), s.getDiscount(),
                s.getTaxRate(), s.getTax(), s.getTotal(), s.getPaymentMethod(),
                s.getReceipt() == null ? null : s.getReceipt().getQrCodeValue(),
                s.getCreatedAt(), s.getCompletedAt(), null, null,
                java.math.BigDecimal.valueOf(s.getRoundingMinor(), 2).doubleValue(),
                java.math.BigDecimal.valueOf(s.payableMinor(), 2).doubleValue());
    }
}

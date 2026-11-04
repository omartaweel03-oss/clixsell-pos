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
        LocalDateTime completedAt
) {
    public record Item(Long saleItemId, Long productId, String productName, String barcode,
                       int quantity, double unitPrice, double lineTotal,
                       int returnedQuantity, int returnableQuantity) {
    }

    public static SaleResponse from(Sale s) {
        List<Item> items = s.getItems().stream()
                .map(i -> new Item(i.getSaleItemId(),
                        i.getProduct() == null ? null : i.getProduct().getProductId(),
                        i.getProductName(), i.getBarcode(), i.getQuantity(), i.getUnitPrice(),
                        i.getLineTotal(), i.getReturnedQuantity(), i.getReturnableQuantity()))
                .toList();
        return new SaleResponse(s.getSaleId(), s.getInvoiceNumber(), s.getStatus(),
                s.getCashier().getFullName(), items, items.size(), s.getSubtotal(), s.getDiscount(),
                s.getTaxRate(), s.getTax(), s.getTotal(), s.getPaymentMethod(),
                s.getReceipt() == null ? null : s.getReceipt().getQrCodeValue(),
                s.getCreatedAt(), s.getCompletedAt());
    }
}

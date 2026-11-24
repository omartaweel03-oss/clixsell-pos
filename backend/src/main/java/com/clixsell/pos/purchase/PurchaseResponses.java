package com.clixsell.pos.purchase;

import java.time.LocalDate;
import java.util.List;

/** JSON answers of the purchasing endpoints. Times are "yyyy-MM-ddTHH:mm:ss" text. */
public final class PurchaseResponses {

    private PurchaseResponses() {
    }

    /** The open order that already holds a product (it cannot go into another order). */
    public record OpenOrder(long orderId, String orderNumber, String status, int quantity) {
        static OpenOrder of(PurchaseQueries.Reservation r) {
            return r == null ? null : new OpenOrder(r.orderId(), r.orderNumber(), r.status(), r.quantity());
        }
    }

    /**
     * A recent order that may already bring the product. type RECENTLY_CLOSED: closed in ClixSell
     * in the last 14 days, receiving not recorded. type CANCELED_AFTER_CONTACT: canceled in
     * ClixSell after the supplier had been contacted (last 30 days); this does NOT mean the
     * supplier canceled — the supplier may still deliver.
     */
    public record Caution(long orderId, String orderNumber, String type, String at, int quantity) {
        static Caution of(PurchaseQueries.Caution c) {
            return new Caution(c.orderId(), c.orderNumber(), c.type(), c.at(), c.quantity());
        }
    }

    /**
     * One recommended product. available = it can go into a new order now (supplier active, not
     * held by an open order, consistent stock). unavailableReason: SUPPLIER_INACTIVE,
     * IN_OPEN_ORDER or INCONSISTENT_STOCK. explanation states the rule and numbers used.
     */
    public record RecommendationItem(long productId, String name, String barcode, int quantity, int minStock,
                                     String classification, String basis, Integer suggestedQuantity,
                                     Integer targetStock, int observedDays, long netUnits, Double unitsPerDay,
                                     Double daysLeft, LocalDate expectedStockout, Double expectedUnitCost,
                                     boolean available, String unavailableReason, OpenOrder openOrder,
                                     List<Caution> cautions, String explanation) {
    }

    public record SupplierRecommendations(long supplierId, String supplierName, boolean active, boolean hasWhatsapp,
                                          List<RecommendationItem> items) {
    }

    /**
     * GET /api/purchase-recommendations. With Guardian Mode OFF: guardianEnabled false and no
     * suppliers (nothing is calculated). engine is always "RULES": no AI model, no confidence.
     */
    public record Recommendations(boolean guardianEnabled, String engine, String generatedAt, int horizonDays,
                                  int urgentDays, int coverDays, List<SupplierRecommendations> suppliers,
                                  List<RecommendationItem> inconsistent) {
    }

    /** A product of the supplier for a manual order: facts only, never a suggested quantity. */
    public record Candidate(long productId, String name, String barcode, int quantity, int minStock,
                            String stockStatus, Double expectedUnitCost, boolean available, String unavailableReason,
                            OpenOrder openOrder, List<Caution> cautions) {
    }

    public record Candidates(long supplierId, String supplierName, boolean active, boolean hasWhatsapp,
                             List<Candidate> products) {
    }

    public record ItemView(long itemId, Long productId, boolean productDeleted, String productName, String barcode,
                           int quantity, Integer suggestedQuantity, String basis, Double expectedUnitCost,
                           boolean reserved, Object calculation) {
    }

    /**
     * A purchase order. allowedActions lists only what this version can do (Phase 2: EDIT,
     * APPROVE, REJECT, CANCEL). approvalBlockers says why APPROVE would be refused now.
     * expectedTotalCost is null when any line has no recorded cost (unknown, not zero).
     */
    public record OrderView(long orderId, String orderNumber, String status, String source, long supplierId,
                            String supplierName, boolean supplierActive, String note, int version,
                            Person createdBy, String createdAt, Person approvedBy, String approvedAt,
                            Person rejectedBy, String rejectedAt, String rejectionReason, Person canceledBy,
                            String canceledAt, String cancelReason, boolean overdue, Double expectedTotalCost,
                            List<String> allowedActions, List<String> approvalBlockers, List<ItemView> items,
                            boolean replayed) {
    }

    public record Person(long userId, String fullName) {
    }

    public record OrderSummary(long orderId, String orderNumber, String status, String source, long supplierId,
                               String supplierName, int itemCount, Person createdBy, String createdAt,
                               boolean overdue, int version) {
    }

    public record OrderPage(int page, int size, long total, List<OrderSummary> orders) {
    }

    public record EventView(long eventId, String type, String fromStatus, String toStatus, Person user, Object details,
                            String createdAt) {
    }
}

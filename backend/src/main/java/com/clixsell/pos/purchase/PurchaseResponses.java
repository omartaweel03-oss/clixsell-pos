package com.clixsell.pos.purchase;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
     * A recent order that may already bring the product (see PurchaseQueries.cautions).
     * RECENTLY_CLOSED: closed in ClixSell in the last 14 days, receiving not recorded.
     * CANCELED_AFTER_CONTACT: canceled after the manager recorded sending (30 days).
     * CONTACT_UNKNOWN: canceled after the message was prepared, the manager did not know whether
     * it was sent (30 days). DECLARED_NOT_SENT: canceled as "not sent" although the message had
     * been copied or WhatsApp opening requested (30 days). A cancellation in ClixSell does NOT
     * mean the supplier canceled — the supplier may still deliver.
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
     * A purchase order. allowedActions lists what can be done now. approvalBlockers says why
     * APPROVE would be refused now. expectedTotalCost is null when any line has no recorded cost
     * (unknown, not zero); it is never part of the supplier message. message: the stored supplier
     * message (null before it is generated). communication: what the history records (client
     * reports and the manager's manual records — never a verified sending or delivery).
     * messageLength: per language, before the message is generated (P3-D1).
     */
    public record OrderView(long orderId, String orderNumber, String status, String source, long supplierId,
                            String supplierName, boolean supplierActive, String note, int version,
                            Person createdBy, String createdAt, Person approvedBy, String approvedAt,
                            Person rejectedBy, String rejectedAt, String rejectionReason, Person canceledBy,
                            String canceledAt, String cancelReason, boolean overdue, Double expectedTotalCost,
                            List<String> allowedActions, List<String> approvalBlockers, List<ItemView> items,
                            boolean replayed, MessageInfo message, Communication communication,
                            Map<String, WhatsappMessageBuilder.Report> messageLength) {
    }

    /**
     * The stored supplier message. There is no link here: a WhatsApp link is only returned by a
     * confirmed opening request, and only while the order is not final (actionable).
     * numberChangedSinceGeneration: the supplier's number changed after the message was prepared;
     * the message stays bound to whatsappNumber (it is never redirected).
     */
    public record MessageInfo(String text, String language, String whatsappNumber, String whatsappDisplay, int length,
                              int maxLength, Person generatedBy, String generatedAt, boolean actionable,
                              boolean numberChangedSinceGeneration, String currentSupplierWhatsapp, String notice) {
    }

    /**
     * copyReports / openRequests / clientOpenReports are reports from the app (not verified).
     * sentRecordedByManager, confirmation…, closed…: manual records by a Store Admin. ClixSell
     * never knows whether a message was delivered. possibleSupplierContact: a canceled order whose
     * supplier may have the order (reorder warnings until a supplier acceptance is recorded).
     */
    public record Communication(int copyReports, int openRequests, int clientOpenReports, boolean sentRecordedByManager,
                                Person sentMarkedBy, String sentMarkedAt, Person confirmationRecordedBy,
                                String confirmationRecordedAt, String confirmationNote, Person closedBy, String closedAt,
                                boolean canceledAfterContact, String contactStatusAtCancel,
                                boolean possibleSupplierContact, boolean supplierCancellationRecorded,
                                boolean supplierCancellationRecordable) {
        Communication withPeople(PurchaseOrder o, Map<Long, String> names) {
            return new Communication(copyReports, openRequests, clientOpenReports, sentRecordedByManager,
                    PurchaseOrderService.person(o.getSentMarkedBy(), names), sentMarkedAt,
                    PurchaseOrderService.person(o.getConfirmationRecordedBy(), names), confirmationRecordedAt,
                    confirmationNote, PurchaseOrderService.person(o.getClosedBy(), names), closedAt, canceledAfterContact,
                    contactStatusAtCancel, possibleSupplierContact, supplierCancellationRecorded,
                    supplierCancellationRecordable);
        }
    }

    /** GET …/message-preview: computed now, nothing saved. Generate with the same previewToken. */
    public record MessagePreview(long orderId, int version, String language, String text, String supplierName,
                                 String whatsappNumber, String whatsappDisplay, WhatsappMessageBuilder.Report length,
                                 Map<String, WhatsappMessageBuilder.Report> lengthByLanguage, String previewToken,
                                 String notice) {
    }

    /**
     * The answer to a client report (copy, opening request, opening result). recordedAs says what
     * was recorded; verified is always false. duplicate: the same clientActionId was already
     * recorded (nothing new saved). whatsappUrl only for a confirmed opening request of a
     * non-final order.
     */
    public record ClientActionResult(long orderId, long eventId, String eventType, String phase, String recordedAs,
                                     boolean verified, boolean duplicate, String whatsappUrl, String whatsappNumber,
                                     String notice) {
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

package com.clixsell.pos.purchase;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

/**
 * A purchase order (table purchase_orders, V6). Identity columns are never updated
 * (the database also refuses it); the version rises by exactly one on every update
 * (optimistic locking, also enforced by a trigger). Phase 3 columns (message, sent,
 * confirmation, close) are read only here: this version never writes them.
 */
@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder {

    public enum Source { GUARDIAN, MANUAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "store_id", nullable = false, updatable = false)
    private Long storeId;

    @Column(name = "order_number", nullable = false, updatable = false)
    private String orderNumber;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private Long supplierId;

    @Column(name = "supplier_name", nullable = false, updatable = false)
    private String supplierName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PurchaseOrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Source source;

    private String note;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Convert(converter = PurchaseTime.class)
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Convert(converter = PurchaseTime.class)
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejected_by")
    private Long rejectedBy;

    @Convert(converter = PurchaseTime.class)
    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "canceled_by")
    private Long canceledBy;

    @Convert(converter = PurchaseTime.class)
    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "canceled_after_contact", nullable = false)
    private boolean canceledAfterContact;

    // ── Phase 3 columns: read only in this version ──
    @Column(name = "message_generated_at", insertable = false, updatable = false)
    private String messageGeneratedAt;

    @Column(name = "sent_marked_at", insertable = false, updatable = false)
    private String sentMarkedAt;

    @Column(name = "confirmation_recorded_at", insertable = false, updatable = false)
    private String confirmationRecordedAt;

    @Column(name = "closed_at", insertable = false, updatable = false)
    private String closedAt;

    @Column(name = "client_request_id", nullable = false, updatable = false)
    private String clientRequestId;

    @Column(name = "request_hash", nullable = false, updatable = false)
    private String requestHash;

    @Version
    @Column(nullable = false)
    private int version;

    protected PurchaseOrder() {
    }

    PurchaseOrder(long storeId, String orderNumber, long supplierId, String supplierName, Source source, String note,
                  long createdBy, LocalDateTime createdAt, String clientRequestId, String requestHash) {
        this.storeId = storeId;
        this.orderNumber = orderNumber;
        this.supplierId = supplierId;
        this.supplierName = supplierName;
        this.status = PurchaseOrderStatus.PENDING_APPROVAL;
        this.source = source;
        this.note = note;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.clientRequestId = clientRequestId;
        this.requestHash = requestHash;
    }

    void setNote(String note) {
        this.note = note;
    }

    void approve(long userId, LocalDateTime at) {
        status = PurchaseOrderStatus.APPROVED;
        approvedBy = userId;
        approvedAt = at;
    }

    void reject(long userId, LocalDateTime at, String reason) {
        status = PurchaseOrderStatus.REJECTED;
        rejectedBy = userId;
        rejectedAt = at;
        rejectionReason = reason;
    }

    /** Phase 2 cancels only before any supplier contact (canceled_after_contact stays false). */
    void cancel(long userId, LocalDateTime at, String reason) {
        status = PurchaseOrderStatus.CANCELED;
        canceledBy = userId;
        canceledAt = at;
        cancelReason = reason;
    }

    public Long getOrderId() { return orderId; }
    public Long getStoreId() { return storeId; }
    public String getOrderNumber() { return orderNumber; }
    public Long getSupplierId() { return supplierId; }
    public String getSupplierName() { return supplierName; }
    public PurchaseOrderStatus getStatus() { return status; }
    public Source getSource() { return source; }
    public String getNote() { return note; }
    public Long getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public Long getApprovedBy() { return approvedBy; }
    public LocalDateTime getApprovedAt() { return approvedAt; }
    public Long getRejectedBy() { return rejectedBy; }
    public LocalDateTime getRejectedAt() { return rejectedAt; }
    public String getRejectionReason() { return rejectionReason; }
    public Long getCanceledBy() { return canceledBy; }
    public LocalDateTime getCanceledAt() { return canceledAt; }
    public String getCancelReason() { return cancelReason; }
    public boolean isCanceledAfterContact() { return canceledAfterContact; }
    public String getClientRequestId() { return clientRequestId; }
    public String getRequestHash() { return requestHash; }
    public int getVersion() { return version; }
}

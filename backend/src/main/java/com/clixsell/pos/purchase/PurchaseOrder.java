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
 * (optimistic locking, also enforced by a trigger). The supplier message (text, number,
 * language) and the manager's manual records of sending, supplier confirmation and closing are
 * written once by their own transition and never changed (the database refuses it, T7).
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

    // ── Phase 3: the supplier message and the manager's manual records (each set once; T7) ──
    @Column(name = "message_text")
    private String messageText;

    @Column(name = "message_language")
    private String messageLanguage;

    @Column(name = "message_whatsapp")
    private String messageWhatsapp;

    @Column(name = "message_generated_by")
    private Long messageGeneratedBy;

    @Convert(converter = PurchaseTime.class)
    @Column(name = "message_generated_at")
    private LocalDateTime messageGeneratedAt;

    @Column(name = "sent_marked_by")
    private Long sentMarkedBy;

    @Convert(converter = PurchaseTime.class)
    @Column(name = "sent_marked_at")
    private LocalDateTime sentMarkedAt;

    @Column(name = "confirmation_recorded_by")
    private Long confirmationRecordedBy;

    @Convert(converter = PurchaseTime.class)
    @Column(name = "confirmation_recorded_at")
    private LocalDateTime confirmationRecordedAt;

    @Column(name = "confirmation_note")
    private String confirmationNote;

    @Column(name = "closed_by")
    private Long closedBy;

    @Convert(converter = PurchaseTime.class)
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

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

    /**
     * CANCELED. afterContact must be true exactly when the order was SENT or CONFIRMED (the
     * manager had recorded sending); the database checks the same rule (T6).
     */
    void cancel(long userId, LocalDateTime at, String reason, boolean afterContact) {
        status = PurchaseOrderStatus.CANCELED;
        canceledBy = userId;
        canceledAt = at;
        cancelReason = reason;
        canceledAfterContact = afterContact;
    }

    /** APPROVED → MESSAGE_GENERATED: the message is prepared and stored (not sent). */
    void generateMessage(long userId, LocalDateTime at, String text, String language, String whatsapp) {
        status = PurchaseOrderStatus.MESSAGE_GENERATED;
        messageText = text;
        messageLanguage = language;
        messageWhatsapp = whatsapp;
        messageGeneratedBy = userId;
        messageGeneratedAt = at;
    }

    /** MESSAGE_GENERATED → SENT: the manager records that they sent it (ClixSell cannot know). */
    void markSent(long userId, LocalDateTime at) {
        status = PurchaseOrderStatus.SENT;
        sentMarkedBy = userId;
        sentMarkedAt = at;
    }

    /** SENT → CONFIRMED: the manager records that the supplier confirmed. */
    void recordSupplierConfirmation(long userId, LocalDateTime at, String note) {
        status = PurchaseOrderStatus.CONFIRMED;
        confirmationRecordedBy = userId;
        confirmationRecordedAt = at;
        confirmationNote = note;
    }

    /** CONFIRMED → CLOSED: the communication ends. Not receiving: stock does not change. */
    void close(long userId, LocalDateTime at) {
        status = PurchaseOrderStatus.CLOSED;
        closedBy = userId;
        closedAt = at;
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
    public String getMessageText() { return messageText; }
    public String getMessageLanguage() { return messageLanguage; }
    public String getMessageWhatsapp() { return messageWhatsapp; }
    public Long getMessageGeneratedBy() { return messageGeneratedBy; }
    public LocalDateTime getMessageGeneratedAt() { return messageGeneratedAt; }
    public Long getSentMarkedBy() { return sentMarkedBy; }
    public LocalDateTime getSentMarkedAt() { return sentMarkedAt; }
    public Long getConfirmationRecordedBy() { return confirmationRecordedBy; }
    public LocalDateTime getConfirmationRecordedAt() { return confirmationRecordedAt; }
    public String getConfirmationNote() { return confirmationNote; }
    public Long getClosedBy() { return closedBy; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public String getClientRequestId() { return clientRequestId; }
    public String getRequestHash() { return requestHash; }
    public int getVersion() { return version; }
}

package com.clixsell.pos.purchase;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/** One entry of a purchase order's history (append only; the database refuses updates and deletes). */
@Entity
@Immutable
@Table(name = "purchase_order_events")
public class PurchaseOrderEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "from_status")
    private String fromStatus;

    @Column(name = "to_status")
    private String toStatus;

    private String details;

    @Convert(converter = PurchaseTime.class)
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected PurchaseOrderEvent() {
    }

    PurchaseOrderEvent(long orderId, long storeId, long userId, String eventType, PurchaseOrderStatus from,
                       PurchaseOrderStatus to, String details, LocalDateTime createdAt) {
        this.orderId = orderId;
        this.storeId = storeId;
        this.userId = userId;
        this.eventType = eventType;
        this.fromStatus = from == null ? null : from.name();
        this.toStatus = to == null ? null : to.name();
        this.details = details;
        this.createdAt = createdAt;
    }

    public Long getEventId() { return eventId; }
    public Long getOrderId() { return orderId; }
    public Long getUserId() { return userId; }
    public String getEventType() { return eventType; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getDetails() { return details; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

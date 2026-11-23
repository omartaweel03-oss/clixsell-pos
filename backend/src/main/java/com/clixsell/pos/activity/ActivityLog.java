package com.clixsell.pos.activity;

import com.clixsell.pos.common.LocalDateTimeTextConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One row of the activity log (table activity_log, migration V5). Rows are only
 * ever inserted: there is no update or delete anywhere in the application.
 * The store and the user always come from the logged-in session.
 */
@Entity
@Table(name = "activity_log")
public class ActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @Column(name = "store_id", nullable = false, updatable = false)
    private long storeId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false)
    private ActivityEventType eventType;

    @Column(name = "entity_type", updatable = false)
    private String entityType;

    @Column(name = "entity_id", updatable = false)
    private Long entityId;

    /** Name at the time of the event (the product may be deleted later). */
    @Column(name = "entity_name", updatable = false)
    private String entityName;

    /** JSON with the event's numbers, e.g. old and new price. */
    @Column(name = "details", updatable = false)
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Convert(converter = LocalDateTimeTextConverter.class)
    private LocalDateTime createdAt;

    protected ActivityLog() {
    }

    ActivityLog(long storeId, long userId, ActivityEventType eventType, String entityType, Long entityId,
                String entityName, String details, LocalDateTime createdAt) {
        this.storeId = storeId;
        this.userId = userId;
        this.eventType = eventType;
        this.entityType = entityType;
        this.entityId = entityId;
        this.entityName = entityName;
        this.details = details;
        this.createdAt = createdAt;
    }

    public Long getLogId() { return logId; }
    public long getStoreId() { return storeId; }
    public long getUserId() { return userId; }
    public ActivityEventType getEventType() { return eventType; }
    public String getEntityType() { return entityType; }
    public Long getEntityId() { return entityId; }
    public String getEntityName() { return entityName; }
    public String getDetails() { return details; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

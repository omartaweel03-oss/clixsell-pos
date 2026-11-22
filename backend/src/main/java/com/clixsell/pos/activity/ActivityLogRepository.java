package com.clixsell.pos.activity;

import org.springframework.data.jpa.repository.JpaRepository;

/** Insert only: the application never updates or deletes activity log rows. */
public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {
}

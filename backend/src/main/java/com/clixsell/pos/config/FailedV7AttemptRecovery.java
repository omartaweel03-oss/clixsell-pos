package com.clixsell.pos.config;

import db.migration.V7__store_operations;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * Lets the application start again after a FAILED, fully rolled-back V7 attempt.
 *
 * <p>V7 cannot run inside Flyway's transaction (its backup uses VACUUM INTO), so it manages its own and
 * rolls back completely on any error — but Flyway still writes a "failed version 7" row, and that row alone
 * blocks every later start until someone runs a manual repair. Before migrating, this removes ONLY that row,
 * and ONLY when {@link V7__store_operations#failedAttemptLeftNoChanges} proves the database holds none of
 * V7's changes. V7 then runs again from the start: pre-flight checks, a new checked backup, one transaction.
 * In every other case nothing is touched and Flyway behaves exactly as before.
 */
@Configuration
public class FailedV7AttemptRecovery {

    private static final Logger log = LoggerFactory.getLogger(FailedV7AttemptRecovery.class);

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            clearRolledBackAttempt(flyway);
            flyway.migrate();
        };
    }

    /** @return true when a failed V7 history row was removed */
    public static boolean clearRolledBackAttempt(Flyway flyway) {
        String table = flyway.getConfiguration().getTable();
        try (Connection c = flyway.getConfiguration().getDataSource().getConnection()) {
            if (!V7__store_operations.failedAttemptLeftNoChanges(c, table)) return false;
            try (PreparedStatement s = c.prepareStatement(
                    "DELETE FROM \"" + table.replace("\"", "\"\"") + "\" WHERE version = '7' AND success = 0")) {
                int n = s.executeUpdate();
                log.warn("A failed V7 migration attempt was rolled back completely (no V7 change in the database); "
                        + "removed its failed history entry ({} row) so V7 can run again with a new backup", n);
                return n > 0;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not check the schema history for a failed V7 attempt: " + e.getMessage(), e);
        }
    }
}

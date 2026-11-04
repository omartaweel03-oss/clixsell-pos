package com.clixsell.pos;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Upgrading a database created by the previous (single-store) version:
 * nothing may be lost, and everything must end up in "Default Store".
 */
class MigrationTest {

    private static final Path DB = Path.of("target/migration-test.db");
    private static final String URL = "jdbc:sqlite:" + DB + "?foreign_keys=on";

    @AfterEach
    void removeFiles() throws IOException {
        Files.deleteIfExists(DB);
        try (DirectoryStream<Path> backups = Files.newDirectoryStream(Path.of("."), "clixsell-backup-before-v2-*.db")) {
            for (Path p : backups) Files.delete(p);
        }
    }

    private static void migrate() {
        Flyway.configure().dataSource(URL, null, null)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1")
                .load().migrate();
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    @Test
    void oldSingleStoreDatabaseIsMovedToDefaultStoreWithoutLosingData() throws Exception {
        Files.deleteIfExists(DB);
        // A database exactly as the previous version created it.
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            for (String sql : Files.readString(Path.of("src/main/resources/db/migration/V1__initial_schema.sql"))
                    .replaceAll("(?m)^--.*$", "").split(";")) {
                if (!sql.isBlank()) s.execute(sql);
            }
            s.execute("INSERT INTO suppliers (supplier_id, name) VALUES (1, 'Dairy Co'), (2, 'Bakery Ltd')");
            s.execute("""
                    INSERT INTO products (product_id, supplier_id, name, barcode, category, selling_price,
                                          quantity, min_stock, image, image_name, created_at, updated_at)
                    VALUES (1, 1, 'Milk', '11111111', 'dairy', 5, 24, 10, X'89504E47', 'milk.png',
                            '2026-10-01T10:00:00', '2026-10-01T10:00:00'),
                           (2, 2, 'Bread', '22222222', 'bakery', 2, 3, 10, NULL, NULL,
                            '2026-10-01T10:00:00', '2026-10-01T10:00:00')""");
        }

        migrate();

        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            assertThat(count(c, "SELECT COUNT(*) FROM stores WHERE store_id = 1 AND name = 'Default Store'")).isEqualTo(1);
            assertThat(count(c, "SELECT COUNT(*) FROM products WHERE store_id = 1")).isEqualTo(2);
            assertThat(count(c, "SELECT COUNT(*) FROM suppliers WHERE store_id = 1")).isEqualTo(2);
            // ids, links and the image are kept
            assertThat(count(c, "SELECT supplier_id FROM products WHERE product_id = 2")).isEqualTo(2);
            assertThat(count(c, "SELECT length(image) FROM products WHERE product_id = 1")).isEqualTo(4);
            assertThat(count(c, "SELECT COUNT(*) FROM users")).isZero();

            // Barcodes are now unique per store, not globally.
            s.execute("INSERT INTO stores (store_id, name, status, created_at) VALUES (2, 'Neshan', 'ACTIVE', '2026-11-01T00:00:00')");
            s.execute("INSERT INTO suppliers (supplier_id, store_id, name) VALUES (3, 2, 'Dairy Co')");
            s.execute("INSERT INTO products (store_id, supplier_id, name, barcode, category, selling_price, quantity, "
                    + "min_stock, created_at, updated_at) VALUES (2, 3, 'Milk', '11111111', 'dairy', 6, 1, 0, 'x', 'x')");
            assertThatThrownBy(() -> s.execute("INSERT INTO products (store_id, supplier_id, name, barcode, category, "
                    + "selling_price, quantity, min_stock, created_at, updated_at) "
                    + "VALUES (1, 1, 'Copy', '11111111', 'dairy', 1, 1, 0, 'x', 'x')"))
                    .hasMessageContaining("UNIQUE");
            // Foreign keys are enforced.
            assertThatThrownBy(() -> s.execute("INSERT INTO products (store_id, supplier_id, name, barcode, category, "
                    + "selling_price, quantity, min_stock, created_at, updated_at) "
                    + "VALUES (99, 1, 'Ghost', '99999999', 'dairy', 1, 1, 0, 'x', 'x')"))
                    .hasMessageContaining("FOREIGN KEY");
        }

        try (DirectoryStream<Path> backups = Files.newDirectoryStream(Path.of("."), "clixsell-backup-before-v2-*.db")) {
            assertThat(backups.iterator().hasNext()).as("backup file written before migrating").isTrue();
        }
    }

    @Test
    void newEmptyDatabaseGetsAllTablesAndNoDefaultStore() throws Exception {
        Files.deleteIfExists(DB);
        migrate();
        try (Connection c = DriverManager.getConnection(URL)) {
            assertThat(count(c, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' "
                    + "AND name IN ('stores', 'users', 'user_sessions', 'products', 'suppliers')")).isEqualTo(5);
            assertThat(count(c, "SELECT COUNT(*) FROM stores")).isZero();
        }
    }
}

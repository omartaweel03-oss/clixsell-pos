package com.clixsell.pos.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A3: a Settings backup of a database in WAL mode (e.g. after another tool opened it) contains
 * the committed rows that are still only in the -wal file, passes the integrity check, leaves
 * the source database and its WAL as they were, and never overwrites an existing backup with
 * the same name. Everything happens in this test's own temporary folder.
 */
public class BackupWalSnapshotTest {

    @TempDir
    Path work;

    private static long count(Path db, String table) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:file:" + db + "?mode=ro");
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    @Test
    void aWalDatabaseIsBackedUpCompletelyWithoutTouchingTheSourceOrOlderBackups() throws Exception {
        Path db = work.resolve("shop.db");
        Path backups = Files.createDirectory(work.resolve("backups"));
        try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            Statement s = writer.createStatement();
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA wal_autocheckpoint=0"); // keep the committed rows in the -wal file
            for (String t : new String[]{"stores", "users", "products", "sales", "returns"}) {
                s.execute("CREATE TABLE " + t + " (id INTEGER PRIMARY KEY, name TEXT)");
            }
            for (int i = 0; i < 250; i++) s.execute("INSERT INTO products (name) VALUES ('item " + i + "')");
            s.execute("INSERT INTO stores (name) VALUES ('Shini')");
            Path wal = work.resolve("shop.db-wal");
            assertThat(wal).exists();
            assertThat(Files.size(wal)).as("committed rows are still in the WAL").isPositive();

            // older backups with the names this second (and the next ones) would get
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Map<Path, byte[]> older = new LinkedHashMap<>();
            for (String name : new String[]{"clixsell-backup-" + stamp + ".db", "clixsell-backup-" + stamp + "-2.db"}) {
                byte[] content = ("older backup " + name).getBytes(StandardCharsets.UTF_8);
                older.put(Files.write(backups.resolve(name), content), content);
            }
            long walBefore = Files.size(wal);

            SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite:" + db, true);
            try {
                BackupInfo b = new BackupService(new JdbcTemplate(ds), backups.toString()).create();
                assertThat(b.integrityCheck()).isEqualTo("ok");
                assertThat(b.rowCounts()).containsEntry("products", 250L).containsEntry("stores", 1L);
                Path file = backups.resolve(b.fileName());
                assertThat(older).doesNotContainKey(file);
                assertThat(count(file, "products")).isEqualTo(250);
                assertThat(file.getFileName().toString()).matches("clixsell-backup-\\d{8}-\\d{6}(-\\d+)?\\.db");
            } finally {
                ds.destroy();
            }
            for (var e : older.entrySet()) {
                assertThat(Files.readAllBytes(e.getKey())).as("older backup unchanged: %s", e.getKey()).isEqualTo(e.getValue());
            }
            assertThat(count(db, "products")).as("the source still has every row").isEqualTo(250);
            assertThat(Files.size(wal)).as("the backup did not rewrite the source WAL").isGreaterThanOrEqualTo(walBefore);
        }
    }
}

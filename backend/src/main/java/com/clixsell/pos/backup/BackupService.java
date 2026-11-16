package com.clixsell.pos.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Database backups for the System Admin (Settings → Backup, report Figure 3.9 / SR25).
 *
 * <p>A backup is a complete, consistent copy of the SQLite database made with
 * {@code VACUUM INTO} (the same way the V4 migration backs up), written to the
 * backup folder ({@code clixsell.backup.dir}, default {@code backups} next to
 * the database). The new file is then opened read-only and checked with
 * {@code PRAGMA integrity_check}; a file that fails is deleted.
 *
 * <p>Safety: file names are made by the server only (date and time), the API
 * never takes a file name or path (no download, delete or restore), and only
 * files named like a backup are listed. The active database is never replaced:
 * restoring is a manual step (see INVENTORY_MODULE.md).
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    static final Pattern BACKUP_NAME = Pattern.compile("clixsell-backup-\\d{8}-\\d{6}(-\\d{1,3})?\\.db");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** Tables counted in the new backup, to show it holds the data. */
    private static final List<String> COUNTED_TABLES = List.of("stores", "users", "products", "sales", "returns");

    private final JdbcTemplate jdbc;
    private final Path folder;

    public BackupService(JdbcTemplate jdbc, @Value("${clixsell.backup.dir:backups}") String folder) {
        this.jdbc = jdbc;
        this.folder = Path.of(folder).toAbsolutePath().normalize();
    }

    /** Creates a new backup and checks it. One at a time. */
    public synchronized BackupInfo create() {
        Path dir = prepareFolder();
        String base = "clixsell-backup-" + LocalDateTime.now().format(STAMP);
        Path file = dir.resolve(base + ".db");
        for (int n = 2; Files.exists(file, LinkOption.NOFOLLOW_LINKS); n++) {
            file = dir.resolve(base + "-" + n + ".db");
        }
        // The path is made only from the folder and the time above, never from a request.
        // VACUUM INTO cannot run inside a transaction: this runs outside one.
        jdbc.execute("VACUUM INTO '" + file.toString().replace("'", "''") + "'");
        ownerOnly(file, "rw-------");

        Map<String, Long> rows = new LinkedHashMap<>();
        String integrity = check(file, rows);
        if (!"ok".equals(integrity)) {
            delete(file);
            throw new IllegalStateException("The backup failed the integrity check and was removed");
        }
        log.info("Database backup created: {}", file.getFileName());
        BackupInfo saved = describe(file);
        return new BackupInfo(saved.fileName(), saved.sizeBytes(), saved.createdAt(), integrity, rows);
    }

    /** The backups in the folder, newest first (only files named like a backup). */
    public BackupList list() {
        if (!Files.isDirectory(folder)) return new BackupList(folder.toString(), List.of());
        try (Stream<Path> files = Files.list(folder)) {
            List<BackupInfo> backups = files
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> BACKUP_NAME.matcher(p.getFileName().toString()).matches())
                    .map(this::describe)
                    .sorted(NEWEST_FIRST)
                    .toList();
            return new BackupList(folder.toString(), backups);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** By the time in the name, then the counter of backups made in the same second (-2, -3, …). */
    private static final Comparator<BackupInfo> NEWEST_FIRST = Comparator
            .comparing((BackupInfo b) -> b.fileName().substring(16, 31))
            .thenComparingInt(b -> sameSecondNumber(b.fileName()))
            .reversed();

    private static int sameSecondNumber(String name) {
        String rest = name.substring(31, name.length() - 3); // "" or "-2"
        return rest.isEmpty() ? 1 : Integer.parseInt(rest.substring(1));
    }

    private Path prepareFolder() {
        try {
            if (Files.isSymbolicLink(folder)) {
                throw new IllegalStateException("The backup folder must not be a link");
            }
            Files.createDirectories(folder);
            ownerOnly(folder, "rwx------");
            return folder;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Opens the backup read-only: PRAGMA integrity_check and row counts. */
    private static String check(Path file, Map<String, Long> rows) {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        try (Connection c = config.createConnection("jdbc:sqlite:" + file);
             Statement st = c.createStatement()) {
            String result;
            try (ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
                result = rs.next() ? rs.getString(1) : "no result";
            }
            for (String table : COUNTED_TABLES) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    rows.put(table, rs.next() ? rs.getLong(1) : 0L);
                }
            }
            return result;
        } catch (SQLException e) {
            return "error: " + e.getMessage();
        }
    }

    private BackupInfo describe(Path file) {
        try {
            LocalDateTime created = LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault()).withNano(0);
            return new BackupInfo(file.getFileName().toString(), Files.size(file), created, null, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Only the server's user may read the backups (where the system supports it). */
    private static void ownerOnly(Path path, String permissions) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows: the folder keeps the permissions of the backend folder.
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not remove the failed backup {}", file.getFileName());
        }
    }
}

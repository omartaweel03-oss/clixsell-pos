package com.clixsell.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Settings → Backup (report Figure 3.9, SR25): System Admin only.
 * POST /api/admin/backups creates a checked copy of the database;
 * GET /api/admin/backups lists the backup files.
 */
class BackupApiTest extends ApiTestBase {

    private static final Path FOLDER = Path.of("target/test-backups");

    private StoreSetup shini;
    private StoreSetup neshan;

    @BeforeEach
    void data() throws Exception {
        if (Files.exists(FOLDER)) {
            try (Stream<Path> files = Files.walk(FOLDER)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
        shini = setupStore("Shini Supermarket", "shini");
        neshan = setupStore("Neshan Market", "neshan");
        long milk = createProduct(shini.adminToken(), "Milk", "11111111", 5.00, 10);
        createProduct(neshan.adminToken(), "Juice", "22222222", 8.00, 5);
        mvc.perform(post("/api/sales").header("Authorization", bearer(shini.cashierToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":%d,\"quantity\":2}],\"paymentMethod\":\"CASH\"}".formatted(milk)))
                .andExpect(status().isCreated());
    }

    private long count(String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static long countIn(Path file, String table) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static List<Path> backupFiles() throws IOException {
        if (!Files.exists(FOLDER)) return List.of();
        try (Stream<Path> files = Files.list(FOLDER)) {
            return files.toList();
        }
    }

    @Test
    void systemAdminCreatesACheckedCompleteBackup() throws Exception {
        long products = count("products");
        long sales = count("sales");
        long users = count("users");
        String sys = sysadminToken();

        MvcResult r = mvc.perform(post("/api/admin/backups").header("Authorization", bearer(sys)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value(org.hamcrest.Matchers.matchesPattern(
                        "clixsell-backup-\\d{8}-\\d{6}(-\\d+)?\\.db")))
                .andExpect(jsonPath("$.sizeBytes").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.integrityCheck").value("ok"))
                .andExpect(jsonPath("$.rowCounts.stores").value(2))
                .andExpect(jsonPath("$.rowCounts.products").value(products))
                .andExpect(jsonPath("$.rowCounts.sales").value(sales))
                .andExpect(jsonPath("$.rowCounts.users").value(users))
                .andReturn();

        Path file = FOLDER.resolve(jsonString(r, "fileName"));
        assertThat(file).exists();
        // The copy is a complete SQLite database with both stores' data.
        assertThat(countIn(file, "products")).isEqualTo(products);
        assertThat(countIn(file, "sales")).isEqualTo(sales);
        assertThat(countIn(file, "sale_items")).isEqualTo(count("sale_items"));
        assertThat(countIn(file, "flyway_schema_history")).isGreaterThan(0);
        // Only the server's user may read it (POSIX systems).
        if (FOLDER.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(file))
                    .isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
        // The active database is not changed by a backup.
        assertThat(count("products")).isEqualTo(products);
        assertThat(count("sales")).isEqualTo(sales);

        mvc.perform(get("/api/admin/backups").header("Authorization", bearer(sys)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folder").value(org.hamcrest.Matchers.endsWith("test-backups")))
                .andExpect(jsonPath("$.backups.length()").value(1))
                .andExpect(jsonPath("$.backups[0].fileName").value(file.getFileName().toString()))
                .andExpect(jsonPath("$.backups[0].sizeBytes").value(Files.size(file)));
    }

    @Test
    void twoBackupsGetDifferentNamesAndAreListedNewestFirst() throws Exception {
        String sys = sysadminToken();
        String first = jsonString(mvc.perform(post("/api/admin/backups").header("Authorization", bearer(sys)))
                .andExpect(status().isCreated()).andReturn(), "fileName");
        String second = jsonString(mvc.perform(post("/api/admin/backups").header("Authorization", bearer(sys)))
                .andExpect(status().isCreated()).andReturn(), "fileName");
        assertThat(second).isNotEqualTo(first);
        mvc.perform(get("/api/admin/backups").header("Authorization", bearer(sys)))
                .andExpect(jsonPath("$.backups.length()").value(2))
                .andExpect(jsonPath("$.backups[0].fileName").value(second))
                .andExpect(jsonPath("$.backups[1].fileName").value(first));
    }

    @Test
    void onlyFilesNamedLikeABackupAreListed() throws Exception {
        Files.createDirectories(FOLDER.resolve("clixsell-backup-20260101-000000.db.d"));
        Files.writeString(FOLDER.resolve("notes.txt"), "not a backup");
        Files.writeString(FOLDER.resolve("clixsell.db"), "not a backup");
        mvc.perform(get("/api/admin/backups").header("Authorization", bearer(sysadminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backups.length()").value(0));
    }

    @Test
    void storeUsersCannotReachBackups() throws Exception {
        for (String token : List.of(shini.adminToken(), shini.cashierToken(), neshan.adminToken(), neshan.cashierToken())) {
            mvc.perform(post("/api/admin/backups").header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/admin/backups").header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/admin/backups")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/backups")).andExpect(status().isUnauthorized());
        assertThat(backupFiles()).isEmpty();
    }

    @Test
    void thereIsNoWayToReadDeleteOrRestoreAFileThroughTheApi() throws Exception {
        String sys = sysadminToken();
        String name = jsonString(mvc.perform(post("/api/admin/backups").header("Authorization", bearer(sys)))
                .andExpect(status().isCreated()).andReturn(), "fileName");
        for (String path : List.of("/api/admin/backups/" + name, "/api/admin/backups/..%2Fclixsell.db",
                "/api/admin/backups/../../clixsell.db")) {
            int got = mvc.perform(get(path).header("Authorization", bearer(sys))).andReturn().getResponse().getStatus();
            assertThat(got).isIn(400, 404, 405);
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/admin/backups/" + name).header("Authorization", bearer(sys)))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(404, 405));
        assertThat(FOLDER.resolve(name)).exists();
    }
}

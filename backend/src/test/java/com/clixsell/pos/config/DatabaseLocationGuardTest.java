package com.clixsell.pos.config;

import com.clixsell.pos.config.DatabaseLocationGuard.Decision;
import com.clixsell.pos.config.DatabaseLocationGuard.UnsafeDatabaseLocationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A2: the database location is checked before the database is opened. Only files in this
 * test's own temporary folder are used; a refused location is never created or changed.
 */
class DatabaseLocationGuardTest {

    @TempDir
    Path dir;

    private static String url(Path p) {
        return "jdbc:sqlite:" + p + "?foreign_keys=on";
    }

    private static Path realDatabase(Path p) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + p)) {
            c.createStatement().execute("CREATE TABLE t (x INTEGER)");
        }
        return p;
    }

    @Test
    void anExistingSqliteDatabaseIsUsedAsItIsAndNeverChanged() throws Exception {
        Path db = realDatabase(dir.resolve("clixsell.db"));
        byte[] before = Files.readAllBytes(db);
        Decision d = DatabaseLocationGuard.check(url(db), false);
        assertThat(d.path()).isEqualTo(db.toAbsolutePath().normalize());
        assertThat(d.isNew()).isFalse();
        assertThat(d.size()).isEqualTo(before.length);
        assertThat(Files.readAllBytes(db)).isEqualTo(before);
        // the same with creation allowed: an existing database is never "new"
        assertThat(DatabaseLocationGuard.check(url(db), true).isNew()).isFalse();
    }

    @Test
    void aMissingDatabaseIsNotCreatedUnlessAskedForExplicitly() {
        Path db = dir.resolve("clixsell.db");
        assertThatThrownBy(() -> DatabaseLocationGuard.check(url(db), false))
                .isInstanceOf(UnsafeDatabaseLocationException.class)
                .hasMessageContaining("No database found at " + db.toAbsolutePath())
                .hasMessageContaining("CLIXSELL_DB_PATH").hasMessageContaining("CLIXSELL_DB_CREATE=true");
        assertThat(db).doesNotExist();
        Decision d = DatabaseLocationGuard.check(url(db), true);
        assertThat(d.isNew()).isTrue();
        assertThat(db).as("the guard itself never creates files").doesNotExist();
    }

    @Test
    void aRelativePathIsResolvedAgainstTheWorkingFolderAndReportedAbsolute() throws Exception {
        Path cwd = Path.of("").toAbsolutePath();
        String name = "guard-relative-" + System.nanoTime() + ".db";
        assertThatThrownBy(() -> DatabaseLocationGuard.check("jdbc:sqlite:" + name, false))
                .hasMessageContaining(cwd.resolve(name).toString());
        assertThat(cwd.resolve(name)).doesNotExist();
    }

    @Test
    void aMissingFolderIsNeverCreated() {
        Path db = dir.resolve("no-such-folder").resolve("clixsell.db");
        assertThatThrownBy(() -> DatabaseLocationGuard.check(url(db), true))
                .isInstanceOf(UnsafeDatabaseLocationException.class)
                .hasMessageContaining("folder for the database does not exist");
        assertThat(db.getParent()).doesNotExist();
    }

    @Test
    void aFolderAnEmptyFileOrANonSqliteFileIsRefusedAndLeftUntouched() throws Exception {
        assertThatThrownBy(() -> DatabaseLocationGuard.check(url(dir), true))
                .hasMessageContaining("is a folder");
        Path empty = Files.createFile(dir.resolve("empty.db"));
        assertThatThrownBy(() -> DatabaseLocationGuard.check(url(empty), false))
                .hasMessageContaining("EMPTY");
        assertThat(DatabaseLocationGuard.check(url(empty), true).isNew()).isTrue();
        Path text = dir.resolve("notes.db");
        Files.writeString(text, "these are my notes, not a database", StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(text);
        assertThatThrownBy(() -> DatabaseLocationGuard.check(url(text), true))
                .hasMessageContaining("not a SQLite database").hasMessageContaining("It was not changed");
        assertThat(Files.readAllBytes(text)).isEqualTo(before);
    }

    @Test
    void aReadOnlyDatabaseOrFolderIsRefused() throws Exception {
        assumeTrue(Files.getFileStore(dir).supportsFileAttributeView("posix"), "POSIX permissions");
        assumeTrue(!"root".equals(System.getProperty("user.name")), "root ignores file permissions");
        Path db = realDatabase(dir.resolve("ro.db"));
        Files.setPosixFilePermissions(db, PosixFilePermissions.fromString("r--r--r--"));
        try {
            assertThatThrownBy(() -> DatabaseLocationGuard.check(url(db), false))
                    .hasMessageContaining("readable and writable");
        } finally {
            Files.setPosixFilePermissions(db, PosixFilePermissions.fromString("rw-------"));
        }
        Path roDir = Files.createDirectory(dir.resolve("ro-dir"));
        Files.setPosixFilePermissions(roDir, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertThatThrownBy(() -> DatabaseLocationGuard.check(url(roDir.resolve("new.db")), true))
                    .hasMessageContaining("not writable");
        } finally {
            Files.setPosixFilePermissions(roDir, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void inMemoryAndOtherDatabasesAreNotChecked() {
        assertThat(DatabaseLocationGuard.check("jdbc:sqlite::memory:", false).path()).isNull();
        assertThat(DatabaseLocationGuard.check("jdbc:sqlite:", false).path()).isNull();
        assertThat(DatabaseLocationGuard.check("jdbc:h2:mem:x", false).path()).isNull();
        assertThat(DatabaseLocationGuard.check(null, false).path()).isNull();
    }

    @Test
    void queryParametersAndFileUrlsAreUnderstood() throws Exception {
        Path db = realDatabase(dir.resolve("q.db"));
        assertThat(DatabaseLocationGuard.check("jdbc:sqlite:file:" + db + "?foreign_keys=on&busy_timeout=3000", false)
                .path()).isEqualTo(db.toAbsolutePath().normalize());
        assertThat(Arrays.asList(DatabaseLocationGuard.check(url(db), false).isNew())).containsExactly(false);
    }

    // ── first start: the System Admin settings are checked BEFORE a new database exists ──────────

    private void start(Path db, String password, boolean allowDemo, String username) {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.url", url(db))
                .withProperty("clixsell.db.create-if-missing", "true")
                .withProperty("clixsell.system-admin.allow-demo-password", String.valueOf(allowDemo));
        if (password != null) env.setProperty("clixsell.system-admin.password", password);
        if (username != null) env.setProperty("clixsell.system-admin.username", username);
        DatabaseLocationGuard guard = new DatabaseLocationGuard();
        guard.setEnvironment(env);
        guard.postProcessBeanFactory(null);
    }

    private void assertNothingCreated(Path db) throws Exception {
        try (var files = Files.list(dir)) {
            assertThat(files.toList()).as("no database, journal or WAL file").isEmpty();
        }
        assertThat(db).doesNotExist();
    }

    @Test
    void aNewDatabaseWithoutAnAdminPasswordIsRefusedBeforeAnythingIsCreated() throws Exception {
        Path db = dir.resolve("new.db");
        for (String missing : new String[]{null, "", "  "}) {
            assertThatThrownBy(() -> start(db, missing, false, null))
                    .isInstanceOf(DatabaseLocationGuard.UnsafeDatabaseLocationException.class)
                    .hasMessageContaining("CLIXSELL_ADMIN_PASSWORD").hasMessageContaining("Nothing was created");
        }
        assertNothingCreated(db);
    }

    @Test
    void aNewDatabaseWithADemoPasswordIsRefusedBeforeAnythingIsCreated() throws Exception {
        Path db = dir.resolve("new.db");
        for (String demo : new String[]{"Admin12345", "admin12345", "ADMIN-PASS-1", "Cashier-Pass-1"}) {
            assertThatThrownBy(() -> start(db, demo, false, null))
                    .isInstanceOf(DatabaseLocationGuard.UnsafeDatabaseLocationException.class)
                    .hasMessageContaining("publicly documented demo password")
                    .hasMessageContaining("Nothing was created")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(demo));
        }
        assertNothingCreated(db);
    }

    @Test
    void aNewDatabaseWithAnInvalidPasswordOrUsernameIsRefusedBeforeAnythingIsCreated() throws Exception {
        Path db = dir.resolve("new.db");
        assertThatThrownBy(() -> start(db, "short1", false, null))
                .isInstanceOf(DatabaseLocationGuard.UnsafeDatabaseLocationException.class)
                .hasMessageContaining("at least").hasMessageContaining("Nothing was created")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("short1"));
        assertThatThrownBy(() -> start(db, "Good-Owner-Pass-7", false, "x"))
                .isInstanceOf(DatabaseLocationGuard.UnsafeDatabaseLocationException.class)
                .hasMessageContaining("Username");
        assertNothingCreated(db);
    }

    @Test
    void validFirstStartSettingsAndTheDemoProfilePassTheGuard() throws Exception {
        Path db = dir.resolve("new.db");
        start(db, "Good-Owner-Pass-7", false, null);   // the database itself is created later by SQLite
        start(db, "Admin12345", true, null);           // demo profile: the demo password is intended
        assertNothingCreated(db);                       // the guard itself never writes anything
    }

    @Test
    void anExistingDatabaseNeedsNoAdminSettingsAtAll() throws Exception {
        Path db = realDatabase(dir.resolve("existing.db"));
        start(db, null, false, null);
        start(db, "Admin12345", false, null); // not used: a database that exists is never initialised again
        assertThat(db).exists();
    }
}

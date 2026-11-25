package com.clixsell.pos;

import com.clixsell.pos.purchase.PurchaseMigrationTest;

import com.clixsell.pos.support.RunTestClasses;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Data safety (pre-merge correction): running the backend tests must never
 * delete, change or overwrite a backup that existed before them.
 *
 * <p>A real installation keeps the upgrade backups {@code clixsell-backup-before-v2/v4/v5-….db}
 * next to {@code clixsell.db}, and Settings backups {@code clixsell-backup-….db} in its backup
 * folder. This test puts such files (with unique names and contents) in a fresh folder, runs
 * every test class that creates or lists backups there, in a separate JVM, and then checks that
 * each file is still there byte for byte, and that those test classes still pass.
 */
class BackupPreservationTest {

    /** Every test class that writes, reads or lists backup files. */
    static final List<Class<?>> BACKUP_TESTS = List.of(MigrationTest.class, GuardianMigrationTest.class,
            UpgradeToSalesVersionTest.class, BackupApiTest.class, PurchaseMigrationTest.class,
            com.clixsell.pos.backup.BackupWalSnapshotTest.class);

    private final Random random = new Random();

    @Test
    void backupsThatExistedBeforeTheTestsAreKeptUnchanged(@TempDir Path work) throws Exception {
        // Older versions of the tests used paths under "target/": keep that layout available.
        Files.createDirectories(work.resolve("target"));
        Path settingsFolder = work.resolve(settingsBackupFolder());
        Files.createDirectories(settingsFolder);

        Map<Path, byte[]> planted = new LinkedHashMap<>();
        plant(planted, work, "clixsell-backup-before-v2-", "");
        plant(planted, work, "clixsell-backup-before-v4-", "");
        plant(planted, work, "clixsell-backup-before-v5-", "-" + String.format("%03d", random.nextInt(1000)));
        plant(planted, settingsFolder, "clixsell-backup-", "");

        Path log = Files.createTempFile("clixsell-backup-safety-", ".log");
        try {
            int exit = runInSeparateJvm(work, log);
            SoftAssertions soft = new SoftAssertions();
            for (var e : planted.entrySet()) {
                soft.assertThat(e.getKey()).as("pre-existing backup kept: %s", e.getKey().getFileName()).exists();
                if (Files.exists(e.getKey())) {
                    soft.assertThat(Files.readAllBytes(e.getKey()))
                            .as("pre-existing backup unchanged: %s", e.getKey().getFileName()).isEqualTo(e.getValue());
                }
            }
            soft.assertThat(exit).as("the backup tests pass with older backups present:%n%s", tail(log)).isZero();
            soft.assertAll();
        } finally {
            Files.deleteIfExists(log);
        }
    }

    /** The Settings backup folder the API tests use ({@code clixsell.backup.dir} of {@link ApiTestBase}). */
    static String settingsBackupFolder() {
        for (String p : ApiTestBase.class.getAnnotation(SpringBootTest.class).properties()) {
            if (p.startsWith("clixsell.backup.dir=")) return p.substring("clixsell.backup.dir=".length());
        }
        throw new IllegalStateException("ApiTestBase sets no clixsell.backup.dir");
    }

    /** A backup-named file from 1999 with unique content; never replaces an existing file. */
    private void plant(Map<Path, byte[]> planted, Path dir, String prefix, String suffix) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            String stamp = String.format("1999%02d%02d-%02d%02d%02d", 1 + random.nextInt(12), 1 + random.nextInt(28),
                    random.nextInt(24), random.nextInt(60), random.nextInt(60));
            Path file = dir.resolve(prefix + stamp + suffix + ".db");
            byte[] content = ("ClixSell pre-existing backup " + UUID.randomUUID()).getBytes();
            try {
                Files.write(file, content, StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException taken) {
                continue;
            }
            Files.setLastModifiedTime(file, FileTime.from(Instant.parse("1999-01-01T00:00:00Z")));
            planted.put(file, content);
            return;
        }
        throw new IllegalStateException("no free name in " + dir);
    }

    /** Runs {@link #BACKUP_TESTS} in a new JVM whose working folder is {@code work}. */
    private static int runInSeparateJvm(Path work, Path log) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), RunTestClasses.class.getName()));
        for (Class<?> c : BACKUP_TESTS) cmd.add(c.getName());
        Process p = new ProcessBuilder(cmd).directory(work.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IllegalStateException("the backup tests did not finish in 10 minutes");
        }
        return p.exitValue();
    }

    private static String tail(Path log) throws Exception {
        List<String> lines = Files.readAllLines(log);
        return String.join(System.lineSeparator(), lines.subList(Math.max(0, lines.size() - 40), lines.size()));
    }

    @Test
    void theListOfBackupTestsCoversEveryTestThatTouchesBackupFiles() throws Exception {
        // A new test that creates or lists backups must be added to BACKUP_TESTS.
        List<String> found = new ArrayList<>();
        Path root = Path.of(BackupPreservationTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .getParent().getParent().resolve("src/test/java");
        try (var files = Files.walk(root)) {
            for (Path f : files.filter(x -> x.toString().endsWith("Test.java")).toList()) {
                String src = Files.readString(f);
                boolean touchesBackups = src.contains("clixsell-backup") || src.contains("OwnedFiles")
                        || src.contains("/api/admin/backups") || src.contains("VACUUM INTO");
                if (touchesBackups && !f.getFileName().toString().equals("BackupPreservationTest.java")) {
                    found.add(f.getFileName().toString().replace(".java", ""));
                }
            }
        }
        assertThat(found).containsExactlyInAnyOrderElementsOf(BACKUP_TESTS.stream().map(Class::getSimpleName).toList());
    }
}

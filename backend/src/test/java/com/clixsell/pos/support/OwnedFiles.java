package com.clixsell.pos.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The files a test creates in a folder, and only those.
 *
 * <p>The folder is read when the test starts; afterwards {@link #created()} lists the matching
 * files that were NOT there before, and {@link #deleteCreated()} deletes exactly those. A file
 * that existed before the test (for example a real backup) is never deleted, moved or changed.
 * Tests run one after another (no parallel tests), so a new file belongs to the running test.
 */
public final class OwnedFiles {

    /** Upgrade backups written by the V2 / V4 / V5 migrations: {@code clixsell-backup-before-v…db}. */
    public static final Predicate<String> MIGRATION_BACKUPS =
            n -> n.startsWith("clixsell-backup-before-v") && n.endsWith(".db");

    private final Path dir;
    private final Predicate<String> names;
    private final Set<Path> before;

    private OwnedFiles(Path dir, Predicate<String> names) {
        this.dir = dir.toAbsolutePath();
        this.names = names;
        this.before = Set.copyOf(list());
    }

    /** Starts watching {@code dir} for new entries whose file name matches {@code names}. */
    public static OwnedFiles watch(Path dir, Predicate<String> names) {
        return new OwnedFiles(dir, names);
    }

    /** Upgrade backups in the working folder: migrations write them there (relative file name). */
    public static OwnedFiles migrationBackups() {
        return watch(Path.of(""), MIGRATION_BACKUPS);
    }

    /** Matching entries that did not exist when watching started, oldest name first. */
    public List<Path> created() {
        return list().stream().filter(p -> !before.contains(p)).sorted().toList();
    }

    /** {@link #created()} whose file name starts with {@code prefix}. */
    public List<Path> created(String prefix) {
        return created().stream().filter(p -> p.getFileName().toString().startsWith(prefix)).toList();
    }

    /** Deletes only {@link #created()} (files first, then empty folders the test made). */
    public void deleteCreated() {
        try {
            for (Path p : created().stream().sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isDirectory(p)) {
                    try (Stream<Path> inside = Files.list(p)) {
                        if (inside.findAny().isPresent()) continue; // not empty: leave it
                    }
                }
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Set<Path> list() {
        if (!Files.isDirectory(dir)) return Set.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> names.test(p.getFileName().toString())).map(Path::toAbsolutePath)
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

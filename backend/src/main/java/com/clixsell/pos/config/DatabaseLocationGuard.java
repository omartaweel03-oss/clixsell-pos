package com.clixsell.pos.config;

import com.clixsell.pos.auth.SystemAdminInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Checks the SQLite database location BEFORE the database is opened (before the DataSource and
 * Flyway exist), so a wrong working folder or a typo can never silently create a new, empty
 * database next to the real one.
 *
 * <ul>
 *   <li>The location comes from {@code spring.datasource.url} — by default
 *       {@code CLIXSELL_DB_PATH}, else {@code clixsell.db} in the folder the backend starts in.
 *       The absolute path is always logged.</li>
 *   <li>An existing file must be a real SQLite database (its 16-byte header is read; nothing is
 *       written) that can be read and written, in a folder that can be written (SQLite needs it
 *       for its journal / WAL files).</li>
 *   <li>A missing file is created ONLY when {@code clixsell.db.create-if-missing=true}
 *       ({@code CLIXSELL_DB_CREATE=true}): the first start of a new installation, the demo
 *       profile, tests. The folder must already exist; it is never created here.</li>
 *   <li>Before a NEW database is created, the first System Admin settings are checked (password set,
 *       not a published demo password outside the demo profile, valid username / password); if they
 *       fail, the start stops and no file is created and no migration runs.</li>
 *   <li>Any other case stops the start with a clear message. Nothing is ever deleted, replaced
 *       or moved.</li>
 * </ul>
 * In-memory and non-SQLite URLs are not checked.
 */
@Component
public class DatabaseLocationGuard implements BeanFactoryPostProcessor, EnvironmentAware, PriorityOrdered {

    private static final Logger log = LoggerFactory.getLogger(DatabaseLocationGuard.class);
    private static final byte[] SQLITE_HEADER = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        String url = environment.getProperty("spring.datasource.url");
        boolean create = environment.getProperty("clixsell.db.create-if-missing", Boolean.class, false);
        Decision d = check(url, create);
        if (d.isNew()) {
            // A new database needs its first System Admin: check those settings BEFORE anything is
            // created, so a refused start leaves no file, no tables and no migration behind.
            String adminPassword = environment.getProperty("clixsell.system-admin.password", "");
            if (adminPassword.isBlank()) {
                throw new UnsafeDatabaseLocationException("A new database would be created at " + d.path() + ", but no"
                        + " initial System Admin password is configured. Set CLIXSELL_ADMIN_PASSWORD for this first"
                        + " start. Nothing was created.");
            }
            String problem = SystemAdminInitializer.initialCredentialsProblem(
                    environment.getProperty("clixsell.system-admin.username", "sysadmin"), adminPassword,
                    environment.getProperty("clixsell.system-admin.allow-demo-password", Boolean.class, false));
            if (problem != null) {
                throw new UnsafeDatabaseLocationException(problem + " A new database would be created at " + d.path()
                        + ". Nothing was created.");
            }
        }
        if (d.path() != null) {
            if (d.isNew()) log.warn("ClixSell database: creating a NEW database at {} (clixsell.db.create-if-missing=true)", d.path());
            else log.info("ClixSell database: {} (existing, {} bytes)", d.path(), d.size());
        }
    }

    /** What the guard decided: the absolute file (null when not checked), new or existing. */
    public record Decision(Path path, boolean isNew, long size) {
    }

    /** Thrown when the database location is not safe to use; the message says what to do. */
    public static final class UnsafeDatabaseLocationException extends IllegalStateException {
        public UnsafeDatabaseLocationException(String message) {
            super(message);
        }
    }

    /** The check itself, without Spring (also used by the tests). */
    public static Decision check(String url, boolean createIfMissing) {
        Path file = sqliteFile(url);
        if (file == null) return new Decision(null, false, 0);
        String help = " Set CLIXSELL_DB_PATH to the full path of your database file"
                + " (or spring.datasource.url=jdbc:sqlite:<full path>?foreign_keys=on).";
        if (Files.isDirectory(file)) {
            throw new UnsafeDatabaseLocationException("The database path is a folder, not a file: " + file + "." + help);
        }
        Path folder = file.getParent();
        if (Files.exists(file)) {
            if (!Files.isRegularFile(file)) {
                throw new UnsafeDatabaseLocationException("The database path is not a regular file: " + file + "." + help);
            }
            long size;
            try {
                size = Files.size(file);
            } catch (IOException e) {
                throw new UnsafeDatabaseLocationException("Cannot read the database file " + file + ": " + e.getMessage());
            }
            if (!Files.isReadable(file) || !Files.isWritable(file)) {
                throw new UnsafeDatabaseLocationException("The database file must be readable and writable: " + file
                        + ". Check its permissions (a read-only database cannot record sales).");
            }
            if (folder != null && !Files.isWritable(folder)) {
                throw new UnsafeDatabaseLocationException("The folder of the database must be writable (SQLite keeps its"
                        + " journal there): " + folder + ".");
            }
            if (size == 0) {
                if (!createIfMissing) {
                    throw new UnsafeDatabaseLocationException("The database file is EMPTY: " + file + ". If this is a new"
                            + " installation, start once with CLIXSELL_DB_CREATE=true. If you expected your existing"
                            + " data, this is the wrong file." + help);
                }
                return new Decision(file, true, 0);
            }
            if (!hasSqliteHeader(file)) {
                throw new UnsafeDatabaseLocationException("This file is not a SQLite database: " + file
                        + ". It was not changed." + help);
            }
            return new Decision(file, false, size);
        }
        if (folder == null || !Files.isDirectory(folder)) {
            throw new UnsafeDatabaseLocationException("The folder for the database does not exist: " + folder
                    + ". Create it first (ClixSell never creates it by itself)." + help);
        }
        if (!createIfMissing) {
            throw new UnsafeDatabaseLocationException("No database found at " + file + ". ClixSell did not create a new,"
                    + " empty one. If you expected your existing data, start the backend from the folder that contains"
                    + " clixsell.db, or set CLIXSELL_DB_PATH. If this is the FIRST start of a new installation, start"
                    + " once with CLIXSELL_DB_CREATE=true.");
        }
        if (!Files.isWritable(folder)) {
            throw new UnsafeDatabaseLocationException("Cannot create the database: the folder is not writable: " + folder + ".");
        }
        return new Decision(file, true, 0);
    }

    /** The database file of a SQLite JDBC URL as an absolute path; null for in-memory or other databases. */
    static Path sqliteFile(String url) {
        if (url == null || !url.startsWith("jdbc:sqlite:")) return null;
        String rest = url.substring("jdbc:sqlite:".length());
        int q = rest.indexOf('?');
        if (q >= 0) rest = rest.substring(0, q);
        if (rest.startsWith("file:")) rest = rest.substring("file:".length());
        if (rest.isBlank() || rest.startsWith(":memory:") || rest.startsWith(":resource:")) return null;
        try {
            return Path.of(rest).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new UnsafeDatabaseLocationException("The database path is not valid: '" + rest + "' (" + e.getReason() + ").");
        }
    }

    private static boolean hasSqliteHeader(Path file) {
        byte[] head = new byte[SQLITE_HEADER.length];
        try (InputStream in = Files.newInputStream(file)) {
            int n = in.readNBytes(head, 0, head.length);
            return n == head.length && Arrays.equals(head, SQLITE_HEADER);
        } catch (IOException e) {
            throw new UnsafeDatabaseLocationException("Cannot read the database file " + file + ": " + e.getMessage());
        }
    }
}

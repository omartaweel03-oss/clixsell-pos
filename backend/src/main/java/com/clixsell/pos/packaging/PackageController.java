package com.clixsell.pos.packaging;

import com.clixsell.pos.auth.CurrentUser;
import com.clixsell.pos.common.ConflictException;
import com.clixsell.pos.common.NotFoundException;
import com.clixsell.pos.common.ValidationException;
import com.clixsell.pos.sale.PackageSnapshot;
import com.clixsell.pos.user.Role;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Piece / carton / box (feature 6, D7). Inventory stays in PIECES (base units); a package is a
 * named size of a product with its own price and an optional barcode of its own.
 *
 * <pre>
 * GET   /api/packages                        store users   every package of the store (POS)
 * POST  /api/products/{id}/packages          Store Admin   {name, unitsPerPackage, price, barcode?}
 * PATCH /api/packages/{id}                   Store Admin   {name?, price?, barcode? ("" = none), active?}
 * </pre>
 * A package's size never changes (past invoices keep their meaning): to sell another size, add a
 * new package and deactivate the old one. A barcode is never shared with a product or another
 * package of the store (checked here and by the database).
 */
@RestController
public class PackageController {

    public record PackageView(long packageId, long productId, String name, int unitsPerPackage, double price,
                              String barcode, boolean active) {
    }

    public record PackageRequest(String name, Integer unitsPerPackage, BigDecimal price, String barcode, Boolean active) {
    }

    private final PackageService service;

    public PackageController(PackageService service) {
        this.service = service;
    }

    @GetMapping("/api/packages")
    public List<PackageView> list(@AuthenticationPrincipal CurrentUser user) {
        return service.list(user.requireStoreId());
    }

    @PostMapping("/api/products/{productId}/packages")
    @ResponseStatus(HttpStatus.CREATED)
    public PackageView create(@AuthenticationPrincipal CurrentUser user, @PathVariable long productId,
                              @RequestBody PackageRequest r) {
        return service.create(user, productId, r);
    }

    @PatchMapping("/api/packages/{id}")
    public PackageView update(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                              @RequestBody PackageRequest r) {
        return service.update(user, id, r);
    }

    @Service
    @Transactional
    public static class PackageService {

        private final JdbcTemplate db;

        public PackageService(JdbcTemplate db) {
            this.db = db;
        }

        private static final String SELECT = "SELECT package_id, product_id, name, units_per_package, price_minor, barcode, "
                + "active FROM product_packages ";

        private static PackageView view(java.sql.ResultSet rs) throws java.sql.SQLException {
            return new PackageView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4),
                    BigDecimal.valueOf(rs.getLong(5), 2).doubleValue(), rs.getString(6), rs.getInt(7) == 1);
        }

        @Transactional(readOnly = true)
        public List<PackageView> list(long storeId) {
            return db.query(SELECT + "WHERE store_id = ? ORDER BY product_id, units_per_package, package_id",
                    (rs, i) -> view(rs), storeId);
        }

        private Optional<PackageView> find(long storeId, long id) {
            return db.query(SELECT + "WHERE store_id = ? AND package_id = ?", (rs, i) -> view(rs), storeId, id)
                    .stream().findFirst();
        }

        /** For a sale: an ACTIVE package of this product in this store, or a clear refusal. */
        @Transactional(readOnly = true)
        public PackageSnapshot forSale(long storeId, long productId, long packageId) {
            PackageView p = find(storeId, packageId).filter(x -> x.productId() == productId)
                    .orElseThrow(() -> new NotFoundException("PACKAGE_NOT_FOUND", "This package is not available for this product"));
            if (!p.active()) throw new ConflictException("PACKAGE_INACTIVE", null, "The package \"" + p.name() + "\" is no longer sold");
            return new PackageSnapshot(p.packageId(), p.name(), p.unitsPerPackage(), p.price());
        }

        public PackageView create(CurrentUser user, long productId, PackageRequest r) {
            requireAdmin(user);
            long storeId = user.requireStoreId();
            Integer n = db.queryForObject("SELECT COUNT(*) FROM products WHERE product_id = ? AND store_id = ?", Integer.class,
                    productId, storeId);
            if (n == null || n == 0) throw new NotFoundException("PRODUCT_NOT_FOUND", "This product was not found");
            if (r == null) throw new ValidationException(Map.of("name", "Enter the package details"));
            String name = name(r.name());
            Integer units = r.unitsPerPackage();
            if (units == null || units < 2 || units > 10_000) {
                throw new ValidationException(Map.of("unitsPerPackage", "A package holds 2 to 10,000 pieces"));
            }
            long price = price(r.price());
            String barcode = barcode(storeId, r.barcode(), null);
            String now = LocalDateTime.now().withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            db.update("INSERT INTO product_packages (store_id, product_id, name, units_per_package, price_minor, barcode, active, "
                    + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)", storeId, productId, name, units, price,
                    barcode, now, now);
            Long id = db.queryForObject("SELECT last_insert_rowid()", Long.class);
            return find(storeId, id).orElseThrow();
        }

        public PackageView update(CurrentUser user, long id, PackageRequest r) {
            requireAdmin(user);
            long storeId = user.requireStoreId();
            PackageView p = find(storeId, id).orElseThrow(() -> new NotFoundException("PACKAGE_NOT_FOUND", "This package was not found"));
            if (r == null) return p;
            if (r.unitsPerPackage() != null && r.unitsPerPackage() != p.unitsPerPackage()) {
                throw new ConflictException("PACKAGE_SIZE_FIXED", "unitsPerPackage",
                        "A package's size cannot change; add a new package and deactivate this one");
            }
            String name = r.name() == null ? p.name() : name(r.name());
            long price = r.price() == null ? BigDecimal.valueOf(p.price()).movePointRight(2).longValue() : price(r.price());
            String barcode = r.barcode() == null ? p.barcode() : barcode(storeId, r.barcode(), id);
            boolean active = r.active() == null ? p.active() : r.active();
            String now = LocalDateTime.now().withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            db.update("UPDATE product_packages SET name = ?, price_minor = ?, barcode = ?, active = ?, updated_at = ?, "
                    + "version = version + 1 WHERE package_id = ? AND store_id = ?", name, price, barcode, active ? 1 : 0, now, id,
                    storeId);
            return find(storeId, id).orElseThrow();
        }

        private static void requireAdmin(CurrentUser user) {
            if (user.role() != Role.STORE_ADMIN) throw new AccessDeniedException("Store Admin only");
        }

        private static String name(String v) {
            String n = v == null ? "" : v.strip();
            if (n.isEmpty() || n.length() > 60) throw new ValidationException(Map.of("name", "Enter a package name of 1–60 characters"));
            return n;
        }

        private static long price(BigDecimal v) {
            if (v == null || v.signum() <= 0 || v.stripTrailingZeros().scale() > 2) {
                throw new ValidationException(Map.of("price", "Enter a price above 0 with at most 2 decimals"));
            }
            if (v.compareTo(BigDecimal.valueOf(1_000_000)) > 0) throw new ValidationException(Map.of("price", "The price is too large"));
            return v.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        }

        /** Optional (D7): blank = no barcode. 8–14 digits, unique among the store's products and packages. */
        private String barcode(long storeId, String v, Long exceptPackage) {
            String b = v == null ? null : v.strip();
            if (b == null || b.isEmpty()) return null;
            if (!b.matches("\\d{8,14}")) throw new ValidationException(Map.of("barcode", "A barcode has 8 to 14 digits"));
            Integer products = db.queryForObject("SELECT COUNT(*) FROM products WHERE store_id = ? AND barcode = ?", Integer.class,
                    storeId, b);
            Integer packages = db.queryForObject("SELECT COUNT(*) FROM product_packages WHERE store_id = ? AND barcode = ? "
                    + "AND package_id IS NOT ?", Integer.class, storeId, b, exceptPackage);
            if ((products != null && products > 0) || (packages != null && packages > 0)) {
                throw new ConflictException("DUPLICATE_BARCODE", "barcode", "Another product or package already uses barcode " + b);
            }
            return b;
        }
    }
}

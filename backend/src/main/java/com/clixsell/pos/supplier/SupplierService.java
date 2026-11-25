package com.clixsell.pos.supplier;

import com.clixsell.pos.common.ConflictException;
import com.clixsell.pos.common.NotFoundException;
import com.clixsell.pos.common.ValidationException;
import com.clixsell.pos.product.Product;
import com.clixsell.pos.product.ProductRepository;
import com.clixsell.pos.store.Store;
import com.clixsell.pos.store.StoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Suppliers of the logged-in user's store.
 *
 * <ul>
 *   <li>Supplier Management (Store Admin): list, details with products, add, edit contact
 *       details, deactivate / activate. Suppliers are never deleted.</li>
 *   <li>The Product Screen still sends a supplier NAME ({@link #findOrCreate}); this keeps
 *       working as before, and links to an existing supplier whose name differs only by
 *       spaces instead of creating a near-duplicate (D26).</li>
 * </ul>
 * The store always comes from the logged-in user, never from the request. A supplier of
 * another store is reported as "not found".
 */
@Service
@Transactional
public class SupplierService {

    public static final int MAX_PHONE_LENGTH = 30;
    public static final int MAX_ADDRESS_LENGTH = 200;

    /** Validated contact details sent by Supplier Management. */
    public record SupplierInput(String name, String phone, String whatsappNumber, String address) {
    }

    /** A supplier with the number of products that use it. */
    public record SupplierSummary(Supplier supplier, long productCount) {
    }

    private final SupplierRepository suppliers;
    private final ProductRepository products;
    private final StoreRepository stores;

    public SupplierService(SupplierRepository suppliers, ProductRepository products, StoreRepository stores) {
        this.suppliers = suppliers;
        this.products = products;
        this.stores = stores;
    }

    // ── Product Screen ───────────────────────────────────────────────────────────────

    /**
     * The supplier {@link #findOrCreate} would link to, WITHOUT creating one: empty when it would
     * create a new supplier; SUPPLIER_NAME_AMBIGUOUS exactly like findOrCreate. Used by the
     * product import preview, which must not write anything.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Supplier> findExisting(long storeId, String name) {
        String trimmed = name.trim();
        var exact = suppliers.findByStore_StoreIdAndNameIgnoreCase(storeId, trimmed);
        if (exact.isPresent()) return exact;
        String key = SupplierNames.key(trimmed);
        List<Supplier> same = suppliers.findAllByStore_StoreIdOrderByNameAsc(storeId).stream()
                .filter(s -> SupplierNames.key(s.getName()).equals(key)).toList();
        if (same.size() == 1) return java.util.Optional.of(same.get(0));
        if (same.size() > 1) {
            throw new ConflictException("SUPPLIER_NAME_AMBIGUOUS", "supplierName",
                    "Several suppliers match this name: " + same.stream().map(s -> "'" + s.getName() + "'")
                            .collect(Collectors.joining(", ")) + ". Type the exact name.");
        }
        return java.util.Optional.empty();
    }

    /**
     * The store's supplier for a name typed on the Product Screen, creating it if needed.
     * <ol>
     *   <li>Exact match (trim, English letters case-insensitive) — exactly the old behavior.</li>
     *   <li>Otherwise a match after normalizing spaces, if there is exactly ONE (e.g. the
     *       legacy supplier "Al  Quds" for "Al Quds"); its stored name is not changed.</li>
     *   <li>Several such matches → 409 SUPPLIER_NAME_AMBIGUOUS (the user types the exact name).</li>
     *   <li>No match → a new ACTIVE supplier with the normalized name.</li>
     * </ol>
     * This method never reactivates an inactive supplier; the caller decides whether an
     * inactive supplier may be used (D16).
     */
    public Supplier findOrCreate(Store store, String name) {
        String trimmed = name.trim();
        var exact = suppliers.findByStore_StoreIdAndNameIgnoreCase(store.getStoreId(), trimmed);
        if (exact.isPresent()) return exact.get();
        String key = SupplierNames.key(trimmed);
        List<Supplier> same = suppliers.findAllByStore_StoreIdOrderByNameAsc(store.getStoreId()).stream()
                .filter(s -> SupplierNames.key(s.getName()).equals(key)).toList();
        if (same.size() == 1) return same.get(0);
        if (same.size() > 1) {
            throw new ConflictException("SUPPLIER_NAME_AMBIGUOUS", "supplierName",
                    "Several suppliers match this name: " + same.stream().map(s -> "'" + s.getName() + "'")
                            .collect(Collectors.joining(", ")) + ". Type the exact name.");
        }
        return suppliers.save(new Supplier(SupplierNames.normalize(trimmed), store));
    }

    // ── Supplier Management ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SupplierSummary> list(long storeId) {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : products.countProductsBySupplier(storeId)) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return suppliers.findAllByStore_StoreIdOrderByNameAsc(storeId).stream()
                .map(s -> new SupplierSummary(s, counts.getOrDefault(s.getSupplierId(), 0L))).toList();
    }

    @Transactional(readOnly = true)
    public Supplier get(long storeId, long supplierId) {
        return suppliers.findBySupplierIdAndStore_StoreId(supplierId, storeId)
                .orElseThrow(() -> new NotFoundException("SUPPLIER_NOT_FOUND", "Supplier " + supplierId + " was not found"));
    }

    @Transactional(readOnly = true)
    public List<Product> productsOf(long storeId, long supplierId) {
        get(storeId, supplierId);
        return products.findAllByStore_StoreIdAndSupplier_SupplierIdOrderByNameAsc(storeId, supplierId);
    }

    public Supplier create(long storeId, SupplierInput input) {
        Clean c = validate(input);
        rejectDuplicate(storeId, c.name(), null);
        Store store = stores.findById(storeId).orElseThrow();
        Supplier s = new Supplier(c.name(), store);
        s.updateDetails(c.name(), c.phone(), c.whatsapp(), c.address());
        return suppliers.save(s);
    }

    /**
     * Edits the contact details. The name stays exactly as stored when it is sent back
     * unchanged (legacy names with double spaces are not rewritten silently); a changed
     * name is normalized and must not match another supplier of the store.
     */
    public Supplier update(long storeId, long supplierId, SupplierInput input) {
        Supplier s = get(storeId, supplierId);
        Clean c = validate(input);
        String name = input.name() != null && input.name().strip().equals(s.getName()) ? s.getName() : c.name();
        if (!name.equals(s.getName())) rejectDuplicate(storeId, name, supplierId);
        s.updateDetails(name, c.phone(), c.whatsapp(), c.address());
        return suppliers.save(s);
    }

    /** Deactivates or reactivates a supplier: the ONLY way a supplier becomes active again. */
    public Supplier setActive(long storeId, long supplierId, boolean active) {
        Supplier s = get(storeId, supplierId);
        s.setActive(active);
        return suppliers.save(s);
    }

    // ── validation ──────────────────────────────────────────────────────────────────

    private record Clean(String name, String phone, String whatsapp, String address) {
    }

    private static Clean validate(SupplierInput in) {
        Map<String, String> errors = new LinkedHashMap<>();
        String name = SupplierNames.normalize(in.name());
        if (name.isEmpty()) errors.put("name", "Supplier name is required");
        else if (name.length() < SupplierNames.MIN_LENGTH) errors.put("name", "Supplier name must be at least 2 characters");
        else if (name.length() > SupplierNames.MAX_LENGTH) errors.put("name", "Supplier name must be 80 characters or fewer");

        String phone = blankToNull(in.phone());
        if (phone != null) {
            long digits = phone.chars().filter(Character::isDigit).count();
            if (phone.length() > MAX_PHONE_LENGTH || !phone.matches("[0-9+\\-(). ]+") || digits < 6) {
                errors.put("phone", "Phone number is not valid");
            }
        }

        String whatsapp = null;
        try {
            whatsapp = WhatsappNumber.parse(in.whatsappNumber());
        } catch (IllegalArgumentException e) {
            errors.put("whatsappNumber", "Enter the WhatsApp number in international format, e.g. 970599123456");
        }

        String address = blankToNull(in.address());
        if (address != null && address.length() > MAX_ADDRESS_LENGTH) {
            errors.put("address", "Address must be 200 characters or fewer");
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return new Clean(name, phone, whatsapp, address);
    }

    /** Another supplier of the store whose name is the same after normalizing → 409. */
    private void rejectDuplicate(long storeId, String name, Long exceptId) {
        String key = SupplierNames.key(name);
        for (Supplier other : suppliers.findAllByStore_StoreIdOrderByNameAsc(storeId)) {
            if (!Objects.equals(other.getSupplierId(), exceptId) && SupplierNames.key(other.getName()).equals(key)) {
                throw new ConflictException("DUPLICATE_SUPPLIER", "name",
                        "A supplier named '" + other.getName() + "' already exists");
            }
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}

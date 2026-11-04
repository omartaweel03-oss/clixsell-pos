package com.clixsell.pos.store;

import com.clixsell.pos.common.AccountStatus;
import com.clixsell.pos.common.LocalDateTimeTextConverter;
import com.clixsell.pos.common.ValidationException;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A store (supermarket) using ClixSell. Every Store Admin, Cashier, Product
 * and Supplier belongs to exactly one store (column store_id).
 */
@Entity
@Table(name = "stores")
public class Store {

    public static final int MAX_NAME_LENGTH = 80;
    public static final int MAX_ADDRESS_LENGTH = 120;
    public static final int MAX_PHONE_LENGTH = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_id")
    private Long storeId;

    @Column(nullable = false, unique = true)
    private String name;

    private String address;

    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false)
    @Convert(converter = LocalDateTimeTextConverter.class)
    private LocalDateTime createdAt;

    /** Required by JPA. Use {@link #create(StoreRequest)}. */
    protected Store() {
    }

    /** Creates a new, active store. @throws ValidationException if a field is invalid */
    public static Store create(StoreRequest request) {
        validate(request);
        Store store = new Store();
        store.applyDetails(request);
        store.status = AccountStatus.ACTIVE;
        store.createdAt = LocalDateTime.now().withNano(0);
        return store;
    }

    /** Changes name, address and phone. @throws ValidationException if a field is invalid */
    public void updateDetails(StoreRequest request) {
        validate(request);
        applyDetails(request);
    }

    public void setStatus(AccountStatus newStatus) {
        this.status = newStatus;
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    private void applyDetails(StoreRequest r) {
        this.name = r.name().trim();
        this.address = blankToNull(r.address());
        this.phone = blankToNull(r.phone());
    }

    public static void validate(StoreRequest r) {
        Map<String, String> errors = new LinkedHashMap<>();
        String name = r == null || r.name() == null ? "" : r.name().trim();
        if (name.isEmpty()) errors.put("name", "Store name is required");
        else if (name.length() < 2) errors.put("name", "Store name must be at least 2 characters");
        else if (name.length() > MAX_NAME_LENGTH) errors.put("name", "Store name must be " + MAX_NAME_LENGTH + " characters or fewer");

        if (r != null && r.address() != null && r.address().trim().length() > MAX_ADDRESS_LENGTH) {
            errors.put("address", "Address must be " + MAX_ADDRESS_LENGTH + " characters or fewer");
        }
        if (r != null && r.phone() != null && !r.phone().isBlank()) {
            String phone = r.phone().trim();
            if (phone.length() > MAX_PHONE_LENGTH || !phone.matches("[0-9+()\\- ]+")) {
                errors.put("phone", "Phone may contain only digits, spaces, +, - and brackets");
            }
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public Long getStoreId() { return storeId; }
    public String getName() { return name; }
    public String getAddress() { return address; }
    public String getPhone() { return phone; }
    public AccountStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

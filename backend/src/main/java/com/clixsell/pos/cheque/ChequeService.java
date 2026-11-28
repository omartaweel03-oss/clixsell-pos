package com.clixsell.pos.cheque;

import com.clixsell.pos.auth.CurrentUser;
import com.clixsell.pos.common.ConflictException;
import com.clixsell.pos.common.NotFoundException;
import com.clixsell.pos.common.ValidationException;
import com.clixsell.pos.sale.SaleRequest;
import com.clixsell.pos.shift.ShiftQueries;
import com.clixsell.pos.user.Role;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CHEQUE payments (feature 9, D6).
 *
 * <ul>
 *   <li>At checkout the cheque (number, bank, due date, notes) is recorded RECEIVED for the full
 *       invoice amount, in the same transaction as the sale.</li>
 *   <li>Lifecycle (Store Admin): RECEIVED → DEPOSITED | CANCELLED; DEPOSITED → CLEARED | BOUNCED;
 *       BOUNCED → DEPOSITED (deposited again) | CANCELLED. Every change is an event (who, when, note);
 *       BOUNCED and CANCELLED need a note. The database applies a status only through its event.</li>
 *   <li>A return of a cheque sale (Store Admin only) creates a PENDING cheque refund: never automatic
 *       cash. It is paid once the cheque is CLEARED — by bank transfer, or in cash from the admin's open
 *       shift, where the drawer movement and the PAID decision are written in ONE transaction — or
 *       cancelled with a reason (e.g. the cheque bounced and was given back).</li>
 * </ul>
 */
@Service
@Transactional
public class ChequeService {

    private static final DateTimeFormatter TEXT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final Pattern REQUEST_KEY = Pattern.compile("[A-Za-z0-9_-]{8,100}");
    static final Map<String, Set<String>> NEXT = Map.of(
            "RECEIVED", Set.of("DEPOSITED", "CANCELLED"),
            "DEPOSITED", Set.of("CLEARED", "BOUNCED"),
            "BOUNCED", Set.of("DEPOSITED", "CANCELLED"),
            "CLEARED", Set.of(),
            "CANCELLED", Set.of());

    private final JdbcTemplate db;
    private final ShiftQueries shifts;

    public ChequeService(JdbcTemplate db, ShiftQueries shifts) {
        this.db = db;
        this.shifts = shifts;
    }

    static String now() {
        return LocalDateTime.now().withNano(0).format(TEXT);
    }

    // ── checkout ────────────────────────────────────────────────────────────────────

    /** Validates the cheque of a CHEQUE sale BEFORE anything is changed. Returns the cleaned details. */
    public SaleRequest.Cheque validate(long storeId, SaleRequest.Cheque c, long totalMinor) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (c == null) throw new ValidationException(Map.of("cheque", "Enter the cheque number, bank and due date"));
        String number = c.number() == null ? "" : c.number().strip();
        String bank = c.bank() == null ? "" : c.bank().strip();
        if (number.isEmpty() || number.length() > 30) errors.put("cheque.number", "Enter the cheque number (1–30 characters)");
        if (bank.length() < 2 || bank.length() > 80) errors.put("cheque.bank", "Enter the bank name (2–80 characters)");
        if (c.dueDate() == null) errors.put("cheque.dueDate", "Enter the due date");
        else if (c.dueDate().isBefore(LocalDate.now().minusYears(1)) || c.dueDate().isAfter(LocalDate.now().plusYears(2))) {
            errors.put("cheque.dueDate", "The due date is not plausible");
        }
        String notes = c.notes() == null || c.notes().isBlank() ? null : c.notes().strip();
        if (notes != null && notes.length() > 300) errors.put("cheque.notes", "Notes must be 300 characters or fewer");
        if (c.amount() != null && c.amount().compareTo(BigDecimal.valueOf(totalMinor, 2)) != 0) {
            errors.put("cheque.amount", "The cheque amount must be the invoice total " + BigDecimal.valueOf(totalMinor, 2).toPlainString());
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
        Integer dup = db.queryForObject("SELECT COUNT(*) FROM cheques WHERE store_id = ? AND bank_name = ? COLLATE NOCASE "
                + "AND cheque_number = ?", Integer.class, storeId, bank, number);
        if (dup != null && dup > 0) {
            throw new ConflictException("CHEQUE_DUPLICATE", "cheque.number", "Cheque " + number + " of " + bank + " is already recorded");
        }
        return new SaleRequest.Cheque(number, bank, BigDecimal.valueOf(totalMinor, 2), c.dueDate(), notes);
    }

    /** Records the cheque of a just-completed (and flushed) CHEQUE sale; same transaction. */
    public long record(long storeId, long saleId, long userId, SaleRequest.Cheque c) {
        Long paymentId = db.queryForObject("SELECT payment_id FROM payments WHERE sale_id = ? AND payment_method = 'CHEQUE'",
                Long.class, saleId);
        long amount = c.amount().movePointRight(2).longValueExact();
        String now = now();
        db.update("INSERT INTO cheques (store_id, sale_id, payment_id, cheque_number, bank_name, amount_minor, due_date, notes, "
                        + "status, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'RECEIVED', ?, ?, ?)",
                storeId, saleId, paymentId, c.number(), c.bank(), amount, c.dueDate().toString(), c.notes(), userId, now, now);
        return db.queryForObject("SELECT cheque_id FROM cheques WHERE sale_id = ?", Long.class, saleId);
    }

    // ── returns of cheque sales ─────────────────────────────────────────────────────

    /** D6: only a Store Admin returns a cheque sale (checked before anything changes). */
    public void requireReturnAllowed(CurrentUser user, long saleId) {
        if (user.role() != Role.STORE_ADMIN) {
            throw new ConflictException("CHEQUE_RETURN_NEEDS_STORE_ADMIN", null,
                    "This invoice was paid by cheque: its return is handled by the Store Admin");
        }
        String status = db.queryForObject("SELECT status FROM cheques WHERE sale_id = ?", String.class, saleId);
        if ("CANCELLED".equals(status)) {
            throw new ConflictException("CHEQUE_CANCELLED", null, "The cheque of this invoice was cancelled; nothing is owed back");
        }
    }

    /** The PENDING refund owed for a return of a cheque sale (written right after the return). */
    public void owe(long storeId, long saleId, long returnId, long amountMinor) {
        if (amountMinor <= 0) return;
        Long cheque = db.queryForObject("SELECT cheque_id FROM cheques WHERE sale_id = ?", Long.class, saleId);
        db.update("INSERT INTO cheque_refunds (store_id, sale_id, return_id, cheque_id, amount_minor, status, created_at) "
                + "VALUES (?, ?, ?, ?, ?, 'PENDING', ?)", storeId, saleId, returnId, cheque, amountMinor, now());
    }

    // ── Store Admin management ──────────────────────────────────────────────────────

    public record ChequeView(long chequeId, long saleId, String invoiceNumber, String chequeNumber, String bankName,
                             double amount, String dueDate, String notes, String status, String createdByName,
                             String createdAt, String updatedAt, long version, double pendingRefunds) {
    }

    public record Event(String fromStatus, String toStatus, String userName, String createdAt, String note) {
    }

    public record Refund(long chequeRefundId, long returnId, double amount, String status, String paidMethod,
                         String decidedByName, String decidedAt, String reason, String createdAt, long version) {
    }

    public record Details(ChequeView cheque, List<Event> events, List<Refund> refunds) {
    }

    public record Summary(Map<String, Long> countByStatus, Map<String, Double> amountByStatus, long dueSoonCount,
                          double dueSoonAmount, long pendingRefundCount, double pendingRefundAmount) {
    }

    private static final String SELECT = """
            SELECT c.cheque_id, c.sale_id, s.invoice_number, c.cheque_number, c.bank_name, c.amount_minor, c.due_date,
                   c.notes, c.status, u.full_name, c.created_at, c.updated_at, c.version,
                   coalesce((SELECT sum(amount_minor) FROM cheque_refunds r WHERE r.cheque_id = c.cheque_id
                             AND r.status = 'PENDING'), 0)
            FROM cheques c JOIN sales s ON s.sale_id = c.sale_id JOIN users u ON u.user_id = c.created_by
            """;

    private static ChequeView view(ResultSet rs) throws SQLException {
        return new ChequeView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                d(rs.getLong(6)), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),
                rs.getString(12), rs.getLong(13), d(rs.getLong(14)));
    }

    @Transactional(readOnly = true)
    public List<ChequeView> list(CurrentUser user, String status) {
        requireAdmin(user);
        String st = status == null || status.isBlank() ? null : status.strip().toUpperCase();
        if (st != null && !NEXT.containsKey(st)) throw new ValidationException(Map.of("status", "Unknown status " + status));
        return st == null
                ? db.query(SELECT + " WHERE c.store_id = ? ORDER BY c.due_date, c.cheque_id", (rs, i) -> view(rs), user.requireStoreId())
                : db.query(SELECT + " WHERE c.store_id = ? AND c.status = ? ORDER BY c.due_date, c.cheque_id", (rs, i) -> view(rs),
                        user.requireStoreId(), st);
    }

    @Transactional(readOnly = true)
    public Details details(CurrentUser user, long chequeId) {
        requireAdmin(user);
        ChequeView c = find(user.requireStoreId(), chequeId);
        List<Event> events = db.query("""
                SELECT e.from_status, e.to_status, u.full_name, e.created_at, e.note FROM cheque_events e
                JOIN users u ON u.user_id = e.user_id WHERE e.cheque_id = ? ORDER BY e.to_version""",
                (rs, i) -> new Event(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), chequeId);
        return new Details(c, events, refunds(chequeId));
    }

    @Transactional(readOnly = true)
    public Summary summary(CurrentUser user) {
        requireAdmin(user);
        long storeId = user.requireStoreId();
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, Double> amounts = new LinkedHashMap<>();
        for (String s : List.of("RECEIVED", "DEPOSITED", "CLEARED", "BOUNCED", "CANCELLED")) {
            counts.put(s, 0L);
            amounts.put(s, 0.0);
        }
        db.query("SELECT status, COUNT(*), sum(amount_minor) FROM cheques WHERE store_id = ? GROUP BY status", rs -> {
            counts.put(rs.getString(1), rs.getLong(2));
            amounts.put(rs.getString(1), d(rs.getLong(3)));
        }, storeId);
        String soon = LocalDate.now().plusDays(7).toString();
        Map<String, Object> due = db.queryForMap("SELECT COUNT(*) AS n, coalesce(sum(amount_minor), 0) AS a FROM cheques "
                + "WHERE store_id = ? AND status = 'RECEIVED' AND due_date <= ?", storeId, soon);
        Map<String, Object> pending = db.queryForMap("SELECT COUNT(*) AS n, coalesce(sum(amount_minor), 0) AS a FROM cheque_refunds "
                + "WHERE store_id = ? AND status = 'PENDING'", storeId);
        return new Summary(counts, amounts, ((Number) due.get("n")).longValue(), d(((Number) due.get("a")).longValue()),
                ((Number) pending.get("n")).longValue(), d(((Number) pending.get("a")).longValue()));
    }

    /** One status change, as an event (the database applies it). */
    public Details changeStatus(CurrentUser user, long chequeId, String toStatus, String note, Long expectedVersion) {
        requireAdmin(user);
        long storeId = user.requireStoreId();
        shifts.takeWriteLock();
        ChequeView c = find(storeId, chequeId);
        String to = toStatus == null ? "" : toStatus.strip().toUpperCase();
        if (!NEXT.getOrDefault(c.status(), Set.of()).contains(to)) {
            throw new ConflictException("CHEQUE_TRANSITION_NOT_ALLOWED", "toStatus",
                    "A " + c.status().toLowerCase() + " cheque cannot become " + (to.isEmpty() ? "?" : to.toLowerCase()));
        }
        if (expectedVersion != null && expectedVersion != c.version()) {
            throw new ConflictException("CHEQUE_CHANGED", null, "This cheque changed in the meantime; reload it");
        }
        String n = note == null || note.isBlank() ? null : note.strip();
        if (n != null && (n.length() < 3 || n.length() > 300)) throw new ValidationException(Map.of("note", "The note must be 3–300 characters"));
        if (n == null && (to.equals("BOUNCED") || to.equals("CANCELLED"))) {
            throw new ValidationException(Map.of("note", "Explain why (3–300 characters)"));
        }
        db.update("INSERT INTO cheque_events (cheque_id, store_id, from_status, to_status, to_version, user_id, created_at, note) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", chequeId, storeId, c.status(), to, c.version() + 1, user.userId(), now(), n);
        return details(user, chequeId);
    }

    /** Pays a PENDING refund of a CLEARED cheque: bank transfer, or cash from the admin's open shift (atomic). */
    public Details payRefund(CurrentUser user, long refundId, String method, String requestKey) {
        requireAdmin(user);
        long storeId = user.requireStoreId();
        shifts.takeWriteLock();
        Refund r = refund(storeId, refundId);
        long chequeId = db.queryForObject("SELECT cheque_id FROM cheque_refunds WHERE cheque_refund_id = ?", Long.class, refundId);
        if (!"PENDING".equals(r.status())) throw new ConflictException("CHEQUE_REFUND_DECIDED", null, "This refund was already " + r.status().toLowerCase());
        String chequeStatus = find(storeId, chequeId).status();
        if (!"CLEARED".equals(chequeStatus)) {
            throw new ConflictException("CHEQUE_NOT_CLEARED", null,
                    "The cheque is " + chequeStatus.toLowerCase() + ": a refund is paid only once the cheque has cleared");
        }
        String m = method == null ? "" : method.strip().toUpperCase();
        long amount = BigDecimal.valueOf(r.amount()).movePointRight(2).longValueExact();
        String now = now();
        if (m.equals("BANK_TRANSFER")) {
            db.update("UPDATE cheque_refunds SET status = 'PAID', paid_method = 'BANK_TRANSFER', decided_by = ?, decided_at = ?, "
                    + "version = version + 1 WHERE cheque_refund_id = ? AND status = 'PENDING'", user.userId(), now, refundId);
        } else if (m.equals("CASH")) {
            if (requestKey == null || !REQUEST_KEY.matcher(requestKey).matches()) {
                throw new ValidationException(Map.of("requestKey", "A request key of 8–100 letters, digits, - or _ is required"));
            }
            ShiftQueries.Shift shift = shifts.openShiftOf(storeId, user.userId()).orElseThrow(() -> new ConflictException(
                    "SHIFT_REQUIRED", null, "Open your cash shift first: the cash is paid from your drawer"));
            if (amount > shifts.cashParts(shift.shiftId()).expectedMinor()) {
                throw new ConflictException("CASH_OUT_TOO_LARGE", null, "Your drawer should not hold that much cash");
            }
            // the movement first, then the decision pointing to it: both checked by the database at COMMIT
            db.update("INSERT INTO cash_movements (store_id, shift_id, type, amount_minor, reason, cheque_refund_id, created_by, "
                    + "created_at, request_key) VALUES (?, ?, 'CHEQUE_REFUND_OUT', ?, ?, ?, ?, ?, ?)", storeId, shift.shiftId(), amount,
                    "Cheque refund (return " + r.returnId() + ")", refundId, user.userId(), now, requestKey);
            Long movement = db.queryForObject("SELECT movement_id FROM cash_movements WHERE cheque_refund_id = ?", Long.class, refundId);
            db.update("UPDATE cheque_refunds SET status = 'PAID', paid_method = 'CASH', payout_movement_id = ?, decided_by = ?, "
                    + "decided_at = ?, version = version + 1 WHERE cheque_refund_id = ? AND status = 'PENDING'",
                    movement, user.userId(), now, refundId);
        } else {
            throw new ValidationException(Map.of("method", "Pay by CASH or BANK_TRANSFER"));
        }
        return details(user, chequeId);
    }

    public Details cancelRefund(CurrentUser user, long refundId, String reason) {
        requireAdmin(user);
        long storeId = user.requireStoreId();
        shifts.takeWriteLock();
        Refund r = refund(storeId, refundId);
        if (!"PENDING".equals(r.status())) throw new ConflictException("CHEQUE_REFUND_DECIDED", null, "This refund was already " + r.status().toLowerCase());
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 3 || why.length() > 300) throw new ValidationException(Map.of("reason", "Explain why (3–300 characters)"));
        db.update("UPDATE cheque_refunds SET status = 'CANCELLED', decided_by = ?, decided_at = ?, reason = ?, version = version + 1 "
                + "WHERE cheque_refund_id = ? AND status = 'PENDING'", user.userId(), now(), why, refundId);
        long chequeId = db.queryForObject("SELECT cheque_id FROM cheque_refunds WHERE cheque_refund_id = ?", Long.class, refundId);
        return details(user, chequeId);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────

    private ChequeView find(long storeId, long chequeId) {
        return db.query(SELECT + " WHERE c.store_id = ? AND c.cheque_id = ?", (rs, i) -> view(rs), storeId, chequeId)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("CHEQUE_NOT_FOUND", "This cheque was not found"));
    }

    private List<Refund> refunds(long chequeId) {
        return db.query("""
                SELECT r.cheque_refund_id, r.return_id, r.amount_minor, r.status, r.paid_method, u.full_name, r.decided_at,
                       r.reason, r.created_at, r.version
                FROM cheque_refunds r LEFT JOIN users u ON u.user_id = r.decided_by
                WHERE r.cheque_id = ? ORDER BY r.cheque_refund_id""",
                (rs, i) -> new Refund(rs.getLong(1), rs.getLong(2), d(rs.getLong(3)), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getLong(10)), chequeId);
    }

    private Refund refund(long storeId, long refundId) {
        Optional<Long> cheque = db.query("SELECT cheque_id FROM cheque_refunds WHERE cheque_refund_id = ? AND store_id = ?",
                (rs, i) -> rs.getLong(1), refundId, storeId).stream().findFirst();
        if (cheque.isEmpty()) throw new NotFoundException("CHEQUE_REFUND_NOT_FOUND", "This refund was not found");
        return refunds(cheque.get()).stream().filter(x -> x.chequeRefundId() == refundId).findFirst().orElseThrow();
    }

    private static void requireAdmin(CurrentUser user) {
        if (user.role() != Role.STORE_ADMIN) throw new AccessDeniedException("Store Admin only");
    }

    static double d(long minor) {
        return BigDecimal.valueOf(minor, 2).doubleValue();
    }
}

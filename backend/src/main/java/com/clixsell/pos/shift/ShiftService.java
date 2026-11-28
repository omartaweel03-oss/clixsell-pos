package com.clixsell.pos.shift;

import com.clixsell.pos.auth.CurrentUser;
import com.clixsell.pos.common.ConflictException;
import com.clixsell.pos.common.NotFoundException;
import com.clixsell.pos.common.ValidationException;
import com.clixsell.pos.shift.ShiftQueries.CashParts;
import com.clixsell.pos.shift.ShiftQueries.Shift;
import com.clixsell.pos.store.StoreRepository;
import com.clixsell.pos.user.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Cash drawers and cash shifts (features 1 and 2; decisions D2, D3).
 *
 * <p>A "drawer" here is a LOGICAL cash drawer: a named place the cash is counted for. Opening a
 * shift does not open any physical drawer; no drawer hardware is connected or claimed.
 *
 * <ul>
 *   <li>A cashier (or a Store Admin working the till) opens their own shift on a free, active drawer
 *       with the counted opening cash; one open shift per user and per drawer.</li>
 *   <li>Sales and cash refunds of that user go into the open shift (see SaleService / ReturnService);
 *       when the store requires shifts (D2) they are refused without one.</li>
 *   <li>The cashier closes their own shift with the counted cash; the expected cash is calculated
 *       here and checked again by the database at the same moment. A difference needs a note.</li>
 *   <li>A Store Admin may close any open shift of the store as an exception, with a reason (D3), and
 *       is the only one who records cash in / cash out (D3).</li>
 * </ul>
 * Closed shifts, their sales, payments and returns are frozen by the database (V7).
 */
@Service
@Transactional
public class ShiftService {

    static final long MAX_CASH_MINOR = 100_000_000L; // 1,000,000.00
    private static final Pattern REQUEST_KEY = Pattern.compile("[A-Za-z0-9_-]{8,100}");
    private static final DateTimeFormatter TEXT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final ShiftQueries q;
    private final StoreRepository stores;

    public ShiftService(ShiftQueries q, StoreRepository stores) {
        this.q = q;
        this.stores = stores;
    }

    static String now() {
        return LocalDateTime.now().withNano(0).format(TEXT);
    }

    // ── drawers (Store Admin manages; everyone in the store can list) ───────────────

    @Transactional(readOnly = true)
    public List<ShiftQueries.Drawer> drawers(CurrentUser user) {
        return q.drawers(user.requireStoreId());
    }

    public ShiftQueries.Drawer createDrawer(CurrentUser user, String name) {
        requireAdmin(user);
        long storeId = user.requireStoreId();
        String n = drawerName(name);
        if (q.drawerNameTaken(storeId, n, null)) {
            throw new ConflictException("DRAWER_NAME_TAKEN", "name", "A drawer named \"" + n + "\" already exists");
        }
        long id = q.createDrawer(storeId, n, now());
        return q.drawer(storeId, id).orElseThrow();
    }

    public ShiftQueries.Drawer updateDrawer(CurrentUser user, long drawerId, String name, Boolean active) {
        requireAdmin(user);
        long storeId = user.requireStoreId();
        ShiftQueries.Drawer d = q.drawer(storeId, drawerId)
                .orElseThrow(() -> new NotFoundException("DRAWER_NOT_FOUND", "This drawer was not found"));
        String n = name == null ? d.name() : drawerName(name);
        boolean a = active == null ? d.active() : active;
        if (q.drawerNameTaken(storeId, n, drawerId)) {
            throw new ConflictException("DRAWER_NAME_TAKEN", "name", "A drawer named \"" + n + "\" already exists");
        }
        if (!a && d.inUse()) {
            throw new ConflictException("DRAWER_IN_USE", null, "This drawer has an open shift; close it first");
        }
        q.updateDrawer(storeId, drawerId, n, a);
        return q.drawer(storeId, drawerId).orElseThrow();
    }

    private static String drawerName(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 60) {
            throw new ValidationException(Map.of("name", "Enter a drawer name of 1–60 characters"));
        }
        return n;
    }

    // ── the user's own shift ────────────────────────────────────────────────────────

    /** The caller's open shift with its live cash summary, or empty. */
    @Transactional(readOnly = true)
    public Optional<ShiftView> current(CurrentUser user) {
        return q.openShiftOf(user.requireStoreId(), user.userId()).map(this::view);
    }

    public ShiftView open(CurrentUser user, Long drawerId, BigDecimal openingCash) {
        requireTillUser(user);
        long storeId = user.requireStoreId();
        q.takeWriteLock();
        if (drawerId == null) throw new ValidationException(Map.of("drawerId", "Choose a drawer"));
        long opening = minor("openingCash", openingCash, true);
        if (q.openShiftOf(storeId, user.userId()).isPresent()) {
            throw new ConflictException("SHIFT_ALREADY_OPEN", null, "You already have an open shift; close it first");
        }
        ShiftQueries.Drawer d = q.drawer(storeId, drawerId)
                .orElseThrow(() -> new NotFoundException("DRAWER_NOT_FOUND", "This drawer was not found"));
        if (!d.active()) throw new ConflictException("DRAWER_INACTIVE", "drawerId", "This drawer is not in use");
        if (d.inUse()) throw new ConflictException("DRAWER_IN_USE", "drawerId", "This drawer already has an open shift");
        long id = q.openShift(storeId, drawerId, user.userId(), opening, now());
        return view(q.shift(storeId, id).orElseThrow());
    }

    /** The cashier closes their OWN open shift with the counted cash (D3). */
    public ShiftView closeOwn(CurrentUser user, long shiftId, BigDecimal countedCash, String note, Long expectedVersion) {
        long storeId = user.requireStoreId();
        q.takeWriteLock();
        Shift s = find(storeId, shiftId);
        if (s.cashierId() != user.userId()) {
            throw new AccessDeniedException("Only the shift's own cashier closes it this way");
        }
        return close(user, s, countedCash, note, null, "CASHIER", expectedVersion);
    }

    /** A Store Admin closes any open shift of the store, as an exception, with a reason (D3). */
    public ShiftView closeAsAdmin(CurrentUser user, long shiftId, BigDecimal countedCash, String note, String reason,
                                  Long expectedVersion) {
        requireAdmin(user);
        long storeId = user.requireStoreId();
        q.takeWriteLock();
        Shift s = find(storeId, shiftId);
        String r = text("reason", reason, true, "Enter the reason for closing this shift (3–300 characters)");
        return close(user, s, countedCash, note, r, "ADMIN_EXCEPTION", expectedVersion);
    }

    private ShiftView close(CurrentUser user, Shift s, BigDecimal countedCash, String note, String reason, String kind,
                            Long expectedVersion) {
        if (!"OPEN".equals(s.status())) {
            throw new ConflictException("SHIFT_CLOSED", null, "This shift is already closed");
        }
        if (expectedVersion != null && expectedVersion != s.version()) {
            throw new ConflictException("SHIFT_CHANGED", null, "This shift changed; reload it and try again");
        }
        long counted = minor("countedCash", countedCash, true);
        long expected = q.cashParts(s.shiftId()).expectedMinor();
        String n = text("note", note, false, "The note must be 3–300 characters");
        if (counted != expected && n == null) {
            throw new ValidationException(Map.of("note", "The counted cash differs from the expected cash by "
                    + money(counted - expected) + "; explain the difference in a note"));
        }
        int rows = q.close(s.storeId(), s.shiftId(), s.version(), now(), user.userId(), kind, reason, expected, counted, n);
        if (rows != 1) throw new ConflictException("SHIFT_CLOSED", null, "This shift was closed in the meantime");
        return view(q.shift(s.storeId(), s.shiftId()).orElseThrow());
    }

    // ── Store Admin: overview, details, cash in / out ───────────────────────────────

    @Transactional(readOnly = true)
    public List<ShiftView> list(CurrentUser user, String status, LocalDate from, LocalDate to) {
        requireAdmin(user);
        String st = status == null || status.isBlank() ? null : status.strip().toUpperCase();
        if (st != null && !st.equals("OPEN") && !st.equals("CLOSED")) {
            throw new ValidationException(Map.of("status", "Status is OPEN or CLOSED"));
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw new ValidationException(Map.of("to", "The end date is before the start date"));
        }
        return q.shifts(user.requireStoreId(), st, from == null ? null : from.atStartOfDay().format(TEXT),
                to == null ? null : to.plusDays(1).atStartOfDay().format(TEXT), 500).stream().map(this::view).toList();
    }

    /** One shift with its sales and movements: the Store Admin, or its own cashier. */
    @Transactional(readOnly = true)
    public ShiftDetails details(CurrentUser user, long shiftId) {
        Shift s = find(user.requireStoreId(), shiftId);
        if (user.role() != Role.STORE_ADMIN && s.cashierId() != user.userId()) {
            throw new AccessDeniedException("Not your shift");
        }
        return new ShiftDetails(view(s), q.sales(shiftId), q.movements(shiftId));
    }

    /** Cash in / cash out of an open shift: Store Admin only (D3); idempotent by requestKey. */
    public ShiftQueries.Movement addMovement(CurrentUser user, long shiftId, String type, BigDecimal amount, String reason,
                                             String requestKey) {
        requireAdmin(user);
        long storeId = user.requireStoreId();
        q.takeWriteLock();
        if (requestKey == null || !REQUEST_KEY.matcher(requestKey).matches()) {
            throw new ValidationException(Map.of("requestKey", "A request key of 8–100 letters, digits, - or _ is required"));
        }
        Optional<ShiftQueries.Movement> again = q.movementByKey(storeId, requestKey);
        if (again.isPresent()) {
            ShiftQueries.Movement m = again.get();
            Long ofShift = q.movementShift(m.movementId());
            if (ofShift == null || ofShift != shiftId || !m.type().equals(type)) {
                throw new ConflictException("REQUEST_KEY_USED", "requestKey", "This request key was used for another movement");
            }
            return m; // the same request sent again: nothing new is recorded
        }
        if (!"CASH_IN".equals(type) && !"CASH_OUT".equals(type)) {
            throw new ValidationException(Map.of("type", "Type is CASH_IN or CASH_OUT"));
        }
        long amountMinor = minor("amount", amount, false);
        String r = text("reason", reason, true, "Enter the reason (3–300 characters)");
        Shift s = find(storeId, shiftId);
        if (!"OPEN".equals(s.status())) throw new ConflictException("SHIFT_CLOSED", null, "This shift is already closed");
        if ("CASH_OUT".equals(type) && amountMinor > q.cashParts(shiftId).expectedMinor()) {
            throw new ConflictException("CASH_OUT_TOO_LARGE", "amount", "The drawer should only hold "
                    + money(q.cashParts(shiftId).expectedMinor()) + "; a larger cash out is not possible");
        }
        long id = q.addMovement(storeId, shiftId, type, amountMinor, r, user.userId(), now(), requestKey);
        return q.movements(shiftId).stream().filter(m -> m.movementId() == id).findFirst().orElseThrow();
    }

    // ── for sales and returns ───────────────────────────────────────────────────────

    /**
     * The shift a sale or cash refund of this user goes into: their open shift, or none. When the
     * store requires shifts (D2) and there is none, the operation is refused before anything changes.
     */
    @Transactional(readOnly = true)
    public Long shiftForTill(CurrentUser user, boolean required) {
        long storeId = user.requireStoreId();
        Optional<Shift> s = q.openShiftOf(storeId, user.userId());
        if (s.isPresent()) return s.get().shiftId();
        if (required && stores.findById(storeId).map(st -> st.isRequireCashShift()).orElse(false)) {
            throw new ConflictException("SHIFT_REQUIRED", null,
                    "Open a cash shift first: this store requires one for every sale and cash refund");
        }
        return null;
    }

    /**
     * Approved cash refund policy: a CASH refund is paid from the user's open drawer and only when the
     * drawer holds enough recorded cash. Takes the write lock first (same transaction as the refund),
     * so two refunds can never spend the same cash. Returns the shift to record the refund in.
     * No open shift = unknown balance = refused (also in stores where shifts are optional).
     */
    public long shiftForCashRefund(CurrentUser user) {
        long storeId = user.requireStoreId();
        q.takeWriteLock();
        return q.openShiftOf(storeId, user.userId()).map(Shift::shiftId).orElseThrow(() -> new ConflictException(
                "CASH_REFUND_NEEDS_SHIFT", null,
                "A cash refund is paid from your cash drawer: open your cash shift first"));
    }

    /** The recorded cash the shift's drawer should hold now (the closing formula), in agorot. */
    public long drawerCashMinor(long shiftId) {
        return q.cashParts(shiftId).expectedMinor();
    }

    // ── views ───────────────────────────────────────────────────────────────────────

    public record ShiftView(long shiftId, long drawerId, String drawerName, long cashierId, String cashierName,
                            String status, String openedAt, double openingCash, double cashSales, int cashSalesCount,
                            double cashRefunds, int cashRefundsCount, double cashIn, double cashOut,
                            double chequeRefundsPaid, double otherSales, int otherSalesCount, double expectedCash,
                            String closedAt, String closedByName, String closeKind, String closeReason,
                            Double countedCash, Double difference, String closingNote, long version) {
    }

    public record ShiftDetails(ShiftView shift, List<ShiftQueries.ShiftSale> sales,
                               List<ShiftQueries.Movement> movements) {
    }

    ShiftView view(Shift s) {
        CashParts p = q.cashParts(s.shiftId());
        // a closed shift shows what was recorded at closing (frozen); an open one the live amount
        long expected = s.expectedCashMinor() != null ? s.expectedCashMinor() : p.expectedMinor();
        return new ShiftView(s.shiftId(), s.drawerId(), s.drawerName(), s.cashierId(), s.cashierName(), s.status(),
                s.openedAt(), d(p.openingMinor()), d(p.cashSalesMinor()), p.cashSalesCount(), d(p.cashRefundsMinor()),
                p.cashRefundsCount(), d(p.cashInMinor()), d(p.cashOutMinor()), d(p.chequeRefundsPaidMinor()),
                d(p.otherSalesMinor()), p.otherSalesCount(), d(expected), s.closedAt(), s.closedByName(), s.closeKind(),
                s.closeReason(), s.countedCashMinor() == null ? null : d(s.countedCashMinor()),
                s.differenceMinor() == null ? null : d(s.differenceMinor()), s.closingNote(), s.version());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────

    private Shift find(long storeId, long shiftId) {
        return q.shift(storeId, shiftId).orElseThrow(() -> new NotFoundException("SHIFT_NOT_FOUND", "This shift was not found"));
    }

    private static void requireAdmin(CurrentUser user) {
        if (user.role() != Role.STORE_ADMIN) throw new AccessDeniedException("Store Admin only");
    }

    private static void requireTillUser(CurrentUser user) {
        if (user.role() != Role.STORE_ADMIN && user.role() != Role.CASHIER) throw new AccessDeniedException("Store users only");
    }

    /** An amount of money in agorot: ≥ 0 (or > 0), at most 2 decimals, at most 1,000,000.00. */
    static long minor(String field, BigDecimal amount, boolean zeroAllowed) {
        if (amount == null) throw new ValidationException(Map.of(field, "Enter the amount"));
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new ValidationException(Map.of(field, "Use at most 2 decimals"));
        }
        if (amount.signum() < 0 || (!zeroAllowed && amount.signum() == 0)) {
            throw new ValidationException(Map.of(field, zeroAllowed ? "The amount cannot be negative" : "The amount must be more than 0"));
        }
        long m = amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        if (m > MAX_CASH_MINOR) throw new ValidationException(Map.of(field, "The amount is too large"));
        return m;
    }

    private static String text(String field, String value, boolean required, String message) {
        String v = value == null ? null : value.strip();
        if (v == null || v.isEmpty()) {
            if (required) throw new ValidationException(Map.of(field, message));
            return null;
        }
        if (v.length() < 3 || v.length() > 300) throw new ValidationException(Map.of(field, message));
        return v;
    }

    static double d(long minor) {
        return BigDecimal.valueOf(minor, 2).doubleValue();
    }

    static String money(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }
}

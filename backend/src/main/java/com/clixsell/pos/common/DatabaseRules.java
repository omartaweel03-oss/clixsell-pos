package com.clixsell.pos.common;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The database's own rules (V6/V7 triggers) refuse a change with a code, e.g.
 * {@code RAISE(ABORT, 'SHIFT_CLOSED_RECORD')}. The services check the same rules first and answer
 * with a clear message; when a rule is still hit (a race, or a path the service did not foresee),
 * this turns the code into the same kind of clear 409 answer instead of a server error.
 */
public final class DatabaseRules {

    private DatabaseRules() {
    }

    private static final Pattern CODE = Pattern.compile("abort \\(([A-Z][A-Z0-9_]{2,60})\\)");

    /** Plain messages for the codes a user can meet; any other code gets a generic message. */
    static final Map<String, String> MESSAGES = Map.ofEntries(
            Map.entry("SHIFT_REQUIRED", "Open a cash shift first: this store requires one for every sale and cash refund"),
            Map.entry("SHIFT_CLOSED", "This shift is already closed"),
            Map.entry("SHIFT_CLOSED_RECORD", "This record belongs to a closed shift and can no longer be changed"),
            Map.entry("SHIFT_EXPECTED_MISMATCH", "The cash in this shift changed while it was being closed; review the amounts and close again"),
            Map.entry("SHIFT_BAD_OPEN", "This shift cannot be opened (check the drawer and the user)"),
            Map.entry("SHIFT_CLOSER_NOT_ALLOWED", "Only the shift's cashier, or a Store Admin with a reason, can close it"),
            Map.entry("SHIFT_IDENTITY", "A shift's opening details cannot be changed"),
            Map.entry("SHIFT_NO_DELETE", "Shifts are never deleted"),
            Map.entry("SALE_SHIFT_NOT_OPEN", "The shift of this sale is not open"),
            Map.entry("SALE_SHIFT_NEEDS_COMPLETED", "Only a completed sale belongs to a shift"),
            Map.entry("RETURN_SHIFT_NOT_OPEN", "The shift for this refund is not open"),
            Map.entry("CASH_MOVEMENT_NOT_ALLOWED", "This cash movement is not allowed (open shift and Store Admin required)"),
            Map.entry("CASH_MOVEMENTS_APPEND_ONLY", "Cash movements cannot be changed or deleted"),
            Map.entry("PAYMENT_AMOUNT_MISMATCH", "The payment does not match the invoice total"),
            Map.entry("PAYMENT_NO_UPDATE", "A recorded payment cannot be changed"),
            Map.entry("RETURN_AMOUNT_INVALID", "The refund amount is not valid for this invoice"),
            Map.entry("RETURN_FIXED", "A recorded return cannot be changed"),
            Map.entry("BARCODE_USED_BY_PACKAGE", "A package of this store already uses this barcode"),
            Map.entry("BARCODE_USED_BY_PRODUCT", "A product of this store already uses this barcode"),
            Map.entry("PACKAGE_IDENTITY_FIXED", "A package's size cannot change; add a new package instead"),
            Map.entry("SALE_ITEM_PACKAGE_SNAPSHOT", "The package on this invoice line is not consistent"),
            Map.entry("CHEQUE_EVENT_INVALID", "This cheque status change is not allowed"),
            Map.entry("CHEQUE_UPDATE_WITHOUT_EVENT", "A cheque changes only through a recorded status change"),
            Map.entry("CHEQUE_REFUND_BAD_DECISION", "This refund decision is not allowed (a refund is paid only once the cheque cleared)"),
            Map.entry("CHEQUE_RETURN_NEEDS_STORE_ADMIN", "This invoice was paid by cheque: its return is handled by the Store Admin"),
            Map.entry("CHEQUE_BAD_INSERT", "The cheque does not match its payment"),
            Map.entry("CHEQUE_REFUND_BAD_INSERT", "The refund is more than the cheque"));

    /** The rule code inside a database error, or null when it is not a rule refusal. */
    public static String code(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && m.contains("SQLITE_CONSTRAINT_TRIGGER")) {
                Matcher x = CODE.matcher(m);
                if (x.find()) return x.group(1);
            }
            if (t.getCause() == t) break;
        }
        return null;
    }

    public static String message(String code) {
        return MESSAGES.getOrDefault(code, "This change is not allowed by the store's records rules (" + code + ")");
    }
}

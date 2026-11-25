package com.clixsell.pos.purchase;

import com.clixsell.pos.common.StrictJson;
import com.clixsell.pos.common.UnknownFieldException;
import com.clixsell.pos.common.ValidationException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict reading of purchasing request bodies (decision D25). Only the fields below are
 * accepted; anything else (storeId, status, orderNumber, createdBy, suggestedQuantity,
 * expectedUnitCost, unitCost, price …) is refused with 400 UNKNOWN_FIELD. Every amount,
 * snapshot and number is calculated by the server.
 */
public final class PurchaseRequests {

    public static final int MAX_ITEMS = 50;
    public static final int MAX_NOTE = 500;
    public static final int MIN_REASON = 3;
    public static final int MAX_REASON = 300;

    private static final Set<String> CREATE_FIELDS = Set.of("clientRequestId", "supplierId", "source", "note", "items",
            "acknowledgedCautions");
    private static final Set<String> EDIT_FIELDS = Set.of("version", "note", "items", "acknowledgedCautions");
    private static final Set<String> ITEM_FIELDS = Set.of("productId", "quantity");
    private static final Set<String> ACK_FIELDS = Set.of("productId", "orderId");
    private static final Set<String> VERSION_FIELDS = Set.of("version");
    private static final Set<String> REASON_FIELDS = Set.of("version", "reason");
    private static final Set<String> CANCEL_FIELDS = Set.of("version", "reason", "contactStatus", "confirmNotSent",
            "acknowledgeSupplierNotNotified");
    private static final Set<String> GENERATE_FIELDS = Set.of("version", "language", "previewToken");
    private static final Set<String> COPY_FIELDS = Set.of("clientActionId");
    private static final Set<String> OPEN_FIELDS = Set.of("clientActionId", "confirmed");
    private static final Set<String> OPEN_RESULT_FIELDS = Set.of("clientActionId", "result");
    private static final Set<String> MARK_SENT_FIELDS = Set.of("version", "confirmSentInWhatsapp");
    private static final Set<String> CONFIRMATION_FIELDS = Set.of("version", "confirmSupplierConfirmed", "note");
    private static final Set<String> CLOSE_FIELDS = Set.of("version", "confirmNoReceiving");
    private static final Set<String> SUPPLIER_CANCEL_FIELDS = Set.of("confirmSupplierAccepted", "channel", "note");
    public static final int MAX_CONFIRMATION_NOTE = 300;
    public static final Set<String> CONTACT_STATUSES = Set.of("NOT_SENT", "UNKNOWN", "SENT");
    public static final Set<String> OPEN_RESULTS = Set.of("OPENED", "FAILED");
    public static final Set<String> CHANNELS = Set.of("PHONE", "WHATSAPP", "IN_PERSON", "OTHER");
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Item(long productId, int quantity) {
    }

    public record Ack(long productId, long orderId) {
    }

    public record Create(String clientRequestId, long supplierId, PurchaseOrder.Source source, String note,
                         List<Item> items, List<Ack> acks) {
    }

    public record Edit(int version, String note, List<Item> items, List<Ack> acks) {
    }

    public record Reason(int version, String reason) {
    }

    /**
     * Cancel. contactStatus / confirmNotSent are for an order whose message was generated
     * (MESSAGE_GENERATED); acknowledgeSupplierNotNotified for an order the manager recorded as
     * sent (SENT / CONFIRMED). Which ones are required is decided by the order's state.
     */
    public record Cancel(int version, String reason, String contactStatus, Boolean confirmNotSent,
                         Boolean acknowledgeSupplierNotNotified) {
    }

    public record Generate(int version, String language, String previewToken) {
    }

    public record OpenResult(String clientActionId, String result) {
    }

    public record Confirmation(int version, String note) {
    }

    public record SupplierCancellation(String channel, String note) {
    }

    private PurchaseRequests() {
    }

    public static Create create(Map<String, Object> body) {
        StrictJson.requireOnly(body, CREATE_FIELDS);
        Map<String, String> errors = new LinkedHashMap<>();
        String key = StrictJson.text(body, "clientRequestId");
        if (key == null || !UUID.matcher(key).matches()) {
            errors.put("clientRequestId", "A UUID is required (generated once per new order)");
        }
        Long supplierId = positiveLong(body.get("supplierId"), "supplierId", errors);
        String sourceText = StrictJson.text(body, "source");
        PurchaseOrder.Source source = null;
        if ("MANUAL".equals(sourceText)) source = PurchaseOrder.Source.MANUAL;
        else if ("GUARDIAN".equals(sourceText)) source = PurchaseOrder.Source.GUARDIAN;
        else errors.put("source", "Must be MANUAL or GUARDIAN");
        String note = note(body, errors);
        List<Item> items = items(body.get("items"), 1, errors);
        List<Ack> acks = acks(body.get("acknowledgedCautions"), errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return new Create(key.toLowerCase(), supplierId, source, note, items, acks);
    }

    public static Edit edit(Map<String, Object> body) {
        StrictJson.requireOnly(body, EDIT_FIELDS);
        Map<String, String> errors = new LinkedHashMap<>();
        Integer version = version(body, errors);
        String note = note(body, errors);
        List<Item> items = items(body.get("items"), 0, errors);
        List<Ack> acks = acks(body.get("acknowledgedCautions"), errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return new Edit(version, note, items, acks);
    }

    public static int version(Map<String, Object> body) {
        StrictJson.requireOnly(body, VERSION_FIELDS);
        Map<String, String> errors = new LinkedHashMap<>();
        Integer v = version(body, errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return v;
    }

    public static Reason reason(Map<String, Object> body) {
        StrictJson.requireOnly(body, REASON_FIELDS);
        Map<String, String> errors = new LinkedHashMap<>();
        Integer v = version(body, errors);
        String reason = reasonText(body, "reason", errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return new Reason(v, reason);
    }

    public static Cancel cancel(Map<String, Object> body) {
        StrictJson.requireOnly(body, CANCEL_FIELDS);
        Map<String, String> errors = new LinkedHashMap<>();
        Integer v = version(body, errors);
        String reason = reasonText(body, "reason", errors);
        String contact = StrictJson.text(body, "contactStatus");
        if (contact != null && !CONTACT_STATUSES.contains(contact)) {
            errors.put("contactStatus", "Must be NOT_SENT, UNKNOWN or SENT");
        }
        Boolean notSent = optionalBoolean(body, "confirmNotSent", errors);
        Boolean ack = optionalBoolean(body, "acknowledgeSupplierNotNotified", errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return new Cancel(v, reason, contact, notSent, ack);
    }

    public static String language(String raw) {
        String l = raw == null || raw.isBlank() ? "ar" : raw.trim();
        if (!WhatsappMessageBuilder.LANGUAGES.contains(l)) throw new ValidationException(Map.of("language", "Must be ar or en"));
        return l;
    }

    public static Generate generate(Map<String, Object> body) {
        StrictJson.requireOnly(body, GENERATE_FIELDS);
        Map<String, String> errors = new LinkedHashMap<>();
        Integer v = version(body, errors);
        String language = StrictJson.text(body, "language");
        if (language == null || !WhatsappMessageBuilder.LANGUAGES.contains(language)) {
            errors.put("language", "Must be ar or en (the language of the preview you saw)");
        }
        String token = StrictJson.text(body, "previewToken");
        if (token == null || !token.matches("[0-9a-f]{64}")) {
            errors.put("previewToken", "The previewToken of the message preview you saw is required");
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return new Generate(v, language, token);
    }

    /** {clientActionId} — a copy reported by the client. */
    public static String copied(Map<String, Object> body) {
        StrictJson.requireOnly(body, COPY_FIELDS);
        Map<String, String> errors = new LinkedHashMap<>();
        String id = actionId(body, errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return id;
    }

    /** {clientActionId, confirmed: true} — the manager confirmed the number and message first. */
    public static String openRequested(Map<String, Object> body) {
        StrictJson.requireOnly(body, OPEN_FIELDS);
        Map<String, String> errors = new LinkedHashMap<>();
        String id = actionId(body, errors);
        mustBeTrue(body, "confirmed", "Confirm the supplier number and the message before opening WhatsApp", errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return id;
    }

    public static OpenResult openResult(Map<String, Object> body) {
        StrictJson.requireOnly(body, OPEN_RESULT_FIELDS);
        Map<String, String> errors = new LinkedHashMap<>();
        String id = actionId(body, errors);
        String result = StrictJson.text(body, "result");
        if (result == null || !OPEN_RESULTS.contains(result)) errors.put("result", "Must be OPENED or FAILED");
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return new OpenResult(id, result);
    }

    public static int markSent(Map<String, Object> body) {
        StrictJson.requireOnly(body, MARK_SENT_FIELDS);
        Map<String, String> errors = new LinkedHashMap<>();
        Integer v = version(body, errors);
        mustBeTrue(body, "confirmSentInWhatsapp", "Confirm that you sent the message to the supplier yourself", errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return v;
    }

    public static Confirmation confirmation(Map<String, Object> body) {
        StrictJson.requireOnly(body, CONFIRMATION_FIELDS);
        Map<String, String> errors = new LinkedHashMap<>();
        Integer v = version(body, errors);
        mustBeTrue(body, "confirmSupplierConfirmed", "Confirm that the supplier confirmed this order", errors);
        String note = null;
        Object raw = body.get("note");
        if (raw != null && !(raw instanceof String)) {
            errors.put("note", "Must be text");
        } else {
            note = trim((String) raw);
            if (note != null && length(note) > MAX_CONFIRMATION_NOTE) {
                errors.put("note", "The note must be " + MAX_CONFIRMATION_NOTE + " characters or fewer");
            }
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return new Confirmation(v, note);
    }

    public static int close(Map<String, Object> body) {
        StrictJson.requireOnly(body, CLOSE_FIELDS);
        Map<String, String> errors = new LinkedHashMap<>();
        Integer v = version(body, errors);
        mustBeTrue(body, "confirmNoReceiving",
                "Confirm that closing does not record receiving: stock does not change", errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return v;
    }

    public static SupplierCancellation supplierCancellation(Map<String, Object> body) {
        StrictJson.requireOnly(body, SUPPLIER_CANCEL_FIELDS);
        Map<String, String> errors = new LinkedHashMap<>();
        mustBeTrue(body, "confirmSupplierAccepted", "Confirm that the supplier accepted the cancellation", errors);
        String channel = StrictJson.text(body, "channel");
        if (channel == null || !CHANNELS.contains(channel)) errors.put("channel", "Must be PHONE, WHATSAPP, IN_PERSON or OTHER");
        String note = reasonText(body, "note", errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return new SupplierCancellation(channel, note);
    }

    /** A required reason-like text: 3..300 characters (code points, as the database counts) after trimming. */
    private static String reasonText(Map<String, Object> body, String field, Map<String, String> errors) {
        Object raw = body.get(field);
        if (raw != null && !(raw instanceof String)) {
            errors.put(field, "Must be text");
            return null;
        }
        String text = trim((String) raw);
        if (text == null || length(text) < MIN_REASON) {
            errors.put(field, "A " + field + " of at least " + MIN_REASON + " characters is required");
        } else if (length(text) > MAX_REASON) {
            errors.put(field, "The " + field + " must be " + MAX_REASON + " characters or fewer");
        }
        return text;
    }

    /** Characters as SQLite's length() counts them (Unicode code points). */
    static int length(String s) {
        return s.codePointCount(0, s.length());
    }

    private static String actionId(Map<String, Object> body, Map<String, String> errors) {
        String id = StrictJson.text(body, "clientActionId");
        if (id == null || !UUID.matcher(id).matches()) {
            errors.put("clientActionId", "A UUID is required (generated once per action; resend the same one when retrying)");
            return null;
        }
        return id.toLowerCase();
    }

    private static void mustBeTrue(Map<String, Object> body, String field, String message, Map<String, String> errors) {
        if (!Boolean.TRUE.equals(body.get(field))) errors.put(field, message + " (" + field + ": true)");
    }

    private static Boolean optionalBoolean(Map<String, Object> body, String field, Map<String, String> errors) {
        Object v = body.get(field);
        if (v == null) return null;
        if (!(v instanceof Boolean b)) {
            errors.put(field, "Must be true or false");
            return null;
        }
        return b;
    }

    /**
     * SHA-256 (hex) of the meaningful client-controlled content of a create request plus the
     * logged-in user (version 1 of the canonical form). Server-calculated values are not part of it.
     */
    public static String hash(Create c, long userId) {
        StringBuilder b = new StringBuilder("{\"v\":1,\"userId\":").append(userId)
                .append(",\"supplierId\":").append(c.supplierId())
                .append(",\"source\":\"").append(c.source().name()).append('"')
                .append(",\"note\":").append(c.note() == null ? "null" : JSON.writeValueAsString(c.note()))
                .append(",\"items\":[");
        List<Item> items = new ArrayList<>(c.items());
        items.sort(Comparator.comparingLong(Item::productId));
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) b.append(',');
            b.append('[').append(items.get(i).productId()).append(',').append(items.get(i).quantity()).append(']');
        }
        b.append("],\"ack\":[");
        List<Ack> acks = new ArrayList<>(new java.util.LinkedHashSet<>(c.acks()));
        acks.sort(Comparator.comparingLong(Ack::productId).thenComparingLong(Ack::orderId));
        for (int i = 0; i < acks.size(); i++) {
            if (i > 0) b.append(',');
            b.append('[').append(acks.get(i).productId()).append(',').append(acks.get(i).orderId()).append(']');
        }
        b.append("]}");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(b.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────

    /** Removes white space (incl. no-break space) at both ends; empty → null. */
    static String trim(String s) {
        if (s == null) return null;
        String t = s.replaceAll("^[\\s\\u00A0]+|[\\s\\u00A0]+$", "");
        return t.isEmpty() ? null : t;
    }

    private static String note(Map<String, Object> body, Map<String, String> errors) {
        Object raw = body.get("note");
        if (raw == null) return null;
        if (!(raw instanceof String s)) {
            errors.put("note", "Must be text");
            return null;
        }
        String note = trim(s);
        if (note != null && length(note) > MAX_NOTE) errors.put("note", "The note must be " + MAX_NOTE + " characters or fewer");
        return note;
    }

    private static Integer version(Map<String, Object> body, Map<String, String> errors) {
        Long v = wholeNumber(body.get("version"));
        if (v == null || v < 0 || v > Integer.MAX_VALUE) {
            errors.put("version", "The order version you saw is required");
            return null;
        }
        return v.intValue();
    }

    private static Long wholeNumber(Object v) {
        if (v instanceof Integer i) return i.longValue();
        if (v instanceof Long l) return l;
        if (v instanceof BigInteger bi && bi.bitLength() < 63) return bi.longValue();
        return null;
    }

    private static Long positiveLong(Object v, String field, Map<String, String> errors) {
        Long n = wholeNumber(v);
        if (n == null || n <= 0) {
            errors.put(field, "Must be a positive whole number");
            return null;
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private static List<Item> items(Object raw, int min, Map<String, String> errors) {
        if (!(raw instanceof List<?> list)) {
            errors.put("items", "A list of {productId, quantity} is required");
            return List.of();
        }
        if (list.size() < min) errors.put("items", "Add at least one product");
        if (list.size() > MAX_ITEMS) errors.put("items", "At most " + MAX_ITEMS + " products per order");
        List<Item> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String f = "items[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> m)) {
                errors.put(f, "Must be {productId, quantity}");
                continue;
            }
            for (Object k : m.keySet()) {
                if (!ITEM_FIELDS.contains(String.valueOf(k))) throw new UnknownFieldException(f + "." + k);
            }
            Long pid = wholeNumber(m.get("productId"));
            Long qty = wholeNumber(m.get("quantity"));
            if (pid == null || pid <= 0) errors.put(f + ".productId", "Must be a positive whole number");
            if (qty == null || qty < 1 || qty > ReorderCalculator.MAX_QUANTITY) {
                errors.put(f + ".quantity", "Quantity must be a whole number from 1 to " + ReorderCalculator.MAX_QUANTITY);
            }
            if (pid != null && pid > 0 && !seen.add(pid)) errors.put(f + ".productId", "This product is already in the list");
            if (pid != null && pid > 0 && qty != null && qty >= 1 && qty <= ReorderCalculator.MAX_QUANTITY) {
                out.add(new Item(pid, qty.intValue()));
            }
        }
        return out;
    }

    private static List<Ack> acks(Object raw, Map<String, String> errors) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            errors.put("acknowledgedCautions", "A list of {productId, orderId} is expected");
            return List.of();
        }
        List<Ack> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String f = "acknowledgedCautions[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> m)) {
                errors.put(f, "Must be {productId, orderId}");
                continue;
            }
            for (Object k : m.keySet()) {
                if (!ACK_FIELDS.contains(String.valueOf(k))) throw new UnknownFieldException(f + "." + k);
            }
            Long pid = wholeNumber(m.get("productId"));
            Long oid = wholeNumber(m.get("orderId"));
            if (pid == null || pid <= 0 || oid == null || oid <= 0) {
                errors.put(f, "productId and orderId must be positive whole numbers");
            } else {
                out.add(new Ack(pid, oid));
            }
        }
        return out;
    }
}

package com.clixsell.pos.purchase;

import com.clixsell.pos.auth.CurrentUser;
import com.clixsell.pos.purchase.PurchaseQueries.ClientAction;
import com.clixsell.pos.purchase.PurchaseResponses.ClientActionResult;
import com.clixsell.pos.purchase.PurchaseResponses.MessagePreview;
import com.clixsell.pos.purchase.PurchaseResponses.OrderView;
import com.clixsell.pos.supplier.Supplier;
import com.clixsell.pos.supplier.SupplierRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.clixsell.pos.purchase.PurchaseOrderService.map;

/**
 * Supplier communication (UR-M15, SR28 / SR29; Phase 3 final plan): prepare and store the
 * supplier message of an APPROVED order, report copies and WhatsApp opening requests, and the
 * manager's MANUAL records of sending, supplier confirmation, closing and supplier acceptance of
 * a cancellation.
 *
 * <p>ClixSell never sends anything and never contacts WhatsApp or a supplier: it returns a
 * wa.me link only after the manager confirmed an opening request, and only for an order that is
 * not final. Copy and opening reports come from the app and are never treated as verified;
 * "sent" and "supplier confirmed" are statements the manager records. Nothing here changes
 * stock, prices, costs, sales or reports; closing is not receiving.
 *
 * <p>Concurrency: every change runs in one transaction that takes SQLite's write lock FIRST
 * (PurchaseQueries.takeWriteLock — no row changes), then checks and writes. State changes are
 * also protected by the order version (V6 T4); event-only actions (copy, opening request and
 * result, supplier cancellation acceptance) are de-duplicated by this check-under-lock — the
 * database itself has no unique constraint for them (documented limitation, P3-D6).
 */
@Service
public class PurchaseCommunicationService {

    static final String REQUESTED = "REQUESTED";
    static final String CLIENT_REPORTED = "CLIENT_REPORTED";
    /** States in which the stored message may still be used (copied / opened in WhatsApp). */
    static final Set<PurchaseOrderStatus> ACTIONABLE = Set.of(PurchaseOrderStatus.MESSAGE_GENERATED,
            PurchaseOrderStatus.SENT, PurchaseOrderStatus.CONFIRMED);
    private static final String NOT_VERIFIED = "Reported by the app; ClixSell cannot verify it. It does not mean the "
            + "message was sent or delivered.";

    private final PurchaseOrderService orders;
    private final PurchaseOrderItemRepository items;
    private final PurchaseQueries queries;
    private final SupplierRepository suppliers;

    public PurchaseCommunicationService(PurchaseOrderService orders, PurchaseOrderItemRepository items,
                                        PurchaseQueries queries, SupplierRepository suppliers) {
        this.orders = orders;
        this.items = items;
        this.queries = queries;
        this.suppliers = suppliers;
    }

    // ── preview and generate ────────────────────────────────────────────────────────

    /** The message as it would be stored now (APPROVED only). Nothing is saved. */
    public MessagePreview preview(CurrentUser user, long orderId, String language) {
        long storeId = user.requireStoreId();
        return orders.readOnly(() -> {
            PurchaseOrder o = orders.find(storeId, orderId);
            requireStatus(o, "PREVIEW_MESSAGE", PurchaseOrderStatus.APPROVED);
            return build(o, language);
        });
    }

    /**
     * APPROVED → MESSAGE_GENERATED. The text, the supplier number and the language are stored
     * once and never change (T7). previewToken must match what the manager saw: if the supplier
     * number, a name or the date changed since, 409 PREVIEW_CHANGED with the new preview.
     */
    public OrderView generate(CurrentUser user, long orderId, PurchaseRequests.Generate req) {
        long storeId = user.requireStoreId();
        return orders.inTx(() -> {
            queries.takeWriteLock();
            LocalDateTime now = PurchaseTime.now();
            PurchaseOrder o = orders.forChange(storeId, orderId, req.version(), "GENERATE_MESSAGE", PurchaseOrderStatus.APPROVED);
            MessagePreview p = build(o, req.language());
            if (!p.previewToken().equals(req.previewToken())) {
                throw new PurchaseException(HttpStatus.CONFLICT, "PREVIEW_CHANGED",
                        "The message or the supplier number changed since your preview. Check the new preview.",
                        Map.of(), map("preview", p));
            }
            o.generateMessage(user.userId(), now, p.text(), p.language(), p.whatsappNumber());
            orders.flush(o);
            orders.event(o, user, "MESSAGE_GENERATED", PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.MESSAGE_GENERATED,
                    map("recordedAs", "MESSAGE_PREPARED", "sent", false, "language", p.language(),
                            "length", p.length().length(), "whatsappNumber", p.whatsappNumber(),
                            "notice", "The message was prepared and stored. It was not sent."), now);
            return orders.view(orders.reload(o), false);
        });
    }

    /** Checks the supplier and builds the message of an APPROVED order (refuses, never shortens). */
    private MessagePreview build(PurchaseOrder o, String language) {
        Supplier s = suppliers.findBySupplierIdAndStore_StoreId(o.getSupplierId(), o.getStoreId()).orElse(null);
        if (s == null || !s.isActive()) {
            throw PurchaseException.conflict("SUPPLIER_INACTIVE",
                    "Supplier '" + o.getSupplierName() + "' is inactive. Activate it in Suppliers first.");
        }
        String number = s.getWhatsappNumber();
        if (number == null || number.isBlank()) {
            throw PurchaseException.conflict("SUPPLIER_NO_WHATSAPP",
                    "Supplier '" + s.getName() + "' has no WhatsApp number. Add it in Suppliers, then preview again.");
        }
        if (!number.matches("[1-9][0-9]{7,14}")) {
            throw PurchaseException.conflict("SUPPLIER_WHATSAPP_INVALID",
                    "The WhatsApp number of '" + s.getName() + "' is not a valid international number. Correct it in Suppliers.");
        }
        List<PurchaseOrderItem> lines = items.findByOrderIdAndStoreIdOrderByItemId(o.getOrderId(), o.getStoreId());
        if (lines.isEmpty()) throw PurchaseException.conflict("ORDER_EMPTY", "The order has no products");
        WhatsappMessageBuilder.Input in = orders.messageInput(o, lines, language);
        String text = WhatsappMessageBuilder.text(in);
        WhatsappMessageBuilder.Report report = WhatsappMessageBuilder.report(in);
        Map<String, WhatsappMessageBuilder.Report> all = orders.messageLength(o, lines);
        if (!report.fits()) {
            throw new PurchaseException(HttpStatus.CONFLICT, "MESSAGE_TOO_LONG",
                    "The message is " + report.length() + " characters; WhatsApp messages of an order are limited to "
                            + WhatsappMessageBuilder.MAX_LENGTH + ". Nothing was changed. Split the order: cancel it "
                            + "(not sent) and create smaller orders, or use another language if it fits.",
                    Map.of(), map("messageLength", all, "language", language));
        }
        return new MessagePreview(o.getOrderId(), o.getVersion(), language, text, o.getSupplierName(), number,
                WhatsappMessageBuilder.display(number), report, all,
                WhatsappMessageBuilder.previewToken(language, text, number),
                "Preview only: nothing is saved or sent. ClixSell never sends messages itself.");
    }

    /** The stored message (read only for a final order). */
    public PurchaseResponses.MessageInfo message(CurrentUser user, long orderId) {
        long storeId = user.requireStoreId();
        return orders.readOnly(() -> {
            PurchaseOrder o = orders.find(storeId, orderId);
            if (o.getMessageText() == null) {
                throw PurchaseException.conflict("MESSAGE_NOT_GENERATED", "No supplier message was prepared for this order");
            }
            return orders.messageInfo(o);
        });
    }

    // ── client reports (events only; never a state change, never verified) ─────────────

    /** The app reports that the manager copied the message. Same clientActionId again → nothing new. */
    public ClientActionResult copied(CurrentUser user, long orderId, String actionId) {
        long storeId = user.requireStoreId();
        return orders.inTx(() -> {
            queries.takeWriteLock();
            PurchaseOrder o = orders.find(storeId, orderId);
            requireActionable(o, "COPY_MESSAGE");
            List<ClientAction> previous = queries.clientActions(storeId, actionId);
            if (!previous.isEmpty()) {
                ClientAction a = previous.get(0);
                if (previous.size() == 1 && a.orderId() == orderId && a.eventType().equals("MESSAGE_COPIED")) {
                    return copyResult(orderId, a.eventId(), true);
                }
                throw actionConflict(actionId);
            }
            PurchaseOrderEvent e = orders.event(o, user, "MESSAGE_COPIED", o.getStatus(), o.getStatus(),
                    map("clientActionId", actionId, "reportedBy", "CLIENT", "verified", false,
                            "recordedAs", "CLIENT_REPORTED_COPY", "notice", NOT_VERIFIED), PurchaseTime.now());
            return copyResult(orderId, e.getEventId(), false);
        });
    }

    private static ClientActionResult copyResult(long orderId, long eventId, boolean duplicate) {
        return new ClientActionResult(orderId, eventId, "MESSAGE_COPIED", null, "CLIENT_REPORTED_COPY", false, duplicate,
                null, null, NOT_VERIFIED);
    }

    /**
     * The manager confirmed the number and the message and asked to open WhatsApp. Records the
     * request (phase REQUESTED) and returns the wa.me link of the STORED message and number. The
     * app opens it (Phase 4); the manager sends it themselves. Same clientActionId → same answer,
     * nothing new recorded.
     */
    public ClientActionResult openRequested(CurrentUser user, long orderId, String actionId) {
        long storeId = user.requireStoreId();
        return orders.inTx(() -> {
            queries.takeWriteLock();
            PurchaseOrder o = orders.find(storeId, orderId);
            requireActionable(o, "REQUEST_WHATSAPP_OPEN");
            String url = WhatsappMessageBuilder.url(o.getMessageWhatsapp(), o.getMessageText());
            List<ClientAction> previous = queries.clientActions(storeId, actionId);
            if (!previous.isEmpty()) {
                ClientAction a = previous.get(0);
                if (a.orderId() == orderId && a.eventType().equals("WHATSAPP_OPEN_REQUESTED") && REQUESTED.equals(a.phase())) {
                    return openResult(o, a.eventId(), true, url);
                }
                throw actionConflict(actionId);
            }
            PurchaseOrderEvent e = orders.event(o, user, "WHATSAPP_OPEN_REQUESTED", o.getStatus(), o.getStatus(),
                    map("clientActionId", actionId, "phase", REQUESTED, "managerConfirmed", true, "verified", false,
                            "whatsappNumber", o.getMessageWhatsapp(), "recordedAs", "WHATSAPP_OPEN_REQUESTED",
                            "notice", "Opening WhatsApp was requested. This does not mean the message was sent."),
                    PurchaseTime.now());
            return openResult(o, e.getEventId(), false, url);
        });
    }

    private static ClientActionResult openResult(PurchaseOrder o, long eventId, boolean duplicate, String url) {
        return new ClientActionResult(o.getOrderId(), eventId, "WHATSAPP_OPEN_REQUESTED", REQUESTED,
                "WHATSAPP_OPEN_REQUESTED", false, duplicate, url, o.getMessageWhatsapp(),
                "Opening WhatsApp was requested. Send the message yourself, then record it with mark-sent. If the link "
                        + "does not open (very long messages may not open on every device), copy the message instead.");
    }

    /**
     * The app reports whether the link opened (OPENED / FAILED) for an earlier request with the
     * same clientActionId (phase CLIENT_REPORTED). Same result again → nothing new; another
     * result for the same request → 409 CLIENT_ACTION_CONFLICT (the first report is kept).
     */
    public ClientActionResult openReported(CurrentUser user, long orderId, PurchaseRequests.OpenResult req) {
        long storeId = user.requireStoreId();
        return orders.inTx(() -> {
            queries.takeWriteLock();
            PurchaseOrder o = orders.find(storeId, orderId);
            List<ClientAction> previous = queries.clientActions(storeId, req.clientActionId());
            boolean requested = false;
            for (ClientAction a : previous) {
                if (a.orderId() != orderId || !a.eventType().equals("WHATSAPP_OPEN_REQUESTED")) {
                    throw actionConflict(req.clientActionId());
                }
                if (REQUESTED.equals(a.phase())) requested = true;
                if (CLIENT_REPORTED.equals(a.phase())) {
                    if (req.result().equals(a.result())) return reportResult(orderId, a.eventId(), true);
                    throw new PurchaseException(HttpStatus.CONFLICT, "CLIENT_ACTION_CONFLICT",
                            "This opening request already has the reported result " + a.result()
                                    + "; it is kept and not replaced.", Map.of(), map("recordedResult", a.result()));
                }
            }
            if (!requested) {
                throw PurchaseException.conflict("OPEN_NOT_REQUESTED",
                        "No WhatsApp opening request with this clientActionId was recorded for this order");
            }
            PurchaseOrderEvent e = orders.event(o, user, "WHATSAPP_OPEN_REQUESTED", o.getStatus(), o.getStatus(),
                    map("clientActionId", req.clientActionId(), "phase", CLIENT_REPORTED, "result", req.result(),
                            "reportedBy", "CLIENT", "verified", false, "recordedAs", "CLIENT_REPORTED_OPEN_" + req.result(),
                            "notice", NOT_VERIFIED), PurchaseTime.now());
            return reportResult(orderId, e.getEventId(), false);
        });
    }

    private static ClientActionResult reportResult(long orderId, long eventId, boolean duplicate) {
        return new ClientActionResult(orderId, eventId, "WHATSAPP_OPEN_REQUESTED", CLIENT_REPORTED,
                "CLIENT_REPORTED_OPEN_RESULT", false, duplicate, null, null, NOT_VERIFIED);
    }

    // ── the manager's manual records (state changes, versioned) ──────────────────────

    /** MESSAGE_GENERATED → SENT: the manager states they sent the message. Not a delivery receipt. */
    public OrderView markSent(CurrentUser user, long orderId, int version) {
        long storeId = user.requireStoreId();
        return orders.inTx(() -> {
            queries.takeWriteLock();
            LocalDateTime now = PurchaseTime.now();
            PurchaseOrder o = orders.forChange(storeId, orderId, version, "MARK_SENT", PurchaseOrderStatus.MESSAGE_GENERATED);
            int copies = queries.countEvents(storeId, orderId, "MESSAGE_COPIED", null);
            int opens = queries.countEvents(storeId, orderId, "WHATSAPP_OPEN_REQUESTED", REQUESTED);
            o.markSent(user.userId(), now);
            orders.flush(o);
            orders.event(o, user, "MARKED_SENT", PurchaseOrderStatus.MESSAGE_GENERATED, PurchaseOrderStatus.SENT,
                    map("recordedAs", "MANAGER_RECORDED_SENDING", "recordedManually", true, "verified", false,
                            "deliveryConfirmed", false, "priorCopyReports", copies, "priorOpenRequests", opens,
                            "notice", "The manager recorded that they sent the message. ClixSell did not send it and "
                                    + "cannot confirm delivery."), now);
            return orders.view(orders.reload(o), false);
        });
    }

    /** SENT → CONFIRMED: the manager records that the supplier confirmed (manual, not verified). */
    public OrderView supplierConfirmation(CurrentUser user, long orderId, PurchaseRequests.Confirmation req) {
        long storeId = user.requireStoreId();
        return orders.inTx(() -> {
            queries.takeWriteLock();
            LocalDateTime now = PurchaseTime.now();
            PurchaseOrder o = orders.forChange(storeId, orderId, req.version(), "RECORD_SUPPLIER_CONFIRMATION",
                    PurchaseOrderStatus.SENT);
            o.recordSupplierConfirmation(user.userId(), now, req.note());
            orders.flush(o);
            orders.event(o, user, "SUPPLIER_CONFIRMATION_RECORDED", PurchaseOrderStatus.SENT, PurchaseOrderStatus.CONFIRMED,
                    map("recordedAs", "MANAGER_RECORDED_SUPPLIER_CONFIRMATION", "recordedManually", true,
                            "verified", false, "note", req.note()), now);
            return orders.view(orders.reload(o), false);
        });
    }

    /** CONFIRMED → CLOSED. Not receiving, not payment, no stock change; reservations end (T11). */
    public OrderView close(CurrentUser user, long orderId, int version) {
        long storeId = user.requireStoreId();
        return orders.inTx(() -> {
            queries.takeWriteLock();
            LocalDateTime now = PurchaseTime.now();
            PurchaseOrder o = orders.forChange(storeId, orderId, version, "CLOSE", PurchaseOrderStatus.CONFIRMED);
            o.close(user.userId(), now);
            orders.flush(o); // the database releases the reservations in the same statement
            orders.event(o, user, "CLOSED", PurchaseOrderStatus.CONFIRMED, PurchaseOrderStatus.CLOSED,
                    map("receivingRecorded", false, "stockChanged", false, "acknowledgedNoReceiving", true,
                            "notice", "Closed in ClixSell. Goods receiving is not recorded and stock did not change; "
                                    + "products of this order show a 'recently ordered' warning for 14 days."), now);
            return orders.view(orders.reload(o), false);
        });
    }

    /**
     * The manager records that the supplier accepted the cancellation of a CANCELED order whose
     * supplier may have it (canceled after contact, or after the message was generated: UNKNOWN / NOT_SENT).
     * Once per order; ends that order's reorder warnings only. Manual, not verified.
     */
    public OrderView supplierCancellation(CurrentUser user, long orderId, PurchaseRequests.SupplierCancellation req) {
        long storeId = user.requireStoreId();
        return orders.inTx(() -> {
            queries.takeWriteLock();
            LocalDateTime now = PurchaseTime.now();
            PurchaseOrder o = orders.find(storeId, orderId);
            if (o.getStatus() != PurchaseOrderStatus.CANCELED
                    || !PurchaseOrderService.possibleContact(o, orders.cancelDetails(o))) {
                throw new PurchaseException(HttpStatus.CONFLICT, "NOT_ELIGIBLE",
                        "Only a canceled order whose supplier may have been contacted needs the supplier's acceptance",
                        Map.of(), map("currentStatus", o.getStatus().name()));
            }
            if (queries.countEvents(storeId, orderId, "SUPPLIER_CANCELLATION_RECORDED", null) > 0) {
                throw PurchaseException.conflict("ALREADY_RECORDED",
                        "The supplier's acceptance of this cancellation was already recorded");
            }
            orders.event(o, user, "SUPPLIER_CANCELLATION_RECORDED", PurchaseOrderStatus.CANCELED, PurchaseOrderStatus.CANCELED,
                    map("recordedAs", "MANAGER_RECORDED_SUPPLIER_ACCEPTED_CANCELLATION", "recordedManually", true,
                            "verified", false, "channel", req.channel(), "note", req.note(),
                            "notice", "The manager recorded that the supplier accepted the cancellation. ClixSell did not "
                                    + "verify it."), now);
            return orders.view(orders.reload(o), false);
        });
    }

    // ── checks ──────────────────────────────────────────────────────────────────────

    private static void requireStatus(PurchaseOrder o, String action, PurchaseOrderStatus... allowed) {
        if (!Set.of(allowed).contains(o.getStatus())) {
            throw new PurchaseException(HttpStatus.CONFLICT, "INVALID_TRANSITION",
                    action + " is not possible for an order that is " + o.getStatus(), Map.of(),
                    map("currentStatus", o.getStatus().name(), "currentVersion", o.getVersion()));
        }
    }

    /** The stored message may be used only while the order is MESSAGE_GENERATED, SENT or CONFIRMED. */
    private static void requireActionable(PurchaseOrder o, String action) {
        if (ACTIONABLE.contains(o.getStatus())) return;
        if (o.getStatus().isFinal()) {
            throw new PurchaseException(HttpStatus.CONFLICT, "ORDER_FINAL",
                    "This order is " + o.getStatus() + ": its message must not be used any more", Map.of(),
                    map("currentStatus", o.getStatus().name()));
        }
        throw new PurchaseException(HttpStatus.CONFLICT, "MESSAGE_NOT_GENERATED",
                action + " needs the supplier message; prepare it first", Map.of(),
                map("currentStatus", o.getStatus().name()));
    }

    private static PurchaseException actionConflict(String id) {
        return new PurchaseException(HttpStatus.CONFLICT, "CLIENT_ACTION_CONFLICT",
                "This clientActionId was already used for another action; use a new one", Map.of(),
                map("clientActionId", id));
    }
}

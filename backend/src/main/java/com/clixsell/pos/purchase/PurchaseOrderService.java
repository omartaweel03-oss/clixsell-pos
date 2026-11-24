package com.clixsell.pos.purchase;

import com.clixsell.pos.auth.CurrentUser;
import com.clixsell.pos.purchase.PurchaseQueries.ProductRow;
import com.clixsell.pos.purchase.PurchaseRecommendationService.Recommended;
import com.clixsell.pos.purchase.PurchaseRequests.Ack;
import com.clixsell.pos.purchase.PurchaseRequests.Item;
import com.clixsell.pos.purchase.PurchaseResponses.ItemView;
import com.clixsell.pos.purchase.PurchaseResponses.OrderPage;
import com.clixsell.pos.purchase.PurchaseResponses.OrderSummary;
import com.clixsell.pos.purchase.PurchaseResponses.OrderView;
import com.clixsell.pos.purchase.PurchaseResponses.Person;
import com.clixsell.pos.purchase.ReorderCalculator.Result;
import com.clixsell.pos.supplier.Supplier;
import com.clixsell.pos.supplier.SupplierRepository;
import com.clixsell.pos.user.User;
import com.clixsell.pos.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Purchase orders, Phase 2 (UR-M13 / UR-M14, SR26 / SR27): create, list, details, history,
 * edit while PENDING_APPROVAL, approve, reject, and cancel before any supplier contact.
 *
 * <p>Every change runs in ONE transaction together with its single history event; a failure
 * leaves nothing behind (no order, no event, no order number taken). The store always comes
 * from the logged-in user. Nothing here changes stock, prices, costs, sales or reports, and
 * nothing contacts a supplier (Phase 3).
 */
@Service
public class PurchaseOrderService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int PAGE_MAX = 100;

    private final PurchaseOrderRepository orders;
    private final PurchaseOrderItemRepository items;
    private final PurchaseOrderEventRepository events;
    private final PurchaseQueries queries;
    private final PurchaseRecommendationService recommendations;
    private final SupplierRepository suppliers;
    private final UserRepository users;
    private final EntityManager em;
    private final TransactionTemplate tx;

    public PurchaseOrderService(PurchaseOrderRepository orders, PurchaseOrderItemRepository items,
                                PurchaseOrderEventRepository events, PurchaseQueries queries,
                                PurchaseRecommendationService recommendations, SupplierRepository suppliers,
                                UserRepository users, EntityManager em, PlatformTransactionManager txManager) {
        this.orders = orders;
        this.items = items;
        this.events = events;
        this.queries = queries;
        this.recommendations = recommendations;
        this.suppliers = suppliers;
        this.users = users;
        this.em = em;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Runs one purchasing change in ONE transaction. If SQLite answers "database is locked"
     * (another connection is writing at the same moment), the whole transaction was rolled back,
     * so it is safely run again (a few times, with a short wait). Still locked → 409
     * DATABASE_BUSY: nothing was saved and the client may retry (creation is idempotent).
     */
    private <T> T inTx(java.util.function.Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> work.get());
            } catch (RuntimeException e) {
                if (!busy(e)) throw e;
                if (attempt >= BUSY_RETRIES) {
                    throw PurchaseException.conflict("DATABASE_BUSY",
                            "The database is busy; nothing was saved. Please try again.");
                }
                try {
                    Thread.sleep(25L * attempt + (long) (Math.random() * 25));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static final int BUSY_RETRIES = 8;

    /** SQLITE_BUSY / SQLITE_LOCKED anywhere in the cause chain. */
    static boolean busy(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.sqlite.SQLiteException se) {
                int code = se.getResultCode().code & 0xff;
                if (code == 5 || code == 6) return true;
            }
            if (t instanceof org.springframework.dao.PessimisticLockingFailureException) return true;
            String m = t.getMessage();
            if (m != null && (m.contains("SQLITE_BUSY") || m.contains("database is locked"))) return true;
        }
        return false;
    }

    private <T> T readOnly(java.util.function.Supplier<T> work) {
        TransactionTemplate ro = new TransactionTemplate(Objects.requireNonNull(tx.getTransactionManager()));
        ro.setReadOnly(true);
        return ro.execute(status -> work.get());
    }

    // ── create ──────────────────────────────────────────────────────────────────────

    /** Marks a lost race on the same clientRequestId (the other request created the order). */
    private static final class SameKeyRace extends RuntimeException {
    }

    public OrderView create(CurrentUser user, PurchaseRequests.Create req) {
        long storeId = user.requireStoreId();
        String hash = PurchaseRequests.hash(req, user.userId());
        try {
            return inTx(() -> {
                OrderView replay = replayOrNull(storeId, req.clientRequestId(), hash);
                return replay != null ? replay : createNew(user, storeId, req, hash);
            });
        } catch (SameKeyRace e) {
            // Another request with the same key committed first: answer like a replay (or 409).
            OrderView replay = inTx(() -> replayOrNull(storeId, req.clientRequestId(), hash));
            if (replay == null) throw PurchaseException.conflict("CONCURRENT_CHANGE", "Please try again");
            return replay;
        }
    }

    /** Same key + same content → the original order (replayed); same key + other content → 409. */
    private OrderView replayOrNull(long storeId, String key, String hash) {
        return orders.findByStoreIdAndClientRequestId(storeId, key).map(o -> {
            if (!o.getRequestHash().equals(hash)) {
                throw PurchaseException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "This clientRequestId was already used for a different order request");
            }
            return view(o, true);
        }).orElse(null);
    }

    private OrderView createNew(CurrentUser user, long storeId, PurchaseRequests.Create req, String hash) {
        LocalDateTime now = PurchaseTime.now();
        Supplier supplier = activeSupplier(storeId, req.supplierId());
        Map<Long, Recommended> recs = req.source() == PurchaseOrder.Source.GUARDIAN ? guardianRecommendations(storeId, now) : Map.of();
        List<Line> lines = checkLines(storeId, supplier, req.source(), req.items(), recs, Set.of());
        List<Map<String, Object>> acknowledged = checkCautions(storeId, now, lines, req.acks());

        String number = "PO-%06d".formatted(queries.takeOrderNumber(storeId));
        PurchaseOrder order;
        try {
            order = orders.saveAndFlush(new PurchaseOrder(storeId, number, supplier.getSupplierId(), supplier.getName(),
                    req.source(), req.note(), user.userId(), now, req.clientRequestId(), hash));
        } catch (DataIntegrityViolationException e) {
            if (mentions(e, "client_request_id")) throw new SameKeyRace();
            throw e;
        }
        List<Map<String, Object>> created = new ArrayList<>();
        for (Line l : lines) {
            insertLine(order, l, acknowledged);
            created.add(lineDetails(l));
        }
        event(order, user, "CREATED", null, PurchaseOrderStatus.PENDING_APPROVAL, map(
                "source", req.source().name(), "items", created, "note", req.note(),
                "cautionsAcknowledged", acknowledged), now);
        return view(reload(order), false);
    }

    // ── edit ────────────────────────────────────────────────────────────────────────

    /**
     * Replaces the note and the list of lines of a PENDING_APPROVAL order (same supplier and
     * store). Existing products keep their snapshot and may change quantity; new products get a
     * fresh snapshot and reservation; omitted lines are removed (and released). Lines whose
     * product was deleted cannot be listed, so an edit removes them. No change → no event.
     */
    public OrderView edit(CurrentUser user, long orderId, PurchaseRequests.Edit req) {
        long storeId = user.requireStoreId();
        return inTx(() -> {
            LocalDateTime now = PurchaseTime.now();
            PurchaseOrder order = forChange(storeId, orderId, req.version(), "EDIT", PurchaseOrderStatus.PENDING_APPROVAL);
            Supplier supplier = activeSupplier(storeId, order.getSupplierId());
            List<PurchaseOrderItem> current = items.findByOrderIdAndStoreIdOrderByItemId(order.getOrderId(), storeId);
            Map<Long, PurchaseOrderItem> byProduct = new HashMap<>();
            for (PurchaseOrderItem i : current) if (i.getProductId() != null) byProduct.put(i.getProductId(), i);

            List<Item> added = req.items().stream().filter(i -> !byProduct.containsKey(i.productId())).toList();
            Map<Long, Recommended> recs = order.getSource() == PurchaseOrder.Source.GUARDIAN && !added.isEmpty()
                    ? guardianRecommendations(storeId, now) : Map.of();
            List<Line> newLines = checkLines(storeId, supplier, order.getSource(), added, recs, Set.of());
            List<Map<String, Object>> acknowledged = checkCautions(storeId, now, newLines, req.acks());

            Set<Long> keep = new HashSet<>();
            List<Map<String, Object>> changed = new ArrayList<>();
            List<Map<String, Object>> removed = new ArrayList<>();
            for (Item i : req.items()) {
                PurchaseOrderItem existing = byProduct.get(i.productId());
                if (existing == null) continue;
                keep.add(existing.getItemId());
                if (existing.getQuantity() != i.quantity()) {
                    changed.add(map("productId", i.productId(), "from", existing.getQuantity(), "to", i.quantity()));
                    existing.setQuantity(i.quantity());
                }
            }
            for (PurchaseOrderItem i : current) {
                if (!keep.contains(i.getItemId())) {
                    removed.add(map("productId", i.getProductId(), "productName", i.getProductName(),
                            "quantity", i.getQuantity(), "productDeleted", i.getProductId() == null));
                    items.delete(i);
                }
            }
            boolean noteChanged = !Objects.equals(order.getNote(), req.note());
            if (changed.isEmpty() && removed.isEmpty() && newLines.isEmpty() && !noteChanged) {
                return view(order, false); // nothing to change: no event, no new version
            }
            items.flush(); // removals and quantity changes first, then new reservations
            List<Map<String, Object>> addedDetails = new ArrayList<>();
            for (Line l : newLines) {
                insertLine(order, l, acknowledged);
                addedDetails.add(lineDetails(l));
            }
            String oldNote = order.getNote();
            if (noteChanged) {
                order.setNote(req.note());
                flush(order); // version + 1 with the note
            } else {
                bumpVersion(order); // line-only edit: the order still gets a new version, now
            }
            event(order, user, "EDITED", PurchaseOrderStatus.PENDING_APPROVAL, PurchaseOrderStatus.PENDING_APPROVAL, map(
                    "added", addedDetails, "removed", removed, "quantityChanged", changed,
                    "note", noteChanged ? map("from", oldNote, "to", req.note()) : null,
                    "cautionsAcknowledged", acknowledged), now);
            return view(reload(order), false);
        });
    }

    // ── approve / reject / cancel ───────────────────────────────────────────────────

    public OrderView approve(CurrentUser user, long orderId, int version) {
        long storeId = user.requireStoreId();
        return inTx(() -> {
            LocalDateTime now = PurchaseTime.now();
            PurchaseOrder order = forChange(storeId, orderId, version, "APPROVE", PurchaseOrderStatus.PENDING_APPROVAL);
            List<String> blockers = approvalBlockers(order, items.findByOrderIdAndStoreIdOrderByItemId(orderId, storeId));
            if (!blockers.isEmpty()) {
                throw new PurchaseException(HttpStatus.CONFLICT, blockers.get(0), "The order cannot be approved now: "
                        + String.join(", ", blockers), Map.of(), map("approvalBlockers", blockers));
            }
            order.approve(user.userId(), now);
            flush(order);
            event(order, user, "APPROVED", PurchaseOrderStatus.PENDING_APPROVAL, PurchaseOrderStatus.APPROVED, null, now);
            return view(reload(order), false);
        });
    }

    public OrderView reject(CurrentUser user, long orderId, PurchaseRequests.Reason req) {
        long storeId = user.requireStoreId();
        return inTx(() -> {
            LocalDateTime now = PurchaseTime.now();
            PurchaseOrder order = forChange(storeId, orderId, req.version(), "REJECT", PurchaseOrderStatus.PENDING_APPROVAL);
            order.reject(user.userId(), now, req.reason());
            flush(order); // the database releases the reservations in the same statement
            event(order, user, "REJECTED", PurchaseOrderStatus.PENDING_APPROVAL, PurchaseOrderStatus.REJECTED,
                    map("reason", req.reason()), now);
            return view(reload(order), false);
        });
    }

    /** Phase 2: only before any supplier contact (PENDING_APPROVAL or APPROVED). */
    public OrderView cancel(CurrentUser user, long orderId, PurchaseRequests.Reason req) {
        long storeId = user.requireStoreId();
        return inTx(() -> {
            LocalDateTime now = PurchaseTime.now();
            PurchaseOrder order = forChange(storeId, orderId, req.version(), "CANCEL",
                    PurchaseOrderStatus.PENDING_APPROVAL, PurchaseOrderStatus.APPROVED);
            PurchaseOrderStatus from = order.getStatus();
            order.cancel(user.userId(), now, req.reason());
            flush(order);
            event(order, user, "CANCELED", from, PurchaseOrderStatus.CANCELED,
                    map("reason", req.reason(), "afterSupplierContact", false), now);
            return view(reload(order), false);
        });
    }

    // ── reads ───────────────────────────────────────────────────────────────────────

    public OrderPage list(CurrentUser user, Collection<PurchaseOrderStatus> status, int page, int size) {
        long storeId = user.requireStoreId();
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), PAGE_MAX);
        return readOnly(() -> {
            Page<PurchaseOrder> result = status == null || status.isEmpty()
                    ? orders.findByStoreIdOrderByOrderIdDesc(storeId, PageRequest.of(p, s))
                    : orders.findByStoreIdAndStatusInOrderByOrderIdDesc(storeId, status, PageRequest.of(p, s));
            List<Long> ids = result.getContent().stream().map(PurchaseOrder::getOrderId).toList();
            Map<Long, Long> counts = new HashMap<>();
            if (!ids.isEmpty()) for (Object[] r : items.countByOrder(storeId, ids)) counts.put((Long) r[0], (Long) r[1]);
            Map<Long, String> names = names(result.getContent().stream().map(PurchaseOrder::getCreatedBy).toList());
            LocalDateTime now = PurchaseTime.now();
            List<OrderSummary> out = result.getContent().stream().map(o -> new OrderSummary(o.getOrderId(),
                    o.getOrderNumber(), o.getStatus().name(), o.getSource().name(), o.getSupplierId(), o.getSupplierName(),
                    counts.getOrDefault(o.getOrderId(), 0L).intValue(), person(o.getCreatedBy(), names),
                    PurchaseTime.text(o.getCreatedAt()), overdue(o, now), o.getVersion())).toList();
            return new OrderPage(p, s, result.getTotalElements(), out);
        });
    }

    public OrderView get(CurrentUser user, long orderId) {
        long storeId = user.requireStoreId();
        return readOnly(() -> view(find(storeId, orderId), false));
    }

    public List<PurchaseResponses.EventView> history(CurrentUser user, long orderId) {
        long storeId = user.requireStoreId();
        return readOnly(() -> {
            find(storeId, orderId);
            List<PurchaseOrderEvent> list = events.findByOrderIdAndStoreIdOrderByEventId(orderId, storeId);
            Map<Long, String> names = names(list.stream().map(PurchaseOrderEvent::getUserId).toList());
            return list.stream().map(e -> new PurchaseResponses.EventView(e.getEventId(), e.getEventType(),
                    e.getFromStatus(), e.getToStatus(), person(e.getUserId(), names), parse(e.getDetails()),
                    PurchaseTime.text(e.getCreatedAt()))).toList();
        });
    }

    // ── checks ──────────────────────────────────────────────────────────────────────

    /** A checked line of a new or edited order (with the server's calculation). */
    private record Line(ProductRow product, int quantity, Recommended rec, PurchaseOrder.Source source) {
    }

    private Supplier activeSupplier(long storeId, long supplierId) {
        Supplier s = suppliers.findBySupplierIdAndStore_StoreId(supplierId, storeId)
                .orElseThrow(() -> PurchaseException.notFound("SUPPLIER_NOT_FOUND", "Supplier " + supplierId + " was not found"));
        if (!s.isActive()) {
            throw PurchaseException.conflict("SUPPLIER_INACTIVE",
                    "Supplier '" + s.getName() + "' is inactive. Activate it in Suppliers first.");
        }
        return s;
    }

    private Map<Long, Recommended> guardianRecommendations(long storeId, LocalDateTime now) {
        if (!recommendations.guardianEnabled(storeId)) {
            throw PurchaseException.conflict("GUARDIAN_DISABLED",
                    "Guardian Mode is off: Guardian recommendations are paused. Create a manual order instead.");
        }
        return recommendations.calculate(storeId, now);
    }

    private List<Line> checkLines(long storeId, Supplier supplier, PurchaseOrder.Source source, List<Item> wanted,
                                  Map<Long, Recommended> recs, Set<Long> ignore) {
        Map<Long, PurchaseQueries.Reservation> held = queries.reservations(storeId);
        List<Line> out = new ArrayList<>();
        List<Map<String, Object>> inOpenOrders = new ArrayList<>();
        for (int i = 0; i < wanted.size(); i++) {
            Item it = wanted.get(i);
            String f = "items[" + i + "].productId";
            ProductRow p = queries.product(storeId, it.productId()).orElseThrow(() -> new PurchaseException(
                    HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Product " + it.productId() + " was not found",
                    Map.of(f, "Product not found"), null));
            if (p.supplierId() != supplier.getSupplierId()) {
                throw PurchaseException.badRequest("PRODUCT_NOT_FROM_SUPPLIER", f,
                        "'" + p.fact().name() + "' is not supplied by '" + supplier.getName() + "'");
            }
            if (p.fact().quantity() < 0) {
                throw PurchaseException.badRequest("INCONSISTENT_STOCK", f,
                        "'" + p.fact().name() + "' has an invalid stock quantity; correct the product first");
            }
            PurchaseQueries.Reservation r = held.get(p.id());
            if (r != null && !ignore.contains(p.id())) {
                inOpenOrders.add(map("productId", p.id(), "productName", p.fact().name(), "orderId", r.orderId(),
                        "orderNumber", r.orderNumber(), "status", r.status(), "quantity", r.quantity()));
                continue;
            }
            Recommended rec = null;
            if (source == PurchaseOrder.Source.GUARDIAN) {
                rec = recs.get(p.id());
                if (rec == null) {
                    throw new PurchaseException(HttpStatus.CONFLICT, "NOT_A_CURRENT_RECOMMENDATION",
                            "'" + p.fact().name() + "' is not a current Guardian recommendation. Use a manual order.",
                            Map.of(f, "Not a current recommendation"), null);
                }
            }
            out.add(new Line(p, it.quantity(), rec, source));
        }
        if (!inOpenOrders.isEmpty()) {
            throw new PurchaseException(HttpStatus.CONFLICT, "PRODUCT_IN_OPEN_ORDER",
                    "Some products are already in an open purchase order", Map.of(), map("openOrders", inOpenOrders));
        }
        return out;
    }

    /**
     * D22 / D24: products of the lines that were in a recently CLOSED order or an order CANCELED
     * after supplier contact need an explicit acknowledgment per (product, order). Missing →
     * 409 REORDER_CAUTION_ACK_REQUIRED with the list. Returns what was acknowledged.
     */
    private List<Map<String, Object>> checkCautions(long storeId, LocalDateTime now, List<Line> lines, List<Ack> acks) {
        if (lines.isEmpty()) return List.of();
        Map<Long, List<PurchaseQueries.Caution>> cautions = queries.cautions(storeId, now);
        Set<Ack> given = new HashSet<>(acks);
        List<Map<String, Object>> missing = new ArrayList<>();
        List<Map<String, Object>> acknowledged = new ArrayList<>();
        for (Line l : lines) {
            for (PurchaseQueries.Caution c : cautions.getOrDefault(l.product().id(), List.of())) {
                Map<String, Object> m = map("productId", c.productId(), "productName", l.product().fact().name(),
                        "orderId", c.orderId(), "orderNumber", c.orderNumber(), "type", c.type(), "at", c.at(),
                        "quantity", c.quantity());
                if (given.contains(new Ack(c.productId(), c.orderId()))) acknowledged.add(m);
                else missing.add(m);
            }
        }
        if (!missing.isEmpty()) {
            throw new PurchaseException(HttpStatus.CONFLICT, "REORDER_CAUTION_ACK_REQUIRED",
                    "Some products were ordered recently and may still arrive. Confirm each one to continue.",
                    Map.of(), map("cautions", missing));
        }
        return acknowledged;
    }

    private void insertLine(PurchaseOrder order, Line l, List<Map<String, Object>> acknowledged) {
        ProductRow p = l.product();
        Map<String, Object> calc = new LinkedHashMap<>();
        calc.put("source", l.source().name());
        calc.put("quantityAtOrder", p.fact().quantity());
        calc.put("minStock", p.fact().minStock());
        String basis;
        Integer suggested;
        if (l.rec() != null) {
            Result r = l.rec().result();
            basis = r.basis().name();
            suggested = r.suggestedQuantity();
            calc.put("classification", r.classification().name());
            calc.put("basis", basis);
            calc.put("observedDays", r.observedDays());
            calc.put("netUnits", r.netUnits());
            calc.put("unitsPerDay", r.unitsPerDay());
            calc.put("daysLeft", r.daysLeft());
            calc.put("expectedStockout", r.expectedStockout() == null ? null : r.expectedStockout().toString());
            calc.put("targetStock", r.targetStock());
            calc.put("coverDays", ReorderCalculator.COVER_DAYS);
            calc.put("horizonDays", ReorderCalculator.HORIZON_DAYS);
            calc.put("suggestedQuantity", suggested);
            calc.put("managerQuantity", l.quantity());
            calc.put("managerChangedQuantity", suggested == null || suggested != l.quantity());
            calc.put("explanation", PurchaseRecommendationService.explanation(p, r));
        } else {
            basis = "MANUAL";
            suggested = null;
            int q = p.fact().quantity();
            calc.put("basis", basis);
            calc.put("stockStatus", q == 0 ? "OUT_OF_STOCK" : q <= p.fact().minStock() ? "LOW_STOCK" : "OK");
            calc.put("managerQuantity", l.quantity());
        }
        calc.put("cautionsAcknowledged", acknowledged.stream().filter(a -> Objects.equals(a.get("productId"), p.id())).toList());
        try {
            items.saveAndFlush(new PurchaseOrderItem(order.getOrderId(), order.getStoreId(), p.id(), p.fact().name(),
                    p.barcode(), l.quantity(), suggested, basis, JSON.writeValueAsString(calc), p.fact().cost()));
        } catch (DataIntegrityViolationException e) {
            if (mentions(e, "open_product_id")) {
                throw new PurchaseException(HttpStatus.CONFLICT, "PRODUCT_IN_OPEN_ORDER",
                        "'" + p.fact().name() + "' was just added to another open purchase order", Map.of(),
                        map("openOrders", List.of(map("productId", p.id(), "productName", p.fact().name()))));
            }
            throw e;
        }
    }

    private static Map<String, Object> lineDetails(Line l) {
        return map("productId", l.product().id(), "productName", l.product().fact().name(), "quantity", l.quantity(),
                "basis", l.rec() == null ? "MANUAL" : l.rec().result().basis().name(),
                "suggestedQuantity", l.rec() == null ? null : l.rec().result().suggestedQuantity());
    }

    private static List<String> approvalBlockers(PurchaseOrder order, List<PurchaseOrderItem> lines, Supplier supplier,
                                                 Map<Long, ProductRow> products) {
        List<String> out = new ArrayList<>();
        if (supplier == null || !supplier.isActive()) out.add("SUPPLIER_INACTIVE");
        if (lines.isEmpty()) out.add("ORDER_EMPTY");
        if (lines.stream().anyMatch(i -> i.getProductId() == null || !products.containsKey(i.getProductId()))) {
            out.add("ITEM_PRODUCT_DELETED");
        }
        if (lines.stream().anyMatch(i -> i.getProductId() != null && products.containsKey(i.getProductId())
                && products.get(i.getProductId()).supplierId() != order.getSupplierId())) {
            out.add("PRODUCT_SUPPLIER_CHANGED");
        }
        return out;
    }

    private List<String> approvalBlockers(PurchaseOrder order, List<PurchaseOrderItem> lines) {
        Supplier supplier = suppliers.findBySupplierIdAndStore_StoreId(order.getSupplierId(), order.getStoreId()).orElse(null);
        Map<Long, ProductRow> products = new HashMap<>();
        for (PurchaseOrderItem i : lines) {
            if (i.getProductId() != null) queries.product(order.getStoreId(), i.getProductId()).ifPresent(p -> products.put(p.id(), p));
        }
        return approvalBlockers(order, lines, supplier, products);
    }

    /** The order of this store, at the version the user saw, in one of the allowed states. */
    private PurchaseOrder forChange(long storeId, long orderId, int version, String action, PurchaseOrderStatus... allowed) {
        PurchaseOrder order = find(storeId, orderId);
        if (!Set.of(allowed).contains(order.getStatus())) {
            throw new PurchaseException(HttpStatus.CONFLICT, "INVALID_TRANSITION",
                    action + " is not possible for an order that is " + order.getStatus(), Map.of(),
                    map("currentStatus", order.getStatus().name(), "currentVersion", order.getVersion()));
        }
        if (order.getVersion() != version) {
            throw new PurchaseException(HttpStatus.CONFLICT, "ORDER_CHANGED",
                    "The order was changed meanwhile (version " + order.getVersion() + "); reload it", Map.of(),
                    map("currentStatus", order.getStatus().name(), "currentVersion", order.getVersion()));
        }
        return order;
    }

    private PurchaseOrder find(long storeId, long orderId) {
        return orders.findByOrderIdAndStoreId(orderId, storeId).orElseThrow(() ->
                PurchaseException.notFound("PURCHASE_ORDER_NOT_FOUND", "Purchase order " + orderId + " was not found"));
    }

    private void flush(PurchaseOrder order) {
        try {
            orders.saveAndFlush(order);
        } catch (OptimisticLockingFailureException e) {
            throw PurchaseException.conflict("ORDER_CHANGED", "The order was changed meanwhile; reload it");
        }
    }

    /**
     * Raises the version by one in ONE conditional statement (fails if anybody changed the order
     * meanwhile), for edits that only change lines. Done at once, not deferred to commit.
     */
    private void bumpVersion(PurchaseOrder order) {
        em.flush();
        int n = em.createQuery("UPDATE PurchaseOrder o SET o.version = o.version + 1 "
                        + "WHERE o.orderId = :id AND o.storeId = :store AND o.version = :v")
                .setParameter("id", order.getOrderId()).setParameter("store", order.getStoreId())
                .setParameter("v", order.getVersion()).executeUpdate();
        if (n != 1) throw PurchaseException.conflict("ORDER_CHANGED", "The order was changed meanwhile; reload it");
        em.refresh(order);
    }

    private PurchaseOrder reload(PurchaseOrder order) {
        em.flush();
        em.detach(order);
        em.clear();
        return find(order.getStoreId(), order.getOrderId());
    }

    private void event(PurchaseOrder o, CurrentUser user, String type, PurchaseOrderStatus from, PurchaseOrderStatus to,
                       Map<String, Object> details, LocalDateTime at) {
        events.saveAndFlush(new PurchaseOrderEvent(o.getOrderId(), o.getStoreId(), user.userId(), type, from, to,
                details == null ? null : JSON.writeValueAsString(details), at));
    }

    // ── views ───────────────────────────────────────────────────────────────────────

    private OrderView view(PurchaseOrder o, boolean replayed) {
        List<PurchaseOrderItem> lines = items.findByOrderIdAndStoreIdOrderByItemId(o.getOrderId(), o.getStoreId());
        Supplier supplier = suppliers.findBySupplierIdAndStore_StoreId(o.getSupplierId(), o.getStoreId()).orElse(null);
        Map<Long, ProductRow> products = new HashMap<>();
        for (PurchaseOrderItem i : lines) {
            if (i.getProductId() != null) queries.product(o.getStoreId(), i.getProductId()).ifPresent(p -> products.put(p.id(), p));
        }
        List<String> actions = switch (o.getStatus()) {
            case PENDING_APPROVAL -> List.of("EDIT", "APPROVE", "REJECT", "CANCEL");
            case APPROVED -> List.of("CANCEL"); // the supplier message is Phase 3
            default -> List.of();
        };
        List<String> blockers = o.getStatus() == PurchaseOrderStatus.PENDING_APPROVAL
                ? approvalBlockers(o, lines, supplier, products) : List.of();
        Double total = 0.0;
        for (PurchaseOrderItem i : lines) {
            if (i.getExpectedUnitCost() == null) {
                total = null;
                break;
            }
            total += i.getExpectedUnitCost() * i.getQuantity();
        }
        if (lines.isEmpty()) total = null;
        Map<Long, String> names = names(List.of(o.getCreatedBy(), nz(o.getApprovedBy()), nz(o.getRejectedBy()),
                nz(o.getCanceledBy())));
        List<ItemView> itemViews = lines.stream().map(i -> new ItemView(i.getItemId(), i.getProductId(),
                i.getProductId() == null, i.getProductName(), i.getBarcode(), i.getQuantity(), i.getSuggestedQuantity(),
                i.getBasis(), i.getExpectedUnitCost(), i.getOpenProductId() != null, parse(i.getCalcJson()))).toList();
        return new OrderView(o.getOrderId(), o.getOrderNumber(), o.getStatus().name(), o.getSource().name(),
                o.getSupplierId(), o.getSupplierName(), supplier != null && supplier.isActive(), o.getNote(), o.getVersion(),
                person(o.getCreatedBy(), names), PurchaseTime.text(o.getCreatedAt()),
                person(o.getApprovedBy(), names), PurchaseTime.text(o.getApprovedAt()),
                person(o.getRejectedBy(), names), PurchaseTime.text(o.getRejectedAt()), o.getRejectionReason(),
                person(o.getCanceledBy(), names), PurchaseTime.text(o.getCanceledAt()), o.getCancelReason(),
                overdue(o, PurchaseTime.now()), total == null ? null : Math.round(total * 100) / 100.0, actions, blockers,
                itemViews, replayed);
    }

    /** D19 display only: pending more than 3 days, approved (message not prepared) more than 1 day. */
    private static boolean overdue(PurchaseOrder o, LocalDateTime now) {
        return switch (o.getStatus()) {
            case PENDING_APPROVAL -> Duration.between(o.getCreatedAt(), now).toDays() >= 3;
            case APPROVED -> o.getApprovedAt() != null && Duration.between(o.getApprovedAt(), now).toDays() >= 1;
            default -> false;
        };
    }

    private Map<Long, String> names(List<Long> ids) {
        Map<Long, String> out = new HashMap<>();
        for (User u : users.findAllById(ids.stream().filter(Objects::nonNull).filter(i -> i > 0).distinct().toList())) {
            out.put(u.getUserId(), u.getFullName());
        }
        return out;
    }

    private static Person person(Long id, Map<Long, String> names) {
        return id == null ? null : new Person(id, names.get(id));
    }

    private static Long nz(Long v) {
        return v == null ? -1L : v;
    }

    private static Object parse(String json) {
        return json == null ? null : JSON.readValue(json, Object.class);
    }

    private static boolean mentions(Throwable e, String text) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(text)) return true;
        }
        return false;
    }

    /** A map that keeps order and allows null values. */
    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}

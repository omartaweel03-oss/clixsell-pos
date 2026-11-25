package com.clixsell.pos.purchase;

import com.clixsell.pos.auth.CurrentUser;
import com.clixsell.pos.common.ValidationException;
import com.clixsell.pos.purchase.PurchaseResponses.Candidates;
import com.clixsell.pos.purchase.PurchaseResponses.EventView;
import com.clixsell.pos.purchase.PurchaseResponses.OrderPage;
import com.clixsell.pos.purchase.PurchaseResponses.ClientActionResult;
import com.clixsell.pos.purchase.PurchaseResponses.MessageInfo;
import com.clixsell.pos.purchase.PurchaseResponses.MessagePreview;
import com.clixsell.pos.purchase.PurchaseResponses.OrderView;
import com.clixsell.pos.purchase.PurchaseResponses.Recommendations;
import com.clixsell.pos.user.Role;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Purchasing — Store Admin of the store only (SecurityConfig, and checked again here).
 *
 * <pre>
 * GET  /api/purchase-recommendations              Guardian recommendations (Guardian ON; OFF → guardianEnabled false)
 * GET  /api/purchase-candidates?supplierId=…      a supplier's products for a manual order (facts only)
 * POST /api/purchase-orders                       create (PENDING_APPROVAL); idempotent by clientRequestId
 * GET  /api/purchase-orders?status=…&amp;q=…&amp;page=&amp;size=  list; q searches all the store's orders
 *                                                  (order number or saved supplier name, ≤ 80 characters)
 * GET  /api/purchase-orders?…&amp;overdue=true      only overdue orders (all the store's orders, then paged)
 * GET  /api/purchase-orders/summary               exact counts by status + overdue (D1; one SELECT, no paging)
 * GET  /api/purchase-orders/{id}                  details
 * GET  /api/purchase-orders/{id}/events           history
 * PUT  /api/purchase-orders/{id}                  edit (PENDING_APPROVAL)        {version, note, items, acknowledgedCautions}
 * POST /api/purchase-orders/{id}/approve          PENDING_APPROVAL → APPROVED     {version}
 * POST /api/purchase-orders/{id}/reject           PENDING_APPROVAL → REJECTED     {version, reason}
 * POST /api/purchase-orders/{id}/cancel           → CANCELED  {version, reason, contactStatus?, confirmNotSent?,
 *                                                  acknowledgeSupplierNotNotified?} (rules: PurchaseOrderService.cancel)
 *
 * Supplier communication (Phase 3, PurchaseCommunicationService):
 * GET  /api/purchase-orders/{id}/message-preview?language=ar|en   APPROVED: the message now (nothing saved)
 * POST /api/purchase-orders/{id}/message                  APPROVED → MESSAGE_GENERATED  {version, language, previewToken}
 * GET  /api/purchase-orders/{id}/message                  the stored message (read only when final)
 * POST /api/purchase-orders/{id}/message-copied           client report (event only)     {clientActionId}
 * POST /api/purchase-orders/{id}/whatsapp-open-requested  confirmed request → wa.me link  {clientActionId, confirmed:true}
 * POST /api/purchase-orders/{id}/whatsapp-open-result     client report of that request   {clientActionId, result}
 * POST /api/purchase-orders/{id}/mark-sent                MESSAGE_GENERATED → SENT        {version, confirmSentInWhatsapp:true}
 * POST /api/purchase-orders/{id}/supplier-confirmation    SENT → CONFIRMED                {version, confirmSupplierConfirmed:true, note?}
 * POST /api/purchase-orders/{id}/close                    CONFIRMED → CLOSED              {version, confirmNoReceiving:true}
 * POST /api/purchase-orders/{id}/supplier-cancellation-recorded  CANCELED (event only)  {confirmSupplierAccepted:true, channel, note}
 * </pre>
 * Bodies are strict; the store is always the logged-in user's. Nothing here sends a message,
 * contacts a supplier, records receiving or changes stock.
 */
@RestController
public class PurchaseController {

    private static final int MAX_QUERY = 80;

    private final PurchaseRecommendationService recommendations;
    private final PurchaseOrderService orders;
    private final PurchaseCommunicationService communication;

    public PurchaseController(PurchaseRecommendationService recommendations, PurchaseOrderService orders,
                              PurchaseCommunicationService communication) {
        this.recommendations = recommendations;
        this.orders = orders;
        this.communication = communication;
    }

    private static long storeOf(CurrentUser user) {
        if (user.role() != Role.STORE_ADMIN) throw new AccessDeniedException("Store Admin only");
        return user.requireStoreId();
    }

    @GetMapping("/api/purchase-recommendations")
    public Recommendations recommendations(@AuthenticationPrincipal CurrentUser user) {
        return recommendations.recommendations(storeOf(user));
    }

    @GetMapping("/api/purchase-candidates")
    public Candidates candidates(@AuthenticationPrincipal CurrentUser user,
                                 @RequestParam(name = "supplierId", required = false) String supplierId) {
        long store = storeOf(user);
        long id;
        try {
            id = Long.parseLong(supplierId == null ? "" : supplierId.trim());
        } catch (NumberFormatException e) {
            id = -1;
        }
        if (id <= 0) throw new ValidationException(Map.of("supplierId", "A supplier id is required"));
        return recommendations.candidates(store, id);
    }

    @PostMapping("/api/purchase-orders")
    public ResponseEntity<OrderView> create(@AuthenticationPrincipal CurrentUser user,
                                            @RequestBody Map<String, Object> body) {
        storeOf(user);
        OrderView v = orders.create(user, PurchaseRequests.create(body));
        // 201 for a new order, 200 when the same request was sent again (replayed)
        return ResponseEntity.status(v.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .location(URI.create("/api/purchase-orders/" + v.orderId())).body(v);
    }

    @GetMapping("/api/purchase-orders")
    public OrderPage list(@AuthenticationPrincipal CurrentUser user,
                          @RequestParam(name = "status", required = false) List<String> status,
                          @RequestParam(name = "q", required = false) String q,
                          @RequestParam(name = "overdue", required = false) String overdue,
                          @RequestParam(name = "page", defaultValue = "0") int page,
                          @RequestParam(name = "size", defaultValue = "20") int size) {
        storeOf(user);
        String query = q == null ? null : q.strip();
        if (query != null && query.isEmpty()) query = null;
        if (query != null && query.codePointCount(0, query.length()) > MAX_QUERY) {
            throw new ValidationException(Map.of("q", "Search text must be " + MAX_QUERY + " characters or fewer"));
        }
        if (query != null && query.chars().anyMatch(Character::isISOControl)) {
            throw new ValidationException(Map.of("q", "Search text must not contain control characters"));
        }
        List<PurchaseOrderStatus> wanted = new ArrayList<>();
        if (status != null) {
            for (String s : status) {
                for (String part : s.split(",")) {
                    if (part.isBlank()) continue;
                    try {
                        wanted.add(PurchaseOrderStatus.valueOf(part.trim()));
                    } catch (IllegalArgumentException e) {
                        throw new ValidationException(Map.of("status", "Unknown status: " + part.trim()));
                    }
                }
            }
        }
        boolean overdueOnly;
        if (overdue == null || overdue.isBlank() || overdue.equals("false")) overdueOnly = false;
        else if (overdue.equals("true")) overdueOnly = true;
        else throw new ValidationException(Map.of("overdue", "overdue must be true or false"));
        return orders.list(user, wanted, query, overdueOnly, page, size);
    }

    @GetMapping("/api/purchase-orders/summary")
    public PurchaseResponses.OrdersSummary summary(@AuthenticationPrincipal CurrentUser user) {
        storeOf(user);
        return orders.summary(user);
    }

    @GetMapping("/api/purchase-orders/{id}")
    public OrderView get(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        storeOf(user);
        return orders.get(user, id);
    }

    @GetMapping("/api/purchase-orders/{id}/events")
    public List<EventView> events(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        storeOf(user);
        return orders.history(user, id);
    }

    @PutMapping("/api/purchase-orders/{id}")
    public OrderView edit(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                          @RequestBody Map<String, Object> body) {
        storeOf(user);
        return orders.edit(user, id, PurchaseRequests.edit(body));
    }

    @PostMapping("/api/purchase-orders/{id}/approve")
    public OrderView approve(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                             @RequestBody Map<String, Object> body) {
        storeOf(user);
        return orders.approve(user, id, PurchaseRequests.version(body));
    }

    @PostMapping("/api/purchase-orders/{id}/reject")
    public OrderView reject(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                            @RequestBody Map<String, Object> body) {
        storeOf(user);
        return orders.reject(user, id, PurchaseRequests.reason(body));
    }

    @PostMapping("/api/purchase-orders/{id}/cancel")
    public OrderView cancel(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                            @RequestBody Map<String, Object> body) {
        storeOf(user);
        return orders.cancel(user, id, PurchaseRequests.cancel(body));
    }

    // ── supplier communication ──────────────────────────────────────────────────────

    @GetMapping("/api/purchase-orders/{id}/message-preview")
    public MessagePreview preview(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                  @RequestParam(name = "language", required = false) String language) {
        storeOf(user);
        return communication.preview(user, id, PurchaseRequests.language(language));
    }

    @PostMapping("/api/purchase-orders/{id}/message")
    public OrderView generate(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                              @RequestBody Map<String, Object> body) {
        storeOf(user);
        return communication.generate(user, id, PurchaseRequests.generate(body));
    }

    @GetMapping("/api/purchase-orders/{id}/message")
    public MessageInfo message(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        storeOf(user);
        return communication.message(user, id);
    }

    @PostMapping("/api/purchase-orders/{id}/message-copied")
    public ClientActionResult copied(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                     @RequestBody Map<String, Object> body) {
        storeOf(user);
        return communication.copied(user, id, PurchaseRequests.copied(body));
    }

    @PostMapping("/api/purchase-orders/{id}/whatsapp-open-requested")
    public ClientActionResult openRequested(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                            @RequestBody Map<String, Object> body) {
        storeOf(user);
        return communication.openRequested(user, id, PurchaseRequests.openRequested(body));
    }

    @PostMapping("/api/purchase-orders/{id}/whatsapp-open-result")
    public ClientActionResult openResult(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                         @RequestBody Map<String, Object> body) {
        storeOf(user);
        return communication.openReported(user, id, PurchaseRequests.openResult(body));
    }

    @PostMapping("/api/purchase-orders/{id}/mark-sent")
    public OrderView markSent(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                              @RequestBody Map<String, Object> body) {
        storeOf(user);
        return communication.markSent(user, id, PurchaseRequests.markSent(body));
    }

    @PostMapping("/api/purchase-orders/{id}/supplier-confirmation")
    public OrderView supplierConfirmation(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                          @RequestBody Map<String, Object> body) {
        storeOf(user);
        return communication.supplierConfirmation(user, id, PurchaseRequests.confirmation(body));
    }

    @PostMapping("/api/purchase-orders/{id}/close")
    public OrderView close(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                           @RequestBody Map<String, Object> body) {
        storeOf(user);
        return communication.close(user, id, PurchaseRequests.close(body));
    }

    @PostMapping("/api/purchase-orders/{id}/supplier-cancellation-recorded")
    public OrderView supplierCancellation(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                          @RequestBody Map<String, Object> body) {
        storeOf(user);
        return communication.supplierCancellation(user, id, PurchaseRequests.supplierCancellation(body));
    }
}

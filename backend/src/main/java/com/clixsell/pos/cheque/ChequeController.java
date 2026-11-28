package com.clixsell.pos.cheque;

import com.clixsell.pos.auth.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Cheque management (feature 9), Store Admin only.
 *
 * <pre>
 * GET  /api/cheques?status=                 the store's cheques (by due date)
 * GET  /api/cheques/summary                 counts / amounts by status, due within 7 days, pending refunds
 * GET  /api/cheques/{id}                    one cheque with its events (audit trail) and refunds
 * POST /api/cheques/{id}/status             {toStatus, note?, version?}
 * POST /api/cheque-refunds/{id}/pay         {method: CASH | BANK_TRANSFER, requestKey (cash)}
 * POST /api/cheque-refunds/{id}/cancel      {reason}
 * </pre>
 */
@RestController
public class ChequeController {

    public record StatusRequest(String toStatus, String note, Long version) {
    }

    public record PayRequest(String method, String requestKey) {
    }

    public record CancelRequest(String reason) {
    }

    private final ChequeService service;

    public ChequeController(ChequeService service) {
        this.service = service;
    }

    @GetMapping("/api/cheques")
    public List<ChequeService.ChequeView> list(@AuthenticationPrincipal CurrentUser user, @RequestParam(required = false) String status) {
        return service.list(user, status);
    }

    @GetMapping("/api/cheques/summary")
    public ChequeService.Summary summary(@AuthenticationPrincipal CurrentUser user) {
        return service.summary(user);
    }

    @GetMapping("/api/cheques/{id}")
    public ChequeService.Details details(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        return service.details(user, id);
    }

    @PostMapping("/api/cheques/{id}/status")
    public ChequeService.Details status(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                        @RequestBody StatusRequest r) {
        return service.changeStatus(user, id, r == null ? null : r.toStatus(), r == null ? null : r.note(),
                r == null ? null : r.version());
    }

    @PostMapping("/api/cheque-refunds/{id}/pay")
    public ChequeService.Details pay(@AuthenticationPrincipal CurrentUser user, @PathVariable long id, @RequestBody PayRequest r) {
        return service.payRefund(user, id, r == null ? null : r.method(), r == null ? null : r.requestKey());
    }

    @PostMapping("/api/cheque-refunds/{id}/cancel")
    public ChequeService.Details cancel(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                        @RequestBody CancelRequest r) {
        return service.cancelRefund(user, id, r == null ? null : r.reason());
    }
}

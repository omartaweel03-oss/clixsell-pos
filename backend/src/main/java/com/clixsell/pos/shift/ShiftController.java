package com.clixsell.pos.shift;

import com.clixsell.pos.auth.CurrentUser;
import com.clixsell.pos.store.StoreRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Cash drawers and shifts. The store always comes from the logged-in user.
 *
 * <pre>
 * GET   /api/drawers                       store users      drawers of the store
 * POST  /api/drawers                       Store Admin      add a drawer {name}
 * PATCH /api/drawers/{id}                  Store Admin      rename / activate / deactivate {name?, active?}
 * GET   /api/shifts/current                store users      own open shift (or null), drawers, store rule
 * POST  /api/shifts                        store users      open own shift {drawerId, openingCash}
 * POST  /api/shifts/{id}/close             its cashier      close own shift {countedCash, note?, version?}
 * POST  /api/shifts/{id}/admin-close       Store Admin      exceptional close {countedCash, note?, reason, version?}
 * GET   /api/shifts?status=&from=&to=      Store Admin      shifts of the store
 * GET   /api/shifts/{id}                   Admin / cashier  one shift with sales and cash movements
 * POST  /api/shifts/{id}/movements         Store Admin      cash in / out {type, amount, reason, requestKey}
 * </pre>
 */
@RestController
public class ShiftController {

    private final ShiftService service;
    private final StoreRepository stores;

    public ShiftController(ShiftService service, StoreRepository stores) {
        this.service = service;
        this.stores = stores;
    }

    public record DrawerRequest(String name, Boolean active) {
    }

    public record OpenRequest(Long drawerId, BigDecimal openingCash) {
    }

    public record CloseRequest(BigDecimal countedCash, String note, String reason, Long version) {
    }

    public record MovementRequest(String type, BigDecimal amount, String reason, String requestKey) {
    }

    public record Current(ShiftService.ShiftView shift, boolean requireCashShift, List<ShiftQueries.Drawer> drawers) {
    }

    @GetMapping("/api/drawers")
    public List<ShiftQueries.Drawer> drawers(@AuthenticationPrincipal CurrentUser user) {
        return service.drawers(user);
    }

    @PostMapping("/api/drawers")
    @ResponseStatus(HttpStatus.CREATED)
    public ShiftQueries.Drawer createDrawer(@AuthenticationPrincipal CurrentUser user, @RequestBody DrawerRequest r) {
        return service.createDrawer(user, r == null ? null : r.name());
    }

    @PatchMapping("/api/drawers/{id}")
    public ShiftQueries.Drawer updateDrawer(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                            @RequestBody DrawerRequest r) {
        return service.updateDrawer(user, id, r == null ? null : r.name(), r == null ? null : r.active());
    }

    @GetMapping("/api/shifts/current")
    public Current current(@AuthenticationPrincipal CurrentUser user) {
        boolean required = stores.findById(user.requireStoreId()).map(s -> s.isRequireCashShift()).orElse(false);
        return new Current(service.current(user).orElse(null), required, service.drawers(user));
    }

    @PostMapping("/api/shifts")
    @ResponseStatus(HttpStatus.CREATED)
    public ShiftService.ShiftView open(@AuthenticationPrincipal CurrentUser user, @RequestBody OpenRequest r) {
        return service.open(user, r == null ? null : r.drawerId(), r == null ? null : r.openingCash());
    }

    @PostMapping("/api/shifts/{id}/close")
    public ShiftService.ShiftView close(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                        @RequestBody CloseRequest r) {
        return service.closeOwn(user, id, r == null ? null : r.countedCash(), r == null ? null : r.note(),
                r == null ? null : r.version());
    }

    @PostMapping("/api/shifts/{id}/admin-close")
    public ShiftService.ShiftView adminClose(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                             @RequestBody CloseRequest r) {
        return service.closeAsAdmin(user, id, r == null ? null : r.countedCash(), r == null ? null : r.note(),
                r == null ? null : r.reason(), r == null ? null : r.version());
    }

    @GetMapping("/api/shifts")
    public List<ShiftService.ShiftView> list(@AuthenticationPrincipal CurrentUser user,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.list(user, status, from, to);
    }

    @GetMapping("/api/shifts/{id}")
    public ShiftService.ShiftDetails details(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        return service.details(user, id);
    }

    @PostMapping("/api/shifts/{id}/movements")
    @ResponseStatus(HttpStatus.CREATED)
    public ShiftQueries.Movement movement(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                          @RequestBody MovementRequest r) {
        return service.addMovement(user, id, r == null ? null : r.type(), r == null ? null : r.amount(),
                r == null ? null : r.reason(), r == null ? null : r.requestKey());
    }
}

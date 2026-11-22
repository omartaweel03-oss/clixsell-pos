package com.clixsell.pos.guardian;

import com.clixsell.pos.auth.CurrentUser;
import com.clixsell.pos.guardian.GuardianResponses.ActivityPage;
import com.clixsell.pos.guardian.GuardianResponses.AlertList;
import com.clixsell.pos.guardian.GuardianResponses.Dashboard;
import com.clixsell.pos.guardian.GuardianResponses.Status;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Guardian Mode API (report Figure 3.1) – Store Admin only (SecurityConfig:
 * /api/guardian/** hasRole STORE_ADMIN; Cashier and System Admin get 403).
 * There is no store id parameter: the store always comes from the session.
 *
 * <pre>
 * GET /api/guardian/status                                    on / off, last change, log start
 * PUT /api/guardian/status    {"enabled": true|false}          turn on / off (logged)
 * GET /api/guardian/dashboard?year=2026&amp;month=11             every section of Figure 3.1
 * GET /api/guardian/alerts?year=…&amp;month=…&amp;severity=HIGH&amp;rule=G1   all alerts ("View all alerts")
 * GET /api/guardian/activity?year=…&amp;month=…&amp;type=…&amp;page=0    activity log ("View activity report")
 * </pre>
 * Without year and month the current month is used. While Guardian Mode is off
 * the GET calls answer 200 with "enabled": false and no analysis.
 */
@RestController
@RequestMapping("/api/guardian")
public class GuardianController {

    private final GuardianService service;

    public GuardianController(GuardianService service) {
        this.service = service;
    }

    public record StatusRequest(Boolean enabled) {
    }

    @GetMapping("/status")
    public Status status(@AuthenticationPrincipal CurrentUser user) {
        return service.status(user.requireStoreId());
    }

    @PutMapping("/status")
    public Status setStatus(@AuthenticationPrincipal CurrentUser user, @RequestBody(required = false) StatusRequest body) {
        return service.setStatus(user, body == null ? null : body.enabled());
    }

    @GetMapping("/dashboard")
    public Dashboard dashboard(@AuthenticationPrincipal CurrentUser user,
                               @RequestParam(required = false) Integer year,
                               @RequestParam(required = false) Integer month) {
        return service.dashboard(user.requireStoreId(), year, month);
    }

    @GetMapping("/alerts")
    public AlertList alerts(@AuthenticationPrincipal CurrentUser user,
                            @RequestParam(required = false) Integer year,
                            @RequestParam(required = false) Integer month,
                            @RequestParam(required = false) String severity,
                            @RequestParam(required = false) String rule) {
        return service.alerts(user.requireStoreId(), year, month, severity, rule);
    }

    @GetMapping("/activity")
    public ActivityPage activity(@AuthenticationPrincipal CurrentUser user,
                                 @RequestParam(required = false) Integer year,
                                 @RequestParam(required = false) Integer month,
                                 @RequestParam(required = false) String type,
                                 @RequestParam(required = false) Integer page) {
        return service.activity(user.requireStoreId(), year, month, type, page);
    }
}

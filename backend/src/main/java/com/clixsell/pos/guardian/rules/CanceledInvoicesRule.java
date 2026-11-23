package com.clixsell.pos.guardian.rules;

import com.clixsell.pos.guardian.Amounts;
import com.clixsell.pos.guardian.GuardianData;
import com.clixsell.pos.guardian.GuardianData.CancelFact;
import com.clixsell.pos.guardian.GuardianData.InvoiceFact;
import com.clixsell.pos.guardian.GuardianData.LogFact;
import com.clixsell.pos.guardian.GuardianResponses.Alert;
import com.clixsell.pos.guardian.GuardianResponses.Ref;
import com.clixsell.pos.guardian.GuardianResponses.Severity;
import com.clixsell.pos.guardian.GuardianThresholds;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.clixsell.pos.guardian.rules.GuardianRule.params;

/**
 * G7 – many canceled (suspended) invoices in one day. Per day:
 * <ul>
 *   <li>MEDIUM: at least 3 cancellations AND at least 10% of (completed + canceled) that day;</li>
 *   <li>HIGH: at least 6 AND at least 20%.</li>
 * </ul>
 * The share keeps busy days from alerting (canceling can be normal: a customer
 * left). The time of cancellation is sales.updated_at (set by Sale.cancel). Who
 * canceled comes ONLY from the activity log; older cancellations show no name.
 */
public final class CanceledInvoicesRule implements GuardianRule {

    @Override
    public List<Alert> evaluate(GuardianData d) {
        Map<LocalDate, List<CancelFact>> byDay = new TreeMap<>();
        for (CancelFact c : d.cancels()) byDay.computeIfAbsent(c.canceledAt().toLocalDate(), k -> new ArrayList<>()).add(c);
        Map<LocalDate, Integer> completed = new TreeMap<>();
        for (InvoiceFact i : d.invoices()) completed.merge(i.completedAt().toLocalDate(), 1, Integer::sum);

        List<Alert> out = new ArrayList<>();
        for (var e : byDay.entrySet()) {
            List<CancelFact> list = e.getValue();
            int n = list.size();
            int done = completed.getOrDefault(e.getKey(), 0);
            int all = n + done;
            Severity sev = null;
            int minCount = 0, minPercent = 0;
            if (n >= GuardianThresholds.CANCEL_HIGH_COUNT && n * 100 >= GuardianThresholds.CANCEL_HIGH_PERCENT * all) {
                sev = Severity.HIGH;
                minCount = GuardianThresholds.CANCEL_HIGH_COUNT;
                minPercent = GuardianThresholds.CANCEL_HIGH_PERCENT;
            } else if (n >= GuardianThresholds.CANCEL_MEDIUM_COUNT
                    && n * 100 >= GuardianThresholds.CANCEL_MEDIUM_PERCENT * all) {
                sev = Severity.MEDIUM;
                minCount = GuardianThresholds.CANCEL_MEDIUM_COUNT;
                minPercent = GuardianThresholds.CANCEL_MEDIUM_PERCENT;
            }
            if (sev == null) continue;
            Map<String, Integer> byUser = new LinkedHashMap<>();
            Map<String, String> roles = new LinkedHashMap<>();
            int notRecorded = 0;
            List<Ref> refs = new ArrayList<>();
            LocalDateTime last = list.get(0).canceledAt();
            for (CancelFact c : list) {
                LogFact log = d.cancelLogs().get(c.saleId());
                if (log == null) notRecorded++;
                else {
                    byUser.merge(log.userName(), 1, Integer::sum);
                    roles.put(log.userName(), log.userRole());
                }
                refs.add(GuardianRule.sale(c.saleId(), c.invoiceNumber()));
                if (c.canceledAt().isAfter(last)) last = c.canceledAt();
            }
            out.add(GuardianRule.alert("G7:day:" + e.getKey(), "G7", "CANCELED_INVOICES", sev, "ACTIVITY",
                    params("count", n, "completed", done, "percent", Amounts.round1(n * 100.0 / all),
                            "minCount", minCount, "minPercent", minPercent, "day", e.getKey().toString(),
                            "byUser", toList(byUser, roles), "notRecorded", notRecorded),
                    refs, null, notRecorded == n ? NOT_RECORDED : null, last,
                    notRecorded < n ? GuardianRule.action("ACTIVITY", null) : null));
        }
        return out;
    }

    static List<Map<String, Object>> toList(Map<String, Integer> byUser, Map<String, String> roles) {
        List<Map<String, Object>> out = new ArrayList<>();
        byUser.forEach((name, count) -> out.add(params("name", name, "count", count, "role", roles.get(name))));
        return out;
    }
}

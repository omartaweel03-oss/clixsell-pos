package com.clixsell.pos.guardian.rules;

import com.clixsell.pos.guardian.GuardianData;
import com.clixsell.pos.guardian.GuardianData.LogFact;
import com.clixsell.pos.guardian.GuardianResponses.Alert;
import com.clixsell.pos.guardian.GuardianResponses.Ref;
import com.clixsell.pos.guardian.GuardianResponses.Severity;
import com.clixsell.pos.guardian.GuardianThresholds;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.clixsell.pos.guardian.rules.GuardianRule.params;

/**
 * G9 – repeated product deletions (report p.18–19). From the activity log only
 * (PRODUCT_DELETED, recorded since migration V5): the same user deleting 5 or
 * more products in one day → MEDIUM, 10 or more → HIGH. Only a Store Admin can
 * delete products, so this watches the store's admin accounts. The names come
 * from the snapshot kept in the log (the products are gone).
 */
public final class ProductDeletionsRule implements GuardianRule {

    @Override
    public List<Alert> evaluate(GuardianData d) {
        Map<String, List<LogFact>> groups = new LinkedHashMap<>();
        for (LogFact l : d.deletions()) {
            groups.computeIfAbsent(l.userId() + ":" + l.createdAt().toLocalDate(), k -> new ArrayList<>()).add(l);
        }
        List<Alert> out = new ArrayList<>();
        for (var e : groups.entrySet()) {
            List<LogFact> list = e.getValue();
            int n = list.size();
            Severity sev = n >= GuardianThresholds.DELETIONS_HIGH ? Severity.HIGH
                    : n >= GuardianThresholds.DELETIONS_MEDIUM ? Severity.MEDIUM : null;
            if (sev == null) continue;
            LocalDateTime last = list.get(0).createdAt();
            List<Ref> refs = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (LogFact l : list) {
                if (l.createdAt().isAfter(last)) last = l.createdAt();
                refs.add(new Ref("LOG", l.logId(), l.entityName()));
                if (names.size() < 20) names.add(l.entityName());
            }
            out.add(GuardianRule.alert("G9:user:" + e.getKey(), "G9", "PRODUCT_DELETIONS", sev, "ACTIVITY",
                    params("count", n, "mediumLimit", GuardianThresholds.DELETIONS_MEDIUM,
                            "highLimit", GuardianThresholds.DELETIONS_HIGH, "products", names,
                            "day", last.toLocalDate().toString()),
                    refs, GuardianRule.actor(list.get(0)), null, last, GuardianRule.action("ACTIVITY", null)));
        }
        return out;
    }
}

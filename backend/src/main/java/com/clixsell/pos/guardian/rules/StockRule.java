package com.clixsell.pos.guardian.rules;

import com.clixsell.pos.guardian.GuardianData;
import com.clixsell.pos.guardian.GuardianData.ProductFact;
import com.clixsell.pos.guardian.GuardianResponses.Alert;
import com.clixsell.pos.guardian.GuardianResponses.Ref;
import com.clixsell.pos.guardian.GuardianResponses.Severity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.clixsell.pos.guardian.rules.GuardianRule.params;

/**
 * G5 – out of stock (HIGH) and low stock (MEDIUM), with exactly the existing
 * product rules (Product.isOutOfStock: quantity ≤ 0; Product.isLowStock:
 * 0 &lt; quantity ≤ minimum stock), so the numbers match the Stock Alerts screen.
 * One grouped alert per kind; current state.
 */
public final class StockRule implements GuardianRule {

    @Override
    public List<Alert> evaluate(GuardianData d) {
        List<ProductFact> out = d.products().stream().filter(ProductFact::outOfStock)
                .sorted(Comparator.comparing(ProductFact::name)).toList();
        List<ProductFact> low = d.products().stream().filter(ProductFact::lowStock)
                .sorted(Comparator.comparingInt(ProductFact::quantity).thenComparing(ProductFact::name)).toList();
        List<Alert> alerts = new ArrayList<>();
        if (!out.isEmpty()) alerts.add(group("G5:out", "OUT_OF_STOCK", Severity.HIGH, out));
        if (!low.isEmpty()) alerts.add(group("G5:low", "LOW_STOCK", Severity.MEDIUM, low));
        return alerts;
    }

    private static Alert group(String key, String code, Severity severity, List<ProductFact> products) {
        List<Ref> refs = products.stream().map(p -> GuardianRule.product(p.id(), p.name())).toList();
        return GuardianRule.alert(key, "G5", code, severity, "STOCK",
                params("count", products.size(), "products", products.stream().map(ProductFact::name).limit(10).toList()),
                refs, null, null, null, GuardianRule.action("STOCK_ALERTS", null));
    }
}

package com.clixsell.pos.guardian.rules;

import com.clixsell.pos.guardian.Amounts;
import com.clixsell.pos.guardian.GuardianData;
import com.clixsell.pos.guardian.GuardianData.ProductFact;
import com.clixsell.pos.guardian.GuardianResponses.Alert;
import com.clixsell.pos.guardian.GuardianResponses.Severity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static com.clixsell.pos.guardian.rules.GuardianRule.params;

/**
 * G4 – profit protection (Figure 3.1 "Profit protection"): the product's price
 * is above its cost, but if it were sold alone with the store's MAXIMUM allowed
 * discount, its price would fall below the cost.
 *
 * <pre>
 * price after max discount = price × (1 − max% ÷ 100)   (2 decimals)
 * safe discount %          = (price − cost) ÷ price × 100 (rounded DOWN, 2 decimals)
 * </pre>
 * Severity LOW: a preventive note. Nothing is changed automatically.
 */
public final class MaxDiscountRiskRule implements GuardianRule {

    /** The numbers for one product, or null when the rule does not apply. */
    public record Risk(ProductFact product, double maxPercent, double priceAfterMax, double safePercent) {
    }

    public static Risk check(ProductFact p, double maxPercent) {
        if (p.cost() == null || maxPercent <= 0) return null;
        BigDecimal price = Amounts.money(p.price());
        BigDecimal cost = Amounts.money(p.cost());
        if (price.compareTo(cost) <= 0) return null; // G3 covers it
        BigDecimal after = price.multiply(BigDecimal.ONE.subtract(
                BigDecimal.valueOf(maxPercent).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
                .setScale(2, RoundingMode.HALF_UP);
        if (after.compareTo(cost) >= 0) return null;
        BigDecimal safe = price.subtract(cost).multiply(BigDecimal.valueOf(100)).divide(price, 2, RoundingMode.DOWN);
        return new Risk(p, maxPercent, after.doubleValue(), safe.doubleValue());
    }

    @Override
    public List<Alert> evaluate(GuardianData d) {
        List<Alert> out = new ArrayList<>();
        for (ProductFact p : d.products()) {
            Risk r = check(p, d.maxDiscountPercent());
            if (r == null) continue;
            out.add(GuardianRule.alert("G4:product:" + p.id(), "G4", "MAX_DISCOUNT_RISK", Severity.LOW, "PROFIT",
                    params("productName", p.name(), "price", p.price(), "cost", p.cost(),
                            "maxPercent", r.maxPercent(), "priceAfterMax", r.priceAfterMax(),
                            "safePercent", r.safePercent()),
                    List.of(GuardianRule.product(p.id(), p.name())), null, null, null,
                    GuardianRule.action("PRODUCT", p.id())));
        }
        return out;
    }
}

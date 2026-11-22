package com.clixsell.pos.guardian.rules;

import com.clixsell.pos.guardian.Amounts;
import com.clixsell.pos.guardian.GuardianData;
import com.clixsell.pos.guardian.GuardianData.ProductFact;
import com.clixsell.pos.guardian.GuardianResponses.Alert;
import com.clixsell.pos.guardian.GuardianResponses.Severity;

import java.util.ArrayList;
import java.util.List;

import static com.clixsell.pos.guardian.rules.GuardianRule.params;

/**
 * G3 – a product's CURRENT selling price is below (HIGH) or equal to (MEDIUM)
 * its recorded purchase cost: every sale of it loses money / earns nothing.
 * Products without a recorded cost are skipped (counted in the cost-data notice).
 */
public final class PriceBelowCostRule implements GuardianRule {

    @Override
    public List<Alert> evaluate(GuardianData d) {
        List<Alert> out = new ArrayList<>();
        for (ProductFact p : d.products()) {
            if (p.cost() == null) continue;
            int cmp = Amounts.money(p.price()).compareTo(Amounts.money(p.cost()));
            if (cmp > 0) continue;
            out.add(GuardianRule.alert("G3:product:" + p.id(), "G3", cmp < 0 ? "PRICE_BELOW_COST" : "PRICE_EQUALS_COST",
                    cmp < 0 ? Severity.HIGH : Severity.MEDIUM, "PROFIT",
                    params("productName", p.name(), "price", p.price(), "cost", p.cost()),
                    List.of(GuardianRule.product(p.id(), p.name())), null, null, null,
                    GuardianRule.action("PRODUCT", p.id())));
        }
        return out;
    }
}

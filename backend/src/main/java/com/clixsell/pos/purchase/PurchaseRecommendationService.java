package com.clixsell.pos.purchase;

import com.clixsell.pos.guardian.GuardianRepository;
import com.clixsell.pos.purchase.PurchaseQueries.ProductRow;
import com.clixsell.pos.purchase.PurchaseResponses.Candidate;
import com.clixsell.pos.purchase.PurchaseResponses.Candidates;
import com.clixsell.pos.purchase.PurchaseResponses.Caution;
import com.clixsell.pos.purchase.PurchaseResponses.OpenOrder;
import com.clixsell.pos.purchase.PurchaseResponses.RecommendationItem;
import com.clixsell.pos.purchase.PurchaseResponses.Recommendations;
import com.clixsell.pos.purchase.PurchaseResponses.SupplierRecommendations;
import com.clixsell.pos.purchase.ReorderCalculator.Classification;
import com.clixsell.pos.purchase.ReorderCalculator.Result;
import com.clixsell.pos.store.StoreRepository;
import com.clixsell.pos.supplier.Supplier;
import com.clixsell.pos.supplier.SupplierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Purchase Recommendations (Guardian Mode ON only, D13) and manual purchase candidates
 * (always). Read only: nothing is saved, ordered or changed here.
 */
@Service
@Transactional(readOnly = true)
public class PurchaseRecommendationService {

    /** One calculated recommendation with its product (used to validate Guardian orders). */
    public record Recommended(ProductRow product, Result result, PurchaseQueries.Reservation reservation,
                              List<PurchaseQueries.Caution> cautions) {
        public boolean available() {
            return product.supplierActive() && reservation == null && result.classification() != Classification.INCONSISTENT;
        }
    }

    private final PurchaseQueries queries;
    private final GuardianRepository guardian;
    private final StoreRepository stores;
    private final SupplierRepository suppliers;

    public PurchaseRecommendationService(PurchaseQueries queries, GuardianRepository guardian, StoreRepository stores,
                                         SupplierRepository suppliers) {
        this.queries = queries;
        this.guardian = guardian;
        this.stores = stores;
        this.suppliers = suppliers;
    }

    public boolean guardianEnabled(long storeId) {
        return stores.findById(storeId).map(s -> s.isGuardianEnabled()).orElse(false);
    }

    /** product id → its current recommendation (only products that need a purchase now). */
    public Map<Long, Recommended> calculate(long storeId, LocalDateTime now) {
        Map<Long, Long> net = guardian.netUnitsLast14(storeId, now.toLocalDate());
        Map<Long, PurchaseQueries.Reservation> held = queries.reservations(storeId);
        Map<Long, List<PurchaseQueries.Caution>> cautions = queries.cautions(storeId, now);
        Map<Long, Recommended> out = new LinkedHashMap<>();
        for (ProductRow p : queries.products(storeId)) {
            Result r = ReorderCalculator.calculate(p.fact(), net, now);
            if (r == null) continue;
            out.put(p.id(), new Recommended(p, r, held.get(p.id()), cautions.getOrDefault(p.id(), List.of())));
        }
        return out;
    }

    public Recommendations recommendations(long storeId) {
        LocalDateTime now = PurchaseTime.now();
        if (!guardianEnabled(storeId)) {
            return new Recommendations(false, "RULES", PurchaseTime.text(now), ReorderCalculator.HORIZON_DAYS,
                    ReorderCalculator.URGENT_DAYS, ReorderCalculator.COVER_DAYS, List.of(), List.of());
        }
        Map<Long, SupplierRecommendations> bySupplier = new LinkedHashMap<>();
        List<RecommendationItem> inconsistent = new ArrayList<>();
        List<Recommended> all = new ArrayList<>(calculate(storeId, now).values());
        all.sort((a, b) -> Integer.compare(rank(a.result().classification()), rank(b.result().classification())));
        for (Recommended x : all) {
            RecommendationItem item = item(x);
            if (x.result().classification() == Classification.INCONSISTENT) {
                inconsistent.add(item);
                continue;
            }
            ProductRow p = x.product();
            bySupplier.computeIfAbsent(p.supplierId(), k -> new SupplierRecommendations(p.supplierId(),
                    p.supplierName(), p.supplierActive(), p.supplierHasWhatsapp(), new ArrayList<>())).items().add(item);
        }
        List<SupplierRecommendations> suppliersOut = new ArrayList<>(bySupplier.values());
        suppliersOut.sort((a, b) -> a.active() != b.active() ? (a.active() ? -1 : 1)
                : a.supplierName().compareToIgnoreCase(b.supplierName()));
        return new Recommendations(true, "RULES", PurchaseTime.text(now), ReorderCalculator.HORIZON_DAYS,
                ReorderCalculator.URGENT_DAYS, ReorderCalculator.COVER_DAYS, suppliersOut, inconsistent);
    }

    /** Manual order: the supplier's products with facts only (works with Guardian Mode ON or OFF). */
    public Candidates candidates(long storeId, long supplierId) {
        Supplier s = suppliers.findBySupplierIdAndStore_StoreId(supplierId, storeId)
                .orElseThrow(() -> PurchaseException.notFound("SUPPLIER_NOT_FOUND", "Supplier " + supplierId + " was not found"));
        LocalDateTime now = PurchaseTime.now();
        Map<Long, PurchaseQueries.Reservation> held = queries.reservations(storeId);
        Map<Long, List<PurchaseQueries.Caution>> cautions = queries.cautions(storeId, now);
        List<Candidate> out = new ArrayList<>();
        for (ProductRow p : queries.productsOfSupplier(storeId, supplierId)) {
            int q = p.fact().quantity();
            String stock = q < 0 ? "INCONSISTENT" : q == 0 ? "OUT_OF_STOCK" : q <= p.fact().minStock() ? "LOW_STOCK" : "OK";
            PurchaseQueries.Reservation r = held.get(p.id());
            String reason = !s.isActive() ? "SUPPLIER_INACTIVE" : r != null ? "IN_OPEN_ORDER" : q < 0 ? "INCONSISTENT_STOCK" : null;
            out.add(new Candidate(p.id(), p.fact().name(), p.barcode(), q, p.fact().minStock(), stock, p.fact().cost(),
                    reason == null, reason, OpenOrder.of(r),
                    cautions.getOrDefault(p.id(), List.of()).stream().map(Caution::of).toList()));
        }
        boolean hasWhatsapp = s.getWhatsappNumber() != null && !s.getWhatsappNumber().isBlank();
        return new Candidates(s.getSupplierId(), s.getName(), s.isActive(), hasWhatsapp, out);
    }

    private static int rank(Classification c) {
        return switch (c) {
            case OUT_OF_STOCK -> 0;
            case URGENT -> 1;
            case SOON -> 2;
            case LOW_STOCK -> 3;
            case INCONSISTENT -> 4;
        };
    }

    static String unavailableReason(Recommended x) {
        if (x.result().classification() == Classification.INCONSISTENT) return "INCONSISTENT_STOCK";
        if (!x.product().supplierActive()) return "SUPPLIER_INACTIVE";
        if (x.reservation() != null) return "IN_OPEN_ORDER";
        return null;
    }

    static RecommendationItem item(Recommended x) {
        ProductRow p = x.product();
        Result r = x.result();
        return new RecommendationItem(p.id(), p.fact().name(), p.barcode(), p.fact().quantity(), p.fact().minStock(),
                r.classification().name(), r.basis().name(), r.suggestedQuantity(), r.targetStock(), r.observedDays(),
                r.netUnits(), round2(r.unitsPerDay()), round1(r.daysLeft()), r.expectedStockout(), p.fact().cost(),
                x.available(), unavailableReason(x), OpenOrder.of(x.reservation()),
                x.cautions().stream().map(Caution::of).toList(), explanation(p, r));
    }

    /** The rule and the numbers used, in plain words (the app translates by basis / classification). */
    static String explanation(ProductRow p, Result r) {
        int q = p.fact().quantity();
        int min = p.fact().minStock();
        return switch (r.basis()) {
            case SALES_RATE -> String.format(Locale.ROOT,
                    "Sales rate: %d net units sold (sold − returned) in %d days = %.2f per day. "
                            + "Target = minimum %d + %d days × rate rounded up = %d. Suggested = %d − %d in stock = %d.",
                    r.netUnits(), r.observedDays(), r.unitsPerDay(), min, ReorderCalculator.COVER_DAYS, r.targetStock(),
                    r.targetStock(), q, r.suggestedQuantity());
            case MIN_STOCK_ONLY -> String.format(Locale.ROOT,
                    "Not enough sales history (%d net units in %d days; at least 3 units in 7 days are needed). "
                            + "Based on the minimum stock only, not a demand forecast: target = 2 × %d = %d. "
                            + "Suggested = %d − %d in stock = %d.",
                    r.netUnits(), r.observedDays(), min, r.targetStock(), r.targetStock(), q, r.suggestedQuantity());
            case MANUAL -> r.classification() == Classification.INCONSISTENT
                    ? "The stock quantity in the database is not valid (" + q + "). Correct the product first; no quantity is suggested."
                    : "Not enough sales history and no minimum stock: there is no basis to suggest a quantity. "
                            + "Choose the quantity yourself.";
        };
    }

    private static Double round2(Double v) {
        return v == null ? null : Math.round(v * 100) / 100.0;
    }

    private static Double round1(Double v) {
        return v == null ? null : Math.round(v * 10) / 10.0;
    }
}

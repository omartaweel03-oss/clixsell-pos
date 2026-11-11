package com.clixsell.pos.demo;

import com.clixsell.pos.auth.CurrentUser;
import com.clixsell.pos.product.Product;
import com.clixsell.pos.product.ProductRequest;
import com.clixsell.pos.product.ProductService;
import com.clixsell.pos.returns.ReturnRequest;
import com.clixsell.pos.returns.ReturnService;
import com.clixsell.pos.sale.DiscountType;
import com.clixsell.pos.sale.PaymentMethod;
import com.clixsell.pos.sale.SaleRequest;
import com.clixsell.pos.sale.SaleRequest.Line;
import com.clixsell.pos.sale.SaleResponse;
import com.clixsell.pos.sale.SaleService;
import com.clixsell.pos.store.StoreRepository;
import com.clixsell.pos.store.StoreRequest;
import com.clixsell.pos.store.StoreService;
import com.clixsell.pos.user.NewUserRequest;
import com.clixsell.pos.user.Role;
import com.clixsell.pos.user.StaffService;
import com.clixsell.pos.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Demo data for presentations and for team members: two Palestinian
 * supermarkets with their Store Admins, Cashiers, 10 products each, and a few
 * sales, suspended invoices and a return.
 *
 * <p>Runs once, only when the database has <b>no stores yet</b> (a new
 * database). Everything is added through the normal services, so the same
 * business rules apply (BCrypt passwords, product validation, stock checks,
 * totals with discount and tax, QR receipts, stock going down and back up).
 * <p>Off by default. Enabled only by the {@code demo} profile
 * ({@code application-demo.properties}) or {@code clixsell.demo-data=true}.
 * A database that already has stores is never changed.
 */
@Component
@Order(2)
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    static final String ADMIN_PASSWORD = "Admin-Pass-1";
    static final String CASHIER_PASSWORD = "Cashier-Pass-1";

    private final boolean enabled;
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;
    private final StoreService stores;
    private final StaffService staff;
    private final ProductService products;
    private final SaleService sales;
    private final ReturnService returns;

    public DemoDataInitializer(@Value("${clixsell.demo-data:false}") boolean enabled,
                               StoreRepository storeRepository, UserRepository userRepository,
                               StoreService stores, StaffService staff, ProductService products,
                               SaleService sales, ReturnService returns) {
        this.enabled = enabled;
        this.storeRepository = storeRepository;
        this.userRepository = userRepository;
        this.stores = stores;
        this.staff = staff;
        this.products = products;
        this.sales = sales;
        this.returns = returns;
    }

    private record P(String name, String barcode, String category, double price, int qty, int minStock,
                     String supplier) {
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || storeRepository.count() > 0) {
            return;
        }

        // ── Store 1: Shini Supermarket (Ramallah) ─────────────────────────
        long shini = stores.createStore(new StoreRequest("Shini Supermarket",
                "Al-Irsal Street, Ramallah", "0599123456", 16.0, 10.0)).storeId();
        staff.create(shini, Role.STORE_ADMIN, new NewUserRequest("shini.admin", "Ahmad Odeh", ADMIN_PASSWORD));
        staff.create(shini, Role.CASHIER, new NewUserRequest("shini.cashier", "Lina Saleh", CASHIER_PASSWORD));
        staff.create(shini, Role.CASHIER, new NewUserRequest("shini.cashier2", "Yousef Khalil", CASHIER_PASSWORD));
        List<Long> s = addProducts(shini, List.of(
                new P("Al-Juneidi Fresh Milk 1L", "6251000000011", "dairy", 6.50, 40, 10, "Al-Juneidi Dairy"),
                new P("Al-Juneidi Labaneh 500g", "6251000000028", "dairy", 9.00, 25, 8, "Al-Juneidi Dairy"),
                new P("Nabulsi White Cheese 500g", "6251000000035", "dairy", 18.00, 12, 5, "Al-Juneidi Dairy"),
                new P("Taboun Bread (6 pcs)", "6251000000042", "bakery", 5.00, 30, 10, "Ramallah Bakery"),
                new P("Ka'ak Al-Quds", "6251000000059", "bakery", 3.00, 4, 6, "Ramallah Bakery"),
                new P("Jericho Medjool Dates 1kg", "6251000000066", "freshProduce", 35.00, 15, 5, "Jericho Farms"),
                new P("Palestinian Olive Oil 1L", "6251000000073", "groceries", 38.00, 20, 5, "Al-Zaytouna Co."),
                new P("Green Freekeh 1kg", "6251000000080", "groceries", 16.00, 0, 5, "Al-Zaytouna Co."),
                new P("Palestinian Za'atar 250g", "6251000000097", "groceries", 12.00, 18, 5, "Al-Zaytouna Co."),
                new P("Mineral Water 1.5L", "6251000000103", "beverages", 2.50, 60, 20, "Jericho Farms")));

        CurrentUser lina = user("shini.cashier");
        CurrentUser yousef = user("shini.cashier2");
        // milk ×2, bread ×1, dates ×1 – cash
        SaleResponse first = sales.complete(lina, sale(Map.of(s.get(0), 2, s.get(3), 1, s.get(5), 1),
                null, null, PaymentMethod.CASH));
        // olive oil ×2, za'atar ×1 – 5% discount, card
        sales.complete(lina, sale(Map.of(s.get(6), 2, s.get(8), 1), DiscountType.PERCENT, 5.0, PaymentMethod.CARD));
        // labaneh ×1, cheese ×1, water ×6 – ₪3 discount, e-wallet
        sales.complete(yousef, sale(Map.of(s.get(1), 1, s.get(2), 1, s.get(9), 6),
                DiscountType.AMOUNT, 3.0, PaymentMethod.WALLET));
        // suspended: milk ×1, ka'ak ×2
        sales.suspend(yousef, sale(Map.of(s.get(0), 1, s.get(4), 2), null, null, null));
        // return 1 milk from the first sale
        long milkLine = first.items().stream()
                .filter(i -> s.get(0).equals(i.productId())).findFirst().orElseThrow().saleItemId();
        returns.processReturn(lina, new ReturnRequest(first.saleId(), milkLine, 1, "Damaged package"));

        // ── Store 2: Neshan Market (Nablus) ───────────────────────────────
        long neshan = stores.createStore(new StoreRequest("Neshan Market",
                "Rafidia Street, Nablus", "0569876543", 16.0, 10.0)).storeId();
        staff.create(neshan, Role.STORE_ADMIN, new NewUserRequest("neshan.admin", "Mohammad Nasser", ADMIN_PASSWORD));
        staff.create(neshan, Role.CASHIER, new NewUserRequest("neshan.cashier", "Rana Haddad", CASHIER_PASSWORD));
        List<Long> n = addProducts(neshan, List.of(
                new P("Hamoudeh Yogurt 1kg", "6252000000010", "dairy", 8.50, 30, 10, "Hamoudeh Dairy"),
                new P("Nabulsi Kunafa 1kg (frozen)", "6252000000027", "frozen", 30.00, 8, 3, "Nablus Sweets"),
                new P("Sinokrot Wafer", "6252000000034", "snacks", 2.00, 50, 15, "Sinokrot Foods"),
                new P("Nabulsi Olive Oil Soap", "6252000000041", "personalCare", 7.00, 25, 5, "Nablus Soap Factory"),
                new P("Arabic Coffee with Cardamom 200g", "6252000000058", "beverages", 15.00, 20, 5, "Nablus Roastery"),
                new P("Tahini 800g", "6252000000065", "groceries", 17.00, 3, 5, "Nablus Sweets"),
                new P("Fresh Cucumbers 1kg", "6252000000072", "freshProduce", 4.00, 40, 10, "Jenin Farms"),
                new P("Tomatoes 1kg", "6252000000089", "freshProduce", 5.00, 0, 10, "Jenin Farms"),
                new P("Arabic Pita Bread (10 pcs)", "6252000000096", "bakery", 4.00, 35, 10, "Nablus Bakery"),
                new P("Dishwashing Liquid 1L", "6252000000102", "cleaning", 9.50, 20, 5, "Nablus Soap Factory")));

        CurrentUser rana = user("neshan.cashier");
        sales.complete(rana, sale(Map.of(n.get(1), 1, n.get(4), 1), null, null, PaymentMethod.CASH));
        sales.complete(rana, sale(Map.of(n.get(0), 2, n.get(2), 5, n.get(6), 2),
                DiscountType.PERCENT, 10.0, PaymentMethod.BANK_TRANSFER));
        sales.suspend(rana, sale(Map.of(n.get(3), 3, n.get(8), 1), null, null, null));

        log.info("""

                ============================================================
                 Demo data added (2 stores, 20 products, sales, a return)
                   System Admin : sysadmin (password from application.properties)
                   Shini        : shini.admin / {}   shini.cashier, shini.cashier2 / {}
                   Neshan       : neshan.admin / {}  neshan.cashier / {}
                 (demo profile – do not use for a real store)
                ============================================================""",
                ADMIN_PASSWORD, CASHIER_PASSWORD, ADMIN_PASSWORD, CASHIER_PASSWORD);
    }

    private List<Long> addProducts(long storeId, List<P> list) {
        List<Long> ids = new ArrayList<>();
        for (P p : list) {
            Product saved = products.createProduct(storeId, new ProductRequest(p.name(), p.barcode(),
                    p.category(), p.price(), p.qty(), p.minStock(), p.supplier(), null, null));
            ids.add(saved.getProductId());
        }
        return ids;
    }

    private CurrentUser user(String username) {
        return CurrentUser.of(userRepository.findByUsernameIgnoreCase(username).orElseThrow());
    }

    private static SaleRequest sale(Map<Long, Integer> lines, DiscountType type, Double value,
                                    PaymentMethod method) {
        List<Line> items = new ArrayList<>();
        lines.forEach((id, qty) -> items.add(new Line(id, qty)));
        items.sort((a, b) -> Long.compare(a.productId(), b.productId()));
        return new SaleRequest(null, items, type, value, method);
    }
}

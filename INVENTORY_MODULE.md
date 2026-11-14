# ClixSell POS – Inventory, Multi-Store, Login and Cashier Dashboard (POS)

## Architecture
```
Flutter app ─HTTP/JSON + login token─▶ Spring Boot API ─▶ business logic ─▶ SQLite database
LoginPage, StoresPage, StaffAccountsPage,    Spring Security      Product, User, Store,    backend/clixsell.db
InventoryPage, ProductFormPage, PosPage      (role rules)         Sale, SaleItem, Payment, stores, users, user_sessions,
ApiHttp → Product/Auth/Stores/Sales clients  *Controller          Receipt, ReturnProduct   products, suppliers, sales, …
```

## Screen design
All screens follow the **desktop dashboard designs** of the report (Figures 3.1–3.9): sidebar,
same columns and tables at every size. On narrower screens the sidebar shrinks to icons and wide
parts (POS columns, tables) scroll sideways instead of overflowing – there is no separate
mobile layout. Only **AI Product Identification (Figure 3.10)** will have a dedicated mobile
screen (not built yet).

## Stores and roles
Several independent stores share one database. Every Store Admin, Cashier, Product and
Supplier belongs to exactly one store (`store_id`). The store a request works on is always
taken from the **logged-in user**, never from the request, so one store cannot see or change
another store's data (another store's record is answered with 404).

| Role | Can do |
|---|---|
| **System Admin** (`sysadmin`) | Create / edit / activate / deactivate stores (incl. tax rate and maximum discount); create and manage each store's Store Admins |
| **Store Admin** (report: Manager) | Products of their store (add, edit, delete); create and manage their store's Cashiers; POS; Reports and Sales Analytics |
| **Cashier** | POS (opens first); view their store's products (read only) |

Everyone uses the **same Login screen** (username + password). Usernames are unique in the
whole system (tip: `store.name`, e.g. `shini.ahmad`). The app opens the right screens for the
role; the backend enforces the same rules on every request (401 not logged in, 403 wrong role).

Security: passwords stored as BCrypt hashes; login tokens random, stored only as SHA-256
hashes, valid 12 hours; 5 wrong passwords lock the account for 5 minutes; deactivating a
user or store logs them out at once. Stores and users are deactivated, never deleted.

## Cashier Dashboard – POS (report Figure 3.2)
Layout as in Figure 3.2: **Invoice Summary + Payment Methods** | **Current Invoice** |
**Categories / Products**, with **New Invoice · Suspend Invoice · Return · Cancel Invoice** at
the bottom (tabs Products / Invoice on narrow screens; Arabic = right-to-left).

* **Add products**: tap a product card, or type / scan a barcode in the search box and press
  Enter. Quantity never goes above the stock; out-of-stock products cannot be added.
* **Totals** (shown live, recalculated by the backend from database prices when saving):
  `subtotal → discount → tax = (subtotal − discount) × tax rate → total`, rounded to 0.01.
* **Discount**: one per invoice, amount or %, at most the store's maximum (default 10%).
* **Tax**: per store, set by the System Admin (default 16%), prices are before tax.
* **Complete Sale**: one payment method (Cash, Credit card, E-wallet, Bank transfer). The
  backend checks all stock first (409 *Not enough stock* → nothing saved), then saves the sale,
  items, payment and receipt and lowers stock — all in one transaction. A digital receipt
  with a **QR code** is shown.
* **Invoice number**: per store `INV-000001`, given at the first save ("New invoice" before).
* **Suspend / resume**: suspended invoices are shared by the store's cashiers, do not reserve
  stock, and use current prices when resumed. Resuming and completing keeps the number.
* **Cancel Invoice**: clears the invoice; a resumed suspended invoice is marked `CANCELED`.
* **Return**: find a completed sale by invoice number or receipt code, choose the product and
  quantity (≤ sold − already returned), give a reason. Allowed within **14 days**
  (`clixsell.returns.max-days`). Refund = the product's share of the paid total, by the
  sale's payment method; stock goes back up.
* Every sale, suspended invoice and return belongs to the cashier's store only.

**Not included (deferred, beyond Figure 3.2):** camera barcode scanning, AI product
identification, offline mode / sync, receipt printing, change calculation, split payments.

## Stock Alerts (Figure 3.5, UR-M9 / SR22)
Store Admin only, own store. Cards *Total alerts*, *Out of stock*, *Low stock* (tap to filter), a
table of the products at or below their alert level (minimum stock): product, supplier,
category, current stock, alert level, status and **Update stock** (opens the product form and
comes back here). The sidebar shows the live alert count. Data: `GET /api/reports/inventory`
with the existing rules (out of stock = quantity 0, low = quantity ≤ minimum stock), so alerts
follow every sale, return and restock.

**Not built yet:** the report's *Create purchase request* needs purchase orders (SR26–SR29:
recommendation, manager approval, WhatsApp to the supplier, new database tables). The button is
shown disabled with this reason – no fake action.

## Reports (Figure 3.8) and Sales Analytics (Figure 3.7)
Store Admin only (the report's Manager), always for the logged-in user's own store.

* **Reports** (layout of Figure 3.8): cards *Net units sold*, *Net revenue*, *Invoice count* and
  *Profit* (vs previous month); featured products and the daily sales chart (monthly chart for a
  whole year); inventory report (UR-M8 / SR21: stock value, status, categories); **Generate
  report** shows the whole report as text that can be copied.
* **Sales Analytics** (layout of Figure 3.7): monthly sales (bars) and daily sales (line),
  products near reorder level, best-selling products (donut with %).
* Month and year selectors reload the figures.

How the figures are calculated (backend `ReportService`, never in the app):
* Only **COMPLETED** sales count (suspended and canceled never). **Sales count on their
  completion date, returns on their return date** – for every figure, so a closed month never
  changes.
* **Net revenue** = what customers paid (after the invoice discount, tax included) − refunds.
  **Net units sold** = units sold − units returned.
* **Best-selling products**: units and paid value of the period's sales minus the period's returns.
* **Near reorder level** = the existing stock rules: out of stock (quantity 0) and low stock
  (quantity ≤ minimum stock level).
* **Profit** = revenue **without tax** (line total × (subtotal − discount) / subtotal, so the
  discount is shared once and the tax never counts) − the **purchase cost saved on each sold
  line** (`sale_items.unit_cost`, copied from `products.cost_price` when the sale is completed).
  Returns subtract their revenue and cost on the return date.
  * *Complete* – every line of the period has a cost.
  * *Partial* – some lines have no cost (sold before costs were entered): the card says
    "Profit (partial)" and the % of revenue it covers; it is never shown as a complete total.
  * *Not available* – no line of the period has a cost.
* **Cost price** is an optional product field for Store Admins; cashiers never receive it.

## Run (Windows / macOS)
Requires **Java JDK 17+** (backend) and **Flutter 3.38+** (frontend). Maven and SQLite need no installation.

1. First login: **`sysadmin` / `Admin12345`** (created on the first start, when the
   database has no System Admin yet). To use another password, change
   `clixsell.system-admin.password=` in `backend/src/main/resources/application.properties`
   **before** the first start. Change the default before real use.
2. Start the backend (terminal 1):
   ```bash
   cd backend
   .\mvnw.cmd spring-boot:run      # Windows
   ./mvnw spring-boot:run          # macOS / Linux
   ```
   Wait for `Started ClixsellBackendApplication`.
3. Start the app (terminal 2, project folder):
   ```bash
   flutter pub get
   flutter run -d chrome
   ```
4. **Demo data (optional, off by default)** – for presentations and testing only. Start the
   backend with the `demo` profile on a **new** database (no `backend/clixsell.db` yet):
   ```bash
   .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"
   ```
   It adds two Palestinian supermarkets:

   | Store | Store Admin | Cashiers | Password (admin / cashier) |
   |---|---|---|---|
   | Shini Supermarket – Ramallah | `shini.admin` | `shini.cashier`, `shini.cashier2` | `Admin-Pass-1` / `Cashier-Pass-1` |
   | Neshan Market – Nablus | `neshan.admin` | `neshan.cashier` | `Admin-Pass-1` / `Cashier-Pass-1` |

   Each store has 10 products (Al-Juneidi milk, olive oil, za'atar, Nabulsi kunafa, …; some
   low or out of stock), completed sales, a suspended invoice, and Shini has one return.
   A database that already has stores is **never changed** (nothing is added, deleted or
   overwritten). Settings: `application-demo.properties`.
5. Without demo data: log in as `sysadmin`, create a store and its Store Admin. Log in as that Store Admin to add
   products and cashiers. Log in as a cashier: the POS opens and sales can be made.

### Upgrading an existing database
On the first start of this version, Flyway upgrades `backend/clixsell.db` automatically:
a backup `clixsell-backup-before-v2-<date>.db` is written first, and all existing products and
suppliers are moved to a store called **"Default Store"** (rename it in the Stores page and
give it a Store Admin). The version with profit adds `products.cost_price` and `sale_items.unit_cost`
(`V4__cost_price.java`): only two empty columns are added, a backup
`clixsell-backup-before-v4-<date>.db` is written first and nothing is changed or deleted; old
sales keep an unknown cost (never guessed).
The sales version adds the sales tables (`V3__sales.sql`); existing stores get tax 16% and
maximum discount 10%, and every existing store, user and product is kept (tested in
`UpgradeToSalesVersionTest`). To start from an empty database instead, stop the backend and delete
`backend/clixsell.db`.

## API
| API | Who | Database |
|---|---|---|
| `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me` | anyone / logged in | `users`, `user_sessions` |
| `GET, POST /api/admin/stores`, `GET, PUT /api/admin/stores/{id}`, `PUT …/{id}/status` | System Admin | `stores` |
| `GET, POST /api/admin/stores/{id}/admins`, `PUT …/admins/{userId}/status`, `PUT …/admins/{userId}/password` | System Admin | `users` |
| `GET /api/store` (own store) | Store Admin, Cashier | `stores` |
| `GET, POST /api/store/cashiers`, `PUT …/cashiers/{id}/status`, `PUT …/cashiers/{id}/password` | Store Admin | `users` |
| `GET /api/products`, `GET /api/products/{id}` | Store Admin, Cashier | `SELECT` (own store) |
| `POST /api/products` · `PUT /api/products/{id}` · `DELETE /api/products/{id}` · `GET /api/products/generate-barcode` | Store Admin | `INSERT` · `UPDATE` · `DELETE` (own store) |
| `POST /api/sales` (complete) | Store Admin, Cashier | `INSERT` sales, sale_items, payments, receipts; `UPDATE` products stock |
| `POST /api/sales/suspended` · `PUT /api/sales/suspended/{id}` | Store Admin, Cashier | `INSERT` / `UPDATE` sales (SUSPENDED) |
| `GET /api/sales/suspended` · `GET /api/sales/{id}` | Store Admin, Cashier | `SELECT` (own store) |
| `DELETE /api/sales/suspended/{id}` | Store Admin, Cashier | `UPDATE` status = CANCELED |
| `GET /api/sales/lookup?code=` (invoice number or QR code) | Store Admin, Cashier | `SELECT` completed sale |
| `POST /api/returns` | Store Admin, Cashier | `INSERT` returns; `UPDATE` sale_items, products stock |
| `GET /api/reports/summary?from=&to=` | Store Admin | `SELECT` sales, returns (cards, vs previous period) |
| `GET /api/reports/daily-sales?from=&to=` · `GET /api/reports/monthly-sales?year=` | Store Admin | `SELECT` (one point per day / month, 0 when no sales) |
| `GET /api/reports/top-products?from=&to=&limit=` | Store Admin | `SELECT` sale_items of completed sales |
| `GET /api/reports/inventory` | Store Admin | `SELECT` products (inventory report, products to reorder) |

Sale request: `{"saleId": null, "items": [{"productId": 1, "quantity": 2}], "discountType": "PERCENT",
"discountValue": 5, "paymentMethod": "CARD"}` — the app sends only ids and quantities; prices
always come from the database.

## Database
```
stores (store_id PK, name UNIQUE, address, phone, status, tax_rate, max_discount_percent,
        next_invoice_number, created_at)
 ├─< users (user_id PK, store_id FK NULL only for SYSTEM_ADMIN, username UNIQUE, full_name,
 │          password_hash, role, status, failed_login_attempts, locked_until, created_at)
 │     └─< user_sessions (session_id PK, user_id FK, token_hash UNIQUE, created_at, expires_at)
 ├─< suppliers (supplier_id PK, store_id FK, name, …, UNIQUE(store_id, name))
 ├─< products  (product_id PK, store_id FK, supplier_id FK, name, barcode, …, cost_price NULL, UNIQUE(store_id, barcode))
 ├─< sales (sale_id PK, store_id FK, cashier_id FK, invoice_number, status SUSPENDED|COMPLETED|CANCELED,
 │          subtotal, discount, tax_rate, tax, total, created_at, updated_at, completed_at,
 │          UNIQUE(store_id, invoice_number))
 │     ├─< sale_items (sale_item_id PK, sale_id FK, product_id FK NULL if deleted, product_name,
 │     │               barcode, quantity, unit_price, line_total, returned_quantity, unit_cost NULL)
 │     ├── payments (payment_id PK, sale_id FK, payment_method, amount, payment_status, paid_at)
 │     └── receipts (receipt_id PK, sale_id FK UNIQUE, qr_code_value UNIQUE, receipt_url, issued_at)
 └─< returns (return_id PK, store_id FK, sale_id FK, sale_item_id FK, product_id FK, cashier_id FK,
              quantity, reason, refund_amount, refund_method, return_date)
```
Migrations: `backend/src/main/resources/db/migration/V1__initial_schema.sql` (original tables)
, `backend/src/main/java/db/migration/V2__multi_store.java` (stores, users, store_id) and
`V3__sales.sql` (sales, sale_items, payments, receipts, returns; store tax settings) and
`V4__cost_price.java` (purchase cost for profit).

## Tests
```bash
cd backend && .\mvnw.cmd test     # 101 backend tests: business logic (Sale, ReturnProduct, …),
                                 #   API + database, two-store isolation, roles, lockout, migration
flutter test                     # 94 Flutter tests (use a fake server), incl. POS cart + screen
```

## Files
| Path | Purpose |
|---|---|
| `lib/main.dart` | App entry: Login screen until logged in, then the user's screens; EN/AR (Arabic = RTL) |
| `lib/state/auth_session.dart` | Who is logged in; login / logout; back to Login when the session ends |
| `lib/services/api_http.dart` | JSON requests with the login token, `ApiException` |
| `lib/services/auth_api_client.dart` | Login / logout API |
| `lib/services/admin_api_clients.dart` | Stores API, Store Admins API, Cashiers API |
| `lib/services/product_api_client.dart` | Product API |
| `lib/models/app_user.dart`, `store_info.dart` | User (role, store) and store models |
| `lib/screens/auth/login_page.dart` | Shared Login screen |
| `lib/screens/admin/stores_page.dart` | System Admin: stores |
| `lib/screens/admin/staff_accounts_page.dart` | Store Admins (System Admin) and Cashiers (Store Admin) |
| `lib/screens/app_shell.dart` | Layout + navigation, pages per role |
| `lib/widgets/app_sidebar.dart` | Sidebar: menu per role, current user, language, Log out |
| `lib/widgets/page_header.dart` | Page header, status pill, empty state, confirm dialog |
| `lib/screens/inventory/*` | Inventory page (read-only for Cashiers), product form, table |
| `lib/models/product.dart`, `product_category.dart`, `lib/state/product_store.dart` | Products |
| `lib/screens/pos/pos_page.dart` | Cashier Dashboard (POS) screen and its actions |
| `lib/screens/pos/pos_widgets.dart`, `pos_dialogs.dart` | POS panels; discount, suspended, return, receipt (QR) dialogs |
| `lib/state/pos_cart.dart` | Current invoice: lines, stock limits, totals, discount, resume |
| `lib/services/sales_api_client.dart`, `lib/models/sale.dart` | Sales / returns API and models |
| `lib/l10n/app_strings.dart` | All UI text in English and Arabic |
| `test/support/fake_product_backend.dart` | Fake API (login, roles, stores) used by the Flutter tests |
| `backend/…/auth/*` | Login, sessions, token filter, first System Admin |
| `backend/…/config/SecurityConfig.java` | Role rules, CORS, BCrypt |
| `backend/…/store/*` | Store entity, service, System Admin and own-store APIs |
| `backend/…/user/*` | User entity + account rules, Store Admin and Cashier APIs |
| `backend/…/product/*` | Product entity + business logic, store-scoped service and API |
| `backend/…/sale/*` | Sale, SaleItem, Payment, Receipt business logic; sales service and API |
| `backend/…/returns/*` | ReturnProduct business logic; returns service and API |
| `backend/…/report/*` | Reports & Sales Analytics: period rules, queries, calculations, API |
| `lib/screens/inventory/stock_alerts_page.dart` | Stock Alerts (Fig 3.5) |
| `lib/screens/reports/*` | Reports page (Fig 3.8), Sales Analytics page (Fig 3.7), shared widgets |
| `lib/widgets/sales_charts.dart` | Line, bar and donut charts (CustomPaint, no extra package) |
| `lib/services/report_api_client.dart`, `lib/models/report.dart` | Reports API client and models |
| `backend/…/demo/DemoDataInitializer.java` | Demo stores, staff, products, sales (`demo` profile, new database only) |
| `backend/…/supplier/*` | Supplier entity (per store) |
| `backend/…/common/*` | JSON errors, date converter, shared exceptions |

## Product rules
* Out of stock: quantity == 0
* Low stock: 0 < quantity <= minimum stock level
* Inventory value: sum of price × quantity
* Barcode unique within a store (two stores may use the same barcode)

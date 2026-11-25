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
| **System Admin** (`sysadmin`) | Create / edit / activate / deactivate stores (incl. tax rate and maximum discount); create and manage each store's Store Admins; Settings: own profile and password, language, **database backups** |
| **Store Admin** (report: Manager) | Products of their store (add, edit, delete); create and manage their store's Cashiers (incl. resetting their passwords); POS; Reports and Sales Analytics; Settings: own store's tax, maximum discount, address and phone, own profile and password, language |
| **Cashier** | POS (opens first); view their store's products (read only). **No Settings** and no own password change: the Store Admin sets cashier passwords |

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

## Suspended Invoices (Figure 3.6, UR-C8 / UR-M10, SR9 / SR23)
Store Admin and Cashier, own store. Cards *Oldest suspended* (how long ago), *Total value*
(prices when suspended) and *Total invoices*; table Invoice · **Cashier** · Amount · Items · Date ·
actions; pages (10 / 20 / 50 per page); month filter (default: all months).
* The report's "Customer" column shows the **cashier** who suspended the invoice: customers are
  not recorded in the system (not in the ERD either).
* **Resume** opens the invoice in the POS with the existing rules (asks before replacing an open
  invoice, current prices, deleted products left out); completing keeps its number.
* Resuming the invoice that is **already open** in the POS with changes that are not saved asks
  first ("Discard your changes?"): *Keep my changes* keeps them (from this screen the POS opens
  with the invoice as it is); *Discard changes* loads the saved version. Without changes nothing
  is asked. Same rule in the POS "Suspended" window.
* **Delete** = cancel: after a confirmation the invoice is marked `CANCELED` and kept in the
  database (`DELETE /api/sales/suspended/{id}`).
* Uses the existing API only (`GET /api/sales/suspended`, `DELETE …/{id}`); no database change.
  The POS "Suspended" window still works as before.

## Product Details (Figure 3.4, UR-M5 / SR18)
A Store Admin screen: in Products, click a product's **name or image**. As in the figure: the
product image on the left; on the right name, barcode, category, price, quantity, minimum
stock, supplier, date added and status (coloured); **Edit Product** and **Delete Product**
below. The cost price is an extra row for the Store Admin (the backend never sends it to
Cashiers). The product is read again with the existing `GET /api/products/{id}` when the
screen opens, so the quantity is current after sales and returns; a product deleted elsewhere
shows "not found". Edit opens the existing form and returns to the details; Delete uses the
same confirmation and Undo as the table (`product_delete.dart`). Cashiers keep the read-only
product list and do not get this screen. No new API, no database change.

## Settings (Figure 3.9, UR-M12 / SR25)
Title, description, today's date and the eight cards of the figure (Printer, Taxes, Users,
Profile / Language, General, AI Mobile, Backup); "Settings" is the last sidebar item. Only the
System Admin and the Store Admin open it; the backend refuses Cashiers every settings API.

| Card | Store Admin | System Admin |
|---|---|---|
| Printer | "Not available yet" (no printing in the system yet) | same |
| Taxes | own store's tax rate and maximum discount (0–100 %) | per store → Stores page |
| Users | existing Cashiers page | existing Stores page (Store Admins) |
| Profile | name, username, role, store; change **own** password | same |
| Language | English / العربية, remembered on this device (`shared_preferences`) | same |
| General | own store's address and phone (the name stays with the System Admin) | per store → Stores page |
| AI Mobile | "Not available yet" (needs AI Product Identification, Figure 3.10) | same |
| Backup | "System Admin only" | create a checked backup, list backups |

* **Only the values sent change:** Taxes sends tax and discount, General sends address and
  phone, so two dialogs (or two managers) saving one after the other never undo each other.
* **Tax / discount:** new sales (and suspended invoices completed later) use the new values;
  completed sales keep the tax rate, tax, discount and total saved on them. The POS reads the
  store's settings again each time it opens.
* **Own password:** current password required (a wrong one is a 400, not a logout, and counts
  like a failed login: 5 in a row lock the account for 5 minutes); usual password rules; saved
  as BCrypt; the user's other sessions end, this one stays; passwords never returned or logged.
* **Backup and restore:** `POST /api/admin/backups` copies the whole database with SQLite
  `VACUUM INTO` into `backend/backups/clixsell-backup-YYYYMMDD-HHmmss.db` (folder
  `clixsell.backup.dir`; owner-only permissions; not in Git), then opens the copy read-only and
  checks `PRAGMA integrity_check` (a failed copy is deleted). The API never takes a file name or
  path (no download, delete or restore). **Manual restore:** 1) stop the backend; 2) keep a copy
  of the current `backend/clixsell.db`; 3) copy the chosen backup over `backend/clixsell.db`;
  4) start the backend (Flyway brings an older backup up to date).

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

## Guardian Mode – Phase 1 (Figure 3.1, UR-M16 / SR30, UR-M17 / SR31)
A Store Admin screen (first item of the menu). **Off by default** for every store; the Store
Admin turns it on (confirmation), and off again with the switch. When it is on it is the Store
Admin's first page after login (as in Figure 3.1); when it is off Products stays first.

**Rule-based, not a trained AI model** – the screen says so. The backend reads the store's real
records (sales and their lines with the sale-time cost, returns, canceled invoices, products and
the activity log) and applies fixed, explainable rules (`backend/…/guardian/GuardianThresholds.java`):

| Rule | When | Severity |
|---|---|---|
| G1 high discount | discount ≥ 75% of the store's maximum discount **and** ≥ 5 ₪ | MEDIUM; HIGH when the same logged user does it 3+ times in a day |
| G2a sold below cost | a line with a known sale-time cost sold at a lower unit price | HIGH |
| G2b confirmed loss | **every** line has a cost and subtotal − discount < cost (tax excluded) | HIGH |
| G2c discount share below cost | a line with a known sale-time cost, priced at or above it, whose share of the invoice discount (the Reports rule: line total × (subtotal − discount) ÷ subtotal) is below its cost, on an invoice that is **not** a confirmed loss; one alert per product per day | MEDIUM |
| G3 price ≤ cost | current price below (HIGH) or equal to (MEDIUM) the recorded cost | HIGH / MEDIUM |
| G4 profit protection | the store's maximum discount would sell the product below cost | LOW |
| G5 stock | out of stock / low stock (same rules as Stock Alerts) | HIGH / MEDIUM |
| G6 stockout soon | own sales rate of the last 14 days (≥ 7 days observed, ≥ 3 net units) → ≤ 3 days | HIGH (≤ 7 days: at-risk list) |
| G7 canceled invoices | per day ≥ 3 and ≥ 10% of the day's invoices (≥ 6 and ≥ 20% → HIGH) | MEDIUM / HIGH |
| G8 returns | G8a: per day refunds ≥ 50 ₪ and ≥ 10% of the day's sales (≥ 25% HIGH); G8b: same product returned 3× in 7 days (see below) | MEDIUM / HIGH |
| G9 deletions | same user deletes ≥ 5 products in a day (≥ 10 HIGH) | MEDIUM / HIGH |

Recommendations (suggestions only, nothing changes automatically): T1 order before stockout
(G6; "Create purchase request" stays disabled until purchase orders exist), T2 margin under
10%, T3 not sold for 30 days with stock above the minimum.

* **Missing costs are never guessed**: an invoice with a line without cost is "incomplete
  cost" – never a loss and not part of the profit part of the health score.
* **Sale-time facts** (decision U7): G2a, G2b and G2c describe the sale when it was made. Units
  returned later are shown next to them, never subtracted: they are not the current net result.
* **G2c** (decision U4) is an *allocation-based product margin risk, not a confirmed invoice
  loss*: it uses the same discount allocation as the Reports profit and only the line's own
  recorded cost (an invoice whose other lines have no cost can still show it; their cost is never
  guessed). The alert shows the worst line of the day (lowest share per unit) and the total
  shortfall. It is not part of the "Profit risks" number (G2a, G2b, G3, unchanged), but it is never
  hidden: when there are G2c alerts the card shows a separate line ("+ 1 margin risk after discount
  sharing, not included in this number · View") that opens the G2c alerts, and the card never says
  just "None" while they exist. The dashboard sends the count as `marginRiskCount`.
* **G8b** (same product returned 3 times within 7 days) also reads the returns of the 14 days
  before the chosen month, so a 7-day window can start in the previous month. For each return
  the returns of that product in the 7 days ending at it are counted; an *episode* starts at the
  return that reaches 3. It is reported once, in the month of that return (never again in the
  next month), one alert per product per month (the largest window; `episodes` = how many).
* **Same incident** (decision U8): different rules can report the same invoice or product
  (e.g. G3 price below cost, G2a sold below cost, G2b that invoice's loss). They stay separate
  alerts, each one lists the others in `related` ("Also reported as: … – one issue"), and the Critical card
  shows how many separate issues the critical alerts are (`criticalIssues`). A "repeated high
  discounts" alert (G1) keeps every invoice in its count and is linked to the other alerts of
  those invoices (e.g. G2b). Alerts about a day or a list of products (G5, G6, G7, G8a, G9) are
  never linked.
* **Cashier area** of the map (decision U2) = POS alerts of the period: G1, G7, G8a, **by any
  user** – every alert shows the recorded user with their role (Store Admin / Cashier), so a
  Store Admin's action is never shown as a cashier's.
* **Who did it** comes only from the activity log, written by the backend with the logged-in
  user in the same transaction (the user who completed a sale set its final discount; the
  user who canceled an invoice). Events from before the log started show no name.
* **Store Health Score**: 4 parts × 25 points (stock, profit, discounts, activity); a part
  without enough data is "not available" with the reason; a number is shown only with 3 or more
  parts. 80+ Good, 60–79 Needs follow-up, below 60 Risk.
  * Activity part (decision U1) = 25 × (1 − days with an activity alert (G7, G8a, G9) ÷ active
    days), where an **active day is a day with a completed sale OR an activity alert** (a day
    with an alert but no sale counts).
  * The score is a general measure (ratios of the period); it does not say there are no risks
    (decision U3). The card names the open critical alerts next to it, and while any critical
    alert is open the band is at most "Needs follow-up" (`bandLimited`); the number is not changed.
* Not in Phase 1 (later phases, not built): saved alerts / read / dismiss, statistical limits,
  removed invoice lines in the POS, suppliers, purchase recommendations and orders, WhatsApp.

### Midnight bounds of Reports and Sales Analytics (U5, fixed)
A time is stored as `LocalDateTime.toString()`, which leaves out `:00` seconds: the first second
after midnight is `"2026-11-01T00:00"`. Reports and Sales Analytics (`ReportPeriod`) used to bound a
day with `"<day>T00:00:00"`, which sorts AFTER that text, so such a sale or return was counted on
the day (month, year) before and was missing from the daily chart. The bounds are now
`"<day>T00:00"` (the smallest text of any time of a day), the same as Guardian Mode. No stored
record, schema or migration changed; only the reading. If an old database holds records stored
exactly as `"…T00:00"`, their past day / month now shows them where they belong. Tests:
`ReportPeriodTest`, `ReportMidnightApiTest` (midnight sales and returns, month and year boundaries,
profit, tax, charts, previous period, best sellers, two stores, the Guardian 7-day chart).

## Purchase Orders – Phase 1: database V6 and Supplier Management (UR-M13–UR-M15 groundwork)

Phase 1 adds the purchase-order **database structure** and **Supplier Management** only. There are no
purchase recommendations, no purchase-order screens or API, and no WhatsApp messages yet (later
phases, each approved separately). Nothing in the app creates a purchase order.

**Suppliers screen** (Store Admin only, sidebar → Suppliers): list of the store's suppliers with
WhatsApp number, phone, number of products and status; search by name or number; filter
All / Active / Inactive; add, edit, details (products of the supplier), deactivate / activate.
Suppliers are never deleted. Arabic and English.

Rules:
- Names: spaces are normalized ("  Al   Quds " → "Al Quds"); duplicates within a store are refused
  (English letters compared without case, like the database). Legacy names in the database are never
  rewritten automatically.
- WhatsApp: international format, stored as digits only (e.g. `970599123456`); a local number
  (`05…`) is refused.
- Deactivating a supplier does not touch its products: they stay linked and keep selling in the POS.
  A new product, or a product moved to another supplier, cannot use an inactive supplier
  (409 `SUPPLIER_INACTIVE`); nothing except the Suppliers screen reactivates a supplier.
- Product form: a supplier name that differs from an existing one only by spaces links to it;
  if several legacy suppliers match that way, 409 `SUPPLIER_NAME_AMBIGUOUS` (type the exact name).
- New endpoints accept only their own fields: anything else (e.g. `storeId`) → 400 `UNKNOWN_FIELD`.

**Database V6** (`V6__purchase_orders.java`, additions only): `suppliers.active`,
`stores.next_purchase_order_number`, unique (id, store_id) indexes, and the tables `purchase_orders`,
`purchase_order_items`, `purchase_order_events` with composite foreign keys (no link between two
stores, even with direct SQL), NULL-safe CHECK constraints and triggers (state diagram of Figure 3.20,
one open order per product, append-only history, Store Admin actors, no deletion of orders or
suppliers). Before changing anything the migration checks integrity and references, writes a new,
verified backup `clixsell-backup-before-v6-<time>.db` (never overwriting a file) and rolls back if
any existing row or value changes.

API: `GET/POST /api/suppliers`, `GET/PUT /api/suppliers/{id}`, `PUT /api/suppliers/{id}/status`.

## Purchase Orders – Phase 2: recommendations, candidates and order workflow (backend only)

Backend API only (the purchase screens are Phase 4). Store Admin only; the store always comes from the
login; another store's data answers 404. The supplier communication that follows APPROVED is Phase 3
(next section); receiving goods is not part of the purchasing feature yet.

| Endpoint | What it does |
|---|---|
| `GET /api/purchase-recommendations` | Guardian Mode ON: products to buy, by supplier, with the rule and numbers. OFF: `guardianEnabled: false`, nothing calculated |
| `GET /api/purchase-candidates?supplierId=` | Manual order: the supplier's products with stock facts, open orders and cautions — never a suggested quantity |
| `POST /api/purchase-orders` | Create (`PENDING_APPROVAL`). Body: `clientRequestId` (UUID), `supplierId`, `source` (MANUAL / GUARDIAN), `note`, `items[{productId, quantity}]`, `acknowledgedCautions[{productId, orderId}]` |
| `GET /api/purchase-orders?status=&q=&overdue=&page=&size=` | List (`overdue=true`: only overdue orders, the server's rule over all orders, then paged) |
| `GET /api/purchase-orders/summary` | Exact counts by status, open and overdue — one SQL statement, no paging, no limit |
| `GET /api/purchase-orders/{id}` / `…/events` | Details (items, allowed actions, approval blockers) / history |
| `PUT /api/purchase-orders/{id}` | Edit while PENDING_APPROVAL: `{version, note, items, acknowledgedCautions}` (full replacement) |
| `POST /api/purchase-orders/{id}/approve` | `{version}` → APPROVED |
| `POST /api/purchase-orders/{id}/reject` | `{version, reason}` → REJECTED (reservations released) |
| `POST /api/purchase-orders/{id}/cancel` | `{version, reason}` from PENDING_APPROVAL or APPROVED → CANCELED (released); after the message see Phase 3 |

**Rules (D5):** candidates are out of stock, at / below the minimum, or running out within 7 days.
URGENT = within 3 days (exactly Guardian's alert), SOON = 4–7 days. With enough history (≥ 7 observed
days, ≥ 3 net units sold − returned in 14 days): target = minimum + ⌈net × 14 ÷ observed⌉ (SALES_RATE);
otherwise, with a minimum: target = 2 × minimum (MIN_STOCK_ONLY, "not a forecast"); otherwise MANUAL
(no quantity). Suggested = target − stock, at most 100000. Rule-based: no AI model, no confidence.

**Safety:** strict bodies (storeId, status, prices, costs … → 400 `UNKNOWN_FIELD`); the server takes
snapshots (name, barcode, calculation, expected unit cost from the product's cost — unknown stays
unknown). One product can be in one open order only (`PRODUCT_IN_OPEN_ORDER`). Idempotent creation:
the same `clientRequestId` with the same content returns the same order (`replayed`), with other
content 409 `IDEMPOTENCY_KEY_REUSED`. Every change checks the version (`ORDER_CHANGED`), runs in one
transaction with exactly one history event; "database is locked" is retried and otherwise answered
409 `DATABASE_BUSY` (nothing saved). Products of a CLOSED order (14 days) or an order CANCELED after
supplier contact (30 days, no recorded supplier acceptance) need an explicit acknowledgment
(`REORDER_CAUTION_ACK_REQUIRED`). Purchasing never changes stock, prices, costs, sales, returns or reports.

## Purchase Orders – Phase 3: supplier communication (backend only, UR-M15 / SR28 / SR29)

Backend API only (the screens and the WhatsApp button are Phase 4). **ClixSell never sends a message
and never contacts WhatsApp or a supplier.** It prepares the message, returns a `wa.me` link after the
manager confirms, and stores what the app reports (copy, opening) and what the manager records by hand
(sent, supplier confirmed, closed, supplier accepted a cancellation). Nothing is ever "delivered" or
"verified". Store Admin only; another store's order answers 404.

| Endpoint | What it does |
|---|---|
| `GET …/{id}/message-preview?language=ar\|en` | APPROVED: the message now (Arabic by default), number, length report, `previewToken`. Nothing saved |
| `POST …/{id}/message` | `{version, language, previewToken}` APPROVED → MESSAGE_GENERATED: text, number, language stored once (V6 T7) |
| `GET …/{id}/message` | The stored message (never a link; read only when the order is final) |
| `POST …/{id}/message-copied` | `{clientActionId}` app report, event only, `verified: false` |
| `POST …/{id}/whatsapp-open-requested` | `{clientActionId, confirmed: true}` event (phase REQUESTED) + `wa.me/<stored number>?text=<stored message>`; MESSAGE_GENERATED / SENT / CONFIRMED only |
| `POST …/{id}/whatsapp-open-result` | `{clientActionId, result: OPENED\|FAILED}` app report for that request (phase CLIENT_REPORTED) |
| `POST …/{id}/mark-sent` | `{version, confirmSentInWhatsapp: true}` MESSAGE_GENERATED → SENT (the manager's statement) |
| `POST …/{id}/supplier-confirmation` | `{version, confirmSupplierConfirmed: true, note?}` SENT → CONFIRMED |
| `POST …/{id}/close` | `{version, confirmNoReceiving: true}` CONFIRMED → CLOSED: not receiving, stock unchanged, reservations released |
| `POST …/{id}/cancel` | MESSAGE_GENERATED: `contactStatus` required — `NOT_SENT` (+ `confirmNotSent: true`), `UNKNOWN`, or `SENT` → 409 `RECORD_SENT_FIRST`. SENT / CONFIRMED: `acknowledgeSupplierNotNotified: true` (canceled after contact) |
| `POST …/{id}/supplier-cancellation-recorded` | `{confirmSupplierAccepted: true, channel, note 3–300}` once, for a canceled order whose supplier may have it |

**Message:** order number, store, date, supplier, and per line the snapshot name, barcode and quantity.
Never a price, cost, total, internal note or score. Length is counted in Unicode code points exactly as
SQLite's `length()` (V6 limit 2000; emoji = 1, not 2); names are cleaned (control and bidi characters
removed) and isolated for Arabic. Nothing is ever cut: an order whose message cannot fit in any language
is not approved (`MESSAGE_TOO_LONG`, with the longest lines and how many to remove); split it while
pending. Order details show `messageLength` per language before approval.

**Warnings (30 days, until a supplier acceptance is recorded):** CANCELED_AFTER_CONTACT, CONTACT_UNKNOWN
(canceled with UNKNOWN), DECLARED_NOT_SENT (every NOT_SENT cancellation after the message was prepared —
final corrections S3: "not sent" is a declaration that cannot be verified, e.g. another manager may have
sent it at the same moment); RECENTLY_CLOSED
14 days. **Overdue (display):** message prepared ≥ 1 day, sent ≥ 3 days, confirmed ≥ 14 days.
**Concurrency:** every Phase 3 change takes SQLite's write lock first with an UPDATE that matches no
row (changes nothing), then checks and writes; client reports are de-duplicated by `clientActionId`
and the supplier acceptance by order. This uniqueness is enforced by the service, not by a database
constraint.

## Purchase Orders – Phase 4: the Flutter screens (Store Admin)

Sidebar section **Purchasing** (Store Admin only): Purchase Orders, Reorder Suggestions, Suppliers.

* **Purchase Orders** — the store's orders: summary cards (waiting for approval, to contact the
  supplier, waiting for the supplier, overdue) with the server's exact counts
  (`GET /api/purchase-orders/summary`), status filter, the overdue card as a server filter
  (`overdue=true`), search by order number or supplier (`GET /api/purchase-orders?q=`, searched by the
  server over all orders), paging. The sidebar badge (overdue orders) is read after login, after an
  order action and when the manager returns to Purchasing — never on a timer.
* **Conflicts (final corrections D3)** — a stale request (409 `ORDER_CHANGED` / `INVALID_TRANSITION`)
  carries `details.lastChange` (type, user, time). The app says whose change won — possibly the
  manager's own earlier request that finished after a timeout — reloads, and never repeats anything.
  After an unknown outcome that is not saved yet, "Check again" only reloads; a new attempt is safe
  because the version check refuses a second change.
* **Reorder Suggestions** — Guardian's rule-based suggestions by supplier (not an AI model):
  stock / minimum, urgency, the server's suggested quantity and how it was calculated, open orders,
  inactive suppliers, recent-order warnings. Select products of one supplier → order form. With
  Guardian Mode off nothing is suggested; a manual order is always possible.
* **New / Edit order** — supplier, products (the supplier's own), quantities, internal note (never in
  the supplier message), recent-order warnings to acknowledge. One `clientRequestId` per form: if
  the answer to "Save" is lost, the form locks and "Check and retry" resends exactly the same request
  (the server returns the existing order), so a second order is never created by accident.
* **Order details** — progress (Figure 3.20), the actions the backend allows (`allowedActions`),
  approval blockers, message length, the supplier message, the communication record and the history.

**WhatsApp (never sent by ClixSell):** "Prepare Message" (Arabic by default, English on request) →
"Open WhatsApp": step 1 shows the saved number and the full message and needs the manager's
confirmation (records the request, the server returns the `wa.me` link); step 2 "Open WhatsApp Now"
opens it with `url_launcher` (a separate click, so browsers do not block the window). Opening or
copying never records sending: the manager presses **"I Have Sent This Message"** and confirms. After
the order was sent, opening again is only under **More**, with a duplicate warning and an extra
confirmation. If the supplier's number changed after the message was prepared, the saved number is
shown, a warning explains it, and opening needs one more confirmation; the link is never redirected.

**Cancel after the message:** the manager must answer "Did you send this message?" — *No, I am sure*
(with a confirmation), *I do not know* (canceled without recording any sending; 30-day warning), or
*Yes* (leads to recording the sending first). After sending: acknowledgment that ClixSell does not
notify the supplier. **Errors:** a changed order is reloaded and the manager decides again (never
replayed automatically); when the outcome is unknown (timeout, busy database) the stored state is
checked before anything is said.

Dependency: `url_launcher` 6.3.2 (with `url_launcher_android` pinned to 6.3.30, which still supports
Flutter 3.38). On the web it hands the link to the browser and always reports success (a popup
blocker cannot be detected), so the app's "opened" record means only "handed to the browser".


Requires **Java JDK 17+** (backend) and **Flutter 3.38+** (frontend). Maven and SQLite need no installation.

1. Database and first System Admin (security / database safety):
   * The database file is `CLIXSELL_DB_PATH` (a full path is recommended), else `clixsell.db` in
     the folder the backend starts in. The full path is printed at start.
   * A missing database is **not** created automatically (a wrong folder would otherwise look
     like "all data lost"). For the **first start of a new installation** set
     `CLIXSELL_DB_CREATE=true` and `CLIXSELL_ADMIN_PASSWORD=<your own password>`; the System Admin
     `sysadmin` is created with that password. There is no built-in password, and no password is
     ever printed or logged. Later starts need neither variable.
   * An existing database with a System Admin starts exactly as before; nothing is reset.
     If a System Admin still uses a published demo password (an installation from before this
     version), the start logs a `SECURITY WARNING` (username only, never the password or hash) and
     Settings shows that System Admin a warning (`GET /api/admin/security-status`, System Admin
     only). Fix: Settings → Profile → Change password. Outside the demo profile a published demo
     password is refused as a new administrator password (creating a Store Admin; own change;
     System Admin resetting a Store Admin). A Store Admin that still uses one is warned about
     their own account in Settings (`GET /api/auth/me/security-status`, System Admin and Store
     Admin, own account only; Cashiers 403).
   * Cashiers: a published demo password is refused when a Cashier is created or reset (outside
     the demo profile). The Store Admin's Cashiers screen marks this store's Cashiers that still
     use one (`GET /api/store/cashiers/security-status`: Store Admin only, own store, ids only;
     Cashier and System Admin 403) and the existing "Reset password" fixes them. Nothing is reset
     or locked automatically.
   * `clixsell.demo-data=true` without the `demo` profile is refused at start, before the database
     is opened (no file, no migration, no demo store or user).
   * A first start whose admin settings are missing or invalid (empty, too short, a demo password)
     stops before the database file is created: no file, no tables, no migration.
   * Folders, empty or non-SQLite files, read-only files or folders are refused with a message.
   * The `demo` profile (throwaway presentation / test database only) still uses the documented
     demo logins (`sysadmin` / `Admin12345`, see the demo table) and may create its new database.
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
Guardian Mode adds `stores.guardian_enabled` (0 = off for every store) and the empty table
`activity_log` (`V5__guardian_mode.java`): a backup `clixsell-backup-before-v5-<date>-<ms>.db` is
written first and **checked** (SQLite integrity check and the same number of rows in every
table) before anything changes; nothing existing is changed or deleted.
If V5 ever stops half-way, it rolls its own change back (every row is kept, tested in
`GuardianMigrationTest`), but Flyway remembers a failed V5 and the next start stops with
"Detected failed migration to version 5". **There is no automatic repair** (decision U6: no
Flyway `repair`, nothing is fixed on start). Recover by hand, only from the real error:
1. Stop the backend and copy `backend/clixsell.db` (and keep the `clixsell-backup-before-v5-…db`).
2. Read the first error of the failed start in the log: it says why V5 stopped. Fix that cause.
3. Only if the log shows that V5 failed and its cause is fixed, remove that one row with any
   SQLite tool (e.g. DB Browser for SQLite):
   `DELETE FROM flyway_schema_history WHERE version = '5' AND success = 0;` and start again.
4. Or put the V5 backup file back in place of `clixsell.db`.
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
| `PUT /api/store/settings` (own store: tax, max. discount, address, phone; only the values sent change) | Store Admin | `UPDATE stores` |
| `PUT /api/auth/me/password` (own password) | System Admin, Store Admin (Cashier 403) | `UPDATE users`; `DELETE` other `user_sessions` |
| `GET, POST /api/admin/backups` (list / create a checked backup) | System Admin | `VACUUM INTO` a file (read only) |
| `GET, POST /api/store/cashiers`, `PUT …/cashiers/{id}/status`, `PUT …/cashiers/{id}/password` | Store Admin | `users` |
| `GET /api/products`, `GET /api/products/{id}` | Store Admin, Cashier | `SELECT` (own store) |
| `POST /api/products` · `PUT /api/products/{id}` · `DELETE /api/products/{id}` · `GET /api/products/generate-barcode` | Store Admin | `INSERT` · `UPDATE` · `DELETE` (own store) |
| `GET /api/product-files/export` · `POST /api/product-files/import/preview` · `POST /api/product-files/import` | Store Admin | CSV export (own store) · check only (nothing saved) · create NEW products, all or none |
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
| `GET /api/guardian/status` · `PUT /api/guardian/status {"enabled": true\|false}` | Store Admin (Cashier, System Admin 403) | `stores.guardian_enabled`; `INSERT activity_log` when it changes |
| `GET /api/guardian/dashboard?year=&month=` · `GET /api/guardian/alerts?…&severity=&rule=` | Store Admin | `SELECT` (rules G1–G9, health, recommendations; nothing when off) |
| `GET /api/guardian/activity?year=&month=&type=&page=` | Store Admin | `SELECT activity_log` (own store) |

Sale request: `{"saleId": null, "items": [{"productId": 1, "quantity": 2}], "discountType": "PERCENT",
"discountValue": 5, "paymentMethod": "CARD"}` — the app sends only ids and quantities; prices
always come from the database.

## Database
```
stores (store_id PK, name UNIQUE, address, phone, status, tax_rate, max_discount_percent,
        next_invoice_number, created_at, guardian_enabled 0|1)
 ├─< activity_log (log_id PK, store_id FK, user_id FK, event_type, entity_type, entity_id,
 │                 entity_name, details JSON, created_at)   -- written by the backend only
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
`V4__cost_price.java` (purchase cost for profit) and `V5__guardian_mode.java` (Guardian Mode).

## Tests
```bash
cd backend && .\mvnw.cmd test     # 204 backend tests: business logic (Sale, ReturnProduct, …),
                                 #   API + database, two-store isolation, roles, lockout, migrations,
                                 #   settings, own password, backups, Guardian rules and activity log
flutter test                     # 195 Flutter tests (use a fake server), incl. POS cart + screens
```
**Test files and backups are safe:**
* Maven runs the backend tests in `backend/target/test-work` (`pom.xml`, surefire
  `workingDirectory`). Every test database (`test-clixsell.db`, `migration-test.db`, …) and every
  backup a test makes is written there, never next to `backend/clixsell.db`. `mvnw clean` removes it.
* A test deletes only what it created: `OwnedFiles` reads the folder when the test starts and later
  deletes only the new entries. No test deletes by wildcard, and none overwrites a file
  (migrations use `VACUUM INTO`, which fails on an existing file; test files use `CREATE_NEW`).
* `BackupPreservationTest` puts older backups (upgrade v2 / v4 / v5 and a Settings backup) in a
  fresh folder, runs every test class that makes or lists backups there in a separate JVM, and
  checks that each older file is still there, byte for byte. It also fails if a new test that
  touches backups is not in its list.
* Still, run the tests in a separate clone of the project, never in the folder of a real store.

## Files
| Path | Purpose |
|---|---|
| `lib/main.dart` | App entry: Login screen until logged in, then the user's screens; EN/AR (Arabic = RTL) |
| `lib/state/auth_session.dart` | Who is logged in; login / logout; back to Login when the session ends |
| `lib/services/api_http.dart` | JSON requests with the login token, `ApiException` |
| `lib/services/auth_api_client.dart` | Login / logout API |
| `lib/services/admin_api_clients.dart` | Stores API, Store Admins API, Cashiers API |
| `lib/services/product_api_client.dart` | Product API |
| `lib/screens/inventory/product_details_page.dart`, `product_delete.dart` | Product Details (Fig 3.4); shared delete with Undo |
| `lib/services/settings_api_client.dart` | Settings API (store settings, own password, backups) |
| `lib/screens/settings/settings_page.dart`, `settings_dialogs.dart` | Settings (Fig 3.9): cards, Taxes, General, Profile, Language, Backup |
| `lib/state/language_preference.dart` | Remembers the chosen language on the device |
| `backend/.../backup/BackupService.java` | Checked database backups (System Admin) |
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
| `lib/screens/pos/suspended_invoices_page.dart`, `pos_resume.dart` | Suspended Invoices (Fig 3.6), shared resume |
| `lib/screens/inventory/stock_alerts_page.dart` | Stock Alerts (Fig 3.5) |
| `lib/screens/reports/*` | Reports page (Fig 3.8), Sales Analytics page (Fig 3.7), shared widgets |
| `lib/widgets/sales_charts.dart` | Line, bar and donut charts (CustomPaint, no extra package) |
| `lib/services/report_api_client.dart`, `lib/models/report.dart` | Reports API client and models |
| `backend/…/demo/DemoDataInitializer.java` | Demo stores, staff, products, sales (`demo` profile, new database only) |
| `backend/…/supplier/*` | Supplier entity (per store) |
| `backend/…/common/*` | JSON errors, date converter, shared exceptions |
| `backend/…/activity/*` | Activity log (who did what), written by the backend with the logged-in user |
| `backend/…/guardian/*`, `guardian/rules/*` | Guardian Mode: thresholds, data queries, rules G1–G9, recommendations, health score, API |
| `lib/screens/guardian/*` | Guardian Mode screen (Fig 3.1), widgets, dialogs (all alerts, health, activity report) |
| `lib/services/guardian_api_client.dart`, `lib/models/guardian.dart`, `lib/l10n/guardian_strings.dart` | Guardian API client, models, English / Arabic text |

## Product rules
* Out of stock: quantity == 0
* Low stock: 0 < quantity <= minimum stock level
* Inventory value: sum of price × quantity
* Barcode unique within a store (two stores may use the same barcode)


## Product import / export (UR-M11, SR24, Figure 3.3)

Products screen (Store Admin): **Import** and **Export** next to *Add product*. Cashiers see neither.

* **File**: CSV, UTF-8 (export adds a byte-order mark so Excel shows Arabic correctly), one header
  row with the columns `name, barcode, category, sellingPrice, costPrice, quantity, minStock,
  supplier` (any order; `costPrice` may be empty = unknown). These are exactly the product form's
  fields; images are not part of the file. `category` uses the keys `dairy, bakery, beverages,
  snacks, groceries, freshProduce, frozen, cleaning, personalCare, other`. Numbers use digits and
  a dot (`12.5`). Quoted fields may contain commas, quotes (`""`) and line breaks.
* **Formula guard**: on export, text starting with `= + - @` (or tab / CR) gets a leading `'`
  (spreadsheet formula injection); the import removes exactly that `'` again, so an exported file
  re-imports unchanged.
* **Import = preview, then confirm**: the server checks the whole file with the product form's
  own rules (`Product.validate`), the barcode unique in the file and in the store, the supplier
  found or created by name exactly as the form does (an inactive supplier is refused), and returns
  every row with its errors, the suppliers that would be created and the file's SHA-256. Nothing
  is saved. **Import** requires the same file, that hash and `confirmImport: true`; everything is
  checked again and all products are created in ONE transaction, or none.
* **Only new products**: an existing barcode is an error. Existing products, stock, prices and
  suppliers are never updated or overwritten by an import (an update / upsert mode would be a
  separate decision). Re-importing the same file is therefore refused as a whole.
* **Limits**: 2 MB and 5000 products per file. No new table and no migration.

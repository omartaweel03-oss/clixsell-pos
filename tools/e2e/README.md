# ClixSell end-to-end checks (`tools/e2e`)

Real checks of the whole system – Flutter web app → API → Spring Boot → SQLite – on **disposable
demo databases only**. They create stores, users, sales, returns and backups, and some write
directly into their test database, so they must never touch real data.

## Safety (read first)
* `run.sh` creates a new folder with `mktemp` (outside the project) for every suite, writes a marker
  file with a random token, and starts a new backend there with the `demo` profile on
  `<folder>/e2e.db`. It never uses `backend/clixsell.db` or any other database.
* Every script calls the safety gate (`safety.py` / `safety.js`) before it opens a database or calls
  the API. It stops (exit code 2) unless **all** of these hold: `CLIXSELL_E2E=1`; the work folder was
  made by `run.sh` (name, marker, token) and is outside the project; the database is exactly
  `<folder>/e2e.db`; and the running backend process uses that very file. Store names inside a
  database are never taken as proof of safety.
* `run.sh` refuses to start if port 8080 or 8090 is already in use (it could be a real backend).
* After each suite the backend is stopped and only the folder this run created is removed.
* Logs and screenshots go to `tools/e2e/out/` (ignored by Git). No database, backup or password of a
  real store is ever written there or committed.
* `python3 tools/e2e/safety_selftest.py` proves the gate: every script stops on 6 unsafe setups and a
  stand-in "user database" is left byte for byte unchanged. It needs no backend.

## Requirements
Java 17+, Python 3, a Flutter web build, Node.js with Playwright (`npm i -g playwright` and its
Chromium), Linux / macOS / Git Bash. The scripts call the API on `localhost:8080` and the web app on
`localhost:8090`.

## Run
```bash
cd backend && ./mvnw -q package -DskipTests && cd ..
tools/e2e/build_web.sh   # flutter build web + a fingerprint of the sources in build/web/.clixsell_web_stamp
CLIXSELL_E2E=1 tools/e2e/run.sh backend/target/clixsell-backend-1.0.0.jar build/web
```
One suite only: add its name, e.g. `... build/web reports`. Suites: api settings reports guardian full pos supplier purchase communication purchase-ui import-export.

| Suite | Scripts | What it checks |
|---|---|---|
| `api` | `api_check.py` | every module through the API, roles, two-store isolation |
| `settings` | `settings_check.py` | store settings, taxes, own password, backups (System Admin only) |
| `reports` | `reports_boundary_check.py`, `reports_ui.js` | U5: midnight sales and returns, month and year boundaries, every Reports / Sales Analytics figure vs. an independent calculation, best sellers, previous period, two stores, the Guardian 7-day chart; the Reports screen in English and Arabic |
| `guardian` | `guardian_scenario.py`, `guardian_ui.js` | Guardian Mode rules G1–G9 / G2c, health score, recommendations, activity log, on / off, English and Arabic, two stores |
| `full` | `full_e2e.js` | the whole app in the browser: login, System Admin, products (every page vs. the database), POS, suspended invoices, stock alerts, reports, settings, both stores, English / Arabic |
| `pos` | `real_pos.js` | real-world use in the browser: a Store Admin adds 12 products (form, search, barcode search, details, edit, duplicate barcode, delete + Undo, stock alerts); a Cashier sells with search, barcode + Enter, +/−, remove, discount, all 4 payment methods, receipt + QR, suspend / resume / cancel, full and partial returns, insufficient stock, Arabic; stock, tax, profit, reports and Guardian checked against independent calculations; a second store and direct API attacks |
| `supplier` | `supplier_check.py`, `supplier_ui.js` | Supplier Management (Purchase Orders Phase 1): V6 in place, roles, list = database per store, add / validate / duplicate / strict body, edit, isolation between two stores, inactive supplier refused on the product form but still sold in the POS, reactivation only from Suppliers; then the Flutter Suppliers screen in English and Arabic (add with validation, search, edit, details, deactivate / activate), Neshan isolation, cashier menu, 1024×768 |
| `purchase` | `purchase_check.py` | Purchase Orders Phase 2 through the real backend (no purchase screens exist yet): roles, Guardian OFF / ON, every Shini product's recommendation compared with an independent recalculation from the raw sales and returns rows, manual candidates, Guardian and manual orders with snapshots, idempotent retry and key reuse, edit, approve, reject, cancel with history and reservations, a closed order needing acknowledgment, two-store isolation, real concurrent requests, and no change to products, sales, returns or the report summary |
| `communication` | `communication_check.py` | Purchase Orders Phase 3 (supplier communication) through the real backend, no screens and no WhatsApp opened: message preview rebuilt from the order's snapshot rows, no cost or internal note, length = SQLite `length()`, generation once with the previewed text and number, number change warning, copy / opening request / client result (never verified, de-duplicated), mark-sent → supplier confirmation → close (no stock change), cancel with NOT_SENT / UNKNOWN / SENT and after contact, the four reorder warnings (NOT_SENT always warns — S3), the exact summary endpoint, supplier acceptance once, a too-long order refused at approval and fixed by editing, English, isolation and roles on all 10 endpoints, concurrent requests, and no change to products, sales, returns, reports, store rows or the activity log |
| `purchase-ui` | `purchase_ui.js` | Purchase Orders Phase 4 in the real Flutter web app on the real backend: suggestions → order (double click = one order) → edit → approve → preview Arabic / English → prepare → two-step WhatsApp opening (the link is captured; every non-local request is aborted in the browser; no font is requested from the internet: the fonts are bundled) → copy (clipboard = stored text) → I have sent → supplier confirmation → close → history; manual order and rejection; a lost answer on create (locked form, one order); two managers (stale version); a real SQLite lock; cancel with UNKNOWN / NOT_SENT after a copy / after contact; a second manager's "not sent" cancellation racing "I have sent" (S3 warning, never recorded as sent, reorder needs the acknowledgment); exact summary cards, the overdue filter and the sidebar badge after login and after an action (D1 / D2); warnings and supplier acceptance; missing and changed WhatsApp number; too-long message; Guardian off; Arabic; 1024×768; Neshan and cashier; money and stock unchanged |
| `import-export` | `import_export_ui.js` | Overnight mission D, real app + backend: Products screen Import / Export (Figure 3.3); a CSV with errors (every row error shown, nothing saved); a mixed Arabic / English CSV previewed, confirmed and imported (double click sends once; every field and supplier checked in the database); re-import refused whole; export downloaded (BOM, quoting, formula guard, own store only) and re-imported into a clean store identically; search, details, edit, Stock Alerts and Suppliers for imported products; Arabic preview; Cashier: sales on imported products (search, +/-, barcode, remove, 10 % discount, cash / card / wallet / bank transfer, suspend + resume, partial + complete return) with totals and stock calculated independently; report deltas and PARTIAL profit with an unknown cost; Guardian (missing cost, low stock, isolation); a purchase order (message preview, communication record, history, no stock change); two-store isolation of products, files, sales, reports, Guardian, suppliers and orders; no external request |

Helpers: `dbq.py` (read-only queries of the disposable database), `safety.py`, `safety.js`.

### Fresh web build (P4-D8)

`run.sh` refuses the browser suites (reports, guardian, full, pos, supplier, purchase-ui, import-export) unless
`build/web/.clixsell_web_stamp` holds the fingerprint of the CURRENT sources (`web_stamp.sh`: the
content of every file in lib/, web/ and assets/ plus pubspec.yaml / pubspec.lock — not file times). Build with
`tools/e2e/build_web.sh`, which writes that stamp, the commit and the Flutter version.

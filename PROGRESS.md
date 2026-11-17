# ClixSell POS – Progress (continue from here)

Last updated: **2026-11-17** · `main` = **09be153** (Settings merged; all feature branches merged and pushed)

Source of truth: the official 58-page project report (`clixsell_3.pdf`). Figures 3.1–3.10 are
the screen designs; Tables 3.2–3.6 are the requirements. Full status per requirement:
[`docs/REQUIREMENTS_STATUS.md`](docs/REQUIREMENTS_STATUS.md).

## Working rules (agreed with the project owner)
1. Plan first, then wait for **"START"** before changing any code.
2. Work on a `feature/...` branch; push only that branch. **Never merge into `main`
   without explicit approval.**
3. Never change the database without approval; keep existing data and behaviour
   (migrations back up the database and check row counts).
4. No fake functionality and no invented features: a feature that does not exist yet is
   shown as "not available yet", never as a working button.
5. Follow the report's **desktop** designs on every screen, with basic responsiveness
   (no overflow, horizontal scroll where needed; no fixed 1280 px canvas).
   **Only AI Product Identification (Figure 3.10) gets a dedicated mobile screen.**
6. Not started without approval: Purchase Orders, Guardian Mode, AI Product Identification.
7. Keep the existing commit author. Reports to the owner: short, simple Arabic, saved as TXT.
8. Every feature: backend + Flutter tests, API check, real browser + server + database run,
   screenshots compared with the report figure.

## Done (merged in `main`)
| Feature | Report | Notes |
|---|---|---|
| Login, 3 roles, multi-store isolation | SR1, SR2, Security | System Admin (stores, store admins), Store Admin, Cashier; lockout; store always taken from the session |
| Inventory: add / edit / delete products, barcode, image, stock status | Fig 3.3, UR-M2–M4, SR15–17 | cost price (V4) for profit |
| POS – Cashier Dashboard | Fig 3.2, UR-C1–C11, SR3, SR5–13 | tap / barcode (USB scanner), quantities, discount, per-store tax, 4 payment methods, QR receipt, suspend/resume, cancel, returns |
| Reports + Sales Analytics | Fig 3.8, 3.7, UR-M7–M8, SR20–21 | profit with sale-time cost |
| Stock Alerts | Fig 3.5, UR-M9, SR22 | "Create purchase request" shown disabled with reason (needs Purchase Orders) |
| Suspended Invoices screen | Fig 3.6, UR-C8, UR-M10, SR9, SR23 | "Customer" column shows **Cashier** (no customers in the system); delete = CANCELED, record kept; asks before discarding unsaved changes of an open invoice |
| Settings | Fig 3.9, UR-M12, SR25 | 8 cards. Store Admin: own store's tax + max. discount, address + phone, Cashiers page, own password, language (remembered). System Admin: own password, language, checked DB backups (`backend/backups`, manual restore). Printer + AI Mobile shown "Not available yet". Cashier: no Settings, cannot change own password (403). Only the values sent change (no lost update). No DB change |
| Desktop designs on every screen | all figures | basic responsiveness |
| Demo data (optional `demo` profile) | – | 2 Palestinian stores; see README |

**Tests on `main` (09be153):** backend 125/125 · Flutter 138/138 · `flutter analyze` clean ·
real API check `tools/e2e/api_check.py` 66/66 · Settings API check (5 accounts) 41/41.

## Pending: owner's own Windows testing (POSTPONED)
Everything was tested in the development environment (Linux, fresh demo databases, real
backend + web app). The owner's own test on the Windows PC is **postponed until later**. To check then:
1. Settings → Backup creates a file in `backend\backups\` (POSIX 700/600 permissions do not apply on Windows).
2. Language stays after F5 (note: `flutter run -d chrome` may start a fresh Chrome profile each run).
3. `flutter pub get` (new package `shared_preferences`, pinned for Flutter 3.38+).
4. Before using the real database: copy `backend\clixsell.db` first (this version has no migration).
5. Arabic text in the owner's Chrome.

## Decisions already approved
* Suspended Invoices (A–D): Cashier column instead of Customer; card "Oldest suspended";
  month filter defaults to "All months"; delete = cancel (record kept).
* Unsaved changes: resuming the invoice already open with changes asks
  "Discard your changes?" (Keep my changes / Discard changes).
* Profit uses the product cost saved on each sale line at sale time (V4 migration).
* Desktop-only layouts except Figure 3.10.
* SR3: the sale record is created when the invoice is first saved (suspend / complete).
* Settings (A–F + corrections): Printer / AI Mobile "Not available yet"; backups System Admin only,
  saved on the server, restore manual; language remembered (`shared_preferences`); Store Admin
  edits address + phone (name stays with System Admin); **Cashiers have no Settings and cannot
  change their own password** (Store Admin resets it); System Admin sees all 8 cards (Taxes /
  General / Users open the Stores page).

## Next: not started – wait for "START"
Settings plan and results (Arabic): `docs/plans/ClixSell_Settings_Plan.txt`,
`docs/reports/ClixSell_Settings_*.txt`, `docs/reports/ClixSell_Final_Test_Results.txt`.

## Still missing (from the report)
1. **Guardian Mode + Purchase Orders** – Fig 3.1, 3.18, 3.20; UR-M13–M17; SR26–SR31
   (recommendations from stock + sales, pending → approve/reject, WhatsApp request to supplier).
   Needs new tables (database change → approval) and supplier phone management.
2. **Import / Export products** – UR-M11, SR24 (inside the Products screen, Fig 3.3).
3. **Product Details view** – Fig 3.4, UR-M5, SR18 (today: table + edit form only).
4. **AI Product Identification (mobile)** – Fig 3.10, UR-C12, SR14; and camera barcode scan – SR4.
5. **Offline mode + sync** – SR32, SR33, Availability / Reliability NFRs.
6. **Receipt printing** – FUTURE_WORK #4; digital receipt sent to the customer (UR-U1 partial).
7. Smaller: stock-adjustment history (UR-M6 / SR19 partial), login by email (SR1 partial),
   Android build (Portability).

Suggested order: Import/Export + Product Details → Guardian Mode + Purchase Orders
→ AI Product Identification / camera → Offline.

## How to continue
```bash
git clone https://github.com/omartaweel03-oss/clixsell-pos.git
cd clixsell-pos
cd backend && ./mvnw test && cd ..            # Windows: .\mvnw.cmd test
flutter pub get && flutter test
```
Run: see `README.md` (demo profile for presentations). Architecture, API and tables:
`INVENTORY_MODULE.md`. Earlier Arabic reports and plans: `docs/reports/`, `docs/plans/`.
Screenshots: `docs/screenshots/`. Browser + API test scripts: `tools/e2e/`.

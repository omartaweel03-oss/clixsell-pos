# ClixSell POS – Progress (continue from here)

Last updated: **2026-11-14** · `main` = **752a5de** (all feature branches merged and pushed)

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
| Desktop designs on every screen | all figures | basic responsiveness |
| Demo data (optional `demo` profile) | – | 2 Palestinian stores; see README |

**Tests on `main`:** backend 105/105 · Flutter 119/119 · `flutter analyze` clean ·
real API check `tools/e2e/api_check.py` 66/66.

## Decisions already approved
* Suspended Invoices (A–D): Cashier column instead of Customer; card "Oldest suspended";
  month filter defaults to "All months"; delete = cancel (record kept).
* Unsaved changes: resuming the invoice already open with changes asks
  "Discard your changes?" (Keep my changes / Discard changes).
* Profit uses the product cost saved on each sale line at sale time (V4 migration).
* Desktop-only layouts except Figure 3.10.
* SR3: the sale record is created when the invoice is first saved (suspend / complete).

## Next: Settings (Figure 3.9) – planned, NOT started (wait for "START")
Full plan (Arabic): [`docs/plans/ClixSell_Settings_Plan.txt`](docs/plans/ClixSell_Settings_Plan.txt) ·
figure: [`docs/plans/figure_3.9_settings.png`](docs/plans/figure_3.9_settings.png)

Report: UR-M12, SR25 (settings, taxes, language, users, backups). Figure: title + 8 tiles —
Printer, Taxes, Users, Profile, Language, General, AI Mobile, Backup; "Settings" last in the sidebar.

| Tile | Today | Plan |
|---|---|---|
| Users | Cashiers page exists | tile opens it |
| Taxes | `tax_rate`, `max_discount_percent` exist; only System Admin edits | `PUT /api/store/settings` for the Store Admin (own store); completed sales keep their saved rate |
| Profile | name/role shown | change own password: `PUT /api/auth/me/password` (current + new; other sessions ended) |
| Language | toggle exists, not remembered | choose in tile; remember choice (decision C) |
| General | store name/address/phone (System Admin) | Store Admin edits address + phone; name stays with System Admin (decision D) |
| Backup | none | System Admin only (DB holds all stores): download a consistent copy (`VACUUM INTO`); restore manual + documented (decision B) |
| Printer | no printing (FUTURE_WORK #4) | "not available yet" tile (decision A) |
| AI Mobile | depends on Fig 3.10 | "available after AI Product Identification" tile (decision E) |

No database change needed. Cashier: no Settings screen (decision F). System Admin: Profile,
Language, Backup. **Decisions A–F are proposed and still need the owner's approval.**
Branch to use: `feature/settings` from `main`.

## Still missing after Settings (from the report)
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

Suggested order: Settings → Import/Export + Product Details → Guardian Mode + Purchase Orders
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

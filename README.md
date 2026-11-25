# ClixSell POS

Smart point-of-sale system for small shops (graduation project).
Flutter app (web / desktop) + Spring Boot backend + SQLite database, with several
independent stores in one system.

| Part | Folder | Technology |
|---|---|---|
| App (screens) | `lib/` | Flutter 3.38+, English / Arabic (right-to-left) |
| Backend (API + business logic) | `backend/` | Java 17+, Spring Boot, Spring Security |
| Database | `backend/clixsell.db` (created on first start) | SQLite, Flyway migrations |

## What it does
* **Login** for three roles: System Admin (stores), Store Admin (products, cashiers, POS),
  Cashier (POS, products read-only). Every store sees only its own data.
* **Inventory**: add / edit / delete products, barcodes, images, stock status and statistics.
* **POS – Cashier Dashboard**: add products by tap or barcode, quantities, discount,
  per-store tax, 4 payment methods, complete sale with a QR digital receipt,
  suspend / resume invoices, cancel, and product returns.
* **Suspended Invoices**, **Stock Alerts**, **Reports**, **Sales Analytics** and **Settings**
  (tax and discount, store details, own password, language, database backups).
* **Guardian Mode** (Store Admin, off by default): rule-based alerts on discounts, losses,
  stock, cancellations, returns and deletions; store health score; suggestions; activity log.
* **AI Product Identification, Phase 1** (Android app only): scan a barcode with the phone
  camera (on-device ML Kit), look it up in the store's inventory, and confirm the product.
  No cloud AI yet. See `ANDROID_PHASE1.md`.

## Run (Windows)
Requires **Java JDK 17+** and **Flutter**. Maven and SQLite need no installation.

Terminal 1 – backend (from the project folder):
```bash
cd backend
.\mvnw.cmd spring-boot:run
```
Wait for `Started ClixsellBackendApplication`.
(macOS / Linux: `./mvnw spring-boot:run`)

Terminal 2 – app (from the project folder):
```bash
flutter pub get
flutter run -d chrome
```

**First start of a new installation** (once): set your own System Admin password and allow
creating the database, e.g. in PowerShell:
```powershell
$env:CLIXSELL_DB_PATH = "C:\ClixSell\data\clixsell.db"   # optional, recommended (full path)
$env:CLIXSELL_DB_CREATE = "true"
$env:CLIXSELL_ADMIN_PASSWORD = "<your own password>"
```
Log in as `sysadmin` with that password, then create a store, its Store Admin, products and
cashiers. Later starts need no variables (only `CLIXSELL_DB_PATH` if you use it): an existing
database is used as it is, and a missing one is never created silently. There is no built-in
password. If the password is missing, too short or a published demo password, the start stops
**before** any database file is created. See `WINDOWS_DEMO.md` for the full Windows guide.

**Older installations** (created before this version) may still have `sysadmin` / `Admin12345`.
ClixSell does not change it by itself: it writes a `SECURITY WARNING` line at start and shows a red
warning to the System Admin in Settings. Sign in and change it: Settings → Profile → Change
password (take a backup first). Setting `CLIXSELL_ADMIN_PASSWORD` does **not** change an existing
password. Outside the demo profile, published demo passwords cannot be chosen for any account
(new Store Admin or Cashier, own password change, Store Admin or Cashier reset). A Store Admin that
still uses one sees the same kind of warning in Settings for their own account, and the Cashiers
screen marks this store's Cashiers that still use one, so the Store Admin can reset them.

## Demo data (optional – presentations and testing only)
Off by default. To start with two ready Palestinian demo stores, use the `demo` profile
**on a new database** (no `backend/clixsell.db` yet):
```bash
cd backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"
```
| Store | Store Admin | Cashiers | Password (admin / cashier) |
|---|---|---|---|
| Shini Supermarket – Ramallah | `shini.admin` | `shini.cashier`, `shini.cashier2` | `Admin-Pass-1` / `Cashier-Pass-1` |
| Neshan Market – Nablus | `neshan.admin` | `neshan.cashier` | `Admin-Pass-1` / `Cashier-Pass-1` |

System Admin on a new demo database: `sysadmin` / `Admin12345` (allowed only by the `demo`
profile; the normal configuration refuses this public password).

A database that already has stores is never changed by the demo profile. Demo data needs the
`demo` profile: `clixsell.demo-data=true` alone is refused at start, before any database is
opened or any demo store or user is created.

## Tests
```bash
cd backend
.\mvnw.cmd test        # backend: business logic, API + database, roles, store isolation, upgrades
cd ..
flutter test           # app: screens and state (with a fake server)
```
The backend tests run in their own folder, `backend/target/test-work`: every test database and
backup is written there, never next to `backend/clixsell.db`, and a test deletes only the files it
created itself (an older backup is never deleted or overwritten; `BackupPreservationTest` checks
this). To be safe anyway, run the tests in a separate copy (clone) of the project, not in the
folder that holds your real store database.

## More
* `INVENTORY_MODULE.md` – architecture, API list, database tables, files.
* `FUTURE_WORK.md` – features postponed for later.
* `ANDROID_PHASE1.md` – Android build, phone-to-backend setup, camera screen, test status.

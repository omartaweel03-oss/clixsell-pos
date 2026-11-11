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

First login: **`sysadmin` / `Admin12345`** (change it before real use; see
`backend/src/main/resources/application.properties`). Then create a store, its Store Admin,
products and cashiers.

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

A database that already has stores is never changed by the demo profile.

## Tests
```bash
cd backend
.\mvnw.cmd test        # backend: business logic, API + database, roles, store isolation, upgrades
cd ..
flutter test           # app: screens and state (with a fake server)
```

## More
* `INVENTORY_MODULE.md` – architecture, API list, database tables, files.
* `FUTURE_WORK.md` – features postponed for later.

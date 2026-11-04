# ClixSell POS – Inventory, Multi-Store and Login

## Architecture
```
Flutter app ─HTTP/JSON + login token─▶ Spring Boot API ─▶ business logic ─▶ SQLite database
LoginPage, StoresPage, StaffAccountsPage,    Spring Security      Product, User, Store     backend/clixsell.db
InventoryPage, ProductFormPage               (role rules)         services                 stores, users, user_sessions,
ApiHttp → Product/Auth/Stores API clients    *Controller          *Repository (JPA)        products, suppliers
```

## Stores and roles
Several independent stores share one database. Every Store Admin, Cashier, Product and
Supplier belongs to exactly one store (`store_id`). The store a request works on is always
taken from the **logged-in user**, never from the request, so one store cannot see or change
another store's data (another store's record is answered with 404).

| Role | Can do |
|---|---|
| **System Admin** (`sysadmin`) | Create / edit / activate / deactivate stores; create and manage each store's Store Admins |
| **Store Admin** (report: Manager) | Products of their store (add, edit, delete); create and manage their store's Cashiers |
| **Cashier** | View their store's products (read only) |

Everyone uses the **same Login screen** (username + password). Usernames are unique in the
whole system (tip: `store.name`, e.g. `shini.ahmad`). The app opens the right screens for the
role; the backend enforces the same rules on every request (401 not logged in, 403 wrong role).

Security: passwords stored as BCrypt hashes; login tokens random, stored only as SHA-256
hashes, valid 12 hours; 5 wrong passwords lock the account for 5 minutes; deactivating a
user or store logs them out at once. Stores and users are deactivated, never deleted.

## Run (Windows / macOS)
Requires **Java JDK 17+** (backend) and **Flutter 3.38+** (frontend). Maven and SQLite need no installation.

1. Choose the first System Admin password (only used when no System Admin exists yet):
   edit `backend/src/main/resources/application.properties`
   → `clixsell.system-admin.password=YourPassword` (at least 8 characters).
   If you leave it empty, a random password is printed **once** in the backend console.
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
4. Log in as `sysadmin`, create a store and its Store Admin. Log in as that Store Admin to add
   products and cashiers. Log in as a cashier to see the store's products.

### Upgrading an existing database
On the first start of this version, Flyway upgrades `backend/clixsell.db` automatically:
a backup `clixsell-backup-before-v2-<date>.db` is written first, and all existing products and
suppliers are moved to a store called **"Default Store"** (rename it in the Stores page and
give it a Store Admin). To start from an empty database instead, stop the backend and delete
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

## Database
```
stores (store_id PK, name UNIQUE, address, phone, status, created_at)
 ├─< users (user_id PK, store_id FK NULL only for SYSTEM_ADMIN, username UNIQUE, full_name,
 │          password_hash, role, status, failed_login_attempts, locked_until, created_at)
 │     └─< user_sessions (session_id PK, user_id FK, token_hash UNIQUE, created_at, expires_at)
 ├─< suppliers (supplier_id PK, store_id FK, name, …, UNIQUE(store_id, name))
 └─< products  (product_id PK, store_id FK, supplier_id FK, name, barcode, …, UNIQUE(store_id, barcode))
```
Migrations: `backend/src/main/resources/db/migration/V1__initial_schema.sql` (original tables)
and `backend/src/main/java/db/migration/V2__multi_store.java` (stores, users, store_id).

## Tests
```bash
cd backend && .\mvnw.cmd test     # 48 backend tests: business logic, API + database,
                                 #   two-store isolation, roles, lockout, migration
flutter test                     # 48 Flutter tests (use a fake server)
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
| `lib/l10n/app_strings.dart` | All UI text in English and Arabic |
| `test/support/fake_product_backend.dart` | Fake API (login, roles, stores) used by the Flutter tests |
| `backend/…/auth/*` | Login, sessions, token filter, first System Admin |
| `backend/…/config/SecurityConfig.java` | Role rules, CORS, BCrypt |
| `backend/…/store/*` | Store entity, service, System Admin and own-store APIs |
| `backend/…/user/*` | User entity + account rules, Store Admin and Cashier APIs |
| `backend/…/product/*` | Product entity + business logic, store-scoped service and API |
| `backend/…/supplier/*` | Supplier entity (per store) |
| `backend/…/common/*` | JSON errors, date converter, shared exceptions |

## Product rules
* Out of stock: quantity == 0
* Low stock: 0 < quantity <= minimum stock level
* Inventory value: sum of price × quantity
* Barcode unique within a store (two stores may use the same barcode)

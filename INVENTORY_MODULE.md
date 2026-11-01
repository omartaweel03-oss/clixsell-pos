# ClixSell POS – Inventory Management module

## Architecture
```
Flutter Product Screen ─HTTP/JSON─▶ Spring Boot API ─▶ Product business logic ─▶ SQLite database
(InventoryPage, ProductFormPage)    ProductController    ProductService + Product     backend/clixsell.db
        ProductStore → ProductApiClient                  ProductRepository (JPA)      tables: products, suppliers
```
| Screen action | API call | Database |
|---|---|---|
| Open the page | `GET /api/products` | `SELECT` |
| Save Product | `POST /api/products` | `INSERT` |
| Save Changes | `PUT /api/products/{id}` | `UPDATE` |
| Delete | `DELETE /api/products/{id}` | `DELETE` |
| Generate barcode | `GET /api/products/generate-barcode` | – |

## Run (Windows / macOS)
Requires **Java JDK 17+** (backend) and **Flutter 3.38+** (frontend). Maven and SQLite need no installation.

1. Start the backend (terminal 1):
   ```bash
   cd backend
   .\mvnw.cmd spring-boot:run      # Windows
   ./mvnw spring-boot:run          # macOS / Linux
   ```
   Wait for `Started ClixsellBackendApplication`. The database file `backend/clixsell.db` is created automatically.
2. Start the app (terminal 2, project folder):
   ```bash
   flutter pub get
   flutter run -d chrome
   ```

## Tests
```bash
cd backend && .\mvnw.cmd test     # 24 backend tests (business logic + API + database)
flutter test                     # 31 Flutter tests (uses a fake server)
```

## Files
| Path | Purpose |
|---|---|
| `lib/main.dart` | App entry, theme, EN/AR locale (Arabic = RTL) |
| `lib/models/product.dart` | Product model + stock-status rules |
| `lib/models/product_category.dart` | Category dropdown values (EN/AR) |
| `lib/state/product_store.dart` | Product data for the screens; every change goes through the API |
| `lib/services/product_api_client.dart` | HTTP client for the Product API + `ApiException` |
| `lib/config/api_config.dart` | Backend address (`http://localhost:8080/api`) |
| `lib/l10n/app_strings.dart` | All UI text in English and Arabic |
| `lib/theme/app_theme.dart` | Colours (navy sidebar, purple actions), spacing, theme |
| `lib/utils/formatters.dart` | Number / currency (₪) formatting |
| `lib/screens/app_shell.dart` | Responsive layout + navigation (Products / Add / Edit) |
| `lib/screens/inventory/inventory_page.dart` | Header, stats cards, search/filters, list, pagination |
| `lib/screens/inventory/product_form_page.dart` | Add / Edit form with image upload |
| `lib/screens/inventory/product_validators.dart` | Form validation rules |
| `lib/screens/inventory/widgets/product_table.dart` | Product table (wide) / cards (narrow) |
| `lib/screens/inventory/widgets/confirm_dialogs.dart` | Delete + discard-changes dialogs |
| `lib/widgets/app_sidebar.dart` | Dark navy sidebar |
| `lib/widgets/stat_card.dart` | Statistics card |
| `lib/widgets/stock_status_badge.dart` | In Stock / Low / Out badge |
| `lib/widgets/product_thumbnail.dart` | Product image / placeholder |
| `test/product_store_test.dart` | Unit tests (rules, stats, JSON, store + API, validators) |
| `test/inventory_page_test.dart` | Widget tests (load, add / edit / delete via API, server errors, nav, RTL, responsive) |
| `test/support/fake_product_backend.dart` | Fake Product API used by the Flutter tests |
| `backend/src/main/java/com/clixsell/pos/product/Product.java` | Product entity + business logic (validate, stock rules, updateStock, generateBarcode) |
| `backend/.../product/ProductService.java` | Insert / update / delete coordination (unique barcode, not found) |
| `backend/.../product/ProductController.java` | REST API `/api/products` |
| `backend/.../product/ProductRepository.java` | Database access (Spring Data JPA) |
| `backend/.../supplier/*` | Supplier entity, repository, find-or-create by name |
| `backend/.../common/*` | JSON error responses, date converter |
| `backend/.../config/CorsConfig.java` | Lets the Chrome app call the API |
| `backend/src/main/resources/schema.sql` | `products` and `suppliers` tables |
| `backend/src/main/resources/application.properties` | Port 8080, SQLite connection |
| `macos/Runner/*.entitlements` | Added `files.user-selected.read-only` for the image picker |

## Rules
* Out of stock: quantity == 0
* Low stock: 0 < quantity <= minimum stock level
* Inventory value: sum of price × quantity

Data is stored in the SQLite database by the backend and survives restarts.
To start with an empty database, stop the backend and delete `backend/clixsell.db`.

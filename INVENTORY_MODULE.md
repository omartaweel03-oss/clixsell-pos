# ClixSell POS – Inventory Management module

## Run (macOS)
```bash
flutter pub get
flutter run -d chrome      # or: flutter run -d macos
flutter test               # 21 unit + widget tests
```
Requires Flutter 3.38+ (Dart 3.10+) because of `file_picker` 13.

## Files
| Path | Purpose |
|---|---|
| `lib/main.dart` | App entry, theme, EN/AR locale (Arabic = RTL) |
| `lib/models/product.dart` | Product model + stock-status rules |
| `lib/models/product_category.dart` | Category dropdown values (EN/AR) |
| `lib/state/product_store.dart` | In-memory store (ChangeNotifier) + computed statistics |
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
| `test/product_store_test.dart` | Unit tests (rules, stats, validators) |
| `test/inventory_page_test.dart` | Widget tests (add / edit / delete / nav / RTL / responsive) |
| `macos/Runner/*.entitlements` | Added `files.user-selected.read-only` for the image picker |

## Rules
* Out of stock: quantity == 0
* Low stock: 0 < quantity <= minimum stock level
* Inventory value: sum of price × quantity

Data is kept in memory only (resets on restart). No database / Firebase / SQLite / backend.

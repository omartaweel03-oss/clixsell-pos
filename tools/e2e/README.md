# End-to-end check scripts (used to verify each feature)

**Use only with a demo database, never with a real store database:** the scripts log in with
the demo accounts and create sales, products and suspended invoices.

| File | What it does |
|---|---|
| `api_check.py` | 66 real API checks: login, roles, store isolation, products, POS, returns, reports, profit, alerts. `python3 api_check.py <path-to-clixsell.db>` |
| `q.py` | small helper: run one SQL query on the database |
| `lib.js` | Playwright helpers for the Flutter web build (login, buttons, screenshots) |
| `suspended_invoices_e2e.js` | Suspended Invoices: suspend, resume, complete, delete, pages, filters, Arabic, other store |
| `resume_unsaved_changes_e2e.js` | "Discard your changes?" when resuming an open invoice with changes |

How they were run:
1. Start the backend with the demo profile **in this folder** (so `clixsell.db` is created here):
   `java -jar ../../backend/target/clixsell-backend-1.0.0.jar --spring.profiles.active=demo`
2. Build and serve the app: `flutter build web` then serve `build/web` on port 8090.
3. `python3 api_check.py clixsell.db` and `node suspended_invoices_e2e.js`
   (needs `npm install playwright`).

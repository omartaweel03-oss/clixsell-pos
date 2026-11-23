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
flutter build web --no-web-resources-cdn
CLIXSELL_E2E=1 tools/e2e/run.sh backend/target/clixsell-backend-1.0.0.jar build/web
```
One suite only: add its name, e.g. `... build/web reports`.

| Suite | Scripts | What it checks |
|---|---|---|
| `api` | `api_check.py` | every module through the API, roles, two-store isolation |
| `settings` | `settings_check.py` | store settings, taxes, own password, backups (System Admin only) |
| `reports` | `reports_boundary_check.py`, `reports_ui.js` | U5: midnight sales and returns, month and year boundaries, every Reports / Sales Analytics figure vs. an independent calculation, best sellers, previous period, two stores, the Guardian 7-day chart; the Reports screen in English and Arabic |
| `guardian` | `guardian_scenario.py`, `guardian_ui.js` | Guardian Mode rules G1–G9 / G2c, health score, recommendations, activity log, on / off, English and Arabic, two stores |
| `full` | `full_e2e.js` | the whole app in the browser: login, System Admin, products (every page vs. the database), POS, suspended invoices, stock alerts, reports, settings, both stores, English / Arabic |

Helpers: `dbq.py` (read-only queries of the disposable database), `safety.py`, `safety.js`.

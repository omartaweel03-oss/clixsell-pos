# Running ClixSell on Windows (demonstration guide)

ClixSell runs on Windows as two local programs on the same computer:

1. the **backend** (Spring Boot, Java 17+) on `http://localhost:8080`, using one SQLite file;
2. the **app** (Flutter web) in Chrome or Edge on `http://localhost:8090` (or the port Flutter picks).

No native Windows build is used, and nothing needs the internet once installed: the app
bundles its fonts (`assets/fonts`) and its web engine (`--no-web-resources-cdn`).

> **Status:** this guide was written and checked on Linux with the same jar and web build. It
> has **not** been run on a Windows computer yet. Please follow it once on the demo computer
> before the presentation.

---

## 1. Check the computer (PowerShell)

```powershell
java -version            # must say 17 or newer
flutter --version        # only needed to build or run the app from source
Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue   # must print nothing
Get-NetTCPConnection -LocalPort 8090 -State Listen -ErrorAction SilentlyContinue   # must print nothing
```

If a port is busy, see *Troubleshooting*. Never stop a program you do not recognise.

## 2. Choose where the database lives (once)

Use a full path in a folder you own, for example `C:\ClixSell\data\clixsell.db`:

```powershell
New-Item -ItemType Directory -Force C:\ClixSell\data | Out-Null
```

ClixSell **never creates the folder by itself** and **never creates a new empty database
silently**. At every start it prints the full path it uses, e.g.
`ClixSell database: C:\ClixSell\data\clixsell.db (existing, 229376 bytes)`.

## 3. Start the backend (terminal 1)

### a) Your existing database
Set only the path (in the same PowerShell window that starts the backend):

```powershell
$env:CLIXSELL_DB_PATH = "C:\ClixSell\data\clixsell.db"
cd <project folder>\backend
.\mvnw.cmd spring-boot:run
```

(Or, with a built jar: `java -jar target\clixsell-backend-1.0.0.jar`.)
Wait for `Started ClixsellBackendApplication`. Nothing else is needed: an existing database
and its System Admin are used exactly as they are. No password is reset.

If you used the old way (database `clixsell.db` inside the `backend` folder), you can keep it:
start from the `backend` folder without `CLIXSELL_DB_PATH`, or set
`CLIXSELL_DB_PATH` to that file's full path.

### b) First start of a NEW installation (only once)

```powershell
$env:CLIXSELL_DB_PATH = "C:\ClixSell\data\clixsell.db"
$env:CLIXSELL_DB_CREATE = "true"
$env:CLIXSELL_ADMIN_PASSWORD = Read-Host "Choose the System Admin password"   # not saved anywhere
cd <project folder>\backend
.\mvnw.cmd spring-boot:run
```

Log in as `sysadmin` with the password you chose. Then close this window; for later starts use
**3a** (do not keep `CLIXSELL_DB_CREATE` or `CLIXSELL_ADMIN_PASSWORD` set). The password must
have at least 8 characters with letters and digits; the public demo passwords are refused.

### c) Presentation with demo data (throwaway database only)

```powershell
$env:CLIXSELL_DB_PATH = "C:\ClixSell\demo\clixsell-demo.db"   # a SEPARATE file, never your real one
cd <project folder>\backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"
```

The demo profile creates two demo stores on a new database (logins in `README.md`, including
`sysadmin` / `Admin12345`, allowed only by this profile). A database that already has stores is
left unchanged. **Never use the demo profile with your real database.**

## 4. Start the app (terminal 2)

Option A — development mode (needs Flutter):

```powershell
cd <project folder>
flutter pub get
flutter run -d chrome --web-port 8090
```

Option B — the built app (same files the automated tests use):

```powershell
cd <project folder>
flutter build web --no-web-resources-cdn
cd build\web
python -m http.server 8090 --bind 127.0.0.1        # or: jwebserver -p 8090 (JDK 18+)
```

Open `http://localhost:8090` in Chrome or Edge. The backend only accepts the app from
`localhost` / `127.0.0.1` (CORS), so open it on the same computer.

## 5. Safe shutdown

1. In the app: finish or suspend open invoices, then **Log out**.
2. Terminal 2 (app): press `Ctrl+C`.
3. Terminal 1 (backend): press `Ctrl+C` and wait until the prompt returns (SQLite closes the file).
4. Optional: **Settings → Backup** (System Admin) before shutting down after a demo day.

Do not close the backend by killing the window while a sale is being saved.

## 6. Troubleshooting

| Message / problem | What it means | What to do |
|---|---|---|
| `No database found at …` | The path has no database; ClixSell refused to create an empty one. | Check `CLIXSELL_DB_PATH` (or start from the folder with `clixsell.db`). Only for a new installation use 3b. |
| `The database file is EMPTY` / `not a SQLite database` | Wrong file. It was not changed. | Point `CLIXSELL_DB_PATH` to the real database file. |
| `The folder for the database does not exist` | Typo or missing folder. | Create the folder (step 2) or fix the path. |
| `must be readable and writable` / `folder … must be writable` | File or folder is read-only (e.g. on a USB stick or in `Program Files`). | Use a folder in your user profile, e.g. `C:\ClixSell\data`. |
| `No System Admin exists yet and no initial password is configured` | New database without `CLIXSELL_ADMIN_PASSWORD`. | Use 3b. |
| `publicly documented demo password` | You used a demo password outside the demo profile. | Choose your own password. No database was created. |
| `SECURITY WARNING: System Admin '…' still uses a publicly documented demo password` (log) / red warning in Settings | An older installation still has the demo password. Nothing was changed. | Take a backup, sign in as `sysadmin`, Settings → Profile → Change password. |
| `Port 8080 was already in use` | Another program uses the port. | Close the other ClixSell window, or check `Get-NetTCPConnection -LocalPort 8080`. You can run the backend on another port with `--server.port=8081`, then start the app with `--dart-define=API_BASE_URL=http://localhost:8081/api`. |
| The app says the server cannot be reached | Backend not started or still starting. | Wait for `Started ClixsellBackendApplication` in terminal 1. |
| Arabic shows as boxes | Should not happen with the bundled fonts. | Rebuild the app (`flutter build web --no-web-resources-cdn`) from the current sources. |

## 7. What this guide does not do

* It does not create a native Windows (`.exe`) app.
* It does not change or move an existing database by itself. (Only a newer backend version
  upgrades an older database through Flyway when it starts; the V2, V4, V5 and V6 upgrades
  write a safety backup first.)
* It does not use the internet, Firebase or any cloud service.

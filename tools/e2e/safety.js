// Safety gate for the ClixSell end-to-end browser scripts (Node). Same rules as safety.py:
// CLIXSELL_E2E=1, a work folder made by run.sh (marker + random token, outside the project),
// the database exactly <workdir>/e2e.db, and the running backend using that very file.
// Store names inside a database are never used as proof of safety.
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const MARKER = '.clixsell-e2e-disposable';
const PROJECT = fs.realpathSync(path.join(__dirname, '..', '..'));

function stop(reason) {
  console.error('E2E SAFETY STOP: ' + reason);
  console.error('Run the scripts only through tools/e2e/run.sh (see tools/e2e/README.md).');
  process.exit(2);
}

function commandLine(pid) {
  try { return fs.readFileSync(`/proc/${pid}/cmdline`).toString().replace(/\0/g, ' '); } catch (e) { /* not Linux */ }
  try { return execFileSync('ps', ['-o', 'command=', '-p', String(pid)]).toString(); } catch (e) { return ''; }
}

/** Returns { db, work, out } for the disposable run, or stops the script. */
function requireDisposable() {
  if (process.env.CLIXSELL_E2E !== '1') stop('CLIXSELL_E2E=1 is not set.');
  let work = process.env.CLIXSELL_E2E_WORKDIR || '';
  const token = process.env.CLIXSELL_E2E_TOKEN || '';
  if (!work || !path.isAbsolute(work) || !fs.existsSync(work) || !fs.statSync(work).isDirectory()) {
    stop('CLIXSELL_E2E_WORKDIR is not an existing absolute folder.');
  }
  work = fs.realpathSync(work);
  if (!path.basename(work).startsWith('clixsell-e2e.')) stop('the work folder was not created by run.sh: ' + work);
  if (work === PROJECT || work.startsWith(PROJECT + path.sep)) stop('the work folder is inside the project: ' + work);
  if (token.length < 16) stop('CLIXSELL_E2E_TOKEN is missing.');
  let marker = '';
  try { marker = fs.readFileSync(path.join(work, MARKER)).toString().trim(); } catch (e) { stop('the marker file is missing in ' + work); }
  if (marker !== token) stop('the marker file does not match this run.');
  const dbEnv = process.env.CLIXSELL_E2E_DB || '';
  const db = fs.existsSync(dbEnv) ? fs.realpathSync(dbEnv) : dbEnv;
  if (db !== path.join(work, 'e2e.db')) stop('CLIXSELL_E2E_DB must be <workdir>/e2e.db, not ' + db);
  const pid = process.env.CLIXSELL_E2E_BACKEND_PID || '';
  if (!/^\d+$/.test(pid) || !commandLine(pid).includes('jdbc:sqlite:' + db)) {
    stop('the backend on localhost:8080 is not the disposable one started by run.sh.');
  }
  const out = process.env.CLIXSELL_E2E_OUT || path.join(work, 'out');
  fs.mkdirSync(out, { recursive: true });
  return { db, work, out };
}

module.exports = { requireDisposable };

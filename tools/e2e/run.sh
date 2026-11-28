#!/usr/bin/env bash
# ClixSell end-to-end checks on DISPOSABLE demo databases only.
#
#   CLIXSELL_E2E=1 tools/e2e/run.sh <backend.jar> <flutter-web-build-dir> [suite ...]
#   suites: api settings reports guardian full pos supplier purchase communication purchase-ui import-export shift store-ops   (default: all, in this order)
#
# For every suite: a new folder from mktemp (outside the project) with a marker file and a
# random token, a new backend started there with the demo profile on <folder>/e2e.db, the
# suite, then the backend is stopped and ONLY that folder is removed. Logs and screenshots go
# to tools/e2e/out/<suite>/ (never a database). The scripts refuse to run without the
# variables set here (safety.py / safety.js). Never point anything at backend/clixsell.db.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
PROJECT=$(cd "$HERE/../.." && pwd)

if [ "${CLIXSELL_E2E:-}" != "1" ]; then
  echo "Refusing to run: set CLIXSELL_E2E=1 to confirm this is a test environment." >&2; exit 2
fi
if [ $# -lt 2 ]; then
  echo "Usage: CLIXSELL_E2E=1 $0 <backend.jar> <flutter-web-build-dir> [api settings reports guardian full pos supplier purchase communication purchase-ui import-export shift store-ops]" >&2; exit 2
fi
JAR=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
WEB=$(cd "$2" && pwd)
shift 2
SUITES=("$@"); [ ${#SUITES[@]} -eq 0 ] && SUITES=(api settings reports guardian full pos supplier purchase communication purchase-ui import-export shift store-ops)
[ -f "$JAR" ] || { echo "No such jar: $JAR" >&2; exit 2; }
[ -f "$WEB/index.html" ] || { echo "Not a Flutter web build: $WEB" >&2; exit 2; }

# P4-D8: browser suites only run against a web build made from exactly the current sources
# (fingerprint of the file contents, written by build_web.sh; not file times).
UI_SUITES=" reports guardian full pos supplier purchase-ui import-export "
needs_ui=0
for s_ in "${SUITES[@]:-}"; do case "$UI_SUITES" in *" $s_ "*) needs_ui=1;; esac; done
if [ ${#SUITES[@]} -eq 0 ] || [ $needs_ui -eq 1 ]; then
  want="$("$HERE/web_stamp.sh")"
  have="$(sed -n 's/^stamp=//p' "$WEB/.clixsell_web_stamp" 2>/dev/null || true)"
  if [ "$want" != "$have" ]; then
    echo "Refusing to run browser suites: $WEB was not built from the current sources" >&2
    echo "  (expected stamp $want, found '${have:-none}'). Run tools/e2e/build_web.sh first." >&2
    exit 2
  fi
  echo "Web build matches the current sources (stamp $want, $(sed -n 's/^commit=//p' "$WEB/.clixsell_web_stamp"))"
fi

port_busy() { python3 -c "import socket,sys; s=socket.socket(); s.settimeout(1); sys.exit(0 if s.connect_ex(('127.0.0.1', $1)) == 0 else 1)"; }
for port in 8080 8090; do
  if port_busy $port; then
    echo "Refusing to run: port $port is already in use (it may be a REAL ClixSell backend). Stop it first." >&2; exit 2
  fi
done

export NODE_PATH="${NODE_PATH:-$(npm root -g 2>/dev/null || true)}"
OUT_ROOT="${CLIXSELL_E2E_OUT_ROOT:-$HERE/out}"
BACKEND_PID=""; WEB_PID=""; WORK=""; TOKEN=""
stop_backend() {
  if [ -n "$BACKEND_PID" ]; then kill "$BACKEND_PID" 2>/dev/null || true; wait "$BACKEND_PID" 2>/dev/null || true; BACKEND_PID=""; fi
}
remove_work() {
  # Only the folder this run created, and only if its marker still holds this run's token.
  if [ -n "$WORK" ] && [ -f "$WORK/.clixsell-e2e-disposable" ] && [ "$(cat "$WORK/.clixsell-e2e-disposable")" = "$TOKEN" ] \
     && [ "${CLIXSELL_E2E_KEEP_WORK:-}" != "1" ]; then
    rm -rf -- "$WORK"
  fi
  WORK=""
}
cleanup() { stop_backend; remove_work; [ -n "$WEB_PID" ] && kill "$WEB_PID" 2>/dev/null || true; }
trap cleanup EXIT INT TERM

python3 -m http.server 8090 --bind 127.0.0.1 --directory "$WEB" >/dev/null 2>&1 &
WEB_PID=$!

declare -A RESULT
for suite in "${SUITES[@]}"; do
  WORK=$(mktemp -d "${TMPDIR:-/tmp}/clixsell-e2e.XXXXXXXX")
  case "$WORK" in "$PROJECT"/*) echo "Refusing: work folder inside the project" >&2; exit 2;; esac
  TOKEN=$(python3 -c 'import secrets; print(secrets.token_hex(16))')
  printf '%s' "$TOKEN" > "$WORK/.clixsell-e2e-disposable"
  DB="$WORK/e2e.db"
  OUT="$OUT_ROOT/$suite"; mkdir -p "$OUT"
  (cd "$WORK" && exec java -jar "$JAR" --spring.profiles.active=demo \
      "--spring.datasource.url=jdbc:sqlite:$DB?foreign_keys=on" "--clixsell.db.create-if-missing=true" "--clixsell.backup.dir=$WORK/backups" \
      > "$OUT/backend.log" 2>&1) &
  BACKEND_PID=$!
  ready=0
  for _ in $(seq 1 150); do
    kill -0 "$BACKEND_PID" 2>/dev/null || break
    # Ready = the demo data AND the System Admin exist (two separate start-up tasks whose order
    # is not fixed; a script that logs in as sysadmin too early would otherwise fail).
    code=$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
      -d '{"username":"neshan.cashier","password":"Cashier-Pass-1"}' http://127.0.0.1:8080/api/auth/login || true)
    sys=$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
      -d '{"username":"sysadmin","password":"Admin12345"}' http://127.0.0.1:8080/api/auth/login || true)
    [ "$code" = "200" ] && [ "$sys" = "200" ] && { ready=1; break; }
    sleep 1
  done
  if [ $ready -ne 1 ]; then echo "[$suite] backend did not start (see $OUT/backend.log)"; RESULT[$suite]=ERROR; stop_backend; remove_work; continue; fi
  export CLIXSELL_E2E=1 CLIXSELL_E2E_WORKDIR="$WORK" CLIXSELL_E2E_TOKEN="$TOKEN" CLIXSELL_E2E_DB="$DB" \
         CLIXSELL_E2E_BACKEND_PID="$BACKEND_PID" CLIXSELL_E2E_OUT="$OUT"
  set +e
  case "$suite" in
    api)      python3 "$HERE/api_check.py" "$DB" ;;
    settings) python3 "$HERE/settings_check.py" "$DB" ;;
    reports)  python3 "$HERE/reports_boundary_check.py" "$DB" && node "$HERE/reports_ui.js" ;;
    guardian) python3 "$HERE/guardian_scenario.py" "$DB" && node "$HERE/guardian_ui.js" ;;
    full)     node "$HERE/full_e2e.js" ;;
    pos)      node "$HERE/real_pos.js" ;;
    supplier) python3 "$HERE/supplier_check.py" "$DB" && node "$HERE/supplier_ui.js" ;;
    purchase) python3 "$HERE/purchase_check.py" "$DB" ;;
    communication) python3 "$HERE/communication_check.py" "$DB" ;;
    shift)    python3 "$HERE/shift_check.py" "$DB" ;;
    store-ops) python3 "$HERE/store_ops_check.py" "$DB" ;;
    purchase-ui) node "$HERE/purchase_ui.js" ;;
    import-export) node "$HERE/import_export_ui.js" ;;
    *)        echo "Unknown suite: $suite"; false ;;
  esac > "$OUT/result.log" 2>&1
  status=$?
  set -e
  grep -E 'PASS [0-9]+ +FAIL|^FAILED|FAILED:|SAFETY STOP|Traceback|Error' "$OUT/result.log" | head -12 | sed "s/^/[$suite] /" || true
  RESULT[$suite]=$([ $status -eq 0 ] && grep -qE 'FAIL 0' "$OUT/result.log" && ! grep -qE 'FAIL [1-9]' "$OUT/result.log" && echo PASS || echo FAIL)
  stop_backend; remove_work
done

echo; echo "Summary (logs in $OUT_ROOT):"
overall=0
for suite in "${SUITES[@]}"; do echo "  $suite: ${RESULT[$suite]}"; [ "${RESULT[$suite]}" = PASS ] || overall=1; done
exit $overall

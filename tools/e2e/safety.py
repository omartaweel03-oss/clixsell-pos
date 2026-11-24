"""Safety gate for the ClixSell end-to-end scripts (Python).

Every script calls require_disposable_db() BEFORE it opens a database or calls the API.
It continues only when ALL of these hold; otherwise it stops with exit code 2:

  1. CLIXSELL_E2E=1 is set (an explicit test-environment flag).
  2. CLIXSELL_E2E_WORKDIR is an existing folder created by run.sh: its name starts with
     "clixsell-e2e.", it is NOT inside this project, and it holds the marker file
     ".clixsell-e2e-disposable" whose content equals CLIXSELL_E2E_TOKEN (random, per run).
  3. CLIXSELL_E2E_DB is exactly <workdir>/e2e.db (never clixsell.db, never a user database);
     a path given on the command line must be that same file.
  4. CLIXSELL_E2E_BACKEND_PID is a running process whose command line uses that exact
     database, so the API on localhost:8080 is the disposable backend started by run.sh.

Store names inside a database are never used as proof of safety.
"""
import os
import subprocess
import sys

MARKER = '.clixsell-e2e-disposable'
PROJECT = os.path.realpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))


def _stop(reason):
    print('E2E SAFETY STOP: ' + reason, file=sys.stderr)
    print('Run the scripts only through tools/e2e/run.sh (see tools/e2e/README.md).', file=sys.stderr)
    sys.exit(2)


def _command_line(pid):
    try:
        with open(f'/proc/{pid}/cmdline', 'rb') as f:
            return f.read().replace(b'\0', b' ').decode(errors='replace')
    except OSError:
        try:
            return subprocess.run(['ps', '-o', 'command=', '-p', str(pid)], capture_output=True, text=True,
                                  check=True).stdout
        except (OSError, subprocess.CalledProcessError):
            return ''


def require_disposable_db(path_argument=None):
    """Returns the absolute path of the disposable test database, or stops the script."""
    if os.environ.get('CLIXSELL_E2E') != '1':
        _stop('CLIXSELL_E2E=1 is not set.')
    work = os.environ.get('CLIXSELL_E2E_WORKDIR', '')
    token = os.environ.get('CLIXSELL_E2E_TOKEN', '')
    if not work or not os.path.isabs(work) or not os.path.isdir(work):
        _stop('CLIXSELL_E2E_WORKDIR is not an existing absolute folder.')
    work = os.path.realpath(work)
    if not os.path.basename(work).startswith('clixsell-e2e.'):
        _stop('the work folder was not created by run.sh: ' + work)
    if work == PROJECT or work.startswith(PROJECT + os.sep):
        _stop('the work folder is inside the project (next to real data?): ' + work)
    if len(token) < 16:
        _stop('CLIXSELL_E2E_TOKEN is missing.')
    try:
        with open(os.path.join(work, MARKER)) as f:
            if f.read().strip() != token:
                _stop('the marker file does not match this run.')
    except OSError:
        _stop('the marker file is missing in ' + work)
    db = os.path.realpath(os.environ.get('CLIXSELL_E2E_DB', ''))
    if db != os.path.join(work, 'e2e.db'):
        _stop('CLIXSELL_E2E_DB must be <workdir>/e2e.db, not ' + db)
    if path_argument is not None and os.path.realpath(path_argument) != db:
        _stop('the database given on the command line is not the disposable one: ' + path_argument)
    pid = os.environ.get('CLIXSELL_E2E_BACKEND_PID', '')
    if not pid.isdigit() or ('jdbc:sqlite:' + db) not in _command_line(pid):
        _stop('the backend on localhost:8080 is not the disposable one started by run.sh.')
    return db

"""Tries ONE write on the disposable test database to prove a database rule, and ALWAYS rolls it back.
Usage: python3 dbtry.py "<SQL>"  ->  prints "REFUSED: <message>" or "ACCEPTED" (rolled back either way).
The database comes from the safety gate (CLIXSELL_E2E=1, run.sh work folder): never any real database."""
import sqlite3
import sys

from safety import require_disposable_db

db = require_disposable_db()
c = sqlite3.connect(db, timeout=30, isolation_level=None)
c.execute('PRAGMA foreign_keys = ON')
c.execute('BEGIN IMMEDIATE')
try:
    c.execute(sys.argv[1])
    c.execute('PRAGMA defer_foreign_keys = OFF')  # deferred keys are checked now, not at a commit that never comes
    bad = c.execute('PRAGMA foreign_key_check').fetchall()
    print('REFUSED: FOREIGN KEY ' + repr(bad[:2]) if bad else 'ACCEPTED')
except sqlite3.Error as e:
    print('REFUSED: ' + str(e))
finally:
    c.execute('ROLLBACK')

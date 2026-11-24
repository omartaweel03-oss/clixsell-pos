"""Read-only query of the disposable test database (used by the browser scripts).
Usage: python3 dbq.py [--list | --json] "<SQL>"  – the database comes from the safety gate, opened read-only.
Default: rows joined with " | "; --list: a Python list; --json: a JSON list of rows."""
import json
import sqlite3
import sys

from safety import require_disposable_db

db = require_disposable_db()
args = sys.argv[1:]
mode = args[0] if args and args[0] in ('--list', '--json') else ''
sql = args[1] if mode else args[0]
rows = sqlite3.connect(f'file:{db}?mode=ro', uri=True).execute(sql).fetchall()
if mode == '--json':
    print(json.dumps(rows))
elif mode == '--list':
    print(rows)
else:
    print(' | '.join(str(r) for r in rows))

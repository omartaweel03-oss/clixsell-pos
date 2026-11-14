import sqlite3,sys
c=sqlite3.connect(sys.argv[1]); print(' | '.join(str(r) for r in c.execute(sys.argv[2]).fetchall()))

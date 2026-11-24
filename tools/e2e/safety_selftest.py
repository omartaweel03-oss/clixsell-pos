"""Self-test of the safety gate: every script must STOP (exit code 2) before touching anything when
the environment is not a disposable run made by run.sh. Uses a stand-in "user database" in a
temporary folder and checks that its bytes never change. Needs no backend; safe to run anywhere:
    python3 tools/e2e/safety_selftest.py
"""
import hashlib
import os
import secrets
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = [['python3', 'api_check.py'], ['python3', 'settings_check.py'], ['python3', 'guardian_scenario.py'],
           ['python3', 'reports_boundary_check.py'], ['python3', 'dbq.py', 'select 1'],
           ['node', 'guardian_ui.js'], ['node', 'full_e2e.js'], ['node', 'reports_ui.js'], ['node', 'real_pos.js'],
           ['python3', 'supplier_check.py'], ['node', 'supplier_ui.js'], ['python3', 'purchase_check.py']]
passed = failed = 0


def check(name, cond):
    global passed, failed
    print(('PASS ' if cond else 'FAIL ') + name)
    passed, failed = passed + cond, failed + (not cond)


with tempfile.TemporaryDirectory() as tmp:
    user_db = os.path.join(tmp, 'clixsell.db')                  # stands for a real store database
    with open(user_db, 'wb') as f:
        f.write(b'REAL STORE DATABASE ' + secrets.token_bytes(32))
    digest = hashlib.sha256(open(user_db, 'rb').read()).hexdigest()
    good_work = tempfile.mkdtemp(prefix='clixsell-e2e.', dir=tmp)
    token = secrets.token_hex(16)
    open(os.path.join(good_work, '.clixsell-e2e-disposable'), 'w').write(token)
    base = {'PATH': os.environ['PATH'], 'NODE_PATH': os.environ.get('NODE_PATH', '')}
    ok_env = dict(base, CLIXSELL_E2E='1', CLIXSELL_E2E_WORKDIR=good_work, CLIXSELL_E2E_TOKEN=token,
                  CLIXSELL_E2E_DB=os.path.join(good_work, 'e2e.db'), CLIXSELL_E2E_BACKEND_PID=str(os.getpid()))
    project_backend = os.path.realpath(os.path.join(HERE, '..', '..', 'backend'))
    cases = {
        'no CLIXSELL_E2E flag': dict(base),
        'flag but no work folder': dict(base, CLIXSELL_E2E='1'),
        'database = a user clixsell.db': dict(ok_env, CLIXSELL_E2E_DB=user_db),
        'work folder = the project backend folder': dict(ok_env, CLIXSELL_E2E_WORKDIR=project_backend,
                                                         CLIXSELL_E2E_DB=os.path.join(project_backend, 'e2e.db')),
        'wrong token (marker of another run)': dict(ok_env, CLIXSELL_E2E_TOKEN=secrets.token_hex(16)),
        'backend process is not the disposable one': ok_env,     # this test's own PID does not use e2e.db
    }
    for label, env in cases.items():
        for cmd in SCRIPTS:
            args = cmd + ([user_db] if cmd[0] == 'python3' and cmd[1] != 'dbq.py' else [])
            r = subprocess.run(args, cwd=HERE, env=env, capture_output=True, text=True, timeout=60)
            check(f'{label}: {cmd[1]} stops (exit {r.returncode})', r.returncode == 2 and 'E2E SAFETY STOP' in r.stderr)
    check('the stand-in user database is unchanged', hashlib.sha256(open(user_db, 'rb').read()).hexdigest() == digest)
    check('no e2e.db was created anywhere', not os.path.exists(os.path.join(good_work, 'e2e.db'))
          and not os.path.exists(os.path.join(project_backend, 'e2e.db')))

print(f'\nSAFETY SELFTEST PASS {passed} FAIL {failed}')
sys.exit(1 if failed else 0)

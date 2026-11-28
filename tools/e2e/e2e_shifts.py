"""Approved cash refund policy: a cash refund is paid from an open drawer that holds the cash. Suites
that refund cash first open a shift through the real API — as a real cashier must — with a counted
opening cash. Used by the E2E suites only (disposable databases, through run.sh)."""
import json, urllib.request

A = 'http://localhost:8080/api'


def _call(path, token, body):
    r = urllib.request.Request(A + path, method='POST', data=json.dumps(body).encode(),
                               headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token})
    with urllib.request.urlopen(r) as x:
        return json.loads(x.read())


def open_shift(admin_token, user_token, drawer_name, opening=1000):
    """A new drawer (Store Admin) and an open shift for the user on it; returns the shift id."""
    d = _call('/drawers', admin_token, {'name': drawer_name})
    return _call('/shifts', user_token, {'drawerId': d['drawerId'], 'openingCash': opening})['shiftId']

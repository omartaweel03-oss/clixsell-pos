"""Cash drawers and shifts (store upgrade features 1-2, D2, D3) through the real HTTP API on a disposable
demo database, with every amount checked against the database rows. Run via run.sh."""
from safety import require_disposable_db  # stops unless this is the disposable test database
import json, sqlite3, sys, urllib.request, uuid
A = 'http://localhost:8080/api'; DB = require_disposable_db(sys.argv[1]); db = sqlite3.connect(DB); db.isolation_level = None
R = {'pass': 0, 'fail': 0}


def call(p, t=None, m='GET', b=None):
    r = urllib.request.Request(A + p, method=m, data=json.dumps(b).encode() if b is not None else None,
                               headers={'Content-Type': 'application/json', **({'Authorization': 'Bearer ' + t} if t else {})})
    try:
        x = urllib.request.urlopen(r); body = x.read(); return x.status, (json.loads(body) if body else None)
    except urllib.error.HTTPError as e:
        body = e.read()
        try: return e.code, (json.loads(body) if body else None)
        except ValueError: return e.code, None


def chk(name, a, b):
    ok = a == b; R['pass' if ok else 'fail'] += 1
    print(('PASS ' if ok else 'FAIL ') + name + ('' if ok else f'  got {a!r} expected {b!r}'))


one = lambda s, *a: db.execute(s, a).fetchone()[0]
T = {}
for u, p in [('shini.admin', 'Admin-Pass-1'), ('neshan.admin', 'Admin-Pass-1'), ('shini.cashier', 'Cashier-Pass-1'),
             ('neshan.cashier', 'Cashier-Pass-1')]:
    T[u] = call('/auth/login', m='POST', b={'username': u, 'password': p})[1]['token']
shini = one("select store_id from stores where name like 'Shini%'")
water = one("select product_id from products where store_id=? and name like 'Mineral Water%'", shini)
price = one('select selling_price from products where product_id=?', water)
tax = one('select tax_rate from stores where store_id=?', shini)
unit_total = round(price * (1 + tax / 100) + 1e-9, 2)


def sell(t, method='CASH', qty=1, extra=None):
    return call('/sales', t, 'POST', {'items': [{'productId': water, 'quantity': qty}], 'paymentMethod': method, **(extra or {})})


def ret(t, sale):
    item = one('select sale_item_id from sale_items where sale_id=?', sale)
    return call('/returns', t, 'POST', {'saleId': sale, 'saleItemId': item, 'quantity': 1, 'reason': 'Damaged'})


print('--- 1. Optional by default; drawers are Store Admin only')
s, b = call('/shifts/current', T['shini.cashier'])
chk('GET /shifts/current: no shift, not required', (s, b['shift'], b['requireCashShift']), (200, None, False))
chk('cashier cannot add a drawer (403)', call('/drawers', T['shini.cashier'], 'POST', {'name': 'Mine'})[0], 403)
s, d = call('/drawers', T['shini.admin'], 'POST', {'name': 'E2E Till'})
chk('Store Admin adds a drawer (201)', s, 201); drawer = d['drawerId']
s, b = sell(T['shini.cashier'])
chk('sale without a shift still works (shifts optional)', s, 201)
chk('DB: that sale has no shift', one('select shift_id from sales where sale_id=?', b['saleId']), None)

print('--- 2. Mandatory shifts: refused before anything changes')
chk('Store Admin requires shifts', call('/store/settings', T['shini.admin'], 'PUT', {'requireCashShift': True})[1]['requireCashShift'], True)
before = (one('select count(*) from sales'), one('select count(*) from payments'), one('select quantity from products where product_id=?', water))
s, b = sell(T['shini.cashier'])
chk('sale without a shift -> 409 SHIFT_REQUIRED', (s, b['code']), (409, 'SHIFT_REQUIRED'))
chk('DB: no sale, no payment, same stock', (one('select count(*) from sales'), one('select count(*) from payments'),
                                           one('select quantity from products where product_id=?', water)), before)
chk('Neshan (other store) still sells without a shift',
    call('/sales', T['neshan.cashier'], 'POST', {'items': [{'productId': one("select product_id from products where store_id<>? limit 1", shini),
                                                             'quantity': 1}], 'paymentMethod': 'CASH'})[0], 201)

print('--- 3. Scenario A: a normal cash shift')
s, sh = call('/shifts', T['shini.cashier'], 'POST', {'drawerId': drawer, 'openingCash': 100})
chk('cashier opens a shift with 100.00 (201)', (s, sh['openingCash'], sh['status']), (201, 100.0, 'OPEN')); shift = sh['shiftId']
chk('a second shift for the same drawer is refused', call('/shifts', T['shini.admin'], 'POST', {'drawerId': drawer, 'openingCash': 0})[1]['code'], 'DRAWER_IN_USE')
s, b = sell(T['shini.cashier'], extra={'cashReceived': 50})
chk('cash sale with 50 received -> change', (s, b['changeDue']), (201, round(50 - unit_total, 2)))
s, b2 = sell(T['shini.cashier']); cash2 = b2['saleId']
chk('card sale', sell(T['shini.cashier'], 'CARD')[0], 201)
s, r = ret(T['shini.cashier'], cash2)
chk('cash refund of one unit', (s, r['refundAmount']), (201, unit_total))
chk('cashier cannot record cash out (403)',
    call(f'/shifts/{shift}/movements', T['shini.cashier'], 'POST', {'type': 'CASH_OUT', 'amount': 5, 'reason': 'x x', 'requestKey': 'e2e-' + uuid.uuid4().hex})[0], 403)
key = 'e2e-' + uuid.uuid4().hex
chk('Store Admin cash out 30.50', call(f'/shifts/{shift}/movements', T['shini.admin'], 'POST',
                                        {'type': 'CASH_OUT', 'amount': 30.5, 'reason': 'Bank deposit', 'requestKey': key})[0], 201)
call(f'/shifts/{shift}/movements', T['shini.admin'], 'POST', {'type': 'CASH_OUT', 'amount': 30.5, 'reason': 'Bank deposit', 'requestKey': key})
chk('the same request again records nothing new', one('select count(*) from cash_movements where shift_id=?', shift), 1)
expected = round(100 + 2 * unit_total - unit_total - 30.5, 2)
s, cur = call('/shifts/current', T['shini.cashier'])
chk('expected cash = 100 + cash sales − cash refund − cash out', cur['shift']['expectedCash'], expected)
db_expected = (10000 + one("select coalesce(sum(cast(round(p.amount*100) as integer)),0) from payments p join sales s on s.sale_id=p.sale_id "
                           "where s.shift_id=? and p.payment_method='CASH'", shift)
               - one("select coalesce(sum(refund_minor),0) from returns where shift_id=? and refund_method='CASH'", shift)
               - one("select coalesce(sum(amount_minor),0) from cash_movements where shift_id=?", shift)) / 100
chk('the same amount recomputed from the database rows', db_expected, expected)
s, b = call(f'/shifts/{shift}/close', T['shini.cashier'], 'POST', {'countedCash': expected - 1})
chk('a 1.00 difference without a note -> 400', (s, 'note' in b['fieldErrors']), (400, True))
s, closed = call(f'/shifts/{shift}/close', T['shini.cashier'], 'POST', {'countedCash': round(expected - 1, 2), 'note': '1.00 missing'})
chk('closed with the note: difference −1.00', (s, closed['status'], closed['difference']), (200, 'CLOSED', -1.0))
chk('DB: closed record', db.execute('select status, expected_cash_minor, counted_cash_minor, difference_minor, close_kind from cash_shifts where shift_id=?',
                                    (shift,)).fetchone(), ('CLOSED', round(expected * 100), round(expected * 100) - 100, -100, 'CASHIER'))
chk('closing again -> 409 SHIFT_CLOSED', call(f'/shifts/{shift}/close', T['shini.cashier'], 'POST', {'countedCash': 0})[1]['code'], 'SHIFT_CLOSED')

print('--- 4. A closed shift stays as it was')
frozen = db.execute('select * from cash_shifts where shift_id=?', (shift,)).fetchone()
try:
    db.execute('delete from payments where sale_id=?', (cash2,)); chk('direct SQL cannot delete its payment', 'deleted', 'refused')
except sqlite3.IntegrityError as e:
    chk('direct SQL cannot delete its payment', 'SHIFT_CLOSED_RECORD' in str(e), True)
try:
    db.execute("update sales set total = total + 1 where sale_id=?", (cash2,)); chk('direct SQL cannot change its sale', 'changed', 'refused')
except sqlite3.IntegrityError as e:
    chk('direct SQL cannot change its sale', 'SHIFT_CLOSED_RECORD' in str(e), True)
s, sh2 = call('/shifts', T['shini.cashier'], 'POST', {'drawerId': drawer, 'openingCash': 0})
first = one('select min(sale_id) from sales where shift_id=?', shift)
s, r = ret(T['shini.cashier'], first)
chk('refund of an old sale after closing -> 201, into the NEW shift', (s, one('select shift_id from returns where sale_id=? order by return_id desc limit 1', first)), (201, sh2['shiftId']))
chk('the closed shift record did not change', db.execute('select * from cash_shifts where shift_id=?', (shift,)).fetchone(), frozen)

print('--- 5. Store isolation and the Store Admin exception')
chk('another store cannot see the shift (404)', call(f'/shifts/{shift}', T['neshan.admin'])[0], 404)
chk('another store cannot close it (404)', call(f'/shifts/{sh2["shiftId"]}/admin-close', T['neshan.admin'], 'POST', {'countedCash': 0, 'reason': 'Not mine'})[0], 404)
chk('admin close without a reason -> 400', call(f'/shifts/{sh2["shiftId"]}/admin-close', T['shini.admin'], 'POST', {'countedCash': 0})[0], 400)
cur = call('/shifts/current', T['shini.cashier'])[1]['shift']
s, b = call(f'/shifts/{sh2["shiftId"]}/admin-close', T['shini.admin'], 'POST',
            {'countedCash': max(cur['expectedCash'], 0), 'reason': 'End of day, cashier left'})
chk('Store Admin exceptional close -> ADMIN_EXCEPTION', (s, b['closeKind']), (200, 'ADMIN_EXCEPTION'))
lst = call('/shifts', T['shini.admin'])[1]
chk('Store Admin list shows both shifts', sorted(x['shiftId'] for x in lst if x['drawerId'] == drawer), sorted([shift, sh2['shiftId']]))
chk('Neshan list does not show them', [x for x in call('/shifts', T['neshan.admin'])[1] if x['drawerId'] == drawer], [])
call('/store/settings', T['shini.admin'], 'PUT', {'requireCashShift': False})
chk('integrity_check', one('pragma integrity_check'), 'ok')
chk('no broken references', db.execute('pragma foreign_key_check').fetchall(), [])
print(f"\nSHIFT CHECK PASS {R['pass']} FAIL {R['fail']}")
sys.exit(1 if R['fail'] else 0)

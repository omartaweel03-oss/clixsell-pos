"""Store operations scenarios B-E (carton, favorites, cash rounding, cheque) through the real HTTP API on a
disposable demo database, every amount checked against the database rows. Run via run.sh."""
from safety import require_disposable_db  # stops unless this is the disposable test database
import datetime as dt, json, sqlite3, sys, urllib.request, uuid
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
T = {u: call('/auth/login', m='POST', b={'username': u, 'password': p})[1]['token']
     for u, p in [('shini.admin', 'Admin-Pass-1'), ('shini.cashier', 'Cashier-Pass-1'), ('neshan.admin', 'Admin-Pass-1')]}
AD, CA = T['shini.admin'], T['shini.cashier']
shini = one("select store_id from stores where name like 'Shini%'")
chk('tax 0 for exact amounts', call('/store/settings', AD, 'PUT', {'taxRate': 0})[0], 200)
s, p = call('/products', AD, 'POST', {'name': 'E2E Water 0.5L', 'barcode': '7290099000013', 'category': 'beverages',
                                      'sellingPrice': 2.0, 'quantity': 100, 'minStock': 5, 'supplierName': 'E2E Supplier'})
chk('product created', s, 201); water = p['productId']

print('--- B. Carton of 24 (stock in pieces)')
s, c = call(f'/products/{water}/packages', AD, 'POST', {'name': 'Carton 24', 'unitsPerPackage': 24, 'price': 40, 'barcode': '7290099000020'})
chk('Store Admin adds a carton (201)', s, 201); carton = c['packageId']
chk('cashier sees it', [x['name'] for x in call('/packages', CA)[1] if x['productId'] == water], ['Carton 24'])
s, sale = call('/sales', CA, 'POST', {'items': [{'productId': water, 'quantity': 2, 'packageId': carton},
                                               {'productId': water, 'quantity': 3}], 'paymentMethod': 'CARD'})
chk('2 cartons + 3 pieces = 86.00', (s, sale['total']), (201, 86.0))
chk('stock 100 − 48 − 3 = 49 pieces', one('select quantity from products where product_id=?', water), 49)
chk('line snapshot', db.execute('select quantity, units_per_package, package_name, unit_price from sale_items where sale_id=? and package_id is not null',
                                (sale['saleId'],)).fetchone(), (2, 24, 'Carton 24', 40.0))
line = one('select sale_item_id from sale_items where sale_id=? and package_id is not null', sale['saleId'])
s, r = call('/returns', CA, 'POST', {'saleId': sale['saleId'], 'saleItemId': line, 'quantity': 1, 'reason': 'Damaged carton'})
chk('return of one whole carton refunds 40.00', (s, r['refundAmount']), (201, 40.0))
chk('24 pieces back in stock', one('select quantity from products where product_id=?', water), 73)
day = dt.date.today().isoformat()
summ = call(f'/reports/summary?from={day}&to={day}', AD)[1]
chk('report counts pieces', (summ['unitsSold'] >= 51, summ['unitsReturned'] >= 24), (True, True))

print('--- C. Favorites in order')
others = [x['productId'] for x in call('/products', AD)[1] if x['productId'] != water][:2]
chk('Store Admin saves 3 favorites', call('/favorites', AD, 'PUT', {'productIds': [water] + others})[0], 200)
chk('cashier gets them in that order', [f['productId'] for f in call('/favorites', CA)[1]], [water] + others)
chk('cashier cannot change them', call('/favorites', CA, 'PUT', {'productIds': []})[0], 403)
chk('reorder', [f['productId'] for f in call('/favorites', AD, 'PUT', {'productIds': others[::-1] + [water]})[1]], others[::-1] + [water])
chk('Neshan has its own (none)', call('/favorites', T['neshan.admin'])[1], [])

print('--- D. Cash rounding 0.10: 19.97 → 20.00, 50 tendered, 30.00 change')
s, oil = call('/products', AD, 'POST', {'name': 'E2E Olive Oil', 'barcode': '7290099000037', 'category': 'groceries',
                                        'sellingPrice': 19.97, 'quantity': 20, 'minStock': 1, 'supplierName': 'E2E Supplier'})
oil = oil['productId']
chk('step 0.10', call('/store/settings', AD, 'PUT', {'cashRoundingMinor': 10})[1]['cashRoundingMinor'], 10)
s, sale = call('/sales', CA, 'POST', {'items': [{'productId': oil, 'quantity': 1}], 'paymentMethod': 'CASH', 'cashReceived': 50})
chk('total 19.97, due 20.00, change 30.00', (s, sale['total'], sale['amountDue'], sale['changeDue']), (201, 19.97, 20.0, 30.0))
chk('DB: total unchanged, rounding 3, payment 20.00',
    (one('select total from sales where sale_id=?', sale['saleId']), one('select rounding_minor from sales where sale_id=?', sale['saleId']),
     one('select amount from payments where sale_id=?', sale['saleId'])), (19.97, 3, 20.0))
s, card = call('/sales', CA, 'POST', {'items': [{'productId': oil, 'quantity': 1}], 'paymentMethod': 'CARD'})
chk('card is not rounded', one('select amount from payments where sale_id=?', card['saleId']), 19.97)
call('/store/settings', AD, 'PUT', {'cashRoundingMinor': 0})

print('--- E. Cheque: checkout, lifecycle, return, payout after clearing')
due = (dt.date.today() + dt.timedelta(days=5)).isoformat()
num = 'E2E' + uuid.uuid4().hex[:8]
s, b = call('/sales', CA, 'POST', {'items': [{'productId': oil, 'quantity': 2}], 'paymentMethod': 'CHEQUE'})
chk('cheque sale without details refused (400)', s, 400)
s, chs = call('/sales', CA, 'POST', {'items': [{'productId': oil, 'quantity': 2}], 'paymentMethod': 'CHEQUE',
                                     'cheque': {'number': num, 'bank': 'Bank of Palestine', 'dueDate': due, 'amount': 39.94}})
chk('cheque sale 39.94 (201)', (s, chs['total']), (201, 39.94))
cid = one('select cheque_id from cheques where sale_id=?', chs['saleId'])
chk('DB: cheque RECEIVED for the full amount', db.execute('select status, amount_minor from cheques where cheque_id=?', (cid,)).fetchone(), ('RECEIVED', 3994))
chk('the same cheque again → 409', call('/sales', CA, 'POST', {'items': [{'productId': oil, 'quantity': 1}], 'paymentMethod': 'CHEQUE',
    'cheque': {'number': num, 'bank': 'Bank of Palestine', 'dueDate': due}})[1]['code'], 'CHEQUE_DUPLICATE')
chk('cashier cannot manage cheques (403)', call('/cheques', CA)[0], 403)
chk('RECEIVED → DEPOSITED', call(f'/cheques/{cid}/status', AD, 'POST', {'toStatus': 'DEPOSITED'})[1]['cheque']['status'], 'DEPOSITED')
item = one('select sale_item_id from sale_items where sale_id=?', chs['saleId'])
chk('cashier cannot return a cheque sale', call('/returns', CA, 'POST', {'saleId': chs['saleId'], 'saleItemId': item, 'quantity': 1,
                                                                        'reason': 'Faulty'})[1]['code'], 'CHEQUE_RETURN_NEEDS_STORE_ADMIN')
s, r = call('/returns', AD, 'POST', {'saleId': chs['saleId'], 'saleItemId': item, 'quantity': 1, 'reason': 'Faulty'})
chk('Store Admin return → owed, PENDING (no cash)', (s, one("select status from cheque_refunds where cheque_id=?", cid)), (201, 'PENDING'))
rid = one('select cheque_refund_id from cheque_refunds where cheque_id=?', cid)
chk('pay before clearing → 409', call(f'/cheque-refunds/{rid}/pay', AD, 'POST', {'method': 'BANK_TRANSFER'})[1]['code'], 'CHEQUE_NOT_CLEARED')
chk('DEPOSITED → CLEARED', call(f'/cheques/{cid}/status', AD, 'POST', {'toStatus': 'CLEARED'})[1]['cheque']['status'], 'CLEARED')
chk('paid by bank transfer', call(f'/cheque-refunds/{rid}/pay', AD, 'POST', {'method': 'BANK_TRANSFER'})[1]['refunds'][0]['status'], 'PAID')
chk('no cash moved for it', one('select count(*) from cash_movements where cheque_refund_id=?', rid), 0)
ev = call(f'/cheques/{cid}', AD)[1]['events']
chk('audit trail: RECEIVED, DEPOSITED, CLEARED with names', [(e['toStatus'], bool(e['userName'])) for e in ev],
    [('RECEIVED', True), ('DEPOSITED', True), ('CLEARED', True)])
try:
    db.execute("update cheques set status='BOUNCED', version=version+1 where cheque_id=?", (cid,)); chk('direct SQL change refused', 'changed', 'refused')
except sqlite3.IntegrityError as e:
    chk('direct SQL change without an event refused', 'CHEQUE' in str(e), True)
chk('Neshan cannot see it (404)', call(f'/cheques/{cid}', T['neshan.admin'])[0], 404)
call('/store/settings', AD, 'PUT', {'taxRate': 16})
chk('integrity_check', one('pragma integrity_check'), 'ok')
chk('no broken references', db.execute('pragma foreign_key_check').fetchall(), [])
print(f"\nSTORE OPS CHECK PASS {R['pass']} FAIL {R['fail']}")
sys.exit(1 if R['fail'] else 0)

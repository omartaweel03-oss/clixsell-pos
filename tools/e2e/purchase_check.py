"""Purchase Orders Phase 2 through the REAL backend (real HTTP, two stores) on a disposable demo
database, checked against the database after each step. Recommended quantities are recalculated
here independently from the raw sales and returns rows. Run via run.sh (suite: purchase)."""
from safety import require_disposable_db  # stops unless this is the disposable test database
import datetime as dt, json, math, sqlite3, sys, threading, urllib.request, uuid

A = 'http://localhost:8080/api'
DB = require_disposable_db(sys.argv[1])
db = sqlite3.connect(DB, timeout=30); db.isolation_level = None
R = {'pass': 0, 'fail': 0}
TF = '%Y-%m-%dT%H:%M:%S'


def call(p, t=None, m='GET', b=None):
    r = urllib.request.Request(A + p, method=m, data=json.dumps(b).encode() if b is not None else None,
                               headers={'Content-Type': 'application/json', **({'Authorization': 'Bearer ' + t} if t else {})})
    try:
        x = urllib.request.urlopen(r); body = x.read(); return x.status, (json.loads(body) if body else None)
    except urllib.error.HTTPError as e:
        body = e.read(); return e.code, (json.loads(body) if body else None)


def chk(sec, name, got, exp):
    ok = got == exp; R['pass' if ok else 'fail'] += 1
    print(('PASS' if ok else 'FAIL'), f'[{sec}] {name}', '' if ok else f'| got {got!r}, expected {exp!r}')


q = lambda s, *a: db.execute(s, a).fetchall()
one = lambda s, *a: db.execute(s, a).fetchone()[0]
tok = lambda u, pw: call('/auth/login', m='POST', b={'username': u, 'password': pw})[1]['token']
SYS = tok('sysadmin', 'Admin12345'); SA = tok('shini.admin', 'Admin-Pass-1'); SC = tok('shini.cashier', 'Cashier-Pass-1')
NA = tok('neshan.admin', 'Admin-Pass-1')
S, N = 1, 2
now = dt.datetime.now().replace(microsecond=0)
order = lambda sup, src, items, key=None, acks=None, note=None: {
    'clientRequestId': key or str(uuid.uuid4()), 'supplierId': sup, 'source': src, 'note': note,
    'items': [{'productId': p, 'quantity': n} for p, n in items], **({'acknowledgedCautions': acks} if acks else {})}

FIN_SQL = ['select product_id, quantity, selling_price, cost_price, supplier_id from products order by product_id',
           'select sale_id, status, total from sales order by sale_id',
           'select sale_item_id, quantity, unit_cost, returned_quantity from sale_items order by sale_item_id',
           'select return_id, quantity, refund_amount from returns order by return_id']

# ── A) start: V6 in place, no orders ────────────────────────────────────────────────────────
chk('A', 'Flyway V6 present, no V7', (one("select count(*) from flyway_schema_history where version='6' and success=1"),
                                      one("select count(*) from flyway_schema_history where version='7'")), (1, 0))
chk('A', 'no purchase orders yet', one('select count(*) from purchase_orders'), 0)

# ── B) roles ────────────────────────────────────────────────────────────────────────────────
for path in ('/purchase-recommendations', '/purchase-candidates?supplierId=1', '/purchase-orders'):
    chk('B', f'cashier {path} → 403', call(path, SC)[0], 403)
    chk('B', f'sysadmin {path} → 403', call(path, SYS)[0], 403)
    chk('B', f'no login {path} → 401', call(path)[0], 401)

# ── C) set up a real sales history in Shini (disposable database) ───────────────────────────
sup = q("select s.supplier_id, s.name from suppliers s where s.store_id=1 and s.active=1 and "
        "(select count(*) from products p where p.supplier_id=s.supplier_id) >= 3 order by s.supplier_id limit 1")[0]
SUP, SUPNAME = sup
prods = [r[0] for r in q('select product_id from products where supplier_id=? order by product_id', SUP)]
P1, P2, P3 = prods[:3]
old = (now - dt.timedelta(days=40)).strftime(TF)
cashier = one("select user_id from users where username='shini.cashier'")
db.execute('update products set created_at=?, quantity=4, min_stock=5 where product_id=?', (old, P1))   # E1-like
db.execute('update products set created_at=?, quantity=10, min_stock=3 where product_id=?', (old, P2))  # E2-like
db.execute('update products set created_at=?, quantity=3, min_stock=4 where product_id=?', (old, P3))   # E5-like
inv = [0]


def sell(pid, units, days_ago):
    at = (now - dt.timedelta(days=days_ago)).strftime(TF); inv[0] += 1
    db.execute("insert into sales (store_id, cashier_id, invoice_number, status, subtotal, discount, tax_rate, tax, total, "
               "created_at, updated_at, completed_at) values (1, ?, ?, 'COMPLETED', ?, 0, 0, 0, ?, ?, ?, ?)",
               (cashier, f'E2E-{inv[0]}', units * 5.0, units * 5.0, at, at, at))
    sid = one('select max(sale_id) from sales')
    db.execute("insert into sale_items (sale_id, product_id, product_name, barcode, quantity, unit_price, line_total, "
               "returned_quantity, unit_cost) select ?, product_id, name, barcode, ?, 5, ?, 0, cost_price from products "
               "where product_id=?", (sid, units, units * 5.0, pid))
    return sid, one('select max(sale_item_id) from sale_items')


for d in range(14): sell(P1, 2, d)
sell(P2, 21, 5)
sid, line = sell(P3, 10, 6)
at = (now - dt.timedelta(days=5)).strftime(TF)
db.execute("insert into returns (store_id, sale_id, sale_item_id, product_id, cashier_id, quantity, reason, refund_amount, "
           "refund_method, return_date) values (1, ?, ?, ?, ?, 8, 'E2E', 40, 'CASH', ?)", (sid, line, P3, cashier, at))
db.execute('update sale_items set returned_quantity=8 where sale_item_id=?', (line,))


def independent(pid):
    """The approved D5 rules, recalculated here from the raw rows."""
    name, qty, mn, created = q('select name, quantity, min_stock, created_at from products where product_id=?', pid)[0]
    today = now.date(); since = str(today - dt.timedelta(days=13)) + 'T00:00'
    sold = one("select coalesce(sum(si.quantity),0) from sale_items si join sales s on s.sale_id=si.sale_id "
               "where s.store_id=1 and s.status='COMPLETED' and s.completed_at >= ? and si.product_id=?", since, pid)
    ret = one('select coalesce(sum(quantity),0) from returns where store_id=1 and return_date >= ? and product_id=?', since, pid)
    net = sold - ret
    age = (today - dt.date.fromisoformat(created[:10])).days + 1
    obs = max(0, min(14, age))
    enough = obs >= 7 and net >= 3
    rate = net / obs if enough else None
    days = (qty / rate) if (rate and qty > 0) else None
    if qty <= 0: cls = 'OUT_OF_STOCK'
    elif days is not None and days <= 3: cls = 'URGENT'
    elif days is not None and days <= 7: cls = 'SOON'
    elif qty <= mn: cls = 'LOW_STOCK'
    else: return None
    if enough: basis, target = 'SALES_RATE', mn + math.ceil(net * 14 / obs)
    elif mn > 0: basis, target = 'MIN_STOCK_ONLY', 2 * mn
    else: basis, target = 'MANUAL', None
    sug = None if target is None else min(target - qty, 100000)
    if sug is not None and sug <= 0: return None
    return {'classification': cls, 'basis': basis, 'suggestedQuantity': sug, 'netUnits': net, 'observedDays': obs}


# ── D) recommendations ──────────────────────────────────────────────────────────────────────
s, b = call('/purchase-recommendations', SA)
chk('D', 'Guardian OFF → guardianEnabled false, empty list', (s, b['guardianEnabled'], b['suppliers']), (200, False, []))
chk('D', 'Guardian OFF → GUARDIAN order refused', call('/purchase-orders', SA, 'POST', order(SUP, 'GUARDIAN', [(P1, 5)]))[1]['code'],
    'GUARDIAN_DISABLED')
call('/guardian/status', SA, 'PUT', {'enabled': True})
s, b = call('/purchase-recommendations', SA)
chk('D', 'Guardian ON → recommendations, engine RULES, horizon 7', (s, b['guardianEnabled'], b['engine'], b['horizonDays']),
    (200, True, 'RULES', 7))
items = {i['productId']: i for sp in b['suppliers'] for i in sp['items']}
# every product of Shini: the API recommends exactly what the independent calculation recommends
mismatch = []
for (pid,) in q('select product_id from products where store_id=1'):
    exp = independent(pid); got = items.get(pid)
    gotv = None if got is None else {k: got[k] for k in ('classification', 'basis', 'suggestedQuantity', 'netUnits', 'observedDays')}
    if exp != gotv: mismatch.append((pid, exp, gotv))
chk('D', f'all {one("select count(*) from products where store_id=1")} Shini products: API = independent calculation',
    mismatch, [])
chk('D', 'E1-like: URGENT, SALES_RATE, 29', (items[P1]['classification'], items[P1]['basis'], items[P1]['suggestedQuantity']),
    ('URGENT', 'SALES_RATE', 29))
chk('D', 'E2-like: SOON, 14', (items[P2]['classification'], items[P2]['suggestedQuantity']), ('SOON', 14))
chk('D', 'E5-like (returns): MIN_STOCK_ONLY, net 2, 5', (items[P3]['basis'], items[P3]['netUnits'], items[P3]['suggestedQuantity']),
    ('MIN_STOCK_ONLY', 2, 5))
chk('D', 'no AI confidence anywhere', 'confidence' in json.dumps(b), False)
chk('D', 'nothing from Neshan', any(i['productId'] in [r[0] for r in q('select product_id from products where store_id=2')]
                                    for i in items.values()), False)

# ── E) manual candidates ────────────────────────────────────────────────────────────────────
s, c = call(f'/purchase-candidates?supplierId={SUP}', SA)
chk('E', 'candidates = supplier products in the DB', sorted(x['productId'] for x in c['products']), sorted(prods))
chk('E', 'candidates never suggest a quantity', 'suggestedQuantity' in json.dumps(c), False)
nsup = one('select min(supplier_id) from suppliers where store_id=2')
chk('E', 'another store supplier → 404', call(f'/purchase-candidates?supplierId={nsup}', SA)[0], 404)

# ── F) create: Guardian + manual, snapshots, idempotency ────────────────────────────────────
fin_before = [q(x) for x in FIN_SQL]
summary_before = call('/reports/summary?from=2020-01-01&to=2035-12-31', SA)[1]
key = str(uuid.uuid4())
s, g = call('/purchase-orders', SA, 'POST', order(SUP, 'GUARDIAN', [(P1, 29), (P3, 5)], key=key, note='E2E guardian'))
chk('F', 'Guardian order 201 PENDING_APPROVAL PO-000001', (s, g['status'], g['orderNumber']), (201, 'PENDING_APPROVAL', 'PO-000001'))
G = g['orderId']
chk('F', 'DB lines: quantities, suggested, basis, reservation, cost snapshot',
    q('select product_id, quantity, suggested_quantity, basis, open_product_id, expected_unit_cost is (select cost_price from products p where p.product_id=i.product_id) '
      'from purchase_order_items i where order_id=? order by product_id', G),
    sorted([(P1, 29, 29, 'SALES_RATE', P1, 1), (P3, 5, 5, 'MIN_STOCK_ONLY', P3, 1)]))
chk('F', 'one CREATED event by shini.admin', q('select event_type, user_id from purchase_order_events where order_id=?', G),
    [('CREATED', one("select user_id from users where username='shini.admin'"))])
s, again = call('/purchase-orders', SA, 'POST', order(SUP, 'GUARDIAN', [(P3, 5), (P1, 29)], key=key, note='E2E guardian'))
chk('F', 'same request again → 200 replayed, same order', (s, again['orderId'], again['replayed']), (200, G, True))
s, bad = call('/purchase-orders', SA, 'POST', order(SUP, 'GUARDIAN', [(P1, 30), (P3, 5)], key=key, note='E2E guardian'))
chk('F', 'same id, other content → 409 IDEMPOTENCY_KEY_REUSED', (s, bad['code']), (409, 'IDEMPOTENCY_KEY_REUSED'))
chk('F', 'counter consumed once', one('select next_purchase_order_number from stores where store_id=1'), 2)
s, dup = call('/purchase-orders', SA, 'POST', order(SUP, 'MANUAL', [(P1, 3)]))
chk('F', 'reserved product → 409 PRODUCT_IN_OPEN_ORDER with the order', (s, dup['code'], dup['details']['openOrders'][0]['orderNumber']),
    (409, 'PRODUCT_IN_OPEN_ORDER', 'PO-000001'))
s, bad = call('/purchase-orders', SA, 'POST', {**order(SUP, 'MANUAL', [(P2, 3)]), 'storeId': 2})
chk('F', 'storeId in body → 400 UNKNOWN_FIELD', (s, bad['code']), (400, 'UNKNOWN_FIELD'))
s, m = call('/purchase-orders', SA, 'POST', order(SUP, 'MANUAL', [(P2, 12)]))
chk('F', 'manual order 201 PO-000002, basis MANUAL, no suggestion', (s, m['orderNumber'], m['items'][0]['basis'],
                                                                      m['items'][0]['suggestedQuantity']), (201, 'PO-000002', 'MANUAL', None))
M = m['orderId']
nprod = one('select min(product_id) from products where supplier_id=?', nsup)
s, n = call('/purchase-orders', NA, 'POST', order(nsup, 'MANUAL', [(nprod, 2)], key=key))
chk('F', 'same key in Neshan → its own PO-000001', (s, n['orderNumber'], one('select store_id from purchase_orders where order_id=?', n['orderId'])),
    (201, 'PO-000001', N))

# ── G) edit, approve, reject, cancel ────────────────────────────────────────────────────────
s, e = call(f'/purchase-orders/{M}', SA, 'PUT', {'version': 0, 'note': 'E2E edit', 'items': [{'productId': P2, 'quantity': 15}]})
chk('G', 'edit quantity → version 1, EDITED', (s, e['version'], [x[0] for x in q('select event_type from purchase_order_events where order_id=? order by event_id', M)]),
    (200, 1, ['CREATED', 'EDITED']))
chk('G', 'stale version → 409 ORDER_CHANGED', call(f'/purchase-orders/{M}/approve', SA, 'POST', {'version': 0})[1]['code'], 'ORDER_CHANGED')
s, a = call(f'/purchase-orders/{G}/approve', SA, 'POST', {'version': 0})
chk('G', 'approve → APPROVED, approved_by shini.admin, version 1', (s, a['status'], one('select approved_by from purchase_orders where order_id=?', G), a['version']),
    (200, 'APPROVED', one("select user_id from users where username='shini.admin'"), 1))
chk('G', 'approved order keeps its reservations', one('select count(*) from purchase_order_items where order_id=? and open_product_id is not null', G), 2)
chk('G', 'approved order cannot be edited', call(f'/purchase-orders/{G}', SA, 'PUT', {'version': 1, 'items': []})[1]['code'], 'INVALID_TRANSITION')
chk('G', 'empty reason refused', call(f'/purchase-orders/{M}/reject', SA, 'POST', {'version': 1, 'reason': '   '})[0], 400)
s, r = call(f'/purchase-orders/{M}/reject', SA, 'POST', {'version': 1, 'reason': 'Too many this week'})
chk('G', 'reject → REJECTED, reservation released', (s, r['status'], one('select count(*) from purchase_order_items where order_id=? and open_product_id is not null', M)),
    (200, 'REJECTED', 0))
s, cx = call(f'/purchase-orders/{G}/cancel', SA, 'POST', {'version': 1, 'reason': 'Plan changed'})
chk('G', 'cancel approved → CANCELED, released, not after contact', (s, cx['status'], one('select canceled_after_contact from purchase_orders where order_id=?', G),
    one('select count(*) from purchase_order_items where order_id=? and open_product_id is not null', G)), (200, 'CANCELED', 0, 0))
chk('G', 'events of each order', ([x[0] for x in q('select event_type from purchase_order_events where order_id=? order by event_id', G)],
                                  [x[0] for x in q('select event_type from purchase_order_events where order_id=? order by event_id', M)]),
    (['CREATED', 'APPROVED', 'CANCELED'], ['CREATED', 'EDITED', 'REJECTED']))
chk('G', 'final orders refuse every change', [call(f'/purchase-orders/{G}/{a_}', SA, 'POST', {'version': 2, 'reason': 'Again'} if a_ != 'approve' else {'version': 2})[1]['code']
                                              for a_ in ('approve', 'reject', 'cancel')], ['INVALID_TRANSITION'] * 3)
chk('G', 'no Phase 3 state reached', one("select count(*) from purchase_orders where status in ('MESSAGE_GENERATED','SENT','CONFIRMED','CLOSED')"), 0)

# ── H) cautions (a closed order written as Phase 3 will) ────────────────────────────────────
admin = one("select user_id from users where username='shini.admin'")
t3 = (now - dt.timedelta(days=3)).strftime(TF)
db.execute("insert into purchase_orders (store_id, order_number, supplier_id, supplier_name, status, source, created_by, created_at, "
           "client_request_id, request_hash) values (1, 'PO-900001', ?, ?, 'PENDING_APPROVAL', 'MANUAL', ?, ?, ?, ?)",
           (SUP, SUPNAME, admin, t3, str(uuid.uuid4()), 'a' * 64))
H = one("select order_id from purchase_orders where order_number='PO-900001'")
db.execute("insert into purchase_order_items (order_id, store_id, product_id, product_store_id, product_name, barcode, quantity, basis, "
           "calc_json, open_product_id) select ?, 1, product_id, 1, name, barcode, 20, 'MANUAL', '{}', product_id from products where product_id=?", (H, P2))
for st in [f"status='APPROVED', approved_by={admin}, approved_at='{t3}'",
           f"status='MESSAGE_GENERATED', message_text='x', message_language='ar', message_whatsapp='970599000001', message_generated_by={admin}, message_generated_at='{t3}'",
           f"status='SENT', sent_marked_by={admin}, sent_marked_at='{t3}'",
           f"status='CONFIRMED', confirmation_recorded_by={admin}, confirmation_recorded_at='{t3}'",
           f"status='CLOSED', closed_by={admin}, closed_at='{t3}'"]:
    db.execute(f'update purchase_orders set {st}, version=version+1 where order_id=?', (H,))
s, cv = call('/purchase-orders', SA, 'POST', order(SUP, 'MANUAL', [(P2, 4)]))
chk('H', 'recently closed → 409 REORDER_CAUTION_ACK_REQUIRED', (s, cv['code'], cv['details']['cautions'][0]['orderNumber']),
    (409, 'REORDER_CAUTION_ACK_REQUIRED', 'PO-900001'))
s, ok = call('/purchase-orders', SA, 'POST', order(SUP, 'MANUAL', [(P2, 4)], acks=[{'productId': P2, 'orderId': H}]))
chk('H', 'with acknowledgment → 201, recorded', (s, 'cautionsAcknowledged' in (one('select details from purchase_order_events where order_id=?', ok['orderId']) or '')),
    (201, True))

# ── I) isolation ────────────────────────────────────────────────────────────────────────────
for m_, p_, b_ in (('GET', f'/purchase-orders/{M}', None), ('GET', f'/purchase-orders/{M}/events', None),
                   ('POST', f'/purchase-orders/{ok["orderId"]}/approve', {'version': 0})):
    chk('I', f'Neshan {m_} {p_} → 404', call(p_, NA, m_, b_)[0], 404)
chk('I', 'Neshan list = only its order', [x['orderId'] for x in call('/purchase-orders', NA)[1]['orders']], [n['orderId']])
chk('I', 'Shini order unchanged by Neshan', one('select status from purchase_orders where order_id=?', ok['orderId']), 'PENDING_APPROVAL')

# ── J) real concurrent HTTP ─────────────────────────────────────────────────────────────────
P4 = prods[3] if len(prods) > 3 else None
target = P4 or one('select min(product_id) from products where supplier_id=? and product_id not in (?,?,?)', SUP, P1, P2, P3)
res = []
def hit(b): res.append(call('/purchase-orders', SA, 'POST', b))
bodies = [order(SUP, 'MANUAL', [(target, 1 + i)]) for i in range(8)]
th = [threading.Thread(target=hit, args=(b,)) for b in bodies]
[t.start() for t in th]; [t.join() for t in th]
chk('J', '8 orders for one product at once → exactly one created, the rest 409',
    (sorted(s for s, _ in res).count(201), all(s in (201, 409) for s, _ in res)), (1, True))
chk('J', 'exactly one reservation', one('select count(*) from purchase_order_items where open_product_id=?', target), 1)
res.clear(); same = order(SUP, 'MANUAL', [(P1, 2)])
th = [threading.Thread(target=hit, args=(same,)) for _ in range(6)]
[t.start() for t in th]; [t.join() for t in th]
chk('J', 'same request 6× at once → one order', len({b['orderId'] for s, b in res if s in (200, 201)}), 1)
chk('J', 'one CREATED event for it', one("select count(*) from purchase_order_events e join purchase_orders o on o.order_id=e.order_id "
                                         "where o.client_request_id=? and o.store_id=1", same['clientRequestId']), 1)

# ── K) nothing financial changed; database consistent ──────────────────────────────────────
chk('K', 'products, sales, sale lines, returns unchanged by purchasing', [q(x) for x in FIN_SQL], fin_before)
chk('K', 'report summary unchanged', call('/reports/summary?from=2020-01-01&to=2035-12-31', SA)[1], summary_before)
chk('K', 'reservation invariant', one("select count(*) from purchase_order_items i join purchase_orders o on o.order_id=i.order_id where "
                                      "(o.status in ('REJECTED','CANCELED','CLOSED') and i.open_product_id is not null) or "
                                      "(o.status not in ('REJECTED','CANCELED','CLOSED') and i.open_product_id is null and i.product_id is not null)"), 0)
chk('K', 'no cross-store rows', one('select count(*) from purchase_order_items i join purchase_orders o on o.order_id=i.order_id '
                                    'where i.store_id<>o.store_id'), 0)
chk('K', 'foreign_key_check clean', q('pragma foreign_key_check'), [])
print(f"\nPURCHASE CHECK PASS {R['pass']} FAIL {R['fail']}")
sys.exit(1 if R['fail'] else 0)

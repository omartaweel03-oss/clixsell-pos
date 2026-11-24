"""Guardian Mode Phase 1 – real end-to-end check (scratch database only).

1. Real API actions in two stores by different users (Store Admin, two cashiers).
2. A few past days of history inserted directly into the SCRATCH database
   (the API always dates a sale "now").
3. Every number Guardian shows is recomputed here from the database with the
   approved rules and compared with /api/guardian/*.
"""
import json, sqlite3, sys, urllib.request, datetime as dt
from safety import require_disposable_db  # stops unless this is the disposable test database
from decimal import Decimal, ROUND_HALF_UP

A = 'http://localhost:8080/api'
db = sqlite3.connect(require_disposable_db(sys.argv[1])); db.isolation_level = None
R = {'pass': 0, 'fail': 0}; FAILS = []


def call(p, t=None, m='GET', b=None):
    r = urllib.request.Request(A + p, method=m, data=json.dumps(b).encode() if b is not None else None,
                               headers={'Content-Type': 'application/json', **({'Authorization': 'Bearer ' + t} if t else {})})
    try:
        x = urllib.request.urlopen(r); body = x.read(); return x.status, (json.loads(body) if body else None)
    except urllib.error.HTTPError as e:
        body = e.read(); return e.code, (json.loads(body) if body else None)


def chk(sec, name, got, exp):
    ok = got == exp; R['pass' if ok else 'fail'] += 1
    print(('PASS ' if ok else 'FAIL ') + f'[{sec}] {name}' + ('' if ok else f': got {got!r}, expected {exp!r}'))
    if not ok: FAILS.append(name)


def login(u, p): return call('/auth/login', m='POST', b={'username': u, 'password': p})[1]['token']


def money(v): return Decimal(str(v)).quantize(Decimal('0.01'), ROUND_HALF_UP)


q = lambda s, *a: db.execute(s, a).fetchall()
one = lambda s, *a: db.execute(s, a).fetchone()[0]

SA = login('shini.admin', 'Admin-Pass-1')      # Ahmad Odeh
LINA = login('shini.cashier', 'Cashier-Pass-1')   # Lina Saleh
YOUSEF = login('shini.cashier2', 'Cashier-Pass-1')  # Yousef Khalil
NA = login('neshan.admin', 'Admin-Pass-1')
NC = login('neshan.cashier', 'Cashier-Pass-1')
SYS = login('sysadmin', 'Admin12345')
SHINI = one("select store_id from stores where name like 'Shini%'")
NESHAN = one("select store_id from stores where name like 'Neshan%'")
uid = lambda u: one('select user_id from users where username=?', u)
now = dt.datetime.now(); today = now.date()

# ── 0) roles and default state ───────────────────────────────────────────
for path in ['/guardian/status', '/guardian/dashboard', '/guardian/alerts', '/guardian/activity']:
    chk('0', f'Store Admin {path}', call(path, SA)[0], 200)
    chk('0', f'Cashier {path} → 403', call(path, LINA)[0], 403)
    chk('0', f'System Admin {path} → 403', call(path, SYS)[0], 403)
    chk('0', f'no login {path} → 401', call(path)[0], 401)
chk('0', 'cashier cannot turn it on', call('/guardian/status', LINA, 'PUT', {'enabled': True})[0], 403)
chk('0', 'off by default (Shini)', call('/guardian/status', SA)[1]['enabled'], False)
chk('0', 'off by default (Neshan)', call('/guardian/status', NA)[1]['enabled'], False)
chk('0', 'off: dashboard has no analysis', call('/guardian/dashboard', SA)[1]['topAlerts'], [])


# ── 1) real actions through the API (Shini) ──────────────────────────────
def product(t, name, bc, cat, price, cost, qty, mn):
    s, b = call('/products', t, 'POST', {'name': name, 'barcode': bc, 'category': cat, 'sellingPrice': price, 'quantity': qty,
                                       'minStock': mn, 'supplierName': 'Guardian Test Supplier', 'costPrice': cost})
    assert s == 201, (s, b); return b['productId']


def sale(t, lines, dtype=None, dval=None, sale_id=None):
    s, b = call('/sales', t, 'POST', {'saleId': sale_id, 'items': [{'productId': p, 'quantity': n} for p, n in lines.items()],
                                     'discountType': dtype, 'discountValue': dval, 'paymentMethod': 'CASH'})
    assert s == 201, (s, b); return b


def suspend(t, lines, dtype=None, dval=None):
    s, b = call('/sales/suspended', t, 'POST', {'items': [{'productId': p, 'quantity': n} for p, n in lines.items()],
                                                'discountType': dtype, 'discountValue': dval})
    assert s == 201, (s, b); return b['saleId']


milk = product(SA, 'G Milk 1L', '7290000000011', 'dairy', 5, 3.2, 60, 10)
cheese = product(SA, 'G Cheese 250g', '7290000000028', 'dairy', 10, 9.5, 40, 5)          # G4 + T2
cake = product(SA, 'G Promo Cake', '7290000000035', 'bakery', 2, 3.0, 30, 2)            # G3 (+ G2a/G2b when sold)
bread = product(SA, 'G Bread', '7290000000042', 'bakery', 2, None, 40, 10)              # no cost
water = product(SA, 'G Water 1.5L', '7290000000059', 'beverages', 2, 1.0, 50, 5)        # G6
jam = product(SA, 'G Dusty Jam', '7290000000066', 'groceries', 9, 4.0, 30, 5)           # T3
oil = product(SA, 'G Olive Oil', '7290000000073', 'groceries', 20, 14.0, 25, 3)         # returns
yogurt = product(SA, 'G Yogurt', '7290000000097', 'dairy', 5, 4.6, 40, 5)               # G2c (+ G4, T2)
choc = product(SA, 'G Chocolate', '7290000000103', 'snacks', 20, 8.0, 40, 5)
juicebox = product(SA, 'G Juice Box', '7290000000110', 'beverages', 3, None, 40, 5)     # G8b: Oct 30, 31, Nov 1
jamjar = product(SA, 'G Jam Jar', '7290000000127', 'groceries', 3, None, 40, 5)         # G8b: Oct 29, 30, 31 (+ Nov 1)
db.execute("update products set created_at=? where product_id=?", ((now - dt.timedelta(days=30)).replace(microsecond=0).isoformat(), water))
db.execute("update products set created_at=? where product_id=?", ((now - dt.timedelta(days=40)).replace(microsecond=0).isoformat(), jam))

s1 = sale(LINA, {milk: 10}, 'PERCENT', 10)                                  # G1, completed by Lina
resumed = suspend(LINA, {milk: 12}, 'PERCENT', 9)                           # Lina starts and suspends …
s2 = sale(YOUSEF, {milk: 12}, 'PERCENT', 9, sale_id=resumed)               # … Yousef completes it (5.40 ₪)
s_admin = sale(SA, {milk: 10}, 'PERCENT', 10)                               # U2: G1 by the Store Admin
s_share = sale(LINA, {yogurt: 1, choc: 1}, 'PERCENT', 10)                   # U4: yogurt share 4.50 < 4.60, invoice profitable
s_share2 = sale(LINA, {yogurt: 2, bread: 1}, 'PERCENT', 10)                 # U4: other line without cost (never guessed)
s3 = sale(LINA, {cake: 5})                                                  # confirmed loss 5.00
s4 = sale(LINA, {bread: 3, milk: 1})                                        # incomplete cost
for _ in range(7): sale(YOUSEF, {water: 6})                                 # 42 units → 8 left
s5 = sale(LINA, {oil: 5})
oil_item = one('select sale_item_id from sale_items where sale_id=? and product_id=?', s5['saleId'], oil)
for _ in range(3):                                                         # 3 returns of the same product
    st, _b = call('/returns', LINA, 'POST', {'saleId': s5['saleId'], 'saleItemId': oil_item, 'quantity': 1, 'reason': 'Leaking'})
    assert st == 201, (st, _b)
for _ in range(3): call(f'/sales/suspended/{suspend(LINA, {milk: 1})}', YOUSEF, 'DELETE')   # Lina starts, Yousef cancels
for i in range(5): assert call(f'/products/{product(SA, f"G Old {i}", f"72900001000{i}{i}", "other", 1, None, 1, 0)}', SA, 'DELETE')[0] == 204
st, _ = call(f'/products/{milk}', SA, 'PUT', {'name': 'G Milk 1L', 'barcode': '7290000000011', 'category': 'dairy', 'sellingPrice': 5,
                                            'quantity': 45, 'minStock': 10, 'supplierName': 'Guardian Test Supplier', 'costPrice': 3.2})
assert st == 200

# Neshan: a little of its own activity.
nmilk = product(NA, 'N Juice', '7290000000080', 'beverages', 8, 5.0, 20, 2)
sale(NC, {nmilk: 2}, 'PERCENT', 10)

# ── 2) past days, inserted in the SCRATCH database (no activity log rows exist for them) ─
def insert_sale(store, cashier, day, hour, status, items, discount=0.0):
    sub = round(sum(q_ * p for _, _, q_, p, _ in items), 2)
    tax = float(money((sub - discount) * 0.16)); total = round(sub - discount + tax, 2)
    at = dt.datetime.combine(day, dt.time(hour, 0)).isoformat()
    n = one('select count(*) from sales where store_id=?', store) + 9000
    cur = db.execute("insert into sales (store_id, cashier_id, invoice_number, status, subtotal, discount, tax_rate, tax, total, "
                     "created_at, updated_at, completed_at) values (?,?,?,?,?,?,16,?,?,?,?,?)",
                     (store, cashier, f'HIST-{n:06d}', status, sub, discount, tax, total, at, at, at if status == 'COMPLETED' else None))
    sid = cur.lastrowid
    for pid, name, qty, price, cost in items:
        db.execute("insert into sale_items (sale_id, product_id, product_name, barcode, quantity, unit_price, line_total, "
                   "returned_quantity, unit_cost) values (?,?,?,?,?,?,?,0,?)", (sid, pid, name, '0', qty, price, round(qty * price, 2), cost))
    if status == 'COMPLETED':
        db.execute("insert into payments (sale_id, payment_method, amount, payment_status, paid_at) values (?, 'CASH', ?, 'PAID', ?)", (sid, total, at))
        db.execute("insert into receipts (sale_id, qr_code_value, issued_at) values (?, ?, ?)", (sid, f'HIST-QR-{sid}', at))
    return sid


lina_id, yousef_id = uid('shini.cashier'), uid('shini.cashier2')
history_days = [today - dt.timedelta(days=d) for d in range(1, 7) if (today - dt.timedelta(days=d)).month == today.month]
for d in history_days:
    for h in (9, 12, 17):
        insert_sale(SHINI, lina_id, d, h, 'COMPLETED', [(milk, 'G Milk 1L', 4, 5.0, 3.2)])
if history_days:
    d = history_days[0]   # yesterday: 6 cancellations and a high discount, before any log row
    for h in range(10, 16): insert_sale(SHINI, lina_id, d, h, 'CANCELED', [(milk, 'G Milk 1L', 1, 5.0, None)])
    insert_sale(SHINI, lina_id, d, 18, 'COMPLETED', [(milk, 'G Milk 1L', 12, 5.0, 3.2)], discount=6.0)  # 10%, no loss
    insert_sale(SHINI, lina_id, d, 19, 'COMPLETED', [(cheese, 'G Cheese 250g', 6, 10.0, 9.5)], discount=6.0)  # 54 < 57: loss

# U1: a day earlier this month with 3 canceled invoices and NO sale → G7 on a zero-sales day.
zero_day = today - dt.timedelta(days=8)
if zero_day.month == today.month:
    for h in (10, 11, 12): insert_sale(SHINI, lina_id, zero_day, h, 'CANCELED', [(milk, 'G Milk 1L', 1, 5.0, None)])

# G8b across two months: returns at the end of last month and on the 1st of this month.
first = today.replace(day=1); last_month_end = first - dt.timedelta(days=1)
def old_sale_with_returns(pid, name, days):
    sid = insert_sale(SHINI, lina_id, last_month_end - dt.timedelta(days=5), 9, 'COMPLETED', [(pid, name, 10, 3.0, None)])
    item = one('select sale_item_id from sale_items where sale_id=?', sid)
    for d in days:
        db.execute("insert into returns (store_id, sale_id, sale_item_id, product_id, cashier_id, quantity, reason, refund_amount, "
                   "refund_method, return_date) values (?,?,?,?,?,1,'Damaged',3.48,'CASH',?)",
                   (SHINI, sid, item, pid, lina_id, dt.datetime.combine(d, dt.time(9, 0)).isoformat()))
    db.execute('update sale_items set returned_quantity=? where sale_item_id=?', (len(days), item))
old_sale_with_returns(juicebox, 'G Juice Box', [last_month_end - dt.timedelta(days=1), last_month_end, first])
old_sale_with_returns(jamjar, 'G Jam Jar', [last_month_end - dt.timedelta(days=2), last_month_end - dt.timedelta(days=1), last_month_end, first])

# ── 3) Guardian ON and the checks ────────────────────────────────────────
chk('1', 'turn on (Shini)', call('/guardian/status', SA, 'PUT', {'enabled': True})[1]['enabled'], True)
call('/guardian/status', SA, 'PUT', {'enabled': True})
chk('1', 'on/off is logged once with the Store Admin', q("select event_type, user_id from activity_log where store_id=? and event_type like 'GUARDIAN_%'", SHINI),
    [('GUARDIAN_ENABLED', uid('shini.admin'))])
D = call('/guardian/dashboard', SA)[1]
AL = call('/guardian/alerts', SA)[1]['alerts']
by = lambda code: [a for a in AL if a['code'] == code]
start = today.replace(day=1).isoformat() + 'T00:00:00'; end = (today + dt.timedelta(days=1)).isoformat() + 'T00:00:00'

# Invoices of the month and their cost status, computed here.
inv = q("""select s.sale_id, s.subtotal, s.discount, s.total, s.completed_at, count(si.sale_item_id),
                  sum(case when si.unit_cost is null then 1 else 0 end), coalesce(sum(case when si.unit_cost is not null then si.unit_cost*si.quantity end),0)
           from sales s join sale_items si on si.sale_id=s.sale_id
           where s.store_id=? and s.status='COMPLETED' and s.completed_at>=? and s.completed_at<? and s.subtotal>0 group by s.sale_id""", SHINI, start, end)
status = {}
for sid, sub, disc, tot, at, lines, nocost, cost in inv:
    status[sid] = 'INCOMPLETE' if nocost else ('LOSS' if money(cost) - (money(sub) - money(disc)) >= Decimal('0.01') else 'OK')
chk('2', 'invoices of the month', D['costData']['invoices'], len(inv))
chk('2', 'confirmed losses (every cost known)', D['costData']['confirmedLossInvoices'], sum(1 for v in status.values() if v == 'LOSS'))
chk('2', 'incomplete-cost invoices are not losses', D['costData']['incompleteCostInvoices'], sum(1 for v in status.values() if v == 'INCOMPLETE'))
chk('2', 'confirmed losses: the cake sale and yesterday\'s discounted cheese (54.00 < 57.00)',
    sorted(a['params']['invoiceNumber'] for a in by('CONFIRMED_LOSS')), sorted([s3['invoiceNumber'], one("select invoice_number from sales where store_id=? and invoice_number like 'HIST-%' and discount=6 and subtotal=60 and total>0 order by sale_id desc limit 1", SHINI)]))
chk('2', 'discounted loss before the log: no user name', [a['actorNote'] for a in by('CONFIRMED_LOSS') if a['params']['invoiceNumber'].startswith('HIST-')], ['NOT_RECORDED'])
chk('2', 'cake loss = 15.00 − 10.00', [a['params']['loss'] for a in by('CONFIRMED_LOSS') if a['params']['invoiceNumber'] == s3['invoiceNumber']], [5.0])
chk('2', 'bread + milk sale is INCOMPLETE (not a loss)', status[s4['saleId']], 'INCOMPLETE')
chk('2', 'G2a below-cost line (cake 2.00 < 3.00)', [(a['params']['productName'], a['params']['unitPrice'], a['params']['unitCost']) for a in by('BELOW_COST_SALE')],
    [('G Promo Cake', 2.0, 3.0)])
chk('2', 'G3 current price below cost', [a['params']['productName'] for a in by('PRICE_BELOW_COST')], ['G Promo Cake'])
chk('2', 'G4 cheese: max 10% → 9.00 < 9.50, safe 5%', [(a['params']['productName'], a['params']['priceAfterMax'], a['params']['safePercent']) for a in by('MAX_DISCOUNT_RISK') if a['params']['productName'] == 'G Cheese 250g'],
    [('G Cheese 250g', 9.0, 5.0)])
g4_exp = sorted(n for n, pr, c in q("select name, selling_price, cost_price from products where store_id=? and cost_price is not null", SHINI)
                if money(pr) > money(c) and money(Decimal(str(pr)) * Decimal('0.9')) < money(c))
chk('2', 'G4 products = independent check (max 10%)', sorted(a['params']['productName'] for a in by('MAX_DISCOUNT_RISK')), g4_exp)
chk('2', 'profit risk card = G2a + G2b + G3', D['profitRiskCount'], len([a for a in AL if a['rule'] in ('G2a', 'G2b', 'G3')]))

# G1 – high discounts, users from the log only.
hd = [sid for sid, sub, disc, *_ in inv if disc > 0 and money(disc) >= Decimal('5.00') and money(disc) * 100 / money(sub) >= Decimal('7.5')]
chk('3', 'high-discount invoices (count)', D['activity']['highDiscounts'], len(hd))
g1 = {a['params']['invoiceNumber']: a for a in by('HIGH_DISCOUNT')}
chk('3', 'Lina\'s 10% discount → completed by Lina', (g1[s1['invoiceNumber']]['actor']['name'], g1[s1['invoiceNumber']]['params']['percent']), ('Lina Saleh', 10.0))
chk('3', 'resumed invoice → completed by Yousef, started by Lina', (g1[s2['invoiceNumber']]['actor']['name'], g1[s2['invoiceNumber']]['params']['startedBy']),
    ('Yousef Khalil', 'Lina Saleh'))
chk('3', 'the invoice itself still shows the starter (unchanged)', one('select cashier_id from sales where sale_id=?', s2['saleId']), lina_id)
chk('U2', 'G1 by the Store Admin shows the Store Admin role (not a cashier)', (g1[s_admin['invoiceNumber']]['actor']['name'], g1[s_admin['invoiceNumber']]['actor']['role']),
    ('Ahmad Odeh', 'STORE_ADMIN'))
chk('U2', 'G1 by a cashier shows the Cashier role', g1[s1['invoiceNumber']]['actor']['role'], 'CASHIER')
chk('U2', 'resumed invoice: starter role recorded too', g1[s2['invoiceNumber']]['params']['startedByRole'], 'CASHIER')
if history_days:
    old = [a for a in by('HIGH_DISCOUNT') if a['params']['invoiceNumber'].startswith('HIST-')]
    chk('3', 'yesterday\'s high discount: no user name (before the log)', [(a['actor'], a['actorNote'], a['params']['startedBy']) for a in old],
        [(None, 'NOT_RECORDED', None)])

# G5 – same numbers as the Stock Alerts report.
inv_rep = call('/reports/inventory', SA)[1]
chk('4', 'out of stock = Stock Alerts', sum(a['params']['count'] for a in by('OUT_OF_STOCK')), inv_rep['outOfStock'])
chk('4', 'low stock = Stock Alerts', sum(a['params']['count'] for a in by('LOW_STOCK')), inv_rep['lowStock'])

# G6 / T1 – Water: 42 net units in 14 days = 3 / day, 8 left → 2.7 days.
w = [p for p in D['atRiskProducts'] if p['name'] == 'G Water 1.5L']
chk('5', 'water forecast', (w[0]['daysLeft'], w[0]['unitsPerDay'], w[0]['expectedStockout'], w[0]['risk']) if w else None,
    (2.7, 3.0, (today + dt.timedelta(days=2)).isoformat(), 'HIGH'))
t1 = [r for r in D['recommendations'] if r['code'] == 'REORDER_SOON'][0]
chk('5', 'T1 lists water', 'G Water 1.5L' in [p['name'] for p in t1['products']], True)
t2 = [r for r in D['recommendations'] if r['code'] == 'LOW_MARGIN'][0]
chk('5', 'T2 cheese margin 5%', [(p['name'], p['marginPercent']) for p in t2['products'] if p['name'] == 'G Cheese 250g'], [('G Cheese 250g', 5.0)])
t3 = [r for r in D['recommendations'] if r['code'] == 'SLOW_MOVING'][0]
chk('5', 'T3 slow-moving jam (40 days, none sold)', 'G Dusty Jam' in [p['name'] for p in t3['products']], True)

# G7 – cancellations per day.
canc = q("select substr(updated_at,1,10), count(*) from sales where store_id=? and status='CANCELED' and updated_at>=? and updated_at<? group by 1", SHINI, start, end)
done = dict(q("select substr(completed_at,1,10), count(*) from sales where store_id=? and status='COMPLETED' and subtotal>0 and completed_at>=? and completed_at<? group by 1", SHINI, start, end))
exp7 = {}
for day, n in canc:
    allc = n + done.get(day, 0)
    if n >= 6 and n * 100 >= 20 * allc: exp7[day] = 'HIGH'
    elif n >= 3 and n * 100 >= 10 * allc: exp7[day] = 'MEDIUM'
chk('6', 'G7 days and severity', {a['params']['day']: a['severity'] for a in by('CANCELED_INVOICES')}, exp7)
today_g7 = [a for a in by('CANCELED_INVOICES') if a['params']['day'] == today.isoformat()]
if today_g7:
    chk('6', 'today: canceled by Yousef (from the log), not Lina who started them', today_g7[0]['params']['byUser'], [{'name': 'Yousef Khalil', 'count': 3, 'role': 'CASHIER'}])
if history_days:
    y = [a for a in by('CANCELED_INVOICES') if a['params']['day'] == history_days[0].isoformat()]
    chk('6', 'yesterday: 6 cancellations, no names (before the log)', (y[0]['severity'], y[0]['params']['notRecorded'], y[0]['actorNote']) if y else None,
        ('HIGH', 6, 'NOT_RECORDED'))

# G8 – returns.
ret = q("select substr(return_date,1,10), sum(refund_amount), count(*) from returns where store_id=? and return_date>=? and return_date<? group by 1", SHINI, start, end)
sales_day = dict(q("select substr(completed_at,1,10), sum(total) from sales where store_id=? and status='COMPLETED' and completed_at>=? and completed_at<? group by 1", SHINI, start, end))
exp8 = {}
for day, ref, n in ret:
    refd, sd = money(ref), money(sales_day.get(day, 0))
    if refd < 50: continue
    if sd == 0: exp8[day] = 'MEDIUM'
    else:
        p = refd * 100 / sd
        if p >= 25: exp8[day] = 'HIGH'
        elif p >= 10: exp8[day] = 'MEDIUM'
chk('7', 'G8a days (refund ≥ 50 and ≥ 10% of the day)', {a['params']['day']: a['severity'] for a in by('RETURNS_SPIKE')}, exp8)
# G8b computed here: episodes over every return of the product (also last month), reported in the month they reach 3.
def g8b_expected(month_start, month_end):
    rows = q("""select coalesce(r.product_id, -1), si.product_name, r.return_date, r.return_id from returns r join sale_items si on si.sale_item_id=r.sale_item_id
                where r.store_id=? and r.return_date < ? order by r.return_date, r.return_id""", SHINI, month_end)
    by_p = {}
    for pid, name, at, rid in rows: by_p.setdefault((pid, name), []).append(dt.datetime.fromisoformat(at))
    out = {}
    for (pid, name), ts in by_p.items():
        cnt = [sum(1 for t in ts[:j + 1] if t > ts[j] - dt.timedelta(days=7)) for j in range(len(ts))]
        best = 0
        for j, t in enumerate(ts):
            if cnt[j] >= 3 and (j == 0 or cnt[j - 1] < 3) and month_start <= t.isoformat() < month_end:
                k = j; peak = cnt[j]
                while k + 1 < len(ts) and cnt[k + 1] >= 3:
                    k += 1; peak = max(peak, cnt[k])
                best = max(best, peak)
        if best: out[name] = best
    return out
chk('7', 'G8b = independent episode check (this month)', {a['params']['productName']: a['params']['count'] for a in by('PRODUCT_RETURNS_REPEATED')},
    g8b_expected(start, end))
chk('7', 'G8b olive oil returned 3 times in 7 days', [a['params']['count'] for a in by('PRODUCT_RETURNS_REPEATED') if a['params']['productName'] == 'G Olive Oil'], [3])
chk('G8b', 'Juice Box: Oct 30 + Oct 31 + Nov 1 → reported this month, count 3',
    [a['params']['count'] for a in by('PRODUCT_RETURNS_REPEATED') if a['params']['productName'] == 'G Juice Box'], [3])
chk('G8b', 'Jam Jar reached 3 last month → not again this month',
    [a for a in by('PRODUCT_RETURNS_REPEATED') if a['params']['productName'] == 'G Jam Jar'], [])
pm = last_month_end
prev = call(f'/guardian/alerts?year={pm.year}&month={pm.month}&rule=G8b', SA)[1]['alerts']
pm_start = pm.replace(day=1).isoformat() + 'T00:00'; pm_end = first.isoformat() + 'T00:00'
chk('G8b', 'last month = independent episode check', {a['params']['productName']: a['params']['count'] for a in prev}, g8b_expected(pm_start, pm_end))
chk('G8b', 'last month: Jam Jar once (count 3), Juice Box not (only 2 there)',
    sorted((a['params']['productName'], a['params']['count']) for a in prev if a['params']['productName'] in ('G Jam Jar', 'G Juice Box')), [('G Jam Jar', 3)])
chk('G8b', 'no product counted in both months', set(a['params']['productName'] for a in prev) & set(a['params']['productName'] for a in by('PRODUCT_RETURNS_REPEATED')), set())

# G9 – deletions (log only).
chk('8', 'G9: 5 deletions by the Store Admin today', [(a['severity'], a['params']['count'], a['actor']['name']) for a in by('PRODUCT_DELETIONS')],
    [('MEDIUM', 5, 'Ahmad Odeh')])

# Activity log: real users, correct events.
log = q("select event_type, u.username from activity_log l join users u on u.user_id=l.user_id where l.store_id=? order by log_id", SHINI)
# The demo data itself completes 2 discounted sales (Lina 5%, Yousef 3 ₪) through the real service.
chk('9', 'DISCOUNT_APPLIED users (2 demo sales + Lina + Yousef on the resumed invoice + the admin + 2 Lina)', [u for e, u in log if e == 'DISCOUNT_APPLIED'],
    ['shini.cashier', 'shini.cashier2', 'shini.cashier', 'shini.cashier2', 'shini.admin', 'shini.cashier', 'shini.cashier'])
chk('9', 'INVOICE_CANCELED users', [u for e, u in log if e == 'INVOICE_CANCELED'], ['shini.cashier2'] * 3)
chk('9', 'PRODUCT_DELETED users', [u for e, u in log if e == 'PRODUCT_DELETED'], ['shini.admin'] * 5)
chk('9', 'STOCK_ADJUSTED by the admin (milk edited)', [u for e, u in log if e == 'STOCK_ADJUSTED'], ['shini.admin'])
page = call('/guardian/activity', SA)[1]
chk('9', 'activity report total = log rows of the month', page['total'], one("select count(*) from activity_log where store_id=? and created_at>=? and created_at<?", SHINI, start, end))
chk('9', 'filter PRODUCT_DELETED', call('/guardian/activity?type=PRODUCT_DELETED', SA)[1]['total'], 5)

# Today's sales (net, like Sales Analytics) and the cards.
net_today = money(one("select coalesce(sum(total),0) from sales where store_id=? and status='COMPLETED' and completed_at>=? and completed_at<=?", SHINI, today.isoformat() + 'T00:00:00', D['to'] + 'T23:59:59')) \
    - money(one("select coalesce(sum(refund_amount),0) from returns where store_id=? and return_date>=?", SHINI, today.isoformat() + 'T00:00:00'))
chk('10', "today's net sales", money(D['todaySales']['today']), net_today)
chk('10', 'critical card = HIGH alerts', D['criticalCount'], len([a for a in AL if a['severity'] == 'HIGH']))
chk('10', 'alert count', D['alertCount'], len(AL))

# Health: recompute every part.
prods = q("select quantity, min_stock from products where store_id=?", SHINI)
healthy = sum(1 for qty, mn in prods if qty > 0 and qty > mn)
C = len(inv); F = sum(1 for v in status.values() if v != 'INCOMPLETE'); L = sum(1 for v in status.values() if v == 'LOSS')
parts = {p['part']: p for p in D['health']['parts']}
chk('11', 'stock part', parts['STOCK']['points'], float(money(25 * healthy / len(prods))))
if F >= 5 and F * 100 >= 50 * C:
    chk('11', 'profit part', parts['PROFIT']['points'], float(money(25 * (1 - L / F))))
else:
    chk('11', 'profit part not available (cost coverage)', parts['PROFIT']['available'], False)
chk('11', 'discount part', parts['DISCOUNTS']['points'], float(money(25 * (1 - len(hd) / C))))
alert_days = {a['day'] for a in AL if a['rule'] in ('G7', 'G8a', 'G9')}
active = set(done) | alert_days
chk('11', 'activity part', parts['ACTIVITY']['points'], float(money(25 * (1 - len(alert_days) / len(active)))))
avail = [float(p['points']) for p in D['health']['parts'] if p['available']]
raw = sum(v for v in [25 * healthy / len(prods)] + ([25 * (1 - L / F)] if parts['PROFIT']['available'] else []) + [25 * (1 - len(hd) / C), 25 * (1 - len(alert_days) / len(active))])
chk('11', 'score', D['health']['score'], int(Decimal(raw / (25 * len(avail)) * 100).quantize(Decimal('1'), ROUND_HALF_UP)) if len(avail) >= 3 else None)

# U1: the zero-sales alert day counts as an active day.
if zero_day.month == today.month:
    chk('U1', 'G7 on a day without any completed sale', [a['severity'] for a in by('CANCELED_INVOICES') if a['params']['day'] == zero_day.isoformat()], ['MEDIUM'])
    chk('U1', 'that day has no sale but is an active day', (zero_day.isoformat() in done, zero_day.isoformat() in alert_days), (False, True))
    chk('U1', 'activeDays = days with a sale ∪ alert days', parts['ACTIVITY']['numbers']['activeDays'], len(active))

# U3: the number is unchanged; while a HIGH alert is open the band is at most ATTENTION.
highs = [a for a in AL if a['severity'] == 'HIGH']
sc = D['health']['score']
raw_band = None if sc is None else ('GOOD' if sc >= 80 else 'ATTENTION' if sc >= 60 else 'RISK')
chk('U3', 'criticalAlerts = HIGH alerts', D['health']['criticalAlerts'], len(highs))
chk('U3', 'band (limited while critical alerts are open)', D['health']['band'], 'ATTENTION' if raw_band == 'GOOD' and highs else raw_band)
chk('U3', 'bandLimited', D['health']['bandLimited'], raw_band == 'GOOD' and bool(highs))

# U4: G2c computed here from the sold lines (Reports allocation), never for a loss invoice or a missing cost.
lines = q("""select s.sale_id, si.product_id, si.product_name, si.quantity, si.unit_price, si.unit_cost, si.line_total, s.subtotal, s.discount,
                    substr(s.completed_at,1,10), si.returned_quantity
             from sale_items si join sales s on s.sale_id=si.sale_id
             where s.store_id=? and s.status='COMPLETED' and s.completed_at>=? and s.completed_at<? and s.subtotal>0 and s.discount>0
               and si.unit_cost is not null""", SHINI, start, end)
exp_g2c = {}
for sid, pid, name, qty, price, cost, lt, sub, disc, day, ret_q in lines:
    if status.get(sid) == 'LOSS' or money(price) < money(cost): continue
    share = Decimal(str(lt)) * (Decimal(str(sub)) - Decimal(str(disc))) / Decimal(str(sub))
    if money(share) >= money(cost) * qty: continue
    e = exp_g2c.setdefault((name, day), [0, 0, Decimal(0), 0])
    e[0] += 1; e[1] += qty; e[2] += money(cost) * qty - share; e[3] += ret_q
got_g2c = {(a['params']['productName'], a['params']['day']): [a['params']['times'], a['params']['units'], money(a['params']['shortfall']), a['params']['unitsReturned']]
           for a in by('DISCOUNT_SHARE_BELOW_COST')}
chk('U4', 'G2c alerts = independent allocation check', got_g2c, {k: [v[0], v[1], money(v[2]), v[3]] for k, v in exp_g2c.items()})
chk('U4', 'G2c yogurt: 2 invoices, 3 units, 4.50 per unit, short 0.30', [(a['params']['times'], a['params']['units'], a['params']['allocatedUnitRevenue'], a['params']['shortfall'], a['severity'])
    for a in by('DISCOUNT_SHARE_BELOW_COST') if a['params']['productName'] == 'G Yogurt'], [(2, 3, 4.5, 0.3, 'MEDIUM')])
chk('U4', 'the yogurt invoices are not losses', (status[s_share['saleId']], status[s_share2['saleId']]), ('OK', 'INCOMPLETE'))
chk('U4', 'G2c is not in the profit-risk card', D['profitRiskCount'], len([a for a in AL if a['rule'] in ('G2a', 'G2b', 'G3')]))
chk('M', 'marginRiskCount = G2c alerts = independent allocation check', (D['marginRiskCount'], len(by('DISCOUNT_SHARE_BELOW_COST'))), (len(exp_g2c), len(exp_g2c)))
chk('M', 'no alert in both counts (profit risks and margin risks)', any(a['rule'] == 'G2c' for a in AL if a['rule'] in ('G2a', 'G2b', 'G3')), False)

# U8 / reconciliation: unique keys, incidents computed here (same subject rule as the plan).
keys = [a['key'] for a in AL]
chk('U8', 'every alert key is unique', len(keys), len(set(keys)))
chk('U8', 'critical alerts (HIGH) and their keys', D['criticalCount'], len({a['key'] for a in highs}))
group = ('G5:', 'G6:', 'G7:', 'G8a:', 'G9:')
def subjects(a):
    if a['key'].startswith(group): return {'alert:' + a['key']}
    out = set()
    for r in a['evidence']:
        if r['id'] is None: continue
        if a['rule'] in ('G1', 'G2b'):
            if r['type'] == 'SALE': out.add('sale:%d' % r['id'])
        elif a['rule'] in ('G2a', 'G2c'):
            if r['type'] in ('SALE', 'PRODUCT'): out.add('%s:%d' % (r['type'].lower(), r['id']))
        elif r['type'] == 'PRODUCT': out.add('product:%d' % r['id'])
    return out or {'alert:' + a['key']}
comp = {a['key']: {a['key']} for a in AL}
subj = {a['key']: subjects(a) for a in AL}
for x in AL:
    for y in AL:
        if x['key'] < y['key'] and subj[x['key']] & subj[y['key']]:
            merged = comp[x['key']] | comp[y['key']]
            for k in merged: comp[k] = merged
chk('U8', 'related = the other alerts of the same incident', {a['key']: sorted(a['related']) for a in AL}, {k: sorted(comp[k] - {k}) for k in comp})
issues = {frozenset(comp[a['key']]) for a in highs}
chk('U8', 'criticalIssues = incidents with a HIGH alert', D['criticalIssues'], len(issues))
cake_keys = sorted(a['rule'] for a in AL if a['severity'] == 'HIGH' and 'G Promo Cake' in json.dumps(a))
chk('U8', 'cake: G2a, G2b, G3 are one incident', sorted(set(a['rule'] for a in AL if a['key'] in comp[[k for k in keys if k.startswith('G3:')][0]])), ['G2a', 'G2b', 'G3'])
print('  critical keys:', sorted(a['key'] for a in highs))
print('  incidents:', [sorted(i) for i in issues])

# U2: the cashier-area tile = POS alerts (G1, G7, G8a) by any user.
tile = [t for t in D['storeMap'] if t['kind'] == 'CASHIER_AREA'][0]
pos = [a for a in AL if a['rule'] in ('G1', 'G7', 'G8a')]
chk('U2', 'cashier area HIGH / MEDIUM', (tile['highAlerts'], tile['mediumAlerts']),
    (sum(1 for a in pos if a['severity'] == 'HIGH'), sum(1 for a in pos if a['severity'] == 'MEDIUM')))
chk('U2', 'G9 deletions by the Store Admin carry the role', [a['actor']['role'] for a in by('PRODUCT_DELETIONS')], ['STORE_ADMIN'])

# ── 4) isolation: Neshan ─────────────────────────────────────────────────
call('/guardian/status', NA, 'PUT', {'enabled': True})
ND = json.dumps(call('/guardian/dashboard', NA)[1]) + json.dumps(call('/guardian/alerts', NA)[1]) + json.dumps(call('/guardian/activity', NA)[1])
for word in ['G Milk', 'G Promo Cake', 'Lina Saleh', 'Yousef Khalil', 'Ahmad Odeh', 'HIST-', 'Shini']:
    chk('12', f'Neshan never sees "{word}"', word in ND, False)
chk('12', 'Neshan invoices are its own', call('/guardian/dashboard', NA)[1]['costData']['invoices'],
    one("select count(*) from sales where store_id=? and status='COMPLETED' and completed_at>=? and subtotal>0", NESHAN, start))
chk('12', 'storeId in the URL is ignored', 'G Milk' in json.dumps(call(f'/guardian/dashboard?storeId={SHINI}', NA)[1]), False)
chk('12', 'Neshan discount by Rana (its own cashier)', [a['actor']['name'] for a in call('/guardian/alerts?rule=G1', NA)[1]['alerts'] if a['actor']],
    ['Rana Haddad'] * len([a for a in call('/guardian/alerts?rule=G1', NA)[1]['alerts'] if a['actor']]))
chk('12', 'no secrets in Guardian answers', any(w in ND.lower() for w in ['password', 'token', 'hash']), False)
for word in ['G Yogurt', 'G Juice Box', 'G Jam Jar']:
    chk('12', f'Neshan never sees "{word}" (G2c / G8b)', word in ND, False)
chk('12', 'Neshan has no G2c', call('/guardian/alerts?rule=G2c', NA)[1]['alerts'], [])

# U8 (audit): Neshan's cashier gives 3 high discounts today; the 3rd invoice is also a confirmed loss.
n_soap = product(NA, 'N Soap', '7290000000134', 'other', 5, 1.0, 50, 2)
n_cream = product(NA, 'N Cream', '7290000000141', 'dairy', 5, 4.6, 50, 2)
sale(NC, {n_soap: 10}, 'PERCENT', 10); sale(NC, {n_soap: 10}, 'PERCENT', 10)
n_loss = sale(NC, {n_cream: 10}, 'PERCENT', 10)                              # 45.00 < cost 46.00
NAL = call('/guardian/alerts', NA)[1]['alerts']; NDD = call('/guardian/dashboard', NA)[1]
grp = [a for a in NAL if a['key'].startswith('G1:user:')]
loss_key = 'G2b:sale:%d' % n_loss['saleId']
chk('U8', 'Neshan: one repeated-discount alert (HIGH, 3 invoices) that keeps the loss invoice',
    [(a['severity'], a['params']['count'], n_loss['invoiceNumber'] in a['params']['invoices'], a['actor']['role']) for a in grp], [('HIGH', 3, True, 'CASHIER')])
chk('U8', 'Neshan: the loss is reported (G2b) and linked to the repeated alert',
    [a['related'] for a in NAL if a['key'] == loss_key], [[grp[0]['key']]] if grp else None)
nhigh = [a for a in NAL if a['severity'] == 'HIGH']
ncomp = {a['key']: {a['key']} for a in NAL}; nsubj = {a['key']: subjects(a) for a in NAL}
for x in NAL:
    for y in NAL:
        if x['key'] < y['key'] and nsubj[x['key']] & nsubj[y['key']]:
            m = ncomp[x['key']] | ncomp[y['key']]
            for k in m: ncomp[k] = m
chk('M', 'Neshan: marginRiskCount = its own G2c alerts (none)', (NDD['marginRiskCount'], len([a for a in NAL if a['rule'] == 'G2c'])), (0, 0))
chk('U8', 'Neshan: criticalIssues = independent incident count', NDD['criticalIssues'], len({frozenset(ncomp[a['key']]) for a in nhigh}))
chk('U8', 'Neshan: the two HIGH alerts about that invoice count once', NDD['criticalCount'] - NDD['criticalIssues'] >= 1, True)
chk('12', 'Shini never sees Neshan\'s new alerts', any('N Cream' in json.dumps(a) or 'N Soap' in json.dumps(a) for a in call('/guardian/alerts', SA)[1]['alerts']), False)

# ── 5) off again: nothing deleted, logging continues ─────────────────────
rows_before = one('select count(*) from activity_log')
call('/guardian/status', SA, 'PUT', {'enabled': False})
chk('13', 'off: dashboard empty', call('/guardian/dashboard', SA)[1]['enabled'], False)
sale(LINA, {milk: 10}, 'PERCENT', 10)
chk('13', 'off: the log keeps recording (disable + new discount)', one('select count(*) from activity_log') - rows_before, 2)
call('/guardian/status', SA, 'PUT', {'enabled': True})
chk('13', 'on again: every alert is back', len(call('/guardian/alerts', SA)[1]['alerts']) >= len(AL), True)

print(f'\nGUARDIAN SCENARIO PASS {R["pass"]} FAIL {R["fail"]}')
for f in FAILS: print('  FAILED:', f)
import os
json.dump({'dashboard': D, 'alerts': AL}, open(os.path.join(os.environ['CLIXSELL_E2E_OUT'], 'scenario_result.json'), 'w'), indent=1, default=str)

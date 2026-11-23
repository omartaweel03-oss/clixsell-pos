"""U5 – Reports, Sales Analytics and the Guardian 7-day chart at midnight, end to end.

Disposable demo database only (run via run.sh). Sales and a return are made through the real API,
then dated in the DISPOSABLE database to the exact text the app stores for the first second after
midnight ("YYYY-MM-01T00:00", no seconds) and to the last second of the month before. Every report
figure is then compared with an independent calculation that only looks at the DATE PART of each
stored time (no range bounds at all), for both stores.
"""
import datetime as dt
import json
import os
import sqlite3
import sys
import urllib.error
import urllib.request

from safety import require_disposable_db  # stops unless this is the disposable test database

DB = require_disposable_db(sys.argv[1] if len(sys.argv) > 1 else None)
A = 'http://localhost:8080/api'
db = sqlite3.connect(DB); db.isolation_level = None
R = {'pass': 0, 'fail': 0}; FAILS = []


def call(path, token=None, method='GET', body=None):
    r = urllib.request.Request(A + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                               headers={'Content-Type': 'application/json', **({'Authorization': 'Bearer ' + token} if token else {})})
    try:
        x = urllib.request.urlopen(r); b = x.read(); return x.status, (json.loads(b) if b else None)
    except urllib.error.HTTPError as e:
        b = e.read(); return e.code, (json.loads(b) if b else None)


def chk(name, got, exp, tol=0.005):
    ok = abs(got - exp) <= tol if isinstance(exp, float) else got == exp
    R['pass' if ok else 'fail'] += 1
    print(('PASS ' if ok else 'FAIL ') + name + ('' if ok else f': got {got!r}, expected {exp!r}'))
    if not ok: FAILS.append(name)


def login(u, p): return call('/auth/login', method='POST', body={'username': u, 'password': p})[1]['token']


one = lambda sql, *a: db.execute(sql, a).fetchone()[0]
SA, SC = login('shini.admin', 'Admin-Pass-1'), login('shini.cashier', 'Cashier-Pass-1')
NA, NC = login('neshan.admin', 'Admin-Pass-1'), login('neshan.cashier', 'Cashier-Pass-1')
SHINI = one("select store_id from users where username='shini.admin'")
NESHAN = one("select store_id from users where username='neshan.admin'")

today = dt.date.today()
m0 = today.replace(day=1)                      # first day of this month
p_last = m0 - dt.timedelta(days=1)             # last day of the month before
y0 = today.replace(month=1, day=1)             # 1 January of this year


def product(token, name, barcode, price, cost):
    s, b = call('/products', token, 'POST', {'name': name, 'barcode': barcode, 'category': 'dairy', 'sellingPrice': price,
                                             'quantity': 200, 'minStock': 1, 'supplierName': 'E2E Supplier', 'costPrice': cost})
    assert s == 201, (s, b); return b['productId']


def sale(token, pid, units, discount=None):
    s, b = call('/sales', token, 'POST', {'items': [{'productId': pid, 'quantity': units}], 'paymentMethod': 'CASH',
                                         'discountType': 'PERCENT' if discount else None, 'discountValue': discount})
    assert s == 201, (s, b); return b['saleId']


def date_sale(sale_id, text):
    db.execute('update sales set completed_at=?, created_at=?, updated_at=? where sale_id=?', (text, text, text, sale_id))


milk = product(SA, 'E2E Boundary Milk', '7299900000011', 10, 6.0)
s1 = sale(SC, milk, 1)                                                     # last second of the month before
item = one('select sale_item_id from sale_items where sale_id=?', s1)
st, ret = call('/returns', SC, 'POST', {'saleId': s1, 'saleItemId': item, 'quantity': 1, 'reason': 'E2E'})
assert st == 201, (st, ret)
date_sale(s1, f'{p_last}T23:59:59')
db.execute('update returns set return_date=? where return_id=?', (f'{m0}T00:00', ret['returnId']))   # midnight return
date_sale(sale(SC, milk, 2, 10), f'{m0}T00:00')                            # midnight sale, 10 % discount
date_sale(sale(SC, milk, 1), f'{m0}T00:00:01')                             # one second later
date_sale(sale(SC, milk, 1), f'{y0}T00:00')                                # first second of the year
if y0 != m0:
    date_sale(sale(SC, milk, 1), f'{y0 - dt.timedelta(days=1)}T23:59:59')  # last second of last year
nmilk = product(NA, 'E2E Boundary Milk', '7299900000011', 10, 6.0)
date_sale(sale(NC, nmilk, 5), f'{m0}T00:00')                                # Neshan, midnight


# ── independent expectations: date part of the stored text only ────────────────────────────────
def expected(store, key_len, key):
    """Figures of the sales / returns whose stored time starts with `key` (YYYY, YYYY-MM or YYYY-MM-DD)."""
    s = db.execute("""select count(*), coalesce(sum(total),0), coalesce(sum(discount),0), coalesce(sum(tax),0)
                      from sales where store_id=? and status='COMPLETED' and substr(completed_at,1,?)=?""",
                   (store, key_len, key)).fetchone()
    units = one("""select coalesce(sum(si.quantity),0) from sale_items si join sales s on s.sale_id=si.sale_id
                   where s.store_id=? and s.status='COMPLETED' and substr(s.completed_at,1,?)=?""", store, key_len, key)
    r = db.execute("""select count(*), coalesce(sum(refund_amount),0), coalesce(sum(quantity),0)
                      from returns where store_id=? and substr(return_date,1,?)=?""", (store, key_len, key)).fetchone()
    rev = one("""select coalesce(sum(si.line_total*(s.subtotal-s.discount)/s.subtotal),0) from sale_items si
                 join sales s on s.sale_id=si.sale_id where s.store_id=? and s.status='COMPLETED' and s.subtotal>0
                 and substr(s.completed_at,1,?)=?""", store, key_len, key) - one(
              """select coalesce(sum(si.line_total*(s.subtotal-s.discount)/s.subtotal*r.quantity/si.quantity),0)
                 from returns r join sale_items si on si.sale_item_id=r.sale_item_id join sales s on s.sale_id=r.sale_id
                 where r.store_id=? and s.subtotal>0 and substr(r.return_date,1,?)=?""", store, key_len, key)
    cost = one("""select coalesce(sum(si.unit_cost*si.quantity),0) from sale_items si join sales s on s.sale_id=si.sale_id
                  where s.store_id=? and s.status='COMPLETED' and s.subtotal>0 and substr(s.completed_at,1,?)=?""",
               store, key_len, key) - one(
               """select coalesce(sum(si.unit_cost*r.quantity),0) from returns r join sale_items si on si.sale_item_id=r.sale_item_id
                  join sales s on s.sale_id=r.sale_id where r.store_id=? and s.subtotal>0 and substr(r.return_date,1,?)=?""",
               store, key_len, key)
    return {'invoices': s[0], 'gross': round(s[1], 2), 'discount': round(s[2], 2), 'tax': round(s[3], 2),
            'refunds': round(r[1], 2), 'net': round(s[1] - r[1], 2), 'returns': r[0], 'units': units, 'unitsReturned': r[2],
            'revenueExTax': round(rev, 2), 'cost': round(cost, 2)}


def month_range(first):
    nxt = (first.replace(day=28) + dt.timedelta(days=4)).replace(day=1)
    return first, nxt - dt.timedelta(days=1)


def check_month(token, store, label, first):
    a, b = month_range(first)
    st, s = call(f'/reports/summary?from={a}&to={b}', token)
    e = expected(store, 7, first.strftime('%Y-%m'))
    for k_api, k_exp in (('invoiceCount', 'invoices'), ('grossSales', 'gross'), ('discounts', 'discount'), ('tax', 'tax'),
                         ('refunds', 'refunds'), ('netSales', 'net'), ('returnCount', 'returns'), ('unitsSold', 'units'),
                         ('unitsReturned', 'unitsReturned')):
        exp = float(e[k_exp]) if isinstance(s[k_api], float) else e[k_exp]
        chk(f'{label} {first:%Y-%m} card {k_api}', s[k_api], exp)
    chk(f'{label} {first:%Y-%m} profit revenue without tax', s['profit']['revenueExTax'], float(e['revenueExTax']))
    chk(f'{label} {first:%Y-%m} profit cost of goods', s['profit']['costOfGoods'], float(e['cost']))
    st, days = call(f'/reports/daily-sales?from={a}&to={b}', token)
    for d in days:
        de = expected(store, 10, d['date'])
        if d['netSales'] != de['net'] or d['invoices'] != de['invoices']:
            chk(f'{label} daily chart {d["date"]}', (d['netSales'], d['invoices']), (de['net'], de['invoices']))
    chk(f'{label} {first:%Y-%m} daily chart: every day = its own records', True, True)
    chk(f'{label} {first:%Y-%m} daily chart total = card', round(sum(d['netSales'] for d in days), 2), float(e['net']))
    st, top = call(f'/reports/top-products?from={a}&to={b}', token)
    chk(f'{label} {first:%Y-%m} best sellers: total units = net units', top['totalUnits'], e['units'] - e['unitsReturned'])
    chk(f'{label} {first:%Y-%m} best sellers: total value = net sales', top['totalSalesValue'], float(e['net']))
    return s, e


cur, cur_e = check_month(SA, SHINI, 'Shini', m0)
prev, prev_e = check_month(SA, SHINI, 'Shini', p_last.replace(day=1))
chk('Shini: the midnight sale and return are in THIS month (2 of its invoices are the test sales at 00:00 / 00:00:01)',
    one(f"select count(*) from sales where store_id=? and completed_at in ('{m0}T00:00','{m0}T00:00:01')", SHINI), 2)
chk('Shini: previous period of this month = the month before (net)', cur['previous']['netSales'], float(prev_e['net']))
chk('Shini: previous period of this month = the month before (invoices)', cur['previous']['invoiceCount'], prev_e['invoices'])
check_month(NA, NESHAN, 'Neshan', m0)
chk('Neshan: its midnight sale is counted in its own month',
    call(f'/reports/summary?from={m0}&to={month_range(m0)[1]}', NA)[1]['unitsSold'] >= 5, True)
chk('Shini never counts Neshan\'s sale (store id in the URL is ignored)',
    call(f'/reports/summary?from={m0}&to={month_range(m0)[1]}&storeId={NESHAN}', SA)[1]['invoiceCount'], cur_e['invoices'])

for year in sorted({y0.year, (y0 - dt.timedelta(days=1)).year}):
    st, ms = call(f'/reports/monthly-sales?year={year}', SA)
    for m in ms['months']:
        e = expected(SHINI, 7, f'{year}-{m["month"]:02d}')
        if m['netSales'] != e['net'] or m['invoices'] != e['invoices']:
            chk(f'year chart {year}-{m["month"]:02d}', (m['netSales'], m['invoices']), (e['net'], e['invoices']))
    chk(f'year chart {year}: every month = its own records', True, True)
    chk(f'year chart {year}: total = sum of its records', ms['totalNetSales'], float(expected(SHINI, 4, str(year))['net']))

# Guardian 7-day chart = Sales Analytics = the records of each day.
call('/guardian/status', SA, 'PUT', {'enabled': True})
st, g = call('/guardian/dashboard', SA)
st, days7 = call(f'/reports/daily-sales?from={today - dt.timedelta(days=6)}&to={today}', SA)
chk('Guardian 7-day chart = Sales Analytics daily figures', [p['netSales'] for p in g['todaySales']['last7Days']],
    [d['netSales'] for d in days7])
chk('Guardian 7-day chart = records of each day', [p['netSales'] for p in g['todaySales']['last7Days']],
    [expected(SHINI, 10, str(today - dt.timedelta(days=6 - i)))['net'] for i in range(7)])
call('/guardian/status', SA, 'PUT', {'enabled': False})

json.dump({'month': str(m0)[:7], 'netSales': cur_e['net'], 'invoices': cur_e['invoices']},
          open(os.path.join(os.environ['CLIXSELL_E2E_OUT'], 'reports_expected.json'), 'w'))
print(f'\nREPORTS BOUNDARY PASS {R["pass"]} FAIL {R["fail"]}')
for f in FAILS: print('  FAILED:', f)
sys.exit(1 if R['fail'] else 0)

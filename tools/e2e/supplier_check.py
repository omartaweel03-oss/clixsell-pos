"""Supplier Management (Purchase Orders Phase 1) through the REAL backend on a disposable demo
database, checked against the database after every step. Two stores. Run via run.sh (suite: supplier).
Writes supplier_expected.json for supplier_ui.js."""
from safety import require_disposable_db  # stops unless this is the disposable test database
import json, os, sqlite3, sys, urllib.request

A = 'http://localhost:8080/api'
DB = require_disposable_db(sys.argv[1])
db = sqlite3.connect(DB); db.isolation_level = None
R = {'pass': 0, 'fail': 0}


def call(p, t=None, m='GET', b=None, raw=None):
    data = raw.encode() if raw is not None else (json.dumps(b).encode() if b is not None else None)
    r = urllib.request.Request(A + p, method=m, data=data,
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

# ── A) database V6 is in place and the demo data kept its suppliers ─────────────────────────────
chk('A', 'Flyway V6 applied', one("select count(*) from flyway_schema_history where version='6' and success=1"), 1)
chk('A', 'V6 triggers present', one("select count(*) from sqlite_master where type='trigger' and name like 'trg_%'"), 20)
chk('A', 'every demo supplier active', one('select count(*) from suppliers where active<>1'), 0)
chk('A', 'no purchase orders exist (Phase 1 has no order feature)', one('select count(*) from purchase_orders'), 0)
try:
    db.execute('delete from suppliers where supplier_id=(select min(supplier_id) from suppliers)'); deleted = True
except sqlite3.DatabaseError as e:
    deleted = 'SUPPLIER_NO_DELETE' in str(e)
    deleted = not deleted
chk('A', 'database refuses deleting a supplier', deleted, False)

# ── B) roles ─────────────────────────────────────────────────────────────────────────────────────
for name, t, exp in (('sysadmin', SYS, 403), ('cashier', SC, 403), ('no token', None, 401)):
    chk('B', f'{name} GET /suppliers', call('/suppliers', t)[0], exp)
    chk('B', f'{name} POST /suppliers', call('/suppliers', t, 'POST', {'name': 'Role Test'})[0], exp)
chk('B', 'no supplier created by refused calls', one("select count(*) from suppliers where name='Role Test'"), 0)

# ── C) list = database, per store ───────────────────────────────────────────────────────────────
for store, t in ((S, SA), (N, NA)):
    s, lst = call('/suppliers', t)
    dbrows = q('''select s.supplier_id, s.name, s.active, (select count(*) from products p where p.supplier_id=s.supplier_id)
                  from suppliers s where s.store_id=? order by s.name collate nocase''', store)
    chk('C', f'store {store}: list = database (id, name, active, product count)',
        sorted((x['supplierId'], x['name'], 1 if x['active'] else 0, x['productCount']) for x in lst), sorted(dbrows))

# ── D) create, validate, duplicate, strict body ─────────────────────────────────────────────────
before = one('select count(*) from suppliers')
s, b = call('/suppliers', SA, 'POST', {'name': '  E2E   Fresh  Farms ', 'phone': '02-2981234',
                                       'whatsappNumber': '+970 59 900 1111', 'address': 'Jenin'})
chk('D', 'create 201', s, 201); FRESH = b['supplierId']
chk('D', 'created row in DB (normalized name, digits only, active, store 1)',
    q('select name, phone, whatsapp_number, address, active, store_id from suppliers where supplier_id=?', FRESH),
    [('E2E Fresh Farms', '02-2981234', '970599001111', 'Jenin', 1, S)])
for bad, field in (({'name': 'Local Number', 'whatsappNumber': '0599001111'}, 'whatsappNumber'),
                   ({'name': 'Short', 'whatsappNumber': '12345'}, 'whatsappNumber'),
                   ({'name': 'X'}, 'name'), ({'name': 'Bad Phone', 'phone': 'call me'}, 'phone')):
    s, b = call('/suppliers', SA, 'POST', bad)
    chk('D', f'invalid {field} → 400 with field error', (s, field in (b or {}).get('fieldErrors', {})), (400, True))
s, b = call('/suppliers', SA, 'POST', {'name': 'e2e fresh   FARMS'})
chk('D', 'duplicate after normalizing → 409 DUPLICATE_SUPPLIER', (s, b['code']), (409, 'DUPLICATE_SUPPLIER'))
s, b = call('/suppliers', SA, 'POST', {'name': 'Sneaky Co', 'storeId': N})
chk('D', 'storeId in body → 400 UNKNOWN_FIELD', (s, b['code']), (400, 'UNKNOWN_FIELD'))
s, b = call(f'/suppliers/{FRESH}', SA, 'PUT', {'name': 'E2E Fresh Farms', 'active': False})
chk('D', 'active in edit body → 400 UNKNOWN_FIELD', (s, b['code']), (400, 'UNKNOWN_FIELD'))
chk('D', 'only the valid supplier was added', one('select count(*) from suppliers'), before + 1)

# ── E) edit ─────────────────────────────────────────────────────────────────────────────────────
s, b = call(f'/suppliers/{FRESH}', SA, 'PUT', {'name': 'E2E Fresh Farms', 'phone': None,
                                               'whatsappNumber': '00970599001112', 'address': 'Jenin, main road'})
chk('E', 'edit 200', s, 200)
chk('E', 'edited row in DB', q('select name, phone, whatsapp_number, address from suppliers where supplier_id=?', FRESH),
    [('E2E Fresh Farms', None, '970599001112', 'Jenin, main road')])

# ── F) isolation ────────────────────────────────────────────────────────────────────────────────
for m, p, body in (('GET', f'/suppliers/{FRESH}', None), ('PUT', f'/suppliers/{FRESH}', {'name': 'Hacked'}),
                   ('PUT', f'/suppliers/{FRESH}/status', {'active': False})):
    chk('F', f'Neshan admin {m} {p} → 404', call(p, NA, m, body)[0], 404)
chk('F', 'Neshan list has no Shini supplier', any(x['supplierId'] == FRESH for x in call('/suppliers', NA)[1]), False)
chk('F', 'Shini supplier unchanged', q('select name, active from suppliers where supplier_id=?', FRESH), [('E2E Fresh Farms', 1)])
s, b = call('/suppliers', NA, 'POST', {'name': 'E2E Fresh Farms', 'whatsappNumber': '970599009999'})
chk('F', 'same name in Neshan is a separate supplier', (s, one('select store_id from suppliers where supplier_id=?', b['supplierId'])), (201, N))
NESHAN_FRESH = b['supplierId']

# ── G) product form rules and POS with an inactive supplier ────────────────────────────────────
prod = lambda name, bc, sup: {'name': name, 'barcode': bc, 'category': 'freshProduce', 'sellingPrice': 6, 'quantity': 10,
                              'minStock': 2, 'supplierName': sup, 'costPrice': 4}
s, b = call('/products', SA, 'POST', prod('E2E Cucumbers 1kg', '7298881000011', 'E2E Fresh Farms'))
chk('G', 'product with the supplier → 201, linked by id', (s, b['supplierId']), (201, FRESH)); CUC = b['productId']
s, b = call(f'/suppliers/{FRESH}/status', SA, 'PUT', {'active': False})
chk('G', 'deactivate → 200', (s, b['active']), (200, False))
chk('G', 'DB: inactive, product still linked', q('select s.active, p.supplier_id from products p join suppliers s on s.supplier_id=p.supplier_id where p.product_id=?', CUC),
    [(0, FRESH)])
s, b = call('/products', SA, 'POST', prod('E2E Lettuce', '7298881000028', 'e2e fresh farms'))
chk('G', 'new product with inactive supplier → 409 SUPPLIER_INACTIVE', (s, b['code']), (409, 'SUPPLIER_INACTIVE'))
chk('G', 'no product saved, supplier still inactive',
    (one("select count(*) from products where barcode='7298881000028'"), one('select active from suppliers where supplier_id=?', FRESH)), (0, 0))
chk('G', 'no near-duplicate supplier created', one("select count(*) from suppliers where store_id=1 and lower(name) like 'e2e fresh%'"), 1)
s, b = call(f'/products/{CUC}', SA, 'PUT', {**prod('E2E Cucumbers 1kg', '7298881000011', 'E2E Fresh Farms'), 'sellingPrice': 6.5})
chk('G', 'editing a product already linked to it → 200', s, 200)
qty0 = one('select quantity from products where product_id=?', CUC)
s, b = call('/sales', SC, 'POST', {'items': [{'productId': CUC, 'quantity': 2}], 'paymentMethod': 'CASH'})
chk('G', 'POS still sells a product of an inactive supplier', s, 201)
chk('G', 'stock reduced by the sale', one('select quantity from products where product_id=?', CUC), qty0 - 2)
s, b = call(f'/suppliers/{FRESH}/status', SA, 'PUT', {'active': True})
chk('G', 'only Supplier Management reactivates', (s, one('select active from suppliers where supplier_id=?', FRESH)), (200, 1))
s, b = call('/products', SA, 'POST', prod('E2E Lettuce', '7298881000028', 'E2E Fresh Farms'))
chk('G', 'after reactivation the product is accepted', s, 201)
existing = q('select s.name, s.supplier_id from suppliers s where s.store_id=1 and s.supplier_id<>? order by s.supplier_id limit 1', FRESH)[0]
s, b = call('/products', SA, 'POST', prod('E2E Existing Supplier Item', '7298881000035', existing[0]))
chk('G', f'existing demo supplier "{existing[0]}" still links as before', (s, b['supplierId']), (201, existing[1]))
s, b = call('/products', SA, 'POST', prod('E2E New Supplier Item', '7298881000042', '  E2E   Brand New Co '))
chk('G', 'a new name on the product form creates one normalized active supplier',
    (s, q('select name, active from suppliers where supplier_id=?', b['supplierId'])), (201, [('E2E Brand New Co', 1)]))

# ── H) details ──────────────────────────────────────────────────────────────────────────────────
s, b = call(f'/suppliers/{FRESH}', SA)
chk('H', 'details list the products of the supplier', sorted(x['name'] for x in b['products']), ['E2E Cucumbers 1kg', 'E2E Lettuce'])
chk('H', 'details product count = DB', b['productCount'], one('select count(*) from products where supplier_id=?', FRESH))

json.dump({'fresh': FRESH, 'neshanFresh': NESHAN_FRESH, 'suppliersBefore': one('select count(*) from suppliers')},
          open(os.path.join(os.environ['CLIXSELL_E2E_OUT'], 'supplier_expected.json'), 'w'))
print(f"\nSUPPLIER CHECK PASS {R['pass']} FAIL {R['fail']}")
sys.exit(1 if R['fail'] else 0)

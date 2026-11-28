"""Purchase Orders Phase 3 (supplier communication) through the REAL backend (real HTTP, two stores)
on a disposable demo database, checked against the database after each step. The expected message
lines are rebuilt here from the order's snapshot rows. No WhatsApp is ever opened and no message is
sent: links are only decoded as text. Run via run.sh (suite: communication)."""
from safety import require_disposable_db  # stops unless this is the disposable test database
import datetime as dt, json, sqlite3, sys, threading, urllib.parse, urllib.request, uuid

A = 'http://localhost:8080/api'
DB = require_disposable_db(sys.argv[1])
db = sqlite3.connect(DB, timeout=30); db.isolation_level = None
R = {'pass': 0, 'fail': 0}


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
PO = '/purchase-orders/'
uid = lambda: str(uuid.uuid4())
events = lambda o: [x[0] for x in q('select event_type from purchase_order_events where order_id=? order by event_id', o)]
version = lambda o: one('select version from purchase_orders where order_id=?', o)
status_of = lambda o: one('select status from purchase_orders where order_id=?', o)
FIN_SQL = ['select product_id, quantity, selling_price, cost_price, supplier_id from products order by product_id',
           'select sale_id, status, total from sales order by sale_id',
           'select sale_item_id, quantity, unit_cost, returned_quantity from sale_items order by sale_item_id',
           'select return_id, quantity, refund_amount from returns order by return_id']
STORES_SQL = 'select * from stores order by store_id'


def stores_without_counter():
    cols = [r[1] for r in q('pragma table_info(stores)')]
    return [tuple(v for c, v in zip(cols, row) if c != 'next_purchase_order_number') for row in q(STORES_SQL)]


# ── A) schema ───────────────────────────────────────────────────────────────────────────────
chk('A', 'Flyway V6 and V7 applied', (one("select count(*) from flyway_schema_history where version='6' and success=1"),
                                      one("select count(*) from flyway_schema_history where version='7' and success=1")), (1, 1))
chk('A', 'the 20 V6 triggers still present', one("select count(*) from sqlite_master where type='trigger' and name in ('trg_po_actor_insert','trg_po_actor_update','trg_po_approve','trg_po_cancel_flag','trg_po_final','trg_po_identity','trg_po_insert','trg_po_message','trg_po_no_delete','trg_po_release','trg_po_set_once','trg_po_transition','trg_po_version','trg_poe_actor','trg_poe_no_delete','trg_poe_no_update','trg_poi_delete','trg_poi_insert','trg_poi_update','trg_suppliers_no_delete')"), 20)

# ── B) setup through the real API (Shini + Neshan with the same names) ──────────────────────
s, sp = call('/suppliers', SA, 'POST', {'name': 'E2E Dairy WA', 'whatsappNumber': '+970 59 977 7001'})
SUP = sp['supplierId']
names = [('E2E لبنة 500غ', '7290100000011', 9.5), ('E2E Milk 1L 😀', '7290100000028', 3.2), ('E2E Cheese', '7290100000035', None),
         ('E2E Butter', '7290100000042', 7.0), ('E2E Cream', '7290100000059', 4.0), ('E2E Yogurt', '7290100000066', 2.5),
         ('E2E Eggs', '7290100000073', 11.0), ('E2E Ghee', '7290100000080', 20.0)]
P = []
for nm, bc, cost in names:
    s, p = call('/products', SA, 'POST', {'name': nm, 'barcode': bc, 'category': 'dairy', 'sellingPrice': 14, 'quantity': 3,
                                          'minStock': 5, 'supplierName': 'E2E Dairy WA', **({'costPrice': cost} if cost else {})})
    P.append(p['productId'])
s, nsp = call('/suppliers', NA, 'POST', {'name': 'E2E Dairy WA', 'whatsappNumber': '970599777009'})
s, np_ = call('/products', NA, 'POST', {'name': 'E2E لبنة 500غ', 'barcode': '7290100000011', 'category': 'dairy', 'sellingPrice': 14,
                                        'quantity': 3, 'minStock': 5, 'supplierName': 'E2E Dairy WA'})
s, nord = call('/purchase-orders', NA, 'POST', {'clientRequestId': uid(), 'supplierId': nsp['supplierId'], 'source': 'MANUAL',
                                                'items': [{'productId': np_['productId'], 'quantity': 2}]})
NORD = nord['orderId']
fin_before = [q(x) for x in FIN_SQL]
stores_before = stores_without_counter()
counter_before = one('select next_purchase_order_number from stores where store_id=1')
activity_before = one('select count(*) from activity_log')
summary_before = call('/reports/summary?from=2020-01-01&to=2035-12-31', SA)[1]


def create(items, note=None, acks=None):
    s, b = call('/purchase-orders', SA, 'POST', {'clientRequestId': uid(), 'supplierId': SUP, 'source': 'MANUAL', 'note': note,
                                                 'items': [{'productId': p, 'quantity': n} for p, n in items],
                                                 **({'acknowledgedCautions': acks} if acks else {})})
    return s, b


def approved(items, note=None):
    s, b = create(items, note)
    call(f'{PO}{b["orderId"]}/approve', SA, 'POST', {'version': 0})
    return b['orderId']


def generated(items, lang='ar'):
    o = approved(items)
    pv = call(f'{PO}{o}/message-preview?language={lang}', SA)[1]
    s, g = call(f'{PO}{o}/message', SA, 'POST', {'version': 1, 'language': lang, 'previewToken': pv['previewToken']})
    assert s == 200, g
    return o


# ── C) preview = the order's snapshots, nothing financial, nothing saved ─────────────────────
O1 = approved([(P[0], 29), (P[1], 6)], note='E2E-INTERNAL-NOTE')
s, pv = call(f'{PO}{O1}/message-preview', SA)
text = pv['text']
num = one('select order_number from purchase_orders where order_id=?', O1)
lines = q('select product_name, barcode, quantity from purchase_order_items where order_id=? order by item_id', O1)
expected_lines = [f'{i}) ⁨{n}⁩ | الباركود: ⁨{b}⁩ | الكمية: ⁨{qn}⁩' for i, (n, b, qn) in enumerate(lines, 1)]
chk('C', 'preview 200, Arabic by default', (s, pv['language']), (200, 'ar'))
chk('C', 'every line = snapshot name, barcode, quantity (rebuilt from the database)',
    [l for l in text.split('\n') if l[:1].isdigit()], expected_lines)
chk('C', 'order number, store, supplier, date in the message',
    all(x in text for x in (num, one('select name from stores where store_id=1'), 'E2E Dairy WA',
                            dt.date.today().isoformat())), True)
costs = [str(c) for _, _, c in names if c] + ['₪', 'E2E-INTERNAL-NOTE']
chk('C', 'no cost, price symbol or internal note in the message', [c for c in costs if c in text], [])
chk('C', 'reported length = Python code points = SQLite length()', (pv['length']['length'], len(text), one('select length(?)', text)),
    (len(text), len(text), len(text)))
chk('C', 'preview saved nothing', (status_of(O1), version(O1), one('select message_text from purchase_orders where order_id=?', O1),
                                   events(O1)), ('APPROVED', 1, None, ['CREATED', 'APPROVED']))
chk('C', 'no link in the preview', 'wa.me' in json.dumps(pv), False)

# ── D) generate ─────────────────────────────────────────────────────────────────────────────
s, bad = call(f'{PO}{O1}/message', SA, 'POST', {'version': 1, 'language': 'ar', 'previewToken': '0' * 64})
chk('D', 'wrong previewToken → 409 PREVIEW_CHANGED, nothing saved', (s, bad['code'], status_of(O1)), (409, 'PREVIEW_CHANGED', 'APPROVED'))
s, g = call(f'{PO}{O1}/message', SA, 'POST', {'version': 1, 'language': 'ar', 'previewToken': pv['previewToken']})
row = q('select status, message_text, message_language, message_whatsapp, version from purchase_orders where order_id=?', O1)[0]
chk('D', 'generate → MESSAGE_GENERATED with exactly the previewed text, number 970599777001',
    (s, row), (200, ('MESSAGE_GENERATED', text, 'ar', '970599777001', 2)))
chk('D', 'one MESSAGE_GENERATED event saying not sent', (events(O1)[-1],
    json.loads(one("select details from purchase_order_events where order_id=? and event_type='MESSAGE_GENERATED'", O1))['sent']),
    ('MESSAGE_GENERATED', False))
chk('D', 'again → 409 INVALID_TRANSITION', call(f'{PO}{O1}/message', SA, 'POST', {'version': 2, 'language': 'ar',
                                                                                'previewToken': pv['previewToken']})[1]['code'], 'INVALID_TRANSITION')
call(f'/suppliers/{SUP}', SA, 'PUT', {'name': 'E2E Dairy WA', 'whatsappNumber': '970599777002'})
s, m = call(f'{PO}{O1}/message', SA)
chk('D', 'supplier number changed later: message keeps its number, warns', (m['whatsappNumber'], m['numberChangedSinceGeneration'],
                                                                         m['currentSupplierWhatsapp'], m['text'] == text),
    ('970599777001', True, '970599777002', True))
try:
    db.execute("update purchase_orders set message_text='x', version=version+1 where order_id=?", (O1,)); rewritten = True
except sqlite3.DatabaseError as e:
    rewritten = 'PO_FIELD_SET_ONCE' not in str(e)
chk('D', 'database refuses rewriting the message', rewritten, False)

# ── E) client reports: copy, opening request and result ─────────────────────────────────────
c1 = uid()
s1, r1 = call(f'{PO}{O1}/message-copied', SA, 'POST', {'clientActionId': c1})
s2, r2 = call(f'{PO}{O1}/message-copied', SA, 'POST', {'clientActionId': c1})
chk('E', 'copy recorded once, never verified', (s1, r1['verified'], r1['duplicate'], s2, r2['duplicate'], events(O1).count('MESSAGE_COPIED')),
    (200, False, False, 200, True, 1))
chk('E', 'open without the confirmation → 400', call(f'{PO}{O1}/whatsapp-open-requested', SA, 'POST', {'clientActionId': uid()})[0], 400)
o1 = uid()
s, op = call(f'{PO}{O1}/whatsapp-open-requested', SA, 'POST', {'clientActionId': o1, 'confirmed': True})
url = op['whatsappUrl']
chk('E', 'link = wa.me/<stored number>?text=<stored message>', (url.split('?')[0],
    urllib.parse.unquote(url.split('?text=', 1)[1]) == text, '+' in url.split('?text=', 1)[1]), ('https://wa.me/970599777001', True, False))
s, rr = call(f'{PO}{O1}/whatsapp-open-result', SA, 'POST', {'clientActionId': o1, 'result': 'OPENED'})
s3, rc = call(f'{PO}{O1}/whatsapp-open-result', SA, 'POST', {'clientActionId': o1, 'result': 'FAILED'})
chk('E', 'request + client result = two events with the same id; a different result is refused',
    ([x[0] for x in q("select json_extract(details,'$.phase') from purchase_order_events where order_id=? and "
                      "event_type='WHATSAPP_OPEN_REQUESTED' order by event_id", O1)], s3, rc['code']),
    (['REQUESTED', 'CLIENT_REPORTED'], 409, 'CLIENT_ACTION_CONFLICT'))
chk('E', 'reports never change the order or record sending', (status_of(O1), version(O1),
                                                            one('select sent_marked_at from purchase_orders where order_id=?', O1)),
    ('MESSAGE_GENERATED', 2, None))
chk('E', 'every report marked verified=false', one("select count(*) from purchase_order_events where order_id=? and "
                                                   "event_type in ('MESSAGE_COPIED','WHATSAPP_OPEN_REQUESTED') and "
                                                   "json_extract(details,'$.verified')=0", O1), 3)

# ── F) manual records → CLOSED (not receiving) ──────────────────────────────────────────────
qty_before = one('select quantity from products where product_id=?', P[0])
chk('F', 'mark-sent needs the confirmation', call(f'{PO}{O1}/mark-sent', SA, 'POST', {'version': 2})[0], 400)
s, x = call(f'{PO}{O1}/mark-sent', SA, 'POST', {'version': 2, 'confirmSentInWhatsapp': True})
chk('F', 'mark-sent → SENT, recorded manually, not verified', (s, x['status'],
    json.loads(one("select details from purchase_order_events where order_id=? and event_type='MARKED_SENT'", O1))['recordedManually'],
    json.loads(one("select details from purchase_order_events where order_id=? and event_type='MARKED_SENT'", O1))['deliveryConfirmed']),
    (200, 'SENT', True, False))
s, x = call(f'{PO}{O1}/supplier-confirmation', SA, 'POST', {'version': 3, 'confirmSupplierConfirmed': True, 'note': 'Phone call'})
chk('F', 'supplier confirmation → CONFIRMED, note stored', (s, x['status'],
    one('select confirmation_note from purchase_orders where order_id=?', O1)), (200, 'CONFIRMED', 'Phone call'))
chk('F', 'close needs confirmNoReceiving', call(f'{PO}{O1}/close', SA, 'POST', {'version': 4})[0], 400)
s, x = call(f'{PO}{O1}/close', SA, 'POST', {'version': 4, 'confirmNoReceiving': True})
chk('F', 'close → CLOSED, reservations released, stock unchanged', (s, x['status'],
    one('select count(*) from purchase_order_items where order_id=? and open_product_id is not null', O1),
    one('select quantity from products where product_id=?', P[0])), (200, 'CLOSED', 0, qty_before))
chk('F', 'closed: no link, no new report', (call(f'{PO}{O1}/whatsapp-open-requested', SA, 'POST', {'clientActionId': uid(), 'confirmed': True})[1]['code'],
                                          call(f'{PO}{O1}/message', SA)[1]['actionable']), ('ORDER_FINAL', False))
chk('F', 'events in order', events(O1), ['CREATED', 'APPROVED', 'MESSAGE_GENERATED', 'MESSAGE_COPIED', 'WHATSAPP_OPEN_REQUESTED',
                                         'WHATSAPP_OPEN_REQUESTED', 'MARKED_SENT', 'SUPPLIER_CONFIRMATION_RECORDED', 'CLOSED'])
s, cv = create([(P[0], 4)])
chk('F', 'reordering a closed order product needs the RECENTLY_CLOSED acknowledgment', (s, cv['code'], cv['details']['cautions'][0]['type']),
    (409, 'REORDER_CAUTION_ACK_REQUIRED', 'RECENTLY_CLOSED'))

# ── G) cancel after the message, warnings, supplier acceptance ──────────────────────────────
O2 = generated([(P[2], 5)])
chk('G', 'cancel without contactStatus → 409 CONTACT_STATUS_REQUIRED', call(f'{PO}{O2}/cancel', SA, 'POST', {'version': 2, 'reason': 'Changed'})[1]['code'],
    'CONTACT_STATUS_REQUIRED')
chk('G', 'contactStatus SENT → 409 RECORD_SENT_FIRST', call(f'{PO}{O2}/cancel', SA, 'POST', {'version': 2, 'reason': 'Changed',
                                                                                          'contactStatus': 'SENT'})[1]['code'], 'RECORD_SENT_FIRST')
s, x = call(f'{PO}{O2}/cancel', SA, 'POST', {'version': 2, 'reason': 'Changed', 'contactStatus': 'UNKNOWN'})
chk('G', 'UNKNOWN → CANCELED, flag 0, never marked sent', (s, q('select status, canceled_after_contact, sent_marked_at from purchase_orders '
                                                              'where order_id=?', O2)[0], 'MARKED_SENT' in events(O2)),
    (200, ('CANCELED', 0, None), False))
cands = lambda: {p['productId']: [c['type'] for c in p['cautions']] for p in call(f'/purchase-candidates?supplierId={SUP}', SA)[1]['products']}
chk('G', 'UNKNOWN warns as CONTACT_UNKNOWN', cands()[P[2]], ['CONTACT_UNKNOWN'])
O3 = generated([(P[3], 5)])
call(f'{PO}{O3}/message-copied', SA, 'POST', {'clientActionId': uid()})
chk('G', 'NOT_SENT needs confirmNotSent', call(f'{PO}{O3}/cancel', SA, 'POST', {'version': 2, 'reason': 'Changed', 'contactStatus': 'NOT_SENT'})[0], 400)
call(f'{PO}{O3}/cancel', SA, 'POST', {'version': 2, 'reason': 'Changed', 'contactStatus': 'NOT_SENT', 'confirmNotSent': True})
chk('G', 'NOT_SENT after a copy → DECLARED_NOT_SENT warning', cands()[P[3]], ['DECLARED_NOT_SENT'])
O4 = generated([(P[4], 5)])
call(f'{PO}{O4}/cancel', SA, 'POST', {'version': 2, 'reason': 'Changed', 'contactStatus': 'NOT_SENT', 'confirmNotSent': True})
# S3 (final corrections): "not sent" is only a declaration — it ALWAYS warns, even with no copy / opening
chk('G', 'NOT_SENT without copy/opening → still a DECLARED_NOT_SENT warning (S3)', cands()[P[4]], ['DECLARED_NOT_SENT'])
s, x = create([(P[4], 2)])
chk('G', 'S3: reorder of that product needs the acknowledgment', (s, x.get('code'), [c['type'] for c in x['details']['cautions']]),
    (409, 'REORDER_CAUTION_ACK_REQUIRED', ['DECLARED_NOT_SENT']))
s, x = create([(P[4], 2)], acks=[{'productId': P[4], 'orderId': O4}])
chk('G', 'S3: with it → created, acknowledgment kept in the history', (s, 'DECLARED_NOT_SENT' in one(
    "select details from purchase_order_events where order_id=? and event_type='CREATED'", x['orderId'])), (201, True))
O4P = x['orderId']
chk('G', 'S3: that new order canceled before any message (no contactStatus needed)',
    call(f'{PO}{O4P}/cancel', SA, 'POST', {'version': 0, 'reason': 'E2E not needed'})[0], 200)
O5 = generated([(P[5], 5)])
call(f'{PO}{O5}/mark-sent', SA, 'POST', {'version': 2, 'confirmSentInWhatsapp': True})
chk('G', 'cancel after sending without the acknowledgment → 409', call(f'{PO}{O5}/cancel', SA, 'POST', {'version': 3, 'reason': 'Too slow'})[1]['code'],
    'ACK_SUPPLIER_NOT_NOTIFIED')
s, x = call(f'{PO}{O5}/cancel', SA, 'POST', {'version': 3, 'reason': 'Too slow', 'acknowledgeSupplierNotNotified': True})
chk('G', 'with it → CANCELED after contact, warns', (s, one('select canceled_after_contact from purchase_orders where order_id=?', O5), cands()[P[5]]),
    (200, 1, ['CANCELED_AFTER_CONTACT']))
chk('G', 'supplier acceptance needs a real note', call(f'{PO}{O2}/supplier-cancellation-recorded', SA, 'POST',
                                                     {'confirmSupplierAccepted': True, 'channel': 'PHONE', 'note': '  '})[0], 400)
s, x = call(f'{PO}{O2}/supplier-cancellation-recorded', SA, 'POST', {'confirmSupplierAccepted': True, 'channel': 'PHONE', 'note': 'E2E supplier agreed'})
chk('G', 'acceptance recorded: manual, ends only that order warning', (s, json.loads(one(
    "select details from purchase_order_events where order_id=? and event_type='SUPPLIER_CANCELLATION_RECORDED'", O2))['verified'],
    cands()[P[2]], cands()[P[5]]), (200, False, [], ['CANCELED_AFTER_CONTACT']))
chk('G', 'second acceptance → 409 ALREADY_RECORDED', call(f'{PO}{O2}/supplier-cancellation-recorded', SA, 'POST',
                                                        {'confirmSupplierAccepted': True, 'channel': 'PHONE', 'note': 'Again'})[1]['code'], 'ALREADY_RECORDED')
chk('G', 'acceptance on a not-contacted order → 409 NOT_ELIGIBLE', call(f'{PO}{O4P}/supplier-cancellation-recorded', SA, 'POST',
                                                                      {'confirmSupplierAccepted': True, 'channel': 'PHONE', 'note': 'Agreed'})[1]['code'], 'NOT_ELIGIBLE')

# ── H) English ──────────────────────────────────────────────────────────────────────────────
O6 = generated([(P[6], 3)], 'en')
chk('H', 'English message stored as en', (one('select message_language from purchase_orders where order_id=?', O6),
                                         one('select message_text from purchase_orders where order_id=?', O6).startswith('Purchase order: ')), ('en', True))

chk('H', 'no activity_log entry from any purchasing or communication step so far', one('select count(*) from activity_log'), activity_before)

# ── I) a message that cannot fit ────────────────────────────────────────────────────────────
LONG = []
for i in range(25):
    nm = (f'E2E Long product {i} ' + 'x' * 80)[:80]
    s, p = call('/products', SA, 'POST', {'name': nm, 'barcode': f'72902{i:08d}', 'category': 'dairy', 'sellingPrice': 5, 'quantity': 3,
                                          'minStock': 5, 'supplierName': 'E2E Dairy WA'})
    LONG.append(p['productId'])
fin_before = [q(x) for x in FIN_SQL]  # new products were added; purchasing must not change anything from here on
activity_before = one('select count(*) from activity_log')
s, lo = create([(p, 1) for p in LONG])
LO = lo['orderId']
s, ref = call(f'{PO}{LO}/approve', SA, 'POST', {'version': 0})
ml = ref['details']['messageLength']
chk('I', 'approval refused: MESSAGE_TOO_LONG, still pending', (s, ref['code'], status_of(LO)), (409, 'MESSAGE_TOO_LONG', 'PENDING_APPROVAL'))
chk('I', 'details: real length > 2000, limit 2000, lines to remove', (ml['ar']['length'] > 2000, ml['ar']['limit'], ml['ar']['minLinesToRemove'] > 0,
                                                                     len(ml['ar']['longestLines'])), (True, 2000, True, 5))
keep = len(LONG) - max(ml['ar']['minLinesToRemove'], ml['en']['minLinesToRemove'])
call(f'{PO}{LO}', SA, 'PUT', {'version': 0, 'items': [{'productId': p, 'quantity': 1} for p in LONG[:keep]]})
s, x = call(f'{PO}{LO}/approve', SA, 'POST', {'version': 1})
pv = call(f'{PO}{LO}/message-preview', SA)[1]
s2, g = call(f'{PO}{LO}/message', SA, 'POST', {'version': 2, 'language': 'ar', 'previewToken': pv['previewToken']})
chk('I', 'after removing those lines: approved and generated, ≤ 2000, nothing cut', (s, s2, one('select length(message_text) from purchase_orders '
    'where order_id=?', LO) <= 2000, all(n in pv['text'] for (n,) in q('select product_name from purchase_order_items where order_id=?', LO))),
    (200, 200, True, True))

# ── J) isolation and roles on every new endpoint ────────────────────────────────────────────
eps = [('GET', 'message-preview', None), ('GET', 'message', None),
       ('POST', 'message', {'version': 0, 'language': 'ar', 'previewToken': '0' * 64}),
       ('POST', 'message-copied', {'clientActionId': uid()}), ('POST', 'whatsapp-open-requested', {'clientActionId': uid(), 'confirmed': True}),
       ('POST', 'whatsapp-open-result', {'clientActionId': uid(), 'result': 'OPENED'}),
       ('POST', 'mark-sent', {'version': 0, 'confirmSentInWhatsapp': True}),
       ('POST', 'supplier-confirmation', {'version': 0, 'confirmSupplierConfirmed': True}),
       ('POST', 'close', {'version': 0, 'confirmNoReceiving': True}),
       ('POST', 'supplier-cancellation-recorded', {'confirmSupplierAccepted': True, 'channel': 'PHONE', 'note': 'Hack it'})]
iso = []
for m_, ep, b_ in eps:
    for who, t, exp in (('neshan', NA, 404), ('cashier', SC, 403), ('sysadmin', SYS, 403), ('nobody', None, 401)):
        st, body = call(f'{PO}{O6}/{ep}', t, m_, b_)
        if st != exp or (body and '970599777' in json.dumps(body)): iso.append((who, ep, st))
    st, _ = call(f'{PO}{NORD}/{ep}', SA, m_, b_)
    if st != 404: iso.append(('shini→neshan', ep, st))
chk('J', 'other store 404, cashier/sysadmin 403, no login 401 on all 10 endpoints; no number leaked', iso, [])
chk('J', 'nothing changed by refused calls', (status_of(O6), version(O6), status_of(NORD), version(NORD)), ('MESSAGE_GENERATED', 2, 'PENDING_APPROVAL', 0))

# ── K) real concurrent HTTP ─────────────────────────────────────────────────────────────────
res = []
def hit(path, b): res.append(call(path, SA, 'POST', b))
same = uid()
th = [threading.Thread(target=hit, args=(f'{PO}{O6}/message-copied', {'clientActionId': same})) for _ in range(6)]
[t.start() for t in th]; [t.join() for t in th]
chk('K', '6 identical copy reports at once → one event, all 200', (events(O6).count('MESSAGE_COPIED'), sorted(s for s, _ in res)), (1, [200] * 6))
res.clear()
call(f'{PO}{O6}/cancel', SA, 'POST', {'version': 2, 'reason': 'Changed', 'contactStatus': 'UNKNOWN'})
th = [threading.Thread(target=hit, args=(f'{PO}{O6}/supplier-cancellation-recorded',
                                         {'confirmSupplierAccepted': True, 'channel': 'OTHER', 'note': 'Agreed by visit'})) for _ in range(5)]
[t.start() for t in th]; [t.join() for t in th]
chk('K', '5 supplier acceptances at once → one event, 4× ALREADY_RECORDED', (events(O6).count('SUPPLIER_CANCELLATION_RECORDED'),
    sorted((s, (b or {}).get('code')) for s, b in res)), (1, [(200, None)] + [(409, 'ALREADY_RECORDED')] * 4))

# ── M) D1: exact summary counts from the server (final corrections) ─────────────────────────
s, sm = call(f'{PO}summary', SA)
exp = {st: one('select count(*) from purchase_orders where store_id=1 and status=?', st) for st in
       ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'MESSAGE_GENERATED', 'SENT', 'CONFIRMED', 'CLOSED', 'CANCELED')}
chk('M', 'summary = the database counts by status (Shini)', (s, sm['byStatus']), (200, exp))
chk('M', 'summary open = the five open states', sm['open'], sum(exp[k] for k in ('PENDING_APPROVAL', 'APPROVED', 'MESSAGE_GENERATED', 'SENT', 'CONFIRMED')))
s, ol = call(f'/purchase-orders?overdue=true&size=100', SA)
chk('M', 'overdue list total = summary overdue (fresh orders: 0)', (s, ol['total'], sm['overdue']), (200, 0, 0))
chk('M', 'Neshan summary is its own (one pending order)', call(f'{PO}summary', NA)[1]['byStatus']['PENDING_APPROVAL'], 1)
chk('M', 'summary: cashier 403, sysadmin 403, nobody 401', [call(f'{PO}summary', t)[0] for t in (SC, SYS, None)], [403, 403, 401])

# ── L) nothing financial or store-related changed; database consistent ─────────────────────
chk('L', 'products, sales, sale lines, returns unchanged', [q(x) for x in FIN_SQL], fin_before)
chk('L', 'report summary unchanged', call('/reports/summary?from=2020-01-01&to=2035-12-31', SA)[1], summary_before)
chk('L', 'store rows unchanged except the order counter', stores_without_counter(), stores_before)
chk('L', 'order numbers taken = orders created', one('select next_purchase_order_number from stores where store_id=1') - counter_before,
    one("select count(*) from purchase_orders where store_id=1"))
chk('L', 'no activity_log entry from purchasing', one('select count(*) from activity_log'), activity_before)
chk('L', 'reservation invariant', one("select count(*) from purchase_order_items i join purchase_orders o on o.order_id=i.order_id where "
                                      "(o.status in ('REJECTED','CANCELED','CLOSED') and i.open_product_id is not null) or "
                                      "(o.status not in ('REJECTED','CANCELED','CLOSED') and i.open_product_id is null and i.product_id is not null)"), 0)
chk('L', 'no cross-store rows', one('select count(*) from purchase_order_events e join purchase_orders o on o.order_id=e.order_id '
                                    'where e.store_id<>o.store_id'), 0)
chk('L', 'foreign_key_check clean', q('pragma foreign_key_check'), [])
print(f"\nCOMMUNICATION CHECK PASS {R['pass']} FAIL {R['fail']}")
sys.exit(1 if R['fail'] else 0)

// Cheque refund payout (feature 9 + approved cash refund policy): the real app in Chromium on a DISPOSABLE
// demo database (run via run.sh). A real cheque sale, its status changes, a return that creates what is owed
// back, and the Store Admin's payout in cash from their own open drawer — authorization, request key,
// duplicate submissions, insufficient drawer cash, atomicity, audit history and store isolation.
// Test data only: no real payment, bank or external transaction of any kind.
const { requireDisposable } = require('./safety.js');
const { out: OUT } = requireDisposable();
const { chromium } = require('playwright');
const { execSync, execFileSync } = require('child_process');
const path = require('path');

const RESULTS = []; let shotNo = 0;
function rec(area, action, expected, actual, pass, evidence = '') {
  RESULTS.push({ area, action, pass: !!pass });
  console.log(`${pass ? 'PASS' : 'FAIL'} [${area}] ${action} | expected: ${expected} | actual: ${actual}${evidence ? ' | ' + evidence : ''}`);
}
const dbJson = sql => JSON.parse(execFileSync('python3', [`${__dirname}/dbq.py`, '--json', sql]).toString());
const one = sql => { const r = dbJson(sql); return r.length ? r[0][0] : null; };
const row = sql => { const r = dbJson(sql); return r.length ? r[0] : null; };
// a write attempt that is ALWAYS rolled back: proves a database rule
const dbTry = sql => execFileSync('python3', [`${__dirname}/dbtry.py`, sql]).toString().trim();
const api = (method, p, token, body) => {
  const args = ['-s', '-w', '\\n%{http_code}', '-X', method, `http://127.0.0.1:8080/api${p}`];
  if (token) args.push('-H', `Authorization: Bearer ${token}`);
  if (body) args.push('-H', 'Content-Type: application/json', '-d', JSON.stringify(body));
  const outp = execFileSync('curl', args).toString();
  const i = outp.lastIndexOf('\n'); let json = null; try { json = JSON.parse(outp.slice(0, i)); } catch (e) { /* */ }
  return { status: +outp.slice(i + 1), json };
};
const token = (u, pw) => api('POST', '/auth/login', null, { username: u, password: pw }).json.token;

let browser; let CUR = null;
async function newPage(w = 1440, h = 1000) {
  const ctx = await browser.newContext({ viewport: { width: w, height: h }, deviceScaleFactor: 1 });
  const p = await ctx.newPage(); CUR = p; const st = { errors: [], bad: [], pays: [] };
  p.on('pageerror', e => st.errors.push(e.message));
  p.on('response', async r => {
    const u = r.url();
    if (u.includes(':8080/api') && r.status() >= 500) st.bad.push(`${r.status()} ${u}`);
    if (/\/api\/cheque-refunds\/\d+\/pay$/.test(u) && r.request().method() === 'POST') {
      let body = null; try { body = JSON.parse(r.request().postData() || 'null'); } catch (e) { /* */ }
      let code = null; try { code = (await r.json()).code || null; } catch (e) { /* */ }
      st.pays.push({ status: r.status(), body, code });
    }
  });
  await p.goto('http://localhost:8090/', { waitUntil: 'networkidle' }); await p.waitForTimeout(3000);
  await p.evaluate(() => document.querySelector('flt-semantics-placeholder')?.click()); await p.waitForTimeout(800);
  return { ctx, p, st };
}
const field = async (p, label, value) => {
  const f = p.locator(`flt-semantics-host input[aria-label^="${label}"], flt-semantics-host textarea[aria-label^="${label}"]`).first();
  await f.click({ timeout: 8000 }); await p.keyboard.press('Control+A'); await p.keyboard.press('Backspace');
  await p.keyboard.type(String(value)); await p.waitForTimeout(300);
};
const click = async (p, name, wait = 1300, nth = 0) => {
  await p.getByRole('button', { name, exact: true }).nth(nth).click({ timeout: 8000 }); await p.waitForTimeout(wait);
};
const tile = async (p, text, wait = 900) => {
  const box = p.locator('flt-semantics-host input').first();
  await box.click({ timeout: 8000 }); await p.keyboard.press('Control+A'); await p.keyboard.press('Backspace');
  await p.keyboard.type(text); await p.waitForTimeout(900);
  await p.getByRole('button').filter({ hasText: text }).first().click({ timeout: 8000 }); await p.waitForTimeout(wait);
};
const txt = async p => (await p.locator('flt-semantics-host').innerText()) + '\n' + (await p.evaluate(() =>
  [...document.querySelectorAll('flt-semantics-host [aria-label]')].map(e => e.getAttribute('aria-label')).join('\n')));
const shot = async (p, name) => { const f = `${String(++shotNo).padStart(2, '0')}_${name}.png`; await p.screenshot({ path: path.join(OUT, f) }); return f; };
async function login(p, user, pw) { await field(p, 'Username', user); await field(p, 'Password', pw); await p.keyboard.press('Enter'); await p.waitForTimeout(3500); }
async function step(area, action, fn) {
  try { await fn(); } catch (e) {
    let f = ''; try { f = await shot(CUR, 'FAILED_step'); } catch (x) { /* */ }
    rec(area, action, 'no error', 'ERROR: ' + e.message.split('\n')[0], false, f);
  }
}
const KEY = /^[A-Za-z0-9_-]{8,100}$/;
const CHQ = 'CR-200300';

(async () => {
  // ── test data through the real API (Shini Supermarket of the demo profile; Neshan = the other store) ──
  const AD = token('shini.admin', 'Admin-Pass-1');
  const CA = token('shini.cashier', 'Cashier-Pass-1');
  const NA = token('neshan.admin', 'Admin-Pass-1');
  const adminId = one("select user_id from users where username = 'shini.admin'");
  const shini = one("select store_id from users where username = 'shini.admin'");
  api('PUT', '/store/settings', AD, { taxRate: 0 });
  const speaker = api('POST', '/products', AD, { name: 'CR Speaker', barcode: '7291110000550', category: 'groceries',
    sellingPrice: 300, quantity: 10, minStock: 1, supplierName: 'CR Supplier' }).json.productId;
  const adminDrawer = api('POST', '/drawers', AD, { name: 'Office Till' }).json.drawerId;
  rec('Setup', 'test product (300.00) and the Store Admin\'s drawer', 'created', `${speaker}/${adminDrawer}`, !!(speaker && adminDrawer));

  browser = await chromium.launch();

  // 1. a genuine cheque-paid sale, made by the cashier in the browser
  let { ctx, p, st } = await newPage();
  await login(p, 'shini.cashier', 'Cashier-Pass-1');
  await step('1 Cheque sale', 'cashier sells 2 × CR Speaker paid by cheque', async () => {
    await tile(p, 'CR Speaker', 900); await tile(p, 'CR Speaker', 900);
    await click(p, 'Cheque', 600);
    await click(p, 'Complete Sale', 1500);
    await field(p, 'Cheque number', CHQ);
    await field(p, 'Bank', 'Test Bank');
    await click(p, 'Choose the due date', 1200);
    await click(p, 'OK', 1000);
    const f = await shot(p, 'cashier_cheque_payment');
    await click(p, 'Complete payment', 2500);
    const c = row(`select c.status, c.amount_minor, p.payment_method, s.store_id, s.status from cheques c
      join sales s on s.sale_id = c.sale_id join payments p on p.sale_id = s.sale_id where c.cheque_number = '${CHQ}'`);
    rec('1 Cheque sale', 'cheque RECEIVED for 600.00, the sale paid by CHEQUE in this store',
      `RECEIVED/60000/CHEQUE/${shini}/COMPLETED`, JSON.stringify(c),
      JSON.stringify(c) === JSON.stringify(['RECEIVED', 60000, 'CHEQUE', shini, 'COMPLETED']), f);
  });
  rec('Browser', 'no page errors / no 5xx (cashier)', '0 / 0', `${st.errors.length} / ${st.bad.length} ${st.errors.join(' | ').slice(0, 200)}`,
    st.errors.length === 0 && st.bad.length === 0);
  await ctx.close();

  const chequeId = one(`select cheque_id from cheques where cheque_number = '${CHQ}'`);
  const saleId = one(`select sale_id from cheques where cheque_number = '${CHQ}'`);
  const itemId = one(`select sale_item_id from sale_items where sale_id = ${saleId}`);
  const invoice = one(`select invoice_number from sales where sale_id = ${saleId}`);

  // 2. valid status transitions, in the browser (Store Admin)
  ({ ctx, p, st } = await newPage());
  await login(p, 'shini.admin', 'Admin-Pass-1');
  const openCheque = async () => {
    await click(p, 'Cheques', 2500);
    await p.getByRole('button').filter({ hasText: CHQ }).first().click({ timeout: 8000 }); await p.waitForTimeout(1500);
  };
  await step('2 Status', 'RECEIVED → DEPOSITED in the browser', async () => {
    await openCheque();
    await click(p, 'Mark as deposited', 1200);
    await click(p, 'Save', 1800);
    rec('2 Status', 'cheque DEPOSITED', 'DEPOSITED', one(`select status from cheques where cheque_id = ${chequeId}`),
      one(`select status from cheques where cheque_id = ${chequeId}`) === 'DEPOSITED', await shot(p, 'admin_cheque_deposited'));
    await p.keyboard.press('Escape'); await p.waitForTimeout(800);
  });
  const bad = api('POST', `/cheques/${chequeId}/status`, AD, { toStatus: 'RECEIVED' });
  rec('2 Status', 'an invalid transition (DEPOSITED → RECEIVED) is refused', '409 CHEQUE_TRANSITION_NOT_ALLOWED',
    `${bad.status} ${bad.json && bad.json.code}`, bad.status === 409 && bad.json.code === 'CHEQUE_TRANSITION_NOT_ALLOWED');

  // 3. a valid return creates the correct obligation (made in the browser by the Store Admin)
  const cashierTry = api('POST', '/returns', CA, { saleId, saleItemId: itemId, quantity: 1, reason: 'Not allowed for a cashier' });
  rec('3 Return', 'a cashier cannot return a cheque invoice', '409 CHEQUE_RETURN_NEEDS_STORE_ADMIN',
    `${cashierTry.status} ${cashierTry.json && cashierTry.json.code}`, cashierTry.status === 409 && cashierTry.json.code === 'CHEQUE_RETURN_NEEDS_STORE_ADMIN');
  await step('3 Return', 'Store Admin returns 1 × CR Speaker from the cheque invoice in the POS', async () => {
    await click(p, 'Point of Sale', 2500);
    await click(p, 'Return', 1300);
    await field(p, 'Invoice number or receipt code', invoice); await click(p, 'Find', 1600);
    await field(p, 'Reason', 'Customer returned one speaker');
    await click(p, 'Confirm Return', 2200);
    const t = await txt(p); const f = await shot(p, 'admin_return_cheque_invoice');
    const r = row(`select r.return_id, r.refund_method, r.refund_minor, cr.status, cr.amount_minor, cr.cheque_id, cr.store_id,
      cr.payout_movement_id from returns r join cheque_refunds cr on cr.return_id = r.return_id where r.sale_id = ${saleId}`);
    rec('3 Return', 'return by CHEQUE 300.00 → one PENDING refund of 300.00 for this cheque and store, nothing paid yet',
      `CHEQUE/30000/PENDING/30000/${chequeId}/${shini}/null`, JSON.stringify(r && r.slice(1)),
      t.includes('Return completed') && r && JSON.stringify(r.slice(1)) === JSON.stringify(['CHEQUE', 30000, 'PENDING', 30000, chequeId, shini, null])
        && one(`select count(*) from cash_movements`) === 0, f);
    await click(p, 'Done', 1000).catch(() => {});
  });
  const refundId = one(`select cheque_refund_id from cheque_refunds where sale_id = ${saleId}`);
  const notCleared = api('POST', `/cheque-refunds/${refundId}/pay`, AD, { method: 'CASH', requestKey: 'cr-not-cleared-01' });
  rec('2 Status', 'a refund is not paid before the cheque has cleared', '409 CHEQUE_NOT_CLEARED',
    `${notCleared.status} ${notCleared.json && notCleared.json.code}`, notCleared.status === 409 && notCleared.json.code === 'CHEQUE_NOT_CLEARED');
  await step('2 Status', 'DEPOSITED → CLEARED in the browser', async () => {
    await openCheque();
    await click(p, 'Mark as cleared', 1200);
    await click(p, 'Save', 1800);
    rec('2 Status', 'cheque CLEARED', 'CLEARED', one(`select status from cheques where cheque_id = ${chequeId}`),
      one(`select status from cheques where cheque_id = ${chequeId}`) === 'CLEARED', await shot(p, 'admin_cheque_cleared'));
    await p.keyboard.press('Escape'); await p.waitForTimeout(800);
  });

  // 4 + 12. only this store's Store Admin can pay
  const cashierPay = api('POST', `/cheque-refunds/${refundId}/pay`, CA, { method: 'CASH', requestKey: 'cr-cashier-try-01' });
  rec('4 Authorization', 'a cashier cannot pay a cheque refund', '403', cashierPay.status, cashierPay.status === 403);
  const otherPay = api('POST', `/cheque-refunds/${refundId}/pay`, NA, { method: 'CASH', requestKey: 'cr-neshan-try-01' });
  const otherSee = api('GET', `/cheques/${chequeId}`, NA);
  const otherList = api('GET', '/cheques', NA);
  rec('12 Multi-Store', 'the other store\'s admin can neither pay, open nor list this cheque', '404 / 404 / not listed',
    `${otherPay.status} / ${otherSee.status} / ${JSON.stringify(otherList.json || []).includes(CHQ) ? 'listed' : 'not listed'}`,
    otherPay.status === 404 && otherSee.status === 404 && otherList.status === 200 && !JSON.stringify(otherList.json).includes(CHQ));
  rec('4 Authorization', 'refused attempts changed nothing', 'PENDING, 0 movements',
    `${one(`select status from cheque_refunds where cheque_refund_id = ${refundId}`)}, ${one('select count(*) from cash_movements')}`,
    one(`select status from cheque_refunds where cheque_refund_id = ${refundId}`) === 'PENDING' && one('select count(*) from cash_movements') === 0);

  const payButton = () => p.getByRole('button', { name: 'Pay in cash', exact: true }).first();
  // the error snackbar lies UNDER the open dialog, so it is not in the semantics tree while the dialog is
  // open; closing the dialog (the snackbar stays ~4 s) puts it in the tree, where a screen reader reads it
  const errorShown = async () => {
    await p.keyboard.press('Escape'); await p.waitForTimeout(500);
    return txt(p);
  };

  // 10a. no open drawer → refused in the browser
  await step('10 Insufficient', 'Pay in cash without an open drawer is refused', async () => {
    await openCheque();
    await payButton().click({ timeout: 8000 }); await p.waitForTimeout(1800);
    const f = await shot(p, 'pay_cash_no_shift'); const t = await errorShown(); await shot(p, 'pay_cash_no_shift_message');
    const msg = 'Open a cash shift first';
    rec('10 Insufficient', 'message: open a cash shift first; still PENDING, no movement', msg,
      t.includes(msg), t.includes(msg) && one(`select status from cheque_refunds where cheque_refund_id = ${refundId}`) === 'PENDING'
        && one('select count(*) from cash_movements') === 0, f);
    await p.keyboard.press('Escape'); await p.waitForTimeout(800);
  });

  // 10b. the Store Admin's own drawer holds 100.00 < 300.00 → refused
  const adminShift = api('POST', '/shifts', AD, { drawerId: adminDrawer, openingCash: 100 }).json.shiftId;
  await step('10 Insufficient', 'Pay in cash 300.00 from a drawer holding 100.00 is refused', async () => {
    await openCheque();
    await payButton().click({ timeout: 8000 }); await p.waitForTimeout(1800);
    const f = await shot(p, 'pay_cash_insufficient_drawer'); const t = await errorShown(); await shot(p, 'pay_cash_insufficient_message');
    rec('10 Insufficient', 'message: the drawer does not hold that much cash; still PENDING, no movement',
      'does not hold that much cash', t.includes('does not hold that much cash'),
      t.includes('does not hold that much cash') && one(`select status from cheque_refunds where cheque_refund_id = ${refundId}`) === 'PENDING'
        && one('select count(*) from cash_movements') === 0, f);
    await p.keyboard.press('Escape'); await p.waitForTimeout(800);
  });
  const refusedKeys = st.pays.map(x => x.body && x.body.requestKey);
  rec('5 Request key', 'the browser sent a valid request key with every cash payout attempt', `${st.pays.length} × [A-Za-z0-9_-]{8,100}`,
    JSON.stringify(refusedKeys), st.pays.length >= 2 && refusedKeys.every(k => KEY.test(k || '')) && new Set(refusedKeys).size === refusedKeys.length);
  rec('8 Atomic', 'refused payouts (SHIFT_REQUIRED, CASH_OUT_TOO_LARGE) left neither a movement nor a decision',
    '409 SHIFT_REQUIRED, 409 CASH_OUT_TOO_LARGE; PENDING', st.pays.map(x => `${x.status} ${x.code}`).join(', '),
    st.pays.map(x => `${x.status} ${x.code}`).join(',') === '409 SHIFT_REQUIRED,409 CASH_OUT_TOO_LARGE'
      && one(`select status || '/' || coalesce(decided_by, 'none') from cheque_refunds where cheque_refund_id = ${refundId}`) === 'PENDING/none');

  // the Store Admin adds real cash: a recorded cash-in with a reason (audit trail)
  const cashIn = api('POST', `/shifts/${adminShift}/movements`, AD, { type: 'CASH_IN', amount: 200, reason: 'Cash from the safe for a cheque refund', requestKey: 'cr-cash-in-0001' });
  rec('10 Insufficient', 'Store Admin records a real cash-in of 200.00 (drawer 300.00)', '201', cashIn.status, cashIn.status === 201);

  // 6 + 9. the payout in the browser — double-clicked, to submit twice
  const before = st.pays.length;
  await step('6 Payout', 'Pay in cash (double-clicked) from the Store Admin\'s open drawer', async () => {
    await openCheque();
    await payButton().dblclick({ timeout: 8000 }); await p.waitForTimeout(2500);
    const t = await txt(p); const f = await shot(p, 'pay_cash_done');
    rec('6 Payout', 'the dialog shows the refund paid', 'Pay in cash no longer offered', !t.includes('Pay in cash'),
      !t.includes('Pay in cash') && one(`select status from cheque_refunds where cheque_refund_id = ${refundId}`) === 'PAID', f);
    await p.keyboard.press('Escape'); await p.waitForTimeout(800);
  });
  const sent = st.pays.slice(before);
  const ok = sent.filter(x => x.status === 200);
  rec('9 Duplicates', 'browser submissions: exactly one accepted, any repeat refused', '1 × 200, others 409',
    sent.map(x => x.status).join(', '), sent.length >= 1 && ok.length === 1 && sent.every(x => x.status === 200 || x.status === 409));
  const key = ok.length ? ok[0].body.requestKey : null;
  rec('5 Request key', 'the accepted payout\'s key was generated by the browser and is valid', 'chq-<uuid>', key,
    !!key && KEY.test(key) && /^chq-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(key));
  // replay the browser's own accepted request (same key, same body)
  const replay = key ? api('POST', `/cheque-refunds/${refundId}/pay`, AD, { method: 'CASH', requestKey: key }) : { status: 0, json: null };
  rec('9 Duplicates', 'the same request replayed → refused, no second payout', '409 CHEQUE_REFUND_DECIDED',
    `${replay.status} ${replay.json && replay.json.code}`, replay.status === 409 && replay.json.code === 'CHEQUE_REFUND_DECIDED');
  const bank = api('POST', `/cheque-refunds/${refundId}/pay`, AD, { method: 'BANK_TRANSFER' });
  rec('9 Duplicates', 'paying again by another method → refused', '409 CHEQUE_REFUND_DECIDED',
    `${bank.status} ${bank.json && bank.json.code}`, bank.status === 409 && bank.json.code === 'CHEQUE_REFUND_DECIDED');

  // 6 + 7 + 8. exactly one authorized movement, in the right shift and store, linked both ways
  const mv = dbJson(`select movement_id, store_id, shift_id, type, amount_minor, created_by, request_key, cheque_refund_id
    from cash_movements where cheque_refund_id = ${refundId}`);
  const rf = row(`select status, paid_method, payout_movement_id, decided_by, decided_at is not null, version from cheque_refunds where cheque_refund_id = ${refundId}`);
  rec('6 Payout', 'exactly one CHEQUE_REFUND_OUT movement of 300.00 by the Store Admin', `1 movement, CHEQUE_REFUND_OUT/30000/${adminId}`,
    JSON.stringify(mv), mv.length === 1 && mv[0][3] === 'CHEQUE_REFUND_OUT' && mv[0][4] === 30000 && mv[0][5] === adminId
      && one(`select count(*) from cash_movements where type = 'CHEQUE_REFUND_OUT'`) === 1);
  rec('7 Shift and store', 'the movement is in the Store Admin\'s OPEN shift of this store', `shift ${adminShift}, store ${shini}`,
    mv.length ? `shift ${mv[0][2]}, store ${mv[0][1]}` : 'none',
    mv.length === 1 && mv[0][2] === adminShift && mv[0][1] === shini
      && one(`select status || '/' || cashier_id || '/' || store_id from cash_shifts where shift_id = ${adminShift}`) === `OPEN/${adminId}/${shini}`);
  rec('5 Request key', 'the stored movement carries the browser\'s key', key, mv.length ? mv[0][6] : null, mv.length === 1 && mv[0][6] === key);
  rec('8 Atomic', 'decision and movement point to each other: PAID by CASH, movement id, decided by the Store Admin',
    `PAID/CASH/${mv.length ? mv[0][0] : '?'}/${adminId}`, JSON.stringify(rf),
    !!rf && mv.length === 1 && rf[0] === 'PAID' && rf[1] === 'CASH' && rf[2] === mv[0][0] && rf[3] === adminId && rf[4] === 1 && rf[5] === 1);
  const expected = one(`select opening_cash_minor from cash_shifts where shift_id = ${adminShift}`)
    + one(`select coalesce(sum(case when type = 'CASH_IN' then amount_minor else -amount_minor end), 0) from cash_movements where shift_id = ${adminShift}`);
  rec('6 Payout', 'the drawer: 100.00 + 200.00 − 300.00 = 0.00', 0, expected, expected === 0);

  // 8. the database keeps decision and movement together, without the application (attempts rolled back)
  const ret2 = api('POST', '/returns', AD, { saleId, saleItemId: itemId, quantity: 1, reason: 'Second speaker returned', requestKey: 'cr-return-2-0001' });
  const refund2 = one(`select cheque_refund_id from cheque_refunds where sale_id = ${saleId} and cheque_refund_id <> ${refundId}`);
  rec('8 Atomic', 'a second return creates a second PENDING refund (for the database checks)', '201, PENDING',
    `${ret2.status}, ${refund2 && one(`select status from cheque_refunds where cheque_refund_id = ${refund2}`)}`, ret2.status === 201 && !!refund2);
  const t1 = dbTry(`UPDATE cheque_refunds SET status = 'PAID', paid_method = 'CASH', decided_by = ${adminId}, decided_at = '2026-01-01T00:00:00',
    version = version + 1 WHERE cheque_refund_id = ${refund2}`);
  rec('8 Atomic', 'database: PAID by CASH without its drawer movement is refused', 'REFUSED: CHEQUE_REFUND_BAD_DECISION', t1,
    t1.startsWith('REFUSED') && t1.includes('CHEQUE_REFUND_BAD_DECISION'));
  const t2 = dbTry(`INSERT INTO cash_movements (store_id, shift_id, type, amount_minor, reason, cheque_refund_id, created_by, created_at, request_key)
    VALUES (${shini}, ${adminShift}, 'CHEQUE_REFUND_OUT', 30000, 'second payout', ${refundId}, ${adminId}, '2026-01-01T00:00:00', 'cr-second-payout-01')`);
  rec('8 Atomic', 'database: a second payout movement for the PAID refund is refused', 'REFUSED', t2, t2.startsWith('REFUSED'));
  const t3 = dbTry(`DELETE FROM cash_movements WHERE cheque_refund_id = ${refundId}`);
  rec('8 Atomic', 'database: the payout movement cannot be deleted', 'REFUSED: CASH_MOVEMENTS_APPEND_ONLY', t3, t3.includes('CASH_MOVEMENTS_APPEND_ONLY'));
  rec('8 Atomic', 'nothing changed by the rolled-back attempts', 'refund 2 PENDING, 1 payout movement',
    `${one(`select status from cheque_refunds where cheque_refund_id = ${refund2}`)}, ${one(`select count(*) from cash_movements where type = 'CHEQUE_REFUND_OUT'`)}`,
    one(`select status from cheque_refunds where cheque_refund_id = ${refund2}`) === 'PENDING' && one(`select count(*) from cash_movements where type = 'CHEQUE_REFUND_OUT'`) === 1);

  // 11. audit history, in the browser and in the database
  await step('11 Audit', 'cheque details: history and the paid refund', async () => {
    await openCheque();
    const t = await txt(p); const f = await shot(p, 'cheque_audit_history');
    const ev = dbJson(`select from_status, to_status, user_id from cheque_events where cheque_id = ${chequeId} order by event_id`);
    rec('11 Audit', 'events: (new) → RECEIVED by the cashier, → DEPOSITED and → CLEARED by the Store Admin; shown in History',
      'RECEIVED, DEPOSITED, CLEARED', JSON.stringify(ev),
      ev.length === 3 && ev.map(e => e[1]).join(',') === 'RECEIVED,DEPOSITED,CLEARED' && ev[1][2] === adminId && ev[2][2] === adminId
        && t.includes('History') && t.includes('Ahmad Odeh'), f);
    await p.keyboard.press('Escape'); await p.waitForTimeout(800);
  });
  await step('11 Audit', 'the Store Admin\'s shift shows the payout movement', async () => {
    await click(p, 'Cash shifts', 2500);
    const handle = await p.evaluateHandle(n => {
      const re = new RegExp('#' + n + '(?!\\d)');
      const hits = [...document.querySelectorAll('flt-semantics-host *')]
        .filter(e => re.test(e.getAttribute('aria-label') || '') || re.test(e.textContent || ''));
      return hits.find(e => !hits.some(o => o !== e && e.contains(o))) || null;
    }, adminShift);
    if (!handle.asElement()) throw new Error('no row for shift #' + adminShift);
    await handle.asElement().click({ timeout: 8000 }); await p.waitForTimeout(1500);
    const t = await txt(p); const f = await shot(p, 'admin_shift_with_payout');
    rec('11 Audit', 'shift details list the cash-in and the cheque refund payout, expected 0.00', 'Cheque refund + 0.00',
      t.includes('Cheque refund'), t.includes('Cheque refund') && t.includes('Cash from the safe for a cheque refund'), f);
    await p.keyboard.press('Escape'); await p.waitForTimeout(800);
  });
  rec('Browser', 'no page errors / no 5xx (Store Admin)', '0 / 0', `${st.errors.length} / ${st.bad.length} ${st.errors.join(' | ').slice(0, 200)}`,
    st.errors.length === 0 && st.bad.length === 0);
  await ctx.close();
  await browser.close();

  // closing: the drawer must match, no difference
  const close = api('POST', `/shifts/${adminShift}/close`, AD, { countedCash: 0 });
  rec('6 Payout', 'Store Admin closes the shift counting 0.00: difference 0', '200, difference 0',
    `${close.status}, ${one(`select difference_minor from cash_shifts where shift_id = ${adminShift}`)}`,
    close.status === 200 && one(`select difference_minor from cash_shifts where shift_id = ${adminShift}`) === 0);
  rec('12 Multi-Store', 'the other store has no cheque, refund or movement of this store', '0 / 0 / 0',
    `${one(`select count(*) from cheques where store_id <> ${shini}`)} / ${one(`select count(*) from cheque_refunds where store_id <> ${shini}`)} / ${one(`select count(*) from cash_movements where store_id <> ${shini}`)}`,
    one(`select count(*) from cheques where store_id <> ${shini}`) === 0 && one(`select count(*) from cheque_refunds where store_id <> ${shini}`) === 0
      && one(`select count(*) from cash_movements where store_id <> ${shini}`) === 0);
  const integ = execFileSync('python3', [`${__dirname}/dbq.py`, 'PRAGMA integrity_check']).toString().trim();
  const fk = execFileSync('python3', [`${__dirname}/dbq.py`, '--json', 'PRAGMA foreign_key_check']).toString().trim();
  rec('8 Atomic', 'database integrity and references after the whole flow', 'ok / []', `${integ} / ${fk}`, integ === "('ok',)" && fk === '[]');

  const pass = RESULTS.filter(r => r.pass).length; const fail = RESULTS.length - pass;
  console.log(`\nCHEQUE REFUND UI PASS ${pass} FAIL ${fail}`);
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('FAILED:', e); process.exit(1); });

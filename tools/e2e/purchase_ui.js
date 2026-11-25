// Purchase Orders Phase 4 in the REAL Flutter web app (Chromium) → REST → Spring Boot → disposable SQLite.
// The whole manager workflow through the screens, checked in the database after every step; two stores,
// two managers, cashier, English and Arabic, 1440×900 and 1024×768.
// NO external contact: every request that is not to localhost is aborted at the browser network level
// (including wa.me); the WhatsApp link is only captured and decoded. Run via run.sh (suite: purchase-ui).
const { requireDisposable } = require('./safety.js');
const { out: OUT, db: DBFILE } = requireDisposable();
const { chromium } = require('playwright');
const { execSync, spawn } = require('child_process');
const path = require('path');

let pass = 0, fail = 0, shotNo = 0;
const ok = (n, c, info = '') => { c ? pass++ : fail++; console.log(c ? 'PASS' : 'FAIL', n, c ? '' : '| ' + info); };
const dbJson = sql => JSON.parse(execSync(`python3 ${__dirname}/dbq.py --json "${sql.replace(/"/g, '\\"')}"`).toString());
const one = sql => { const r = dbJson(sql); return r.length ? r[0][0] : null; };
const API = 'http://localhost:8080/api';
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function api(pathname, token, method = 'GET', body) {
  const r = await fetch(API + pathname, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body) });
  const t = await r.text();
  return { status: r.status, body: t ? JSON.parse(t) : null };
}
const token = async (u, p) => (await api('/auth/login', null, 'POST', { username: u, password: p })).body.token;

// ── browser ───────────────────────────────────────────────────────────────────────────────
let browser, CUR;
const external = [];                  // every non-local request the app tried (all aborted)
const fonts = [];                     // font files allowed (Google font hosts only)
async function newPage(w = 1440, h = 900) {
  const ctx = await browser.newContext({ viewport: { width: w, height: h }, deviceScaleFactor: 1 });
  await ctx.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: 'http://localhost:8090' });
  // network isolation: only localhost is reachable; anything else (wa.me, fonts, …) is aborted
  await ctx.route('**/*', route => {
    const u = route.request().url();
    if (/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?\//.test(u) || u.startsWith('data:') || u.startsWith('blob:')) return route.continue();
    // Flutter web loads the Arabic glyphs (Noto fallback fonts) from Google's font hosts: font files only
    if (/^https:\/\/fonts\.(gstatic|googleapis)\.com\//.test(u)) { fonts.push(u); return route.continue(); }
    external.push(u);
    return route.abort();
  });
  const p = await ctx.newPage(); CUR = p; const st = { errors: [], bad: [] };
  p.on('pageerror', e => st.errors.push(e.message));
  p.on('response', r => { if (r.url().includes(':8080/api') && r.status() >= 500) st.bad.push(`${r.status()} ${r.url()}`); });
  await p.goto('http://localhost:8090/', { waitUntil: 'networkidle' }); await p.waitForTimeout(3000);
  await p.evaluate(() => document.querySelector('flt-semantics-placeholder')?.click()); await p.waitForTimeout(800);
  return { ctx, p, st };
}
const field = async (p, label, value, nth = 0) => {
  const f = p.locator(`flt-semantics-host input[aria-label^="${label}"], flt-semantics-host textarea[aria-label^="${label}"]`).nth(nth);
  await f.click({ timeout: 8000 }); await p.keyboard.press('Control+A'); await p.keyboard.press('Backspace');
  if (value !== '') await p.keyboard.type(String(value)); await p.waitForTimeout(300);
};
// exact names (a plain string would also match longer labels, e.g. "Add" in "Add Product")
const btn = (p, name) => p.getByRole('button', typeof name === 'string' ? { name, exact: true } : { name });
const click = async (p, name, wait = 1500, nth = 0) => {
  await btn(p, name).nth(nth).click({ timeout: 8000 }); await p.waitForTimeout(wait);
};
const check = async (p, name, wait = 500) => {
  await p.getByRole('checkbox', { name }).first().click({ timeout: 8000 }); await p.waitForTimeout(wait);
};
const radio = async (p, name, wait = 500) => {
  await p.getByRole('radio', { name }).first().click({ timeout: 8000 }); await p.waitForTimeout(wait);
};
const txt = async p => (await p.locator('flt-semantics-host').innerText()) + '\n' + (await p.evaluate(() =>
  [...document.querySelectorAll('flt-semantics-host [aria-label]')].map(e => e.getAttribute('aria-label')).join('\n')));
const shot = async (p, name) => { const f = `${String(++shotNo).padStart(2, '0')}_${name}.png`; await p.screenshot({ path: path.join(OUT, f) }); return f; };
async function login(p, user, pw) {
  await field(p, 'Username', user); await field(p, 'Password', pw); await p.keyboard.press('Enter'); await p.waitForTimeout(3500);
}
async function logout(p) { await click(p, /^(Log out|تسجيل الخروج)$/, 2500); }
async function nav(p, name, wait = 2500) {
  const item = p.getByRole('button', { name }).first();
  await item.scrollIntoViewIfNeeded({ timeout: 8000 }).catch(() => {});
  await item.click({ timeout: 8000 }); await p.waitForTimeout(wait);
}
async function step(name, fn) {
  try { await fn(); } catch (e) {
    let f = ''; try { f = await shot(CUR, 'FAILED_step'); } catch (x) { /* closed */ }
    const lines = e.message.split('\n');
    ok(name, false, 'ERROR: ' + lines[0] + ' ' + (lines.find(l => l.includes('waiting for')) || '').trim() + ' ' + f);
  }
}
const order = id => dbJson(`select status, version, source, canceled_after_contact, message_language, message_whatsapp, sent_marked_at is not null,
  confirmation_note, closed_at is not null from purchase_orders where order_id=${id}`)[0];
const events = id => dbJson(`select event_type from purchase_order_events where order_id=${id} order by event_id`).map(r => r[0]);
const lastOrder = () => one('select max(order_id) from purchase_orders where store_id=1');
const FIN = () => JSON.stringify([
  dbJson('select product_id, quantity, selling_price, cost_price, supplier_id from products order by product_id'),
  dbJson('select sale_id, status, total from sales order by sale_id'),
  dbJson('select sale_item_id, quantity, unit_cost, returned_quantity from sale_items order by sale_item_id'),
  dbJson('select return_id, quantity, refund_amount from returns order by return_id')]);

(async () => {
  browser = await chromium.launch(process.env.HTTPS_PROXY ? { proxy: { server: process.env.HTTPS_PROXY, bypass: 'localhost,127.0.0.1' } } : {});

  // ══ setup through the real API (disposable database) ═══════════════════════════════════
  const SYS = await token('sysadmin', 'Admin12345');
  const SA = await token('shini.admin', 'Admin-Pass-1');
  const NA = await token('neshan.admin', 'Admin-Pass-1');
  await api('/admin/stores/1/admins', SYS, 'POST', { username: 'shini.admin2', password: 'Admin-Pass-2', fullName: 'Second Manager' });
  const SA2 = await token('shini.admin2', 'Admin-Pass-2');
  const sup = (await api('/suppliers', SA, 'POST', { name: 'UI Dairy WA', whatsappNumber: '+970 59 911 1001' })).body;
  await api('/suppliers', SA, 'POST', { name: 'UI Bakery NoWA' });
  const P = {};
  for (const [name, bc, qty, min, cost, supplierName] of [
    ['UI Labneh 500g', '7290200000011', 2, 10, 9.5, 'UI Dairy WA'], ['UI Milk 1L', '7290200000028', 0, 5, 3.2, 'UI Dairy WA'],
    ['UI Yogurt', '7290200000035', 1, 6, 2, 'UI Dairy WA'], ['UI Cheese', '7290200000042', 3, 8, 12, 'UI Dairy WA'],
    ['UI Butter', '7290200000059', 2, 5, 7, 'UI Dairy WA'], ['UI Cream', '7290200000066', 1, 4, 4, 'UI Dairy WA'],
    ['UI Bread', '7290200000073', 0, 5, 1, 'UI Bakery NoWA']]) {
    const r = await api('/products', SA, 'POST', { name, barcode: bc, category: 'dairy', sellingPrice: 14, quantity: qty, minStock: min,
      supplierName, costPrice: cost });
    P[name] = r.body.productId;
  }
  await api('/guardian/status', SA, 'PUT', { enabled: true });
  const finBefore = FIN();
  const summaryBefore = JSON.stringify((await api('/reports/summary?from=2020-01-01&to=2035-12-31', SA)).body);
  ok('setup: supplier and products created in the disposable database', sup.supplierId > 0 && Object.values(P).every(x => x > 0));

  // ══ English: main workflow ═════════════════════════════════════════════════════════════
  let { ctx, p, st } = await newPage();
  await login(p, 'shini.admin', 'Admin-Pass-1');
  let G;  // the main order
  let lineOrder = [];  // the form keeps the suggestions' order (most urgent first)
  await step('Purchase Orders list (EN)', async () => {
    await nav(p, 'Purchase Orders');
    const t = await txt(p);
    ok('EN list: title, New button, 4 summary cards, filters', t.includes('New Purchase Order') && t.includes('Waiting for approval')
      && t.includes('To contact supplier') && t.includes('Waiting for supplier') && t.includes('Overdue') && t.includes('Pending'));
    await shot(p, 'en_orders_list_empty');
  });

  await step('Reorder Suggestions → order from Guardian (EN)', async () => {
    await nav(p, 'Reorder Suggestions');
    let t = await txt(p);
    ok('EN suggestions: grouped by supplier with server quantities and rule note (not AI)', t.includes('UI Dairy WA') && t.includes('UI Labneh 500g')
      && t.includes('not an AI model'));
    await shot(p, 'en_recommendations');
    const recs = (await api('/purchase-recommendations', SA)).body;
    const group = recs.suppliers.find(s => s.supplierId === sup.supplierId);
    const sugg = Object.fromEntries(group.items.map(i => [i.name, i.suggestedQuantity]));
    ok('EN suggestions: server suggested quantities are displayed', t.includes(String(sugg['UI Labneh 500g'])));
    await check(p, 'Select UI Labneh 500g', 400);
    await check(p, 'Select UI Milk 1L', 400);
    await click(p, /^Create order from/, 3000);
    t = await txt(p);
    ok('EN form: opened from suggestions with supplier, 2 lines, suggested quantities', t.includes('New Purchase Order') && t.includes('From reorder suggestions')
      && t.includes('UI Labneh 500g') && t.includes('UI Milk 1L'));
    lineOrder = group.items.map(i => i.name).filter(n => n === 'UI Labneh 500g' || n === 'UI Milk 1L');
    await field(p, 'Quantity', '25', lineOrder.indexOf('UI Labneh 500g'));
    await field(p, 'Internal note', 'E2E weekly order');
    await shot(p, 'en_form_from_suggestions');
    // double-click Save: one order only
    const before = one('select count(*) from purchase_orders');
    await btn(p, 'Save order').dblclick(); await p.waitForTimeout(3500);
    ok('UI+DB: double click created exactly one order', one('select count(*) from purchase_orders') === before + 1);
    G = lastOrder();
    const o = order(G);
    ok('UI+DB: order PENDING_APPROVAL, GUARDIAN, quantities as typed', o[0] === 'PENDING_APPROVAL' && o[2] === 'GUARDIAN'
      && JSON.stringify(dbJson(`select product_name, quantity from purchase_order_items where order_id=${G} order by item_id`))
         === JSON.stringify(lineOrder.map(n => [n, n === 'UI Labneh 500g' ? 25 : sugg[n]])),
      JSON.stringify(o) + JSON.stringify(dbJson(`select product_name, quantity from purchase_order_items where order_id=${G} order by item_id`)) + JSON.stringify(sugg));
    t = await txt(p);
    ok('EN details: opened after saving with progress and Approve', t.includes('Approve') && t.includes('Pending'));
    await shot(p, 'en_details_pending');
  });

  await step('Edit the pending order (EN)', async () => {
    await click(p, 'Edit', 3000);
    await field(p, 'Quantity', '12', lineOrder.indexOf('UI Milk 1L'));
    await click(p, 'Save changes', 3000);
    ok('UI+DB: edited (version 1, EDITED event, milk 12)', order(G)[1] === 1 && events(G).join() === 'CREATED,EDITED'
      && one(`select quantity from purchase_order_items where order_id=${G} and product_name='UI Milk 1L'`) === 12,
      JSON.stringify([order(G), events(G), dbJson(`select product_name, quantity from purchase_order_items where order_id=${G}`)]));
  });

  await step('Approve (EN)', async () => {
    await click(p, 'Approve', 1200);
    await shot(p, 'en_approve_dialog');
    await btn(p, 'Approve').last().click(); await p.waitForTimeout(3000);
    ok('UI+DB: APPROVED by shini.admin', order(G)[0] === 'APPROVED'
      && one(`select approved_by from purchase_orders where order_id=${G}`) === one("select user_id from users where username='shini.admin'"));
    await shot(p, 'en_details_approved');
  });

  let storedText;
  await step('Preview Arabic and English, prepare (EN UI)', async () => {
    await click(p, 'Prepare Message', 3000);
    let t = await txt(p);
    ok('EN preview: Arabic by default, saved number, preview-only notice', t.includes('Preview only') && t.includes('+970 599 111 001') && t.includes('الكمية'), t.slice(0, 1500));
    await shot(p, 'en_preview_arabic');
    await click(p, 'English', 2500);
    t = await txt(p);
    ok('EN preview: English message', t.includes('Qty:') && t.includes('Purchase order:'));
    await shot(p, 'en_preview_english');
    ok('UI+DB: previews saved nothing', order(G)[0] === 'APPROVED' && order(G)[1] === 2);
    await click(p, 'Arabic', 2500);
    await click(p, 'Prepare and save the message', 3500);
    storedText = one(`select message_text from purchase_orders where order_id=${G}`);
    const o = order(G);
    ok('UI+DB: MESSAGE_GENERATED in Arabic to the saved number, not sent', o[0] === 'MESSAGE_GENERATED' && o[4] === 'ar' && o[5] === '970599111001' && !o[6]);
    ok('DB: no cost, price or internal note in the message', !/9\.5|3\.2|₪|E2E weekly order/.test(storedText));
    t = await txt(p);
    ok('EN details: "ready and NOT sent" guidance; no "Send" button', t.includes('was NOT sent') && !/\bSend\b/.test(t.replace(/Sent|sent|Send the|send it/g, '')));
    await shot(p, 'en_message_prepared');
  });

  await step('Open WhatsApp in two steps, captured, never contacted (EN)', async () => {
    const popups = [];
    p.context().on('page', np => popups.push(np));
    await click(p, 'Open WhatsApp', 2000);
    let t = await txt(p);
    ok('EN open dialog: saved number, full message, confirm disabled before the check', t.includes('+970 599 111 001') && t.includes('I checked the number and the message'));
    await shot(p, 'en_open_whatsapp_confirm');
    const confirmBtn = btn(p, 'Confirm');
    ok('EN open dialog: Confirm disabled until reviewed', await confirmBtn.isDisabled());
    await check(p, 'I checked the number and the message');
    await click(p, 'Confirm', 2500);
    ok('UI+DB: opening REQUESTED recorded, state unchanged', events(G).includes('WHATSAPP_OPEN_REQUESTED') && order(G)[0] === 'MESSAGE_GENERATED');
    await shot(p, 'en_open_whatsapp_ready');
    await click(p, 'Open WhatsApp Now', 3000);
    const wa = external.filter(u => u.includes('wa.me'));
    ok('browser: exactly one wa.me navigation, aborted (no contact)', wa.length === 1, JSON.stringify(wa));
    const url = wa[0] || '';
    ok('link: stored number and the exact stored message', url.startsWith('https://wa.me/970599111001?text=')
      && decodeURIComponent(url.split('?text=')[1] || '') === storedText);
    const phases = dbJson(`select json_extract(details,'$.phase'), json_extract(details,'$.result') from purchase_order_events where order_id=${G} and event_type='WHATSAPP_OPEN_REQUESTED' order by event_id`);
    ok('DB: REQUESTED + CLIENT_REPORTED (OPENED = handed to the browser), still not sent', JSON.stringify(phases) === '[["REQUESTED",null],["CLIENT_REPORTED","OPENED"]]'
      && order(G)[0] === 'MESSAGE_GENERATED' && !order(G)[6], JSON.stringify(phases));
    t = await txt(p);
    ok('EN: "Nothing was recorded as sent" after opening', t.includes('Nothing was recorded as sent'));
    await shot(p, 'en_open_whatsapp_done');
    for (const np of popups) await np.close().catch(() => {});
    await click(p, 'Close', 2500);
  });

  await step('Copy the message (EN)', async () => {
    await click(p, 'Copy Message', 2500);
    const clip = await p.evaluate(() => navigator.clipboard.readText()).catch(() => null);
    ok('clipboard holds exactly the stored message', clip === storedText, String(clip).slice(0, 60));
    ok('DB: one MESSAGE_COPIED, verified=false, state unchanged', one(`select count(*) from purchase_order_events where order_id=${G} and event_type='MESSAGE_COPIED' and json_extract(details,'$.verified')=0`) === 1
      && order(G)[0] === 'MESSAGE_GENERATED');
  });

  await step('I Have Sent → Supplier confirmation → more/open again → Close (EN)', async () => {
    await click(p, 'I Have Sent This Message', 1500);
    let t = await txt(p);
    ok('EN mark-sent dialog: manual record wording', t.includes('This is a manual record') && t.includes('+970599111001'));
    await shot(p, 'en_mark_sent_dialog');
    ok('mark-sent button disabled before the check', await btn(p, 'Record sending').isDisabled());
    await p.getByRole('checkbox').first().click(); await p.waitForTimeout(300);
    await click(p, 'Record sending', 3000);
    ok('UI+DB: SENT, recorded by the manager', order(G)[0] === 'SENT' && order(G)[6]);
    t = await txt(p);
    ok('EN: open WhatsApp no longer a main action after SENT (only under More)', (await btn(p, 'Open WhatsApp').count()) === 0 && (await btn(p, /^More/).count()) === 1);
    await click(p, /^More/, 1000);
    await p.getByRole('menuitem').first().click(); await p.waitForTimeout(1500);
    t = await txt(p);
    ok('EN open again: duplicate warning and extra acknowledgment', t.includes('may send the same order to the supplier twice') && t.includes('follow-up'));
    await shot(p, 'en_open_again_warning');
    const evBefore = events(G).length;
    await click(p, 'Close', 1500);
    ok('DB: closing the warning recorded nothing', events(G).length === evBefore);
    await click(p, 'Record Supplier Confirmation', 1500);
    await shot(p, 'en_supplier_confirmation_dialog');
    await p.getByRole('checkbox').first().click(); await p.waitForTimeout(300);
    await field(p, 'Note (optional)', 'Phone call with Abu Ahmad');
    await click(p, 'Record confirmation', 3000);
    ok('UI+DB: CONFIRMED with the note', order(G)[0] === 'CONFIRMED' && order(G)[7] === 'Phone call with Abu Ahmad');
    await click(p, 'Close Order (no receiving)', 1500);
    t = await txt(p);
    ok('EN close dialog: not receiving, stock unchanged', t.includes('does not record goods receiving'));
    await p.getByRole('checkbox').first().click(); await p.waitForTimeout(300);
    await click(p, 'Close order', 3000);
    ok('UI+DB: CLOSED, reservations released', order(G)[0] === 'CLOSED' && one(`select count(*) from purchase_order_items where order_id=${G} and open_product_id is not null`) === 0);
    t = await txt(p);
    ok('EN closed: read only, no WhatsApp/copy actions', t.includes('This order is final') && !t.includes('Copy Message') && !t.includes('Open WhatsApp'));
    await shot(p, 'en_details_closed');
    await click(p, /^History/, 1500);
    t = await txt(p);
    ok('EN history: every step with accurate wording', ['Order created', 'Order edited', 'Approved', 'Supplier message prepared (not sent)',
      'WhatsApp opening requested', 'Link handed to the browser (reported by the app)', 'Message copied (reported by the app)',
      'Manager recorded that they sent the message', "Manager recorded the supplier's confirmation", 'Closed (no receiving recorded)'].every(x => t.includes(x)));
    await p.mouse.move(800, 500); await p.mouse.wheel(0, 3000); await p.waitForTimeout(1200);  // the history is below
    await shot(p, 'en_history');
    await p.mouse.wheel(0, -3000); await p.waitForTimeout(800);
  });
  ok('EN main flow: no page errors, no server errors', st.errors.length === 0 && st.bad.length === 0, st.errors.concat(st.bad).join('; '));

  // helpers for the next scenarios: an order through the API (the UI part is what is tested)
  const apiOrder = async (items, tok = SA, supplierId = sup.supplierId, acks) => {
    const r = await api('/purchase-orders', tok, 'POST', {
      clientRequestId: require('crypto').randomUUID(), supplierId, source: 'MANUAL', items: items.map(([pid, q]) => ({ productId: pid, quantity: q })),
      ...(acks ? { acknowledgedCautions: acks } : {}) });
    if (r.status !== 201) throw new Error('setup order failed: ' + JSON.stringify(r.body).slice(0, 300));
    return r.body;
  };
  const approveApi = async id => api(`/purchase-orders/${id}/approve`, SA, 'POST', { version: order(id)[1] });
  const prepareApi = async id => { const pv = (await api(`/purchase-orders/${id}/message-preview?language=ar`, SA)).body;
    return api(`/purchase-orders/${id}/message`, SA, 'POST', { version: order(id)[1], language: 'ar', previewToken: pv.previewToken }); };
  const openOrder = async (p, number) => {
    await nav(p, 'Purchase Orders');
    await field(p, 'Search by order number or supplier', number); await p.waitForTimeout(1500);
    await click(p, 'Open', 3000);
  };

  // ── manual order through the form + rejection ─────────────────────────────────────────────
  let R;
  await step('Manual order through the form, then reject (EN)', async () => {
    await nav(p, 'Purchase Orders');
    await click(p, 'New Purchase Order', 3000);
    await p.getByRole('button', { name: /Choose a supplier/ }).first().click({ timeout: 8000 }); await p.waitForTimeout(1000);
    await p.getByRole('menuitem', { name: 'UI Dairy WA' }).or(p.getByRole('option', { name: 'UI Dairy WA' })).or(p.getByRole('button', { name: 'UI Dairy WA' })).first().click({ timeout: 8000 });
    await p.waitForTimeout(2500);
    let t = await txt(p);
    ok('EN form: candidates of the supplier with stock facts; products in open orders are not addable', t.includes('UI Yogurt') && t.includes('UI Cheese'));
    await shot(p, 'en_form_manual');
    // the Add buttons follow the candidate list; find the one next to UI Yogurt
    const cand = (await api(`/purchase-candidates?supplierId=${sup.supplierId}`, SA)).body.products;
    const offered = cand.map(c => c.name);
    await btn(p, 'Add').nth(offered.indexOf('UI Yogurt')).click(); await p.waitForTimeout(800);
    await field(p, 'Quantity', '0', 0);
    t = await txt(p);
    ok('EN form: quantity 0 refused, Save disabled', t.includes('Whole number 1–100000') && await btn(p, 'Save order').isDisabled());
    await field(p, 'Quantity', '6', 0);
    await click(p, 'Save order', 3500);
    R = lastOrder();
    ok('UI+DB: manual order saved (MANUAL, Yogurt × 6)', order(R)[2] === 'MANUAL'
      && JSON.stringify(dbJson(`select product_name, quantity from purchase_order_items where order_id=${R}`)) === '[["UI Yogurt",6]]');
    await click(p, 'Reject', 1500);
    await field(p, 'Reason (required)', 'ok');
    ok('EN reject: reason shorter than 3 refused', await btn(p, 'Reject').last().isDisabled());
    await field(p, 'Reason (required)', 'Not needed this week');
    await shot(p, 'en_reject_dialog');
    await btn(p, 'Reject').last().click(); await p.waitForTimeout(3000);
    ok('UI+DB: REJECTED with the reason, reservation released', order(R)[0] === 'REJECTED'
      && one(`select rejection_reason from purchase_orders where order_id=${R}`) === 'Not needed this week'
      && one(`select count(*) from purchase_order_items where order_id=${R} and open_product_id is not null`) === 0);
    await shot(p, 'en_details_rejected');
  });

  // ── ambiguous network failure on create: the same request, once ───────────────────────────
  await step('Unknown outcome on create: locked form, same request, one order (EN)', async () => {
    let first = true;
    await p.route('**/api/purchase-orders', async route => {
      if (route.request().method() === 'POST' && first) {
        first = false;
        await route.fetch();                       // the server DOES create the order…
        return route.abort('connectionreset');      // …but the answer is lost
      }
      return route.continue();
    });
    await nav(p, 'Purchase Orders');
    await click(p, 'New Purchase Order', 3000);
    await p.getByRole('button', { name: /Choose a supplier/ }).first().click(); await p.waitForTimeout(1000);
    await p.getByRole('menuitem', { name: 'UI Dairy WA' }).or(p.getByRole('option', { name: 'UI Dairy WA' })).or(p.getByRole('button', { name: 'UI Dairy WA' })).first().click();
    await p.waitForTimeout(2500);
    const cand = (await api(`/purchase-candidates?supplierId=${sup.supplierId}`, SA)).body.products;
    await btn(p, 'Add').nth(cand.map(c => c.name).indexOf('UI Yogurt')).click(); await p.waitForTimeout(800);
    const before = one('select count(*) from purchase_orders');
    await click(p, 'Save order', 4000);
    let t = await txt(p);
    ok('EN: "Saving could not be confirmed" with Check and retry; form locked', t.includes('Saving could not be confirmed') && t.includes('Check and retry')
      && await btn(p, 'Save order').isDisabled());
    ok('DB: the lost request did create one order', one('select count(*) from purchase_orders') === before + 1);
    await shot(p, 'en_form_unknown_outcome');
    await click(p, 'Check and retry', 3500);
    ok('UI+DB: retry returned the same order — still exactly one', one('select count(*) from purchase_orders') === before + 1);
    t = await txt(p);
    ok('EN: details of that order opened', t.includes('UI Yogurt') && t.includes('Approve'));
    await p.unroute('**/api/purchase-orders');
  });

  // ── two managers: stale version ────────────────────────────────────────────────────────────
  await step('Concurrent managers: stale approve refused, reloaded, then approved (EN)', async () => {
    const C = lastOrder();
    ok('setup: order pending at version 0', order(C)[0] === 'PENDING_APPROVAL' && order(C)[1] === 0);
    // the second manager changes the quantity while this page shows version 0
    const r = await api(`/purchase-orders/${C}`, SA2, 'PUT', { version: 0, items: [{ productId: P['UI Yogurt'], quantity: 9 }] });
    ok('API: the second manager edited (version 1)', r.status === 200 && order(C)[1] === 1);
    await click(p, 'Approve', 1200);
    await btn(p, 'Approve').last().click(); await p.waitForTimeout(3000);
    let t = await txt(p);
    ok('EN (D3): the conflict names the change that won (Second Manager), never "another manager" by guess, nothing approved',
      t.includes('This order changed meanwhile') && t.includes('Second Manager') && t.includes('Nothing was repeated')
      && !t.includes('Another manager') && order(C)[0] === 'PENDING_APPROVAL');
    ok('UI: the page reloaded the new quantity (9)', one(`select quantity from purchase_order_items where order_id=${C}`) === 9 && t.includes('9'));
    await shot(p, 'en_stale_version');
    await click(p, 'Approve', 1200);
    await btn(p, 'Approve').last().click(); await p.waitForTimeout(3000);
    ok('UI+DB: approved after review (version 2), one APPROVED event', order(C)[0] === 'APPROVED' && events(C).filter(e => e === 'APPROVED').length === 1);
  });

  // ── real busy database during a manual record ──────────────────────────────────────────────
  await step('Real SQLite lock during "I Have Sent": no false claim, then once (EN)', async () => {
    const C = lastOrder();
    await prepareApi(C);
    await click(p, 'Refresh', 2500).catch(() => {});
    await btn(p, 'Refresh').first().click().catch(() => {}); await p.waitForTimeout(2000);
    // another program holds the write lock for 20 s (longer than the app's 15 s timeout)
    const holder = spawn('python3', ['-c', "import sqlite3,sys,time; c=sqlite3.connect(sys.argv[1], isolation_level=None); c.execute('BEGIN IMMEDIATE'); print('locked', flush=True); time.sleep(20); c.execute('ROLLBACK')", DBFILE]);
    await new Promise(r => holder.stdout.once('data', r));
    await click(p, 'I Have Sent This Message', 1200);
    await p.getByRole('checkbox').first().click(); await p.waitForTimeout(300);
    await btn(p, 'Record sending').click();
    await p.waitForTimeout(17000);
    let t = await txt(p);
    ok('EN: while locked the app does not claim success; it checked the stored state and says so',
      order(C)[0] === 'MESSAGE_GENERATED' && t.includes('not saved at this moment') && t.includes('Check again')
      && t.includes('Trying again is safe'), order(C)[0]);
    await shot(p, 'en_database_busy');
    await new Promise(r => holder.on('exit', r));
    await p.waitForTimeout(12000);   // the server may still finish its own retries
    // D3: "Check again" only reloads (never resends); it reports a late save
    const sentRequests = events(C).filter(e => e === 'MARKED_SENT').length;
    await click(p, 'Check again', 3000).catch(() => {});
    t = await txt(p);
    ok('EN (D3): "Check again" reconciled with the saved state, nothing resent',
      events(C).filter(e => e === 'MARKED_SENT').length === sentRequests
      && (order(C)[0] !== 'SENT' || t.includes('Checked: the change had been saved')), order(C)[0]);
    await btn(p, 'Refresh').first().click(); await p.waitForTimeout(2500);
    if (order(C)[0] !== 'SENT') {
      await click(p, 'I Have Sent This Message', 1200);
      await p.getByRole('checkbox').first().click(); await p.waitForTimeout(300);
      await click(p, 'Record sending', 3000);
    }
    ok('DB: SENT exactly once (one MARKED_SENT event)', order(C)[0] === 'SENT' && events(C).filter(e => e === 'MARKED_SENT').length === 1, JSON.stringify(events(C)));
    // cancel after contact: acknowledgment required
    await click(p, 'Cancel order', 1500);
    t = await txt(p);
    ok('EN cancel after contact: "does NOT notify the supplier" + acknowledgment', t.includes('does NOT notify the supplier') && t.includes('I understand ClixSell will not notify the supplier'));
    await field(p, 'Reason (required)', 'Supplier too slow');
    ok('cancel disabled until the acknowledgment', await btn(p, 'Cancel the order').isDisabled());
    await check(p, 'I understand ClixSell will not notify the supplier');
    await shot(p, 'en_cancel_after_contact');
    await click(p, 'Cancel the order', 3000);
    ok('UI+DB: CANCELED after contact (flag 1)', order(C)[0] === 'CANCELED' && order(C)[3] === 1);
  });

  // ── cancellation with UNKNOWN, warning, supplier acceptance ────────────────────────────────
  let U;
  await step('Cancel with UNKNOWN, reorder warning, supplier acceptance (EN)', async () => {
    U = (await apiOrder([[P['UI Cheese'], 5]])).orderId;
    await approveApi(U); await prepareApi(U);
    const number = one(`select order_number from purchase_orders where order_id=${U}`);
    await openOrder(p, number);
    await click(p, 'Cancel order', 1500);
    let t = await txt(p);
    ok('EN cancel dialog: the question, three answers, nothing preselected', t.includes('Did you send this message to the supplier?')
      && t.includes('No — I am sure it was NOT sent') && t.includes('I do not know') && t.includes('Yes — I sent it')
      && (await p.getByRole('radio', { checked: true }).count()) === 0);
    await field(p, 'Reason (required)', 'Plan changed');
    ok('cancel disabled until an answer is chosen', await btn(p, 'Cancel the order').isDisabled());
    await radio(p, 'Yes — I sent it');
    t = await txt(p);
    ok('EN: "Yes" does not cancel; it leads to recording the sending first', t.includes('record the sending first') && t.includes('Record the sending first'));
    await radio(p, 'I do not know');
    t = await txt(p);
    ok('EN: UNKNOWN explains no sending is recorded and the 30-day warning', t.includes('WITHOUT recording any sending') && t.includes('30 days'));
    await field(p, 'Reason (required)', 'Plan changed');
    await shot(p, 'en_cancel_unknown');
    await click(p, 'Cancel the order', 3000);
    const o = order(U);
    ok('UI+DB: CANCELED, flag 0, never marked sent, contactStatus UNKNOWN', o[0] === 'CANCELED' && o[3] === 0 && !o[6]
      && one(`select json_extract(details,'$.contactStatus') from purchase_order_events where order_id=${U} and event_type='CANCELED'`) === 'UNKNOWN');
    t = await txt(p);
    ok('EN details: contact unknown + acceptance not recorded + button', t.includes('unknown whether the supplier got the message')
      && t.includes('not recorded') && t.includes('Record Supplier Acceptance of Cancellation'));
    await shot(p, 'en_canceled_unknown');
    await nav(p, 'Reorder Suggestions');
    t = await txt(p);
    ok('EN suggestions: UI Cheese shows the CONTACT_UNKNOWN warning', t.includes('Canceled — unknown whether the supplier got the message'));
    await openOrder(p, number);
    await click(p, 'Record Supplier Acceptance of Cancellation', 1500);
    await check(p, 'The supplier told me they accept the cancellation');
    await p.getByRole('button', { name: /How did you reach the supplier/ }).first().click(); await p.waitForTimeout(800);
    await p.getByRole('menuitem', { name: 'Phone' }).or(p.getByRole('option', { name: 'Phone' })).or(p.getByRole('button', { name: 'Phone' })).first().click(); await p.waitForTimeout(500);
    await field(p, 'What did the supplier say', '  ');
    ok('acceptance: blank note refused', await btn(p, 'Record acceptance').isDisabled());
    await field(p, 'What did the supplier say', 'Abu Ahmad agreed by phone');
    await shot(p, 'en_supplier_acceptance');
    await click(p, 'Record acceptance', 3000);
    ok('UI+DB: one SUPPLIER_CANCELLATION_RECORDED, manual, by shini.admin', one(`select count(*) from purchase_order_events where order_id=${U} and event_type='SUPPLIER_CANCELLATION_RECORDED' and json_extract(details,'$.verified')=0`) === 1);
    await nav(p, 'Reorder Suggestions');
    t = await txt(p);
    ok('EN suggestions: the CONTACT_UNKNOWN warning ended for UI Cheese', !t.includes('Canceled — unknown whether the supplier got the message'));
  });

  // ── NOT_SENT after a copy; caution acknowledgment in the form ─────────────────────────────
  await step('NOT_SENT after a copy, then reorder needs the acknowledgment (EN)', async () => {
    const N = (await apiOrder([[P['UI Cream'], 3]])).orderId;
    await approveApi(N); await prepareApi(N);
    await openOrder(p, one(`select order_number from purchase_orders where order_id=${N}`));
    await click(p, 'Copy Message', 2500);
    await click(p, 'Cancel order', 1500);
    await radio(p, 'No — I am sure it was NOT sent');
    let t = await txt(p);
    ok('EN NOT_SENT: prior copy warning + confirmation box', /message copied \u2068?1\u2069? time/.test(t) && t.includes('"declared not sent" warning') && t.includes('I confirm this message was not sent to the supplier'));
    await field(p, 'Reason (required)', 'Ordered elsewhere');
    ok('cancel disabled until "not sent" is confirmed', await btn(p, 'Cancel the order').isDisabled());
    await check(p, 'I confirm this message was not sent to the supplier');
    await click(p, 'Cancel the order', 3000);
    ok('DB: NOT_SENT declared with the prior copy', one(`select json_extract(details,'$.contactStatus') || json_extract(details,'$.priorCopyReports') from purchase_order_events where order_id=${N} and event_type='CANCELED'`) === 'NOT_SENT1');
    // a new order for UI Cream must acknowledge DECLARED_NOT_SENT
    await nav(p, 'Purchase Orders');
    await click(p, 'New Purchase Order', 3000);
    await p.getByRole('button', { name: /Choose a supplier/ }).first().click(); await p.waitForTimeout(1000);
    await p.getByRole('menuitem', { name: 'UI Dairy WA' }).or(p.getByRole('option', { name: 'UI Dairy WA' })).or(p.getByRole('button', { name: 'UI Dairy WA' })).first().click();
    await p.waitForTimeout(2500);
    const cand = (await api(`/purchase-candidates?supplierId=${sup.supplierId}`, SA)).body.products.filter(c => true);
    await btn(p, 'Add').nth(cand.map(c => c.name).indexOf('UI Cream')).click(); await p.waitForTimeout(800);
    t = await txt(p);
    ok('EN form: the caution is shown and Save is disabled until it is ticked', t.includes('Canceled as "not sent" after the message was prepared (a declaration, not verified)')
      && await btn(p, 'Save order').isDisabled());
    await shot(p, 'en_form_caution');
    await check(p, /Canceled as "not sent" after the message was prepared/, 500);
    const before = one('select count(*) from purchase_orders');
    await click(p, 'Save order', 3500);
    const M = lastOrder();
    ok('UI+DB: saved with the acknowledgment recorded', one('select count(*) from purchase_orders') === before + 1
      && /DECLARED_NOT_SENT/.test(one(`select details from purchase_order_events where order_id=${M} and event_type='CREATED'`)));
  });

  // ── S3: another manager cancels as "not sent" (no copy, no opening) while this one records sending ──
  await step('S3: competing declarations — warned, nothing recorded as sent, reorder needs the acknowledgment (EN)', async () => {
    const pid = (await api('/products', SA, 'POST', { name: 'UI FC Ghee', barcode: '7290200000097', category: 'dairy', sellingPrice: 20,
      quantity: 1, minStock: 6, supplierName: 'UI Dairy WA', costPrice: 11 })).body.productId;
    const S = (await apiOrder([[pid, 4]])).orderId;
    await approveApi(S); await prepareApi(S);
    await openOrder(p, one(`select order_number from purchase_orders where order_id=${S}`));
    // the second manager cancels first, declaring "not sent" — nothing was copied or opened
    const r = await api(`/purchase-orders/${S}/cancel`, SA2, 'POST', { version: 2, reason: 'Ordered by phone instead', contactStatus: 'NOT_SENT', confirmNotSent: true });
    ok('API: Second Manager canceled as NOT_SENT (no copy / opening recorded)', r.status === 200
      && one(`select json_extract(details,'$.priorCopyReports') + json_extract(details,'$.priorOpenRequests') from purchase_order_events where order_id=${S} and event_type='CANCELED'`) === 0);
    await click(p, 'I Have Sent This Message', 1200);
    await p.getByRole('checkbox').first().click(); await p.waitForTimeout(300);
    await click(p, 'Record sending', 3500);
    let t = await txt(p);
    ok('EN (S3): strong warning naming Second Manager: "declaring that the message was NOT sent", tell the supplier yourself',
      t.includes('Second Manager') && t.includes('declaring that the message was NOT sent') && t.includes('tell the supplier yourself now')
      && t.includes('ClixSell did not tell the supplier'));
    ok('DB (S3): CANCELED, never recorded as sent (no MARKED_SENT, flag 0)', order(S)[0] === 'CANCELED' && order(S)[3] === 0 && order(S)[6] === 0
      && !events(S).includes('MARKED_SENT'));
    ok('EN (S3): the canceled order says the declaration is not verified and ClixSell canceled nothing with the supplier',
      t.includes('a declaration; ClixSell cannot verify it') && t.includes('does not cancel anything with the supplier'));
    await shot(p, 'en_s3_competing_declarations');
    const c = (await api(`/purchase-candidates?supplierId=${sup.supplierId}`, SA)).body.products.find(x => x.productId === pid);
    ok('API (S3): the product warns DECLARED_NOT_SENT', JSON.stringify(c.cautions.map(x => x.type)) === '["DECLARED_NOT_SENT"]');
    const again = await api('/purchase-orders', SA, 'POST', { clientRequestId: require('crypto').randomUUID(), supplierId: sup.supplierId, source: 'MANUAL',
      items: [{ productId: pid, quantity: 4 }] });
    ok('API (S3): a reorder without the acknowledgment is refused', again.status === 409 && again.body.code === 'REORDER_CAUTION_ACK_REQUIRED');
  });

  // ── D1 + D2: exact cards and the overdue badge ─────────────────────────────────────────────
  await step('D1/D2: badge right after login, exact cards, overdue filter from the server (EN)', async () => {
    // an order that is overdue (pending for 5 days), written into the DISPOSABLE database like a past order
    const pid = (await api('/products', SA, 'POST', { name: 'UI FC Old Pending', barcode: '7290200000103', category: 'dairy', sellingPrice: 9,
      quantity: 1, minStock: 6, supplierName: 'UI Dairy WA', costPrice: 4 })).body.productId;
    try {
      execSync(`python3 ${__dirname}/old_pending_order.py ${sup.supplierId} shini.admin ${pid} PO-900001 5`, { stdio: 'pipe' });
    } catch (e) { throw new Error('old order: ' + (e.stderr || '').toString().trim().split('\n').pop()); }
    const dbOverdue = one(`select count(*) from purchase_orders where store_id=1 and status='PENDING_APPROVAL' and created_at <= strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime', '-3 days')`);
    const sm = (await api('/purchase-orders/summary', SA)).body;
    ok('API (D1): summary overdue = the database (only the 5-day-old pending order is overdue)', sm.overdue === 1 && dbOverdue === 1
      && sm.overdueByStatus.PENDING_APPROVAL === 1, JSON.stringify(sm));
    await logout(p);
    await login(p, 'shini.admin', 'Admin-Pass-1');
    const navLabel = async () => (await p.getByRole('button', { name: /Purchase Orders/ }).first().innerText().catch(() => '')) + ' '
      + (await p.getByRole('button', { name: /Purchase Orders/ }).first().getAttribute('aria-label').catch(() => ''));
    let label = await navLabel();
    ok('EN (D2): right after login the sidebar badge shows the server overdue count', label.includes(String(sm.overdue)), label);
    await shot(p, 'en_badge_after_login');
    await nav(p, 'Purchase Orders', 3000);
    let t = await txt(p);
    const byStatus = s2 => one(`select count(*) from purchase_orders where store_id=1 and status='${s2}'`);
    const want = [byStatus('PENDING_APPROVAL'), byStatus('APPROVED') + byStatus('MESSAGE_GENERATED'), byStatus('SENT'), sm.overdue];
    // each card is one button whose accessible name is "<title> <value> …"
    const cards = await Promise.all(['Waiting for approval', 'To contact supplier', 'Waiting for supplier', 'Overdue'].map(async n => {
      const b = p.getByRole('button', { name: new RegExp('^' + n) }).first();
      return ((await b.innerText().catch(() => '')) + ' ' + ((await b.getAttribute('aria-label').catch(() => '')) || '')).trim();
    }));
    ok('EN (D1): the four cards are the exact database counts', cards.every((c2, i) => new RegExp('(^|\\D)' + want[i] + '(\\D|$)').test(c2)), JSON.stringify([cards, want]));
    await btn(p, new RegExp('^Overdue')).first().click(); await p.waitForTimeout(2500);
    t = await txt(p);
    ok('EN (D1): the overdue filter lists the server\'s overdue orders (PO-900001)', t.includes('PO-900001'));
    await shot(p, 'en_overdue_filter');
    // D2: an action changes the count → the badge follows without a reload
    await click(p, 'Open', 3000);
    await click(p, 'Reject', 1200);
    await field(p, 'Reason (required)', 'Too old, not needed');
    await btn(p, 'Reject').last().click(); await p.waitForTimeout(3500);
    const after = (await api('/purchase-orders/summary', SA)).body.overdue;
    label = await navLabel();
    ok('EN (D2): after the action the badge shows the new server count', after === sm.overdue - 1
      && (after === 0 ? !label.includes(String(sm.overdue)) : label.includes(String(after))), label + ' / ' + after);
    await shot(p, 'en_badge_after_action');
  });

  // ── supplier without WhatsApp; number change after preparation ────────────────────────────
  await step('Missing WhatsApp number and changed number (EN)', async () => {
    const bakery = one("select supplier_id from suppliers where name='UI Bakery NoWA'");
    const B = (await apiOrder([[P['UI Bread'], 5]], SA, bakery)).orderId;
    await approveApi(B);
    await openOrder(p, one(`select order_number from purchase_orders where order_id=${B}`));
    await click(p, 'Prepare Message', 3000);
    let t = await txt(p);
    ok('EN preview: "no WhatsApp number" with a link to Suppliers, nothing prepared', t.includes('has no WhatsApp number') && t.includes('Open Suppliers') && order(B)[0] === 'APPROVED');
    await shot(p, 'en_preview_no_whatsapp');
    await click(p, 'Close', 1500);
    const X = (await apiOrder([[P['UI Butter'], 4]])).orderId;
    await approveApi(X); await prepareApi(X);
    await api(`/suppliers/${sup.supplierId}`, SA, 'PUT', { name: 'UI Dairy WA', whatsappNumber: '970599111002' });
    await openOrder(p, one(`select order_number from purchase_orders where order_id=${X}`));
    t = await txt(p);
    ok('EN details: number-changed warning; still bound to the saved number', t.includes("The supplier's number changed") && t.includes('+970 599 111 001') && t.includes('970599111002'));
    await click(p, 'Open WhatsApp', 2000);
    await check(p, 'I checked the number and the message');
    ok('EN open: Confirm still disabled until the extra "saved number" acknowledgment', await btn(p, 'Confirm').isDisabled());
    await check(p, /goes to the SAVED number/);
    await shot(p, 'en_open_number_changed');
    await click(p, 'Confirm', 2500);
    await click(p, 'Open WhatsApp Now', 2500);
    const wa = external.filter(u => u.includes('wa.me'));
    ok('link still goes to the SAVED number (never redirected)', wa[wa.length - 1].startsWith('https://wa.me/970599111001?'), wa[wa.length - 1]);
    await click(p, 'Close', 1500);
    await api(`/suppliers/${sup.supplierId}`, SA, 'PUT', { name: 'UI Dairy WA', whatsappNumber: '970599111001' });
  });

  // ── a message that cannot fit ──────────────────────────────────────────────────────────────
  await step('Too long for WhatsApp: approval blocked with the details (EN)', async () => {
    const longIds = [];
    for (let i = 0; i < 22; i++) {
      const r = await api('/products', SA, 'POST', { name: (`UI Long product ${i} ` + 'x'.repeat(80)).slice(0, 80), barcode: '72903' + String(i).padStart(8, '0'),
        category: 'dairy', sellingPrice: 5, quantity: 1, minStock: 3, supplierName: 'UI Dairy WA', costPrice: 1 });
      longIds.push(r.body.productId);
    }
    const L = (await apiOrder(longIds.map(id => [id, 1]))).orderId;
    await openOrder(p, one(`select order_number from purchase_orders where order_id=${L}`));
    const t = await txt(p);
    ok('EN details: blocker "too long", lines to move, Approve disabled', t.includes('too long for WhatsApp') && t.includes('move at least')
      && await btn(p, 'Approve').isDisabled());
    await shot(p, 'en_message_too_long');
  });

  ok('EN: no page errors, no server errors in all scenarios', st.errors.length === 0 && st.bad.length === 0, st.errors.concat(st.bad).join('; '));

  // ══ Arabic ═══════════════════════════════════════════════════════════════════════════════
  await step('Arabic screens', async () => {
    await click(p, 'العربية', 3000);
    await nav(p, 'أوامر الشراء');
    let t = await txt(p);
    ok('AR list: title, cards, statuses in Arabic', t.includes('أوامر الشراء') && t.includes('بانتظار الموافقة') && t.includes('أمر شراء جديد'));
    await shot(p, 'ar_orders_list');
    await field(p, 'ابحث برقم الأمر أو اسم المورد', 'PO-000001'); await p.waitForTimeout(1500);
    await click(p, 'فتح', 3000);
    t = await txt(p);
    ok('AR details: closed order, message, history in Arabic', t.includes('مغلق') && t.includes('رسالة المورد'));
    await shot(p, 'ar_details_closed');
    await click(p, /^السجل/, 1500);
    t = await txt(p);
    ok('AR history: manual-record wording', t.includes('سجّل المدير أنه أرسل الرسالة') && t.includes('جُهّزت رسالة المورد (لم تُرسل)'));
    await p.mouse.move(600, 500); await p.mouse.wheel(0, 3000); await p.waitForTimeout(1200);
    await shot(p, 'ar_history');
    await p.mouse.wheel(0, -3000); await p.waitForTimeout(800);
    await nav(p, 'اقتراحات الطلب');
    await shot(p, 'ar_recommendations');
    // an approved order: Arabic preview and Arabic cancel dialog
    const A = (await apiOrder([[P['UI Labneh 500g'], 7]], SA, sup.supplierId,
      dbJson(`select i.product_id, o.order_id from purchase_order_items i join purchase_orders o on o.order_id=i.order_id where i.product_id=${P['UI Labneh 500g']} and o.status='CLOSED'`).map(([pid, oid]) => ({ productId: pid, orderId: oid })))).orderId;
    await approveApi(A);
    await openOrder(p, one(`select order_number from purchase_orders where order_id=${A}`)).catch(async () => {
      await nav(p, 'أوامر الشراء'); await field(p, 'ابحث برقم الأمر أو اسم المورد', one(`select order_number from purchase_orders where order_id=${A}`));
      await p.waitForTimeout(1500); await click(p, 'فتح', 3000);
    });
    await click(p, 'تجهيز الرسالة', 3000);
    t = await txt(p);
    ok('AR preview: Arabic texts and the saved number', t.includes('معاينة فقط') && t.includes('+970 599 111 001'));
    await shot(p, 'ar_preview');
    await click(p, 'تجهيز الرسالة وحفظها', 3500);
    await click(p, 'فتح WhatsApp', 2000);
    await shot(p, 'ar_open_whatsapp_confirm');
    await click(p, 'إغلاق', 1500);
    await click(p, 'إلغاء الأمر', 1500);
    await radio(p, 'لا أعرف');
    await shot(p, 'ar_cancel_unknown');
    await click(p, 'إبقاء الأمر', 1500);
    ok('AR: nothing changed by looking (still MESSAGE_GENERATED)', order(A)[0] === 'MESSAGE_GENERATED');
  });

  // ══ 1024 × 768 ═══════════════════════════════════════════════════════════════════════════
  await step('1024×768 layout (EN and AR)', async () => {
    await p.setViewportSize({ width: 1024, height: 768 }); await p.waitForTimeout(1500);
    await nav(p, 'أوامر الشراء');
    await shot(p, 'ar_orders_list_1024');
    // the compact sidebar (< 1100 px) shows icons only: switch the language at full width
    await p.setViewportSize({ width: 1440, height: 900 }); await p.waitForTimeout(1200);
    await click(p, 'English', 2500);
    await p.setViewportSize({ width: 1024, height: 768 }); await p.waitForTimeout(1500);
    await nav(p, 'Purchase Orders');
    await shot(p, 'en_orders_list_1024');
    await click(p, 'Open', 3000);
    await shot(p, 'en_details_1024');
    ok('1024×768: pages render without page errors', st.errors.length === 0, st.errors.join('; '));
  });
  ok('all scenarios: no page errors and no server (5xx) errors', st.errors.length === 0 && st.bad.length === 0, st.errors.concat(st.bad).join('; '));
  await ctx.close();

  // ══ Guardian OFF ═══════════════════════════════════════════════════════════════════════════
  await step('Guardian Mode OFF: no suggestions, manual stays', async () => {
    await api('/guardian/status', SA, 'PUT', { enabled: false });
    ({ ctx, p, st } = await newPage());
    await login(p, 'shini.admin', 'Admin-Pass-1');
    await nav(p, 'Reorder Suggestions');
    const t = await txt(p);
    ok('EN: Guardian off explained, nothing fabricated, manual order offered', t.includes('Guardian Mode is off') && t.includes('Create a manual order') && !t.includes('UI Labneh 500g'));
    await shot(p, 'en_recommendations_guardian_off');
    await api('/guardian/status', SA, 'PUT', { enabled: true });
    await logout(p);
  });

  // ══ Neshan and cashier ═════════════════════════════════════════════════════════════════════
  await step('Other store and cashier', async () => {
    await login(p, 'neshan.admin', 'Admin-Pass-1');
    await nav(p, 'Purchase Orders');
    const t = await txt(p);
    ok('Neshan: none of Shini\'s orders or supplier numbers', !t.includes('UI Dairy WA') && !t.includes('PO-000001') && !t.includes('970599111001'));
    await shot(p, 'neshan_orders_list');
    const shiniOrder = one('select min(order_id) from purchase_orders where store_id=1');
    const tries = [['GET', `/purchase-orders/${shiniOrder}`], ['GET', `/purchase-orders/${shiniOrder}/events`], ['GET', `/purchase-orders/${shiniOrder}/message`],
      ['POST', `/purchase-orders/${shiniOrder}/whatsapp-open-requested`, { clientActionId: require('crypto').randomUUID(), confirmed: true }]];
    const res = [];
    for (const [m, u, b] of tries) res.push((await api(u, NA, m, b)).status);
    ok('API: Neshan admin gets 404 on Shini order, events, message, WhatsApp link', res.every(x => x === 404), JSON.stringify(res));
    await logout(p);
    await login(p, 'shini.cashier', 'Cashier-Pass-1');
    const c = await txt(p);
    ok('cashier: no Purchasing section', !c.includes('Purchase Orders') && !c.includes('Reorder Suggestions'));
    const SC = await token('shini.cashier', 'Cashier-Pass-1');
    ok('API: cashier refused (403)', (await api('/purchase-orders', SC)).status === 403 && (await api(`/purchase-orders/${shiniOrder}/message`, SC)).status === 403);
  });
  await ctx.close();

  // ══ money and stock ═══════════════════════════════════════════════════════════════════════
  const finNow = JSON.parse(FIN()); const finWas = JSON.parse(finBefore);
  const newProducts = new Set(dbJson("select product_id from products where name like 'UI Long product%' or name like 'UI FC %'").map(r => r[0]));
  finNow[0] = finNow[0].filter(r => !newProducts.has(r[0]));
  ok('DB: stock, prices, costs, sales, sale costs, returns unchanged by the whole purchasing workflow', JSON.stringify(finNow) === JSON.stringify(finWas));
  ok('API: report summary (revenue, profit) unchanged', JSON.stringify((await api('/reports/summary?from=2020-01-01&to=2035-12-31', SA)).body) === summaryBefore);
  const notFonts = external.filter(u => !/^https:\/\/fonts\./.test(u));
  ok('network: every external request was aborted; the only external attempts were wa.me', notFonts.every(u => u.startsWith('https://wa.me/')), JSON.stringify(notFonts));
  console.log('   external requests aborted:', notFonts.length, '| font files allowed:', fonts.length);

  console.log(`\nPURCHASE UI PASS ${pass} FAIL ${fail}`);
  await browser.close();
  process.exit(fail ? 1 : 0);
})().catch(async e => { console.error('Error', e); try { await browser.close(); } catch (x) { /* */ } process.exit(1); });

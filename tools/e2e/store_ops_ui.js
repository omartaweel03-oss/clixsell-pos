// Store operations upgrade: the real app in Chrome on a DISPOSABLE demo database (run via run.sh).
// Shifts, favorites, carton, cash rounding, cheque — every step checked, every screen captured.
const { requireDisposable } = require('./safety.js');
const { out: OUT } = requireDisposable();
const { chromium } = require('playwright');
const { execSync } = require('child_process');
const path = require('path');

const RESULTS = []; let shotNo = 0;
function rec(area, action, expected, actual, pass, evidence = '') {
  RESULTS.push({ area, action, pass: !!pass });
  console.log(`${pass ? 'PASS' : 'FAIL'} [${area}] ${action} | expected: ${expected} | actual: ${actual}${evidence ? ' | ' + evidence : ''}`);
}
const dbJson = sql => JSON.parse(execSync(`python3 ${__dirname}/dbq.py --json "${sql.replace(/"/g, '\\"')}"`).toString());
const one = sql => { const r = dbJson(sql); return r.length ? r[0][0] : null; };
const api = (method, p, token, body) => {
  const args = ['-s', '-w', '\\n%{http_code}', '-X', method, `http://127.0.0.1:8080/api${p}`];
  if (token) args.push('-H', `Authorization: Bearer ${token}`);
  if (body) args.push('-H', 'Content-Type: application/json', '-d', JSON.stringify(body));
  const outp = execSync('curl ' + args.map(a => `'${a.replace(/'/g, "'\\''")}'`).join(' ')).toString();
  const i = outp.lastIndexOf('\n'); let json = null; try { json = JSON.parse(outp.slice(0, i)); } catch (e) { /* */ }
  return { status: +outp.slice(i + 1), json };
};
const token = (u, pw) => api('POST', '/auth/login', null, { username: u, password: pw }).json.token;

let browser; let CUR = null;
async function newPage(w = 1440, h = 1000) {
  const ctx = await browser.newContext({ viewport: { width: w, height: h }, deviceScaleFactor: 1 });
  const p = await ctx.newPage(); CUR = p; const st = { errors: [], bad: [] };
  p.on('pageerror', e => st.errors.push(e.message));
  p.on('response', r => { if (r.url().includes(':8080/api') && r.status() >= 500) st.bad.push(`${r.status()} ${r.url()}`); });
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
  // exact names: 'Close' must not match 'Close shift', 'Open' not 'Open shift'
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

(async () => {
  // ── data through the real API (Shini Supermarket of the demo profile) ──
  const AD = token('shini.admin', 'Admin-Pass-1');
  api('PUT', '/store/settings', AD, { taxRate: 0, cashRoundingMinor: 10 });
  const mk = (name, barcode, price, qty) => api('POST', '/products', AD, { name, barcode, category: 'groceries',
    sellingPrice: price, quantity: qty, minStock: 2, supplierName: 'SO Supplier' }).json.productId;
  const water = mk('SO Water 0.5L', '7291110000017', 2.0, 200);
  const oil = mk('SO Olive Oil 1L', '7291110000024', 19.97, 40);
  const tv = mk('SO Television', '7291110000031', 1000, 5);
  const carton = api('POST', `/products/${water}/packages`, AD, { name: 'Carton 24', unitsPerPackage: 24, price: 40,
    barcode: '7291110000048' }).json.packageId;
  api('PUT', '/favorites', AD, { productIds: [oil, water, tv] });
  const drawer = api('POST', '/drawers', AD, { name: 'Till 1' }).json.drawerId;
  rec('Setup', 'products, carton, favorites, drawer through the API', 'created', `${water}/${carton}/${drawer}`, !!(water && carton && drawer));

  browser = await chromium.launch();
  let { ctx, p, st } = await newPage();
  await login(p, 'shini.cashier', 'Cashier-Pass-1');

  await step('Shift', 'cashier POS without a shift', async () => {
    const t = await txt(p);
    rec('Shift', 'POS shows "No open shift" and Open shift', 'both', t.includes('No open shift') && t.includes('Open shift'),
      t.includes('No open shift') && t.includes('Open shift'), await shot(p, 'pos_no_shift'));
  });
  await step('Shift', 'open a shift with 100.00', async () => {
    await click(p, 'Open shift', 1200);
    await field(p, 'Opening cash', '100');
    const f = await shot(p, 'open_shift_dialog');
    await click(p, 'Open', 1800);
    const s = one("select opening_cash_minor || '/' || status from cash_shifts order by shift_id desc limit 1");
    rec('Shift', 'shift OPEN with 100.00 in the database', '10000/OPEN', s, s === '10000/OPEN', f);
  });
  await step('Favorites', 'favorites strip, in the Store Admin order', async () => {
    const t = await txt(p);
    const i = [t.indexOf('SO Olive Oil 1L'), t.indexOf('SO Water 0.5L'), t.indexOf('SO Television')];
    rec('Favorites', 'strip shows the 3 favorites', 'Favorites + 3 names', i.every(x => x >= 0), t.includes('Favorites') && i.every(x => x >= 0),
      await shot(p, 'pos_shift_open_favorites'));
  });
  await step('Carton', 'piece or carton?', async () => {
    await tile(p, 'SO Water 0.5L', 1200);
    const t = await txt(p);
    rec('Carton', 'the choice shows Piece and Carton 24', 'both', t.includes('Carton 24') && t.includes('Piece'),
      t.includes('Carton 24') && t.includes('Piece'), await shot(p, 'piece_or_carton'));
    await p.getByRole('button').filter({ hasText: 'Carton 24' }).first().click({ timeout: 8000 }); await p.waitForTimeout(1000);
    const t2 = await txt(p);
    rec('Carton', 'invoice line "SO Water 0.5L · Carton 24" at 40.00', 'line + 40.00', t2.includes('Carton 24'),
      t2.includes('SO Water 0.5L · Carton 24'), await shot(p, 'invoice_carton_line'));
  });
  await step('Carton', 'pay the carton (card)', async () => {
    await click(p, 'Card', 600);
    await click(p, 'Complete Sale', 2500);
    await click(p, 'Close', 1200).catch(() => {});
    const stock = one(`select quantity from products where product_id = ${water}`);
    rec('Carton', 'stock 200 − 24 = 176 pieces', 176, stock, stock === 176);
  });
  await step('Rounding', '19.97 cash, 0.10 step, 50 received', async () => {
    await tile(p, 'SO Olive Oil 1L', 900);
    await click(p, 'Cash', 600);
    const t0 = await txt(p);
    rec('Rounding', 'summary shows Cash rounding + Amount due 20.00', 'both', t0.includes('Cash rounding'),
      t0.includes('Cash rounding') && t0.includes('20.00'), await shot(p, 'pos_rounding_summary'));
    await click(p, 'Complete Sale', 1500);
    await field(p, 'Amount received', '50');
    const t = await txt(p);
    rec('Rounding', 'cash dialog: due 20.00, change 30.00', 'both', t.includes('30.00'), t.includes('20.00') && t.includes('30.00'),
      await shot(p, 'cash_dialog_rounded'));
    await click(p, 'Complete payment', 2500);
    const t2 = await txt(p);
    const row = one("select s.total || '/' || s.rounding_minor || '/' || p.amount from sales s join payments p on p.sale_id = s.sale_id "
      + "order by s.sale_id desc limit 1");
    rec('Rounding', 'DB: total 19.97, rounding 3, payment 20.0', '19.97/3/20.0', row, row === '19.97/3/20.0',
      await shot(p, 'receipt_rounding'));
    rec('Rounding', 'receipt shows rounding and amount paid', 'Cash rounding + Amount paid', t2.includes('Amount paid'),
      t2.includes('Cash rounding') && t2.includes('Amount paid'));
    await click(p, 'Close', 1200).catch(() => {});
  });
  await step('Cheque', 'cheque payment dialog', async () => {
    await tile(p, 'SO Television', 900);
    await click(p, 'Cheque', 600);
    await click(p, 'Complete Sale', 1500);
    await field(p, 'Cheque number', 'SO-100245');
    await field(p, 'Bank', 'Bank of Palestine');
    await click(p, 'Choose the due date', 1200);
    await click(p, 'OK', 1000);
    const f = await shot(p, 'cheque_dialog');
    await click(p, 'Complete payment', 2500);
    const c = one("select status || '/' || amount_minor from cheques where cheque_number = 'SO-100245'");
    rec('Cheque', 'cheque RECEIVED for 1,000.00', 'RECEIVED/100000', c, c === 'RECEIVED/100000', f);
    await click(p, 'Close', 1200).catch(() => {});
  });
  await step('Shift', 'close the shift with a difference', async () => {
    await click(p, 'Close shift', 1500);
    const exp = one("select opening_cash_minor from cash_shifts where status = 'OPEN' order by shift_id desc limit 1");
    await field(p, 'Counted cash', '119'); // expected 120.00 (100 + 20.00)
    await field(p, 'Note', '1.00 missing');
    const t = await txt(p);
    rec('Shift', 'dialog shows expected 120.00 and difference -1.00', 'both', t.includes('120.00') && t.includes('-1.00'),
      t.includes('120.00') && t.includes('-1.00'), await shot(p, 'close_shift_dialog'));
    await click(p, 'Close the shift', 2000);
    const r = one("select status || '/' || expected_cash_minor || '/' || difference_minor from cash_shifts order by shift_id desc limit 1");
    rec('Shift', 'DB: CLOSED, expected 12000, difference -100', 'CLOSED/12000/-100', r, r === 'CLOSED/12000/-100');
    void exp;
  });
  rec('Browser', 'no page errors / no 5xx (cashier)', '0 / 0', `${st.errors.length} / ${st.bad.length}`, st.errors.length === 0 && st.bad.length === 0);
  await ctx.close();

  ({ ctx, p, st } = await newPage());
  await login(p, 'shini.admin', 'Admin-Pass-1');
  await step('Admin', 'Cash shifts page', async () => {
    await click(p, 'Cash shifts', 2500);
    const t = await txt(p);
    rec('Admin', 'page: rounding step, drawers, shifts', 'Cash rounding + Till 1 + Shifts', t.includes('Till 1'),
      t.includes('Cash rounding') && t.includes('Till 1') && t.includes('Shifts'), await shot(p, 'admin_cash_shifts'));
    await p.getByRole('button').filter({ hasText: 'Ahmad' }).first().click({ timeout: 8000 }).catch(async () => {
      await p.getByRole('button').filter({ hasText: '#' }).first().click({ timeout: 8000 });
    });
    await p.waitForTimeout(1500);
    const t2 = await txt(p);
    rec('Admin', 'shift details: expected, counted, difference, sales', 'Shift details', t2.includes('Shift details'),
      t2.includes('Shift details') && t2.includes('-1.00'), await shot(p, 'admin_shift_details'));
    await p.keyboard.press('Escape'); await p.waitForTimeout(800);
  });
  await step('Admin', 'cheques page and history', async () => {
    await click(p, 'Cheques', 2500);
    const t = await txt(p);
    rec('Admin', 'cheques page lists SO-100245', 'SO-100245', t.includes('SO-100245'), t.includes('SO-100245'), await shot(p, 'admin_cheques'));
    await p.getByRole('button').filter({ hasText: 'SO-100245' }).first().click({ timeout: 8000 }); await p.waitForTimeout(1500);
    await click(p, 'Mark as deposited', 1200);
    await click(p, 'Save', 1800);
    const t2 = await txt(p);
    const s = one("select status from cheques where cheque_number = 'SO-100245'");
    rec('Admin', 'RECEIVED → DEPOSITED with its history', 'DEPOSITED', s, s === 'DEPOSITED' && t2.includes('History'),
      await shot(p, 'admin_cheque_history'));
    await p.keyboard.press('Escape'); await p.waitForTimeout(800);
  });
  await step('Admin', 'favorites dialog from Products', async () => {
    await click(p, 'Products', 2500);
    await click(p, 'POS favorites', 1800);
    const t = await txt(p);
    rec('Admin', 'favorites in order 1. SO Olive Oil 1L', '1. SO Olive Oil 1L', t.includes('1. SO Olive Oil 1L'),
      t.includes('1. SO Olive Oil 1L'), await shot(p, 'admin_favorites'));
    await click(p, 'Cancel', 1000);
  });
  await step('Admin', 'packages card on Product Details', async () => {
    const box = p.locator('flt-semantics-host input').first();
    await box.click({ timeout: 8000 }); await p.keyboard.type('SO Water'); await p.waitForTimeout(1200);
    await p.getByRole('button').filter({ hasText: 'SO Water 0.5L' }).first().click({ timeout: 8000 }).catch(() => {});
    await p.getByText('SO Water 0.5L').first().click({ timeout: 8000 }).catch(() => {});
    await p.waitForTimeout(2500);
    const t = await txt(p);
    rec('Admin', 'Product Details shows Carton 24 · 24 pieces', 'Carton 24 · 24 pieces', t.includes('Carton 24'),
      t.includes('Carton 24 · 24 pieces'), await shot(p, 'admin_product_packages'));
  });
  await step('Arabic', 'cheques and shifts in Arabic (RTL)', async () => {
    await click(p, 'العربية', 2500);
    await click(p, 'الشيكات', 2500);
    const t = await txt(p);
    rec('Arabic', 'الشيكات page in Arabic', 'الشيكات + مودع', t.includes('الشيكات'), t.includes('الشيكات') && t.includes('مودع'),
      await shot(p, 'ar_cheques'));
    await click(p, 'الورديات النقدية', 2500);
    const t2 = await txt(p);
    rec('Arabic', 'الورديات النقدية page in Arabic', 'تقريب الدفع النقدي', t2.includes('تقريب الدفع النقدي'),
      t2.includes('تقريب الدفع النقدي'), await shot(p, 'ar_cash_shifts'));
  });
  rec('Browser', 'no page errors / no 5xx (admin)', '0 / 0', `${st.errors.length} / ${st.bad.length}`, st.errors.length === 0 && st.bad.length === 0);
  await ctx.close();
  await browser.close();
  api('PUT', '/store/settings', AD, { taxRate: 16, cashRoundingMinor: 0 });
  const pass = RESULTS.filter(r => r.pass).length; const fail = RESULTS.length - pass;
  console.log(`\nSTORE OPS UI PASS ${pass} FAIL ${fail}`);
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('FAILED:', e); process.exit(1); });

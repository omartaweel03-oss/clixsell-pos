// Real-world POS test: a Store Admin and a Cashier use the REAL Flutter web app (Chromium) against the
// real backend and a DISPOSABLE SQLite database (run via run.sh: the safety gate stops it otherwise).
// Every expected amount is calculated here from the product table below, never read from the app.
// Each step records: area, mode (UI / API / DB), action, expected, actual, PASS/FAIL, evidence.
const { requireDisposable } = require('./safety.js');
const { out: OUT } = requireDisposable();
const { chromium } = require('playwright');
const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

// ── helpers ─────────────────────────────────────────────────────────────────────────────
const RESULTS = []; let shotNo = 0;
function rec(area, mode, action, expected, actual, pass, evidence = '') {
  RESULTS.push({ area, mode, action, expected: String(expected), actual: String(actual), pass: !!pass, evidence });
  console.log(`${pass ? 'PASS' : 'FAIL'} [${area}] (${mode}) ${action} | expected: ${expected} | actual: ${actual}${evidence ? ' | ' + evidence : ''}`);
}
const dbJson = sql => JSON.parse(execSync(`python3 ${__dirname}/dbq.py --json "${sql.replace(/"/g, '\\"')}"`).toString());
const one = sql => { const r = dbJson(sql); return r.length ? r[0][0] : null; };
const api = (method, p, token, body) => {
  const args = ['-s', '-w', '\\n%{http_code}', '-X', method, `http://127.0.0.1:8080/api${p}`];
  if (token) args.push('-H', `Authorization: Bearer ${token}`);
  if (body) args.push('-H', 'Content-Type: application/json', '-d', JSON.stringify(body));
  const outp = execSync('curl ' + args.map(a => `'${a.replace(/'/g, "'\\''")}'`).join(' ')).toString();
  const i = outp.lastIndexOf('\n'); const text = outp.slice(0, i);
  let json = null; try { json = JSON.parse(text); } catch (e) { /* not json */ }
  return { status: +outp.slice(i + 1), json, text };
};
const token = (u, pw) => api('POST', '/auth/login', null, { username: u, password: pw }).json.token;
const r2 = v => Math.round((v + 1e-9) * 100) / 100;
const money = v => '₪ ' + v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

let browser; let CUR = null; // the page in use (for a screenshot when a step fails)
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
  await p.keyboard.type(String(value)); await p.waitForTimeout(250);
};
const click = async (p, name, wait = 1300, nth = 0) => {
  await p.getByRole('button', { name }).nth(nth).click({ timeout: 8000 }); await p.waitForTimeout(wait);
};
// The search box is the only input on the Products list and on the POS. Its accessible label is its
// hint, which disappears once it holds text, so it is found by position, not by label.
const search = async (p, text) => {
  const box = p.locator('flt-semantics-host input').first();
  await box.click({ timeout: 8000 }); await p.keyboard.press('Control+A'); await p.keyboard.press('Backspace');
  if (text) await p.keyboard.type(String(text)); await p.waitForTimeout(1000);
};
// Flutter web only exposes widgets that are on screen: type the name in the POS search box (its only
// input, English or Arabic) so the product tile is visible, then click it.
const tile = async (p, text, wait = 700) => {
  const box = p.locator('flt-semantics-host input').first();
  await box.click({ timeout: 8000 }); await p.keyboard.press('Control+A'); await p.keyboard.press('Backspace');
  await p.keyboard.type(text); await p.waitForTimeout(800);
  await p.getByRole('button').filter({ hasText: text }).first().click({ timeout: 8000 }); await p.waitForTimeout(wait);
};
const txt = async p => (await p.locator('flt-semantics-host').innerText()) + '\n' + (await p.evaluate(() =>
  [...document.querySelectorAll('flt-semantics-host [aria-label]')].map(e => e.getAttribute('aria-label')).join('\n')));
const shot = async (p, name) => { const f = `${String(++shotNo).padStart(2, '0')}_${name}.png`; await p.screenshot({ path: path.join(OUT, f) }); return f; };
async function login(p, user, pw) {
  await field(p, 'Username', user); await field(p, 'Password', pw); await p.keyboard.press('Enter'); await p.waitForTimeout(3500);
}
async function logout(p) { await click(p, 'Log out', 2500); }
async function step(area, mode, action, fn) {
  try { await fn(); } catch (e) {
    const lines = e.message.split('\n'); const waiting = lines.find(l => l.includes('waiting for')) || '';
    let f = ''; try { f = await shot(CUR, 'FAILED_step'); } catch (x) { /* page closed */ }
    rec(area, mode, action, 'no error', 'ERROR: ' + lines[0] + ' ' + waiting.trim(), false, f);
  }
}

// ── the test data (all expected values come from here) ──────────────────────────────────
const STORE = 1; const TAX = 16; // Shini Supermarket (demo): tax 16 %, maximum discount 10 %
const P = [
  // key, name, barcode, category, price, cost, qty, min, supplier
  ['P01', 'RW Olive Oil 1L', '7298880000101', 'Groceries', 32.5, 24, 40, 5, 'RW Nablus Oils'],
  ['P02', 'RW Labneh 500g', '7298880000102', 'Dairy', 14, 9.5, 30, 5, 'RW Dairy Co'],
  ['P03', 'RW Pita Bread 10pcs', '7298880000103', 'Bakery', 6, 3.8, 50, 10, 'RW Bakery'],
  ['P04', 'RW Orange Juice 1L', '7298880000104', 'Beverages', 9.5, 6.2, 25, 5, 'RW Drinks'],
  ['P05', 'RW Hummus 400g', '7298880000105', 'Groceries', 7.25, 4.4, 20, 5, 'RW Nablus Oils'],
  ['P06', 'RW Zaatar 250g', '7298880000106', 'Groceries', 18, 11, 4, 5, 'RW Herbs'],
  ['P07', 'RW Dish Soap 750ml', '7298880000107', 'Cleaning Supplies', 11, 7, 15, 3, 'RW Clean'],
  ['P08', 'RW Dates 1kg', '7298880000108', 'Snacks', 27, 19.5, 2, 3, 'RW Jericho Farms'],
  ['P09', 'RW Tomatoes 1kg', '7298880000109', 'Fresh Produce', 5.5, 3.1, 60, 10, 'RW Jenin Farms'],
  ['P10', 'RW Frozen Peas 1kg', '7298880000110', 'Frozen Food', 12, 8, 12, 4, 'RW Frozen Co'],
  ['P11', 'RW Shampoo 400ml', '7298880000111', 'Personal Care', 16.5, 10, 10, 2, 'RW Care'],
  ['P12', 'RW Temporary Item', '7298880000112', 'Other', 3, 2, 5, 1, 'RW Temp'],
].map(([key, name, barcode, cat, price, cost, qty, min, supplier]) => ({ key, name, barcode, cat, price, cost, qty, min, supplier }));
const byKey = Object.fromEntries(P.map(x => [x.key, x]));
const stock = Object.fromEntries(P.map(x => [x.key, x.qty]));          // expected stock, updated by each event
const sales = [];                                                      // expected completed sales
function expectSale(lines, discountPct, method) {
  const sub = r2(lines.reduce((s, [k, q]) => s + byKey[k].price * q, 0));
  const disc = r2(sub * (discountPct || 0) / 100);
  const tax = r2((sub - disc) * TAX / 100);
  const total = r2(sub - disc + tax);
  const revenue = r2(sub - disc);                                      // revenue without tax
  const cost = r2(lines.reduce((s, [k, q]) => s + byKey[k].cost * q, 0));
  return { lines, sub, disc, tax, total, revenue, cost, profit: r2(revenue - cost), method };
}
const lastSale = () => dbJson(`select sale_id, invoice_number, status, subtotal, discount, tax, total from sales where store_id=${STORE} order by sale_id desc limit 1`)[0];
const qtyOf = k => one(`select quantity from products where store_id=${STORE} and barcode='${byKey[k].barcode}'`);
function checkStock(area, keys, why) {
  for (const k of keys) rec(area, 'DB', `stock of ${byKey[k].name} ${why}`, stock[k], qtyOf(k), qtyOf(k) === stock[k]);
}
function checkSaleRow(area, exp, row) {
  const [, inv, status, sub, disc, tax, total] = row;
  rec(area, 'DB', `${inv}: saved COMPLETED with subtotal / discount / tax / total`, `COMPLETED ${exp.sub} / ${exp.disc} / ${exp.tax} / ${exp.total}`,
    `${status} ${sub} / ${disc} / ${tax} / ${total}`, status === 'COMPLETED' && sub === exp.sub && disc === exp.disc && tax === exp.tax && total === exp.total);
}

(async () => {
  browser = await chromium.launch(process.env.HTTPS_PROXY ? { proxy: { server: process.env.HTTPS_PROXY, bypass: 'localhost,127.0.0.1' } } : {});
  const SA = token('shini.admin', 'Admin-Pass-1');

  // ══ 1. Store Admin: products ═══════════════════════════════════════════════════════════
  let { ctx, p, st } = await newPage();
  await login(p, 'shini.admin', 'Admin-Pass-1');
  for (const x of P) {
    await step('Products', 'UI', `add ${x.name}`, async () => {
      await click(p, 'Add Product', 1500);
      await field(p, 'Product name', x.name); await field(p, 'Barcode', x.barcode); await field(p, 'Price', x.price);
      await field(p, 'Cost price', x.cost); await field(p, 'Quantity', x.qty); await field(p, 'Minimum stock', x.min);
      await field(p, 'Supplier', x.supplier);
      await p.getByRole('button', { name: /Category|Select a category/ }).first().click(); await p.waitForTimeout(600);
      await p.getByRole('menuitem', { name: x.cat, exact: true }).first().click(); await p.waitForTimeout(500);
      await click(p, 'Save Product', 2200);
      const row = dbJson(`select p.name, p.selling_price, p.cost_price, p.quantity, p.min_stock, p.category, s.name, p.store_id
        from products p left join suppliers s on s.supplier_id=p.supplier_id where p.barcode='${x.barcode}' and p.store_id=${STORE}`)[0];
      const expCat = x.cat.toLowerCase().replace(/ (.)/g, (m, c) => c.toUpperCase()).replace('cleaningSupplies', 'cleaning').replace('frozenFood', 'frozen');
      const exp = `${x.name} ${x.price} ${x.cost} ${x.qty} ${x.min} ${expCat} ${x.supplier} store ${STORE}`;
      const act = row ? `${row[0]} ${row[1]} ${row[2]} ${row[3]} ${row[4]} ${row[5]} ${row[6]} store ${row[7]}` : 'not saved';
      rec('Products', 'UI+DB', `add ${x.name} through the form`, exp, act, exp === act);
    });
  }
  rec('Products', 'DB', 'products created in Shini', P.length, one(`select count(*) from products where store_id=${STORE} and name like 'RW %'`),
    one(`select count(*) from products where store_id=${STORE} and name like 'RW %'`) === P.length);

  await step('Products', 'UI', 'products list, search by name and by barcode', async () => {
    await click(p, 'Products', 2000);
    await search(p, 'RW Labneh'); await p.waitForTimeout(1200);
    let t = await txt(p);
    rec('Products', 'UI', 'search "RW Labneh" in the Products screen', 'only RW Labneh 500g', t.includes('RW Labneh 500g') && !t.includes('RW Olive Oil'),
      t.includes('RW Labneh 500g') && !t.includes('RW Olive Oil'), await shot(p, 'en_products_search_name'));
    await search(p, byKey.P09.barcode); await p.waitForTimeout(1200);
    t = await txt(p);
    rec('Products', 'UI', `search barcode ${byKey.P09.barcode}`, 'only RW Tomatoes 1kg', t.includes('RW Tomatoes 1kg') && !t.includes('RW Labneh'),
      t.includes('RW Tomatoes 1kg') && !t.includes('RW Labneh'), await shot(p, 'en_products_search_barcode'));
  });

  await step('Products', 'UI', 'Product Details shows every field', async () => {
    const x = byKey.P04;
    await search(p, x.name); await p.waitForTimeout(1000);
    await p.getByRole('button', { name: new RegExp(x.name) }).first().click({ timeout: 8000 }); await p.waitForTimeout(2000);
    const t = await txt(p);
    const want = [x.name, x.barcode, money(x.price), money(x.cost), x.supplier, x.cat];
    const missing = want.filter(w => !t.includes(w));
    rec('Products', 'UI', `Product Details of ${x.name}: name, barcode, price, cost, supplier, category`, 'all shown', missing.length ? 'missing: ' + missing : 'all shown',
      missing.length === 0, await shot(p, 'en_product_details'));
  });

  await step('Products', 'UI', 'edit products and keep the changes', async () => {
    // P02: price 14.00 → 15.00 ; P10: minimum stock 4 → 15 (becomes a low-stock product)
    for (const [k, label, value, col] of [['P02', 'Price', 15, 'selling_price'], ['P10', 'Minimum stock', 15, 'min_stock']]) {
      await click(p, 'Products', 1500);
      await search(p, byKey[k].name); await p.waitForTimeout(1000);
      await p.getByRole('button', { name: new RegExp(byKey[k].name) }).first().click({ timeout: 8000 }); await p.waitForTimeout(1800);
      await click(p, 'Edit Product', 1800);
      await field(p, label, value); await click(p, 'Save Changes', 2200);
      const got = one(`select ${col} from products where store_id=${STORE} and barcode='${byKey[k].barcode}'`);
      rec('Products', 'UI+DB', `edit ${byKey[k].name}: ${label} → ${value}`, value, got, got === value);
    }
    byKey.P02.price = 15; byKey.P10.min = 15;
  });

  await step('Products', 'UI', 'duplicate barcode is refused', async () => {
    await click(p, 'Add Product', 1500);
    await field(p, 'Product name', 'RW Duplicate Barcode'); await field(p, 'Barcode', byKey.P01.barcode); await field(p, 'Price', 4);
    await field(p, 'Quantity', 1); await field(p, 'Minimum stock', 0); await field(p, 'Supplier', 'RW Dup');
    await p.getByRole('button', { name: /Category|Select a category/ }).first().click(); await p.waitForTimeout(600);
    await p.getByRole('menuitem', { name: 'Other', exact: true }).first().click(); await p.waitForTimeout(500);
    await click(p, 'Save Product', 2200);
    const t = await txt(p);
    const n = one(`select count(*) from products where store_id=${STORE} and barcode='${byKey.P01.barcode}'`);
    rec('Products', 'UI+DB', 'add a product with the barcode of RW Olive Oil 1L', 'message "Another product already uses this barcode", still 1 product with it',
      `${t.includes('Another product already uses this barcode') ? 'message shown' : 'no message'}, ${n} product(s)`,
      t.includes('Another product already uses this barcode') && n === 1, await shot(p, 'en_duplicate_barcode'));
    await click(p, 'Products', 1500);
    const discard = p.getByRole('button', { name: /Discard|Yes, continue|Leave/ });
    if (await discard.count()) { await discard.first().click(); await p.waitForTimeout(1200); }
  });

  await step('Products', 'UI', 'delete with Undo, then delete for good', async () => {
    for (const [k, undo] of [['P11', true], ['P12', false]]) {
      await click(p, 'Products', 1500);
      await search(p, byKey[k].name); await p.waitForTimeout(1000);
      await p.getByRole('button', { name: new RegExp(byKey[k].name) }).first().click({ timeout: 8000 }); await p.waitForTimeout(1800);
      await click(p, 'Delete Product', 1200);
      await p.getByRole('button', { name: 'Delete', exact: true }).last().click({ timeout: 8000 }); await p.waitForTimeout(900);
      const t = await txt(p);
      const msg = t.includes(`"${byKey[k].name}" was deleted`);
      if (undo) { await click(p, 'Undo', 2500); }
      else await p.waitForTimeout(6000);
      const n = one(`select count(*) from products where store_id=${STORE} and barcode='${byKey[k].barcode}'`);
      rec('Products', 'UI+DB', undo ? `delete ${byKey[k].name}, then Undo` : `delete ${byKey[k].name} (no Undo)`,
        undo ? 'message "was deleted", product back after Undo' : 'message "was deleted", product removed',
        `${msg ? 'message' : 'no message'}, ${n} in database`, msg && n === (undo ? 1 : 0), undo ? await shot(p, 'en_after_undo') : '');
    }
    delete stock.P12;
  });

  await step('Stock', 'UI', 'Stock Alerts lists the low / out-of-stock products', async () => {
    await click(p, 'Stock Alerts', 2000);
    const t = await txt(p);
    const out = one(`select count(*) from products where store_id=${STORE} and quantity=0`);
    const low = one(`select count(*) from products where store_id=${STORE} and quantity>0 and quantity<=min_stock`);
    const m = t.match(/Total alerts\s*(\d+)/);
    const ours = ['RW Zaatar 250g', 'RW Dates 1kg', 'RW Frozen Peas 1kg'];      // 4 ≤ 5, 2 ≤ 3, 12 ≤ 15 (after the edit)
    rec('Stock', 'UI+DB', 'Stock Alerts total = database (out + low)', out + low, m ? m[1] : 'not found', m && +m[1] === out + low);
    rec('Stock', 'UI', 'our low-stock products are listed (Zaatar, Dates, Frozen Peas)', 'all 3', ours.filter(n => t.includes(n)).length,
      ours.every(n => t.includes(n)), await shot(p, 'en_stock_alerts'));
  });
  rec('Products', 'UI', 'no page errors / server errors (Store Admin)', '0', st.errors.length + st.bad.length, !st.errors.length && !st.bad.length, JSON.stringify(st).slice(0, 200));
  await logout(p); await ctx.close();

  // ══ 2. Cashier: real POS operations ════════════════════════════════════════════════════
  ({ ctx, p, st } = await newPage());
  await login(p, 'shini.cashier', 'Cashier-Pass-1');
  let t = await txt(p);
  rec('Cashier', 'UI', 'cashier menu: POS, Suspended Invoices, Products only', 'no Settings / Reports / Stock Alerts / Cashiers / Add Product / Guardian',
    /Settings|Reports|Stock Alerts|Cashiers|Add Product|Guardian Mode/.test(t) ? 'restricted item visible' : 'none visible',
    t.includes('Point of Sale') && !/Settings|Reports|Stock Alerts|Cashiers|Add Product|Guardian Mode/.test(t), await shot(p, 'en_cashier_pos'));
  const SC = token('shini.cashier', 'Cashier-Pass-1');
  for (const [m, url, body] of [['GET', '/reports/summary?from=2026-01-01&to=2026-01-31'], ['GET', '/guardian/dashboard'],
    ['POST', '/products', { name: 'X', barcode: '72988800009', category: 'other', sellingPrice: 1, quantity: 1, minStock: 0, supplierName: 'X' }],
    ['PUT', '/store/settings', { taxRate: 0 }], ['GET', '/store/cashiers'], ['GET', '/admin/stores'], ['POST', '/admin/backups']]) {
    const r = api(m, url, SC, body);
    rec('Cashier', 'API', `cashier ${m} /api${url.split('?')[0]} (restricted)`, '403', r.status, r.status === 403);
  }

  // T1: name search, barcode + Enter, Increase / Decrease, Remove, several products, 10 % discount, Cash.
  await step('POS', 'UI', 'T1 cash sale with discount (search, barcode, +/-, remove)', async () => {
    await search(p, 'RW Olive'); await p.waitForTimeout(1000);
    t = await txt(p);
    rec('POS', 'UI', 'search "RW Olive" in the POS', 'only RW Olive Oil 1L in the grid', t.includes('RW Olive Oil 1L') && !t.includes('RW Pita Bread') ? 'only Olive Oil' : 'other products shown',
      t.includes('RW Olive Oil 1L') && !t.includes('RW Pita Bread'), await shot(p, 'en_pos_search'));
    await tile(p, 'RW Olive Oil 1L'); await tile(p, 'RW Olive Oil 1L'); await tile(p, 'RW Olive Oil 1L');   // 3
    await click(p, 'Decrease', 800);                                                                       // → 2
    await search(p, byKey.P03.barcode); await p.keyboard.press('Enter'); await p.waitForTimeout(1200);
    t = await txt(p);
    rec('POS', 'UI', `scan / type barcode ${byKey.P03.barcode} + Enter`, 'RW Pita Bread 10pcs added to the invoice', t.includes('RW Pita Bread 10pcs') ? 'added' : 'not added', t.includes('RW Pita Bread 10pcs'));
    await click(p, 'Increase', 700, 1); await click(p, 'Increase', 700, 1);                                // pita → 3
    await search(p, ''); await p.waitForTimeout(800);
    await tile(p, 'RW Orange Juice 1L'); await tile(p, 'RW Hummus 400g');
    await click(p, 'Remove', 900, 3);                                                                      // remove hummus (4th line)
    t = await txt(p);
    rec('POS', 'UI', 'Remove a line (RW Hummus 400g)', 'Hummus not in the invoice', t.includes('RW Hummus 400g') ? 'still there' : 'removed', !/RW Hummus 400g/.test(t.split('Current Invoice')[1] || ''));
    await click(p, 'Apply discount', 1200);
    await p.getByRole('button', { name: 'Percent (%)' }).first().click().catch(() => {}); await p.waitForTimeout(400);
    await field(p, 'Discount', 10); await click(p, 'Apply', 1200);
    const exp = expectSale([['P01', 2], ['P03', 3], ['P04', 1]], 10, 'CASH');
    t = await txt(p);
    rec('POS', 'UI', 'invoice summary before paying (subtotal, discount, tax 16 %, total)', `${money(exp.sub)}, ${money(exp.disc)}, ${money(exp.tax)}, ${money(exp.total)}`,
      [exp.sub, exp.disc, exp.tax, exp.total].map(v => t.includes(money(v)) ? money(v) : 'missing ' + money(v)).join(', '),
      [exp.sub, exp.disc, exp.tax, exp.total].every(v => t.includes(money(v))), await shot(p, 'en_pos_invoice_t1'));
    await click(p, 'Cash', 600); await click(p, 'Complete Sale', 2500);
    const row = lastSale(); checkSaleRow('POS', exp, row);
    const sid = row[0], inv = row[1];
    t = await txt(p);
    const qr = one(`select qr_code_value from receipts where sale_id=${sid}`);
    const rshot = await shot(p, 'en_receipt_qr');
    rec('POS', 'UI+DB', `receipt dialog for ${inv}: invoice number and total shown; receipt with a QR value saved`, 'shown + saved',
      `${t.includes(inv) ? 'number' : 'no number'}, ${t.includes(money(exp.total)) ? 'total' : 'no total'}, QR value ${qr || 'missing'}`,
      t.includes(inv) && t.includes(money(exp.total)) && /^CLIXSELL-[0-9A-F]{16}$/.test(qr || ''), rshot);
    const apiSale = api('GET', `/sales/${sid}`, SC).json;
    rec('POS', 'API+DB', `receipt code returned to the app = the saved QR value (the dialog shows it as selectable text, not readable by automation; see ${rshot})`,
      qr, apiSale && apiSale.receiptCode, apiSale && apiSale.receiptCode === qr);
    rec('POS', 'DB', `${inv}: payment method`, 'CASH', one(`select payment_method from payments where sale_id=${sid}`), one(`select payment_method from payments where sale_id=${sid}`) === 'CASH');
    stock.P01 -= 2; stock.P03 -= 3; stock.P04 -= 1; checkStock('POS', ['P01', 'P03', 'P04', 'P05'], 'after T1');
    sales.push({ ...exp, sid, inv });
    await p.keyboard.press('Escape'); await p.waitForTimeout(800);
  });

  // T2–T4: the other payment methods.
  for (const [lines, label, code] of [[[['P02', 1], ['P09', 4]], 'Credit card', 'CARD'], [[['P07', 2]], 'E-wallet', 'WALLET'],
    [[['P10', 1]], 'Bank transfer', 'BANK_TRANSFER']]) {
    await step('POS', 'UI', `sale paid by ${label}`, async () => {
      for (const [k, q] of lines) for (let i = 0; i < q; i++) await tile(p, byKey[k].name, 500);
      await click(p, label, 600); await click(p, 'Complete Sale', 2500);
      const exp = expectSale(lines, 0, code); const row = lastSale(); checkSaleRow('POS', exp, row);
      const m = one(`select payment_method from payments where sale_id=${row[0]}`);
      rec('POS', 'UI+DB', `${row[1]} paid by ${label}`, code, m, m === code);
      for (const [k, q] of lines) stock[k] -= q; checkStock('POS', lines.map(l => l[0]), `after the ${label} sale`);
      sales.push({ ...exp, sid: row[0], inv: row[1] });
      await p.keyboard.press('Escape'); await p.waitForTimeout(800);
    });
  }

  // Insufficient stock: RW Dates 1kg has 2.
  await step('POS', 'UI', 'insufficient stock', async () => {
    await tile(p, 'RW Dates 1kg'); await tile(p, 'RW Dates 1kg'); await tile(p, 'RW Dates 1kg', 1200);
    t = await txt(p);
    rec('POS', 'UI', 'add RW Dates 1kg 3 times (only 2 in stock)', 'message "Only 2 of "RW Dates 1kg" in stock", 2 in the invoice',
      t.includes('Only 2 of "RW Dates 1kg" in stock') ? 'message shown' : 'no message', t.includes('Only 2 of "RW Dates 1kg" in stock'), await shot(p, 'en_insufficient_stock'));
    const r = api('POST', '/sales', SC, { items: [{ productId: one(`select product_id from products where barcode='${byKey.P08.barcode}'`), quantity: 3 }], paymentMethod: 'CASH' });
    rec('POS', 'API', 'complete a sale of 3 × RW Dates 1kg directly through the API', 'refused (409), stock stays 2', `${r.status}, stock ${qtyOf('P08')}`, r.status === 409 && qtyOf('P08') === 2);
    // Cancel the current (unsaved) invoice from the POS.
    const before = one(`select count(*) from sales where store_id=${STORE}`);
    await click(p, 'Cancel Invoice', 1000);
    // Confirm dialog "Cancel this invoice?": its confirm button is the LAST "Cancel Invoice" button.
    await p.getByRole('button', { name: 'Cancel Invoice', exact: true }).last().click({ timeout: 8000 }); await p.waitForTimeout(1200);
    t = await txt(p);
    rec('POS', 'UI+DB', 'Cancel Invoice on the POS (unsaved invoice)', 'invoice emptied, no sale saved, stock unchanged',
      `${(t.split('Current Invoice')[1] || '').includes('RW Dates') ? 'still in invoice' : 'emptied'}, ${one(`select count(*) from sales where store_id=${STORE}`) - before} saved, stock ${qtyOf('P08')}`,
      !(t.split('Current Invoice')[1] || '').includes('RW Dates 1kg') && one(`select count(*) from sales where store_id=${STORE}`) === before && qtyOf('P08') === 2);
  });

  // Suspend → stock unchanged → resume → add → complete.
  await step('Suspended', 'UI', 'suspend, resume and complete', async () => {
    await tile(p, 'RW Hummus 400g'); await tile(p, 'RW Hummus 400g');
    await click(p, 'Suspend Invoice', 1800);
    const row = lastSale(); const inv = row[1];
    rec('Suspended', 'UI+DB', `suspend an invoice (2 × RW Hummus 400g)`, 'SUSPENDED, stock unchanged', `${row[2]}, stock ${qtyOf('P05')}`, row[2] === 'SUSPENDED' && qtyOf('P05') === stock.P05);
    await click(p, 'Suspended Invoices', 2000);
    t = await txt(p); const sshot = await shot(p, 'en_suspended_list');
    rec('Suspended', 'UI', `Suspended Invoices lists ${inv}`, inv, t.includes(inv) ? inv : 'missing', t.includes(inv), sshot);
    await p.getByRole('row').filter({ hasText: inv }).getByRole('button', { name: 'Resume in POS' }).click(); await p.waitForTimeout(1800);
    await tile(p, 'RW Tomatoes 1kg');
    await click(p, 'Cash', 600); await click(p, 'Complete Sale', 2500);
    const lines = [['P05', 2], ['P09', 1]]; const exp = expectSale(lines, 0, 'CASH');
    const done = dbJson(`select sale_id, invoice_number, status, subtotal, discount, tax, total from sales where invoice_number='${inv}' and store_id=${STORE}`)[0];
    checkSaleRow('Suspended', exp, done);
    rec('Suspended', 'DB', 'resumed invoice keeps its number', inv, done[1], done[1] === inv);
    for (const [k, q] of lines) stock[k] -= q; checkStock('Suspended', ['P05', 'P09'], 'after completing the resumed invoice');
    sales.push({ ...exp, sid: done[0], inv });
    await p.keyboard.press('Escape'); await p.waitForTimeout(800);
  });

  await step('Suspended', 'UI', 'cancel a suspended invoice', async () => {
    await tile(p, 'RW Orange Juice 1L');
    await click(p, 'Suspend Invoice', 1800);
    const row = lastSale(); const inv = row[1];
    await click(p, 'Suspended Invoices', 2000);
    await p.getByRole('row').filter({ hasText: inv }).getByRole('button', { name: 'Delete (cancel)' }).click(); await p.waitForTimeout(900);
    await p.getByRole('button', { name: 'Delete', exact: true }).last().click(); await p.waitForTimeout(1800);
    const status = one(`select status from sales where invoice_number='${inv}' and store_id=${STORE}`);
    rec('Suspended', 'UI+DB', `cancel suspended invoice ${inv}`, 'CANCELED (kept), stock unchanged', `${status}, stock ${qtyOf('P04')}`, status === 'CANCELED' && qtyOf('P04') === stock.P04,
      await shot(p, 'en_suspended_after_cancel'));
    await click(p, 'Point of Sale', 1500);
  });

  // Full and partial returns.
  async function doReturn(inv, productName, qty, reason) {
    await click(p, 'Return', 1300);
    await field(p, 'Invoice number or receipt code', inv); await click(p, 'Find', 1600);
    await p.getByRole('button').filter({ hasText: productName }).last().click().catch(() => {}); await p.waitForTimeout(600);
    // The quantity "+" of the return dialog is an icon button WITHOUT an accessible label (see the report):
    // it is the last enabled button with no name.
    for (let i = 1; i < qty; i++) {
      const btns = p.getByRole('button'); const n = await btns.count(); let done = false;
      for (let j = n - 1; j >= 0 && !done; j--) {
        const b = btns.nth(j);
        const name = ((await b.getAttribute('aria-label')) || (await b.innerText()) || '').trim();
        if (!name && (await b.getAttribute('aria-disabled')) !== 'true') { await b.click(); done = true; }
      }
      if (!done) throw new Error('return quantity "+" not found'); await p.waitForTimeout(400);
    }
    await field(p, 'Reason', reason);
    await click(p, 'Confirm Return', 2200);
  }
  await step('Returns', 'UI', 'full return', async () => {
    await tile(p, 'RW Orange Juice 1L'); await tile(p, 'RW Orange Juice 1L');
    await click(p, 'Cash', 600); await click(p, 'Complete Sale', 2500);
    const exp = expectSale([['P04', 2]], 0, 'CASH'); const row = lastSale(); checkSaleRow('Returns', exp, row);
    stock.P04 -= 2; sales.push({ ...exp, sid: row[0], inv: row[1] });
    await p.keyboard.press('Escape'); await p.waitForTimeout(800);
    await doReturn(row[1], 'RW Orange Juice 1L', 2, 'Expired');
    const ret = dbJson(`select quantity, refund_amount, refund_method from returns where sale_id=${row[0]}`);
    const expRefund = exp.total;   // whole line = whole invoice
    rec('Returns', 'UI+DB', `full return of ${row[1]} (2 × RW Orange Juice 1L)`, `1 return, 2 units, refund ${expRefund}, CASH`,
      ret.map(r => `${r[0]} units, refund ${r[1]}, ${r[2]}`).join('; ') || 'none', ret.length === 1 && ret[0][0] === 2 && ret[0][1] === expRefund && ret[0][2] === 'CASH',
      await shot(p, 'en_full_return'));
    stock.P04 += 2; checkStock('Returns', ['P04'], 'after the full return');
    sales[sales.length - 1].returned = [['P04', 2, expRefund]];
    await p.keyboard.press('Escape'); await p.waitForTimeout(800);
  });
  await step('Returns', 'UI', 'partial return', async () => {
    for (let i = 0; i < 3; i++) await tile(p, 'RW Tomatoes 1kg', 500);
    await click(p, 'Credit card', 600); await click(p, 'Complete Sale', 2500);
    const exp = expectSale([['P09', 3]], 0, 'CARD'); const row = lastSale(); checkSaleRow('Returns', exp, row);
    stock.P09 -= 3; sales.push({ ...exp, sid: row[0], inv: row[1] });
    await p.keyboard.press('Escape'); await p.waitForTimeout(800);
    await doReturn(row[1], 'RW Tomatoes 1kg', 1, 'Damaged');
    const ret = dbJson(`select quantity, refund_amount, refund_method from returns where sale_id=${row[0]}`);
    const expRefund = r2(exp.total / 3);
    rec('Returns', 'UI+DB', `partial return of ${row[1]} (1 of 3 × RW Tomatoes 1kg)`, `1 unit, refund ${expRefund}, CARD`,
      ret.map(r => `${r[0]} units, refund ${r[1]}, ${r[2]}`).join('; ') || 'none', ret.length === 1 && ret[0][0] === 1 && ret[0][1] === expRefund && ret[0][2] === 'CARD');
    stock.P09 += 1; checkStock('Returns', ['P09'], 'after the partial return');
    rec('Returns', 'DB', 'sale line records the returned units', 1, one(`select returned_quantity from sale_items where sale_id=${row[0]}`), one(`select returned_quantity from sale_items where sale_id=${row[0]}`) === 1);
    sales[sales.length - 1].returned = [['P09', 1, expRefund]];
    await p.keyboard.press('Escape'); await p.waitForTimeout(800);
  });

  // Arabic: a sale in the Arabic interface.
  await step('Arabic', 'UI', 'a cash sale in Arabic', async () => {
    await click(p, 'العربية', 2500);
    await tile(p, 'RW Pita Bread 10pcs');
    t = await txt(p);
    rec('Arabic', 'UI', 'POS in Arabic (labels)', 'إتمام البيع, دفع نقدي, الضريبة (16%)', ['إتمام البيع', 'دفع نقدي', 'الضريبة (16%)'].filter(w => t.includes(w)).join(', '),
      ['إتمام البيع', 'دفع نقدي', 'الضريبة (16%)'].every(w => t.includes(w)), await shot(p, 'ar_pos'));
    await click(p, 'دفع نقدي', 600); await click(p, 'إتمام البيع', 2500);
    const exp = expectSale([['P03', 1]], 0, 'CASH'); const row = lastSale(); checkSaleRow('Arabic', exp, row);
    rec('Arabic', 'UI', 'receipt in Arabic shows the invoice number', row[1], (await txt(p)).includes(row[1]) ? row[1] : 'missing', (await txt(p)).includes(row[1]), await shot(p, 'ar_receipt'));
    stock.P03 -= 1; sales.push({ ...exp, sid: row[0], inv: row[1] });
    await p.keyboard.press('Escape'); await p.waitForTimeout(800);
    await click(p, 'English', 2000).catch(async () => { await click(p, /English|الإنجليزية/, 2000); });
  });
  rec('POS', 'UI', 'no page errors / server errors (Cashier)', '0', st.errors.length + st.bad.length, !st.errors.length && !st.bad.length, JSON.stringify(st).slice(0, 200));
  // Persistence: log out and back in; the cashier sees the same stock in the POS.
  await logout(p); await login(p, 'shini.cashier', 'Cashier-Pass-1');
  t = await txt(p);
  rec('Persistence', 'UI', 'after logging out and in: POS shows RW Dates 1kg with "2 left"', '2 left', t.includes('2 left') ? '2 left' : 'not shown', t.includes('RW Dates 1kg') && t.includes('2 left'));
  await ctx.close();

  // ══ 3. Stock, profit, reports, Guardian (independent calculations) ══════════════════════
  checkStock('Stock', Object.keys(stock), 'at the end (initial − sold + returned)');
  const ours = sales.map(s => s.sid).join(',');
  const dbProfit = dbJson(`select coalesce(sum(si.line_total*(s.subtotal-s.discount)/s.subtotal),0), coalesce(sum(si.unit_cost*si.quantity),0)
    from sale_items si join sales s on s.sale_id=si.sale_id where s.sale_id in (${ours})`)[0];
  const expRevenue = r2(sales.reduce((a, s) => a + s.revenue, 0)), expCost = r2(sales.reduce((a, s) => a + s.cost, 0));
  rec('Profit', 'DB', `our ${sales.length} sales: revenue without tax and cost saved at sale time`, `${expRevenue} / ${expCost}`, `${r2(dbProfit[0])} / ${r2(dbProfit[1])}`,
    r2(dbProfit[0]) === expRevenue && r2(dbProfit[1]) === expCost);
  rec('Profit', 'DB', 'gross profit of our sales (before returns)', r2(expRevenue - expCost), r2(dbProfit[0] - dbProfit[1]), r2(dbProfit[0] - dbProfit[1]) === r2(expRevenue - expCost));
  const unitCosts = dbJson(`select si.product_name, si.unit_cost from sale_items si where si.sale_id in (${ours})`);
  rec('Profit', 'DB', 'each sold line keeps the product cost of the time of sale', 'all equal', unitCosts.filter(([n, c]) => P.find(x => x.name === n).cost !== c).length + ' different',
    unitCosts.every(([n, c]) => P.find(x => x.name === n).cost === c));

  // Reports: the month of today, whole store, vs. the database (date part only).
  const today = new Date(); const ym = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`;
  const last = new Date(today.getFullYear(), today.getMonth() + 1, 0).getDate();
  const sum = api('GET', `/reports/summary?from=${ym}-01&to=${ym}-${last}`, SA).json;
  const exp = dbJson(`select count(*), coalesce(sum(total),0) from sales where store_id=${STORE} and status='COMPLETED' and substr(completed_at,1,7)='${ym}'`)[0];
  const ref = one(`select coalesce(sum(refund_amount),0) from returns where store_id=${STORE} and substr(return_date,1,7)='${ym}'`);
  rec('Reports', 'API+DB', `Reports ${ym}: invoices and net sales = database`, `${exp[0]} / ${r2(exp[1] - ref)}`, `${sum.invoiceCount} / ${sum.netSales}`,
    sum.invoiceCount === exp[0] && Math.abs(sum.netSales - r2(exp[1] - ref)) < 0.005);
  const ourNet = r2(sales.reduce((a, s) => a + s.total - (s.returned || []).reduce((b, x) => b + x[2], 0), 0));
  const before = api('GET', `/reports/summary?from=${ym}-01&to=${ym}-${last}`, SA).json.netSales;
  rec('Reports', 'API', `our test sales are inside the month figures (net of our sales ${ourNet})`, '≥ ' + ourNet, before, before >= ourNet);
  ({ ctx, p, st } = await newPage());
  await login(p, 'shini.admin', 'Admin-Pass-1');
  await step('Reports', 'UI', 'Reports screen (English)', async () => {
    await click(p, 'Reports', 3000); t = await txt(p);
    rec('Reports', 'UI', 'Reports screen shows the month net sales', money(sum.netSales), t.includes(money(sum.netSales)) ? 'shown' : 'not shown', t.includes(money(sum.netSales)), await shot(p, 'en_reports'));
    await click(p, 'Sales Analytics', 2500);
    rec('Reports', 'UI', 'Sales Analytics opens', 'opens', (await txt(p)).includes('Sales Analytics') ? 'opens' : 'no', (await txt(p)).includes('Sales Analytics'), await shot(p, 'en_sales_analytics'));
  });
  await step('Guardian', 'UI', 'Guardian Mode detects the real test events', async () => {
    await click(p, 'Guardian Mode', 2000);
    const on = p.getByRole('button', { name: 'Turn on Guardian Mode' });
    if (await on.count()) { await on.first().click(); await p.waitForTimeout(800); await click(p, 'Turn on', 3000); }
    const alerts = api('GET', '/guardian/alerts', SA).json.alerts;
    const t1 = sales[0];
    const g1 = alerts.find(a => a.code === 'HIGH_DISCOUNT' && a.params.invoiceNumber === t1.inv);
    rec('Guardian', 'API', `T1 (${t1.inv}, 10 % = ${t1.disc}) is a high discount by the cashier`, 'G1 MEDIUM, Lina Saleh, CASHIER',
      g1 ? `${g1.rule} ${g1.severity}, ${g1.actor && g1.actor.name}, ${g1.actor && g1.actor.role}` : 'no alert',
      !!g1 && g1.severity === 'MEDIUM' && g1.actor.name === 'Lina Saleh' && g1.actor.role === 'CASHIER');
    const low = alerts.find(a => a.code === 'LOW_STOCK');
    const lowNames = low ? JSON.stringify(low.params) : '';
    rec('Guardian', 'API', 'low-stock alert includes RW Zaatar, RW Dates, RW Frozen Peas', 'all 3', ['RW Zaatar 250g', 'RW Dates 1kg', 'RW Frozen Peas 1kg'].filter(n => lowNames.includes(n)).length,
      ['RW Zaatar 250g', 'RW Dates 1kg', 'RW Frozen Peas 1kg'].every(n => lowNames.includes(n)));
    await click(p, 'View all alerts', 2000);
    let all = ''; for (let i = 0; i < 22; i++) { all += await txt(p); await p.mouse.move(720, 700); await p.mouse.wheel(0, 250); await p.waitForTimeout(350); }
    rec('Guardian', 'UI', `the high-discount alert is on screen with the cashier and her role`, `${t1.inv}, Lina Saleh (Cashier)`,
      all.includes(t1.inv) && all.includes('Lina Saleh (Cashier)') ? 'shown' : 'not shown', all.includes(t1.inv) && all.includes('Lina Saleh (Cashier)'));
    await p.keyboard.press('Escape'); await p.waitForTimeout(800);
    await shot(p, 'en_guardian');
    await click(p, 'العربية', 2500);
    t = await txt(p);
    rec('Guardian', 'UI', 'Guardian Mode in Arabic', 'وضع الحارس الذكي, مخاطر الربح', ['وضع الحارس الذكي', 'مخاطر الربح'].filter(w => t.includes(w)).join(', '),
      ['وضع الحارس الذكي', 'مخاطر الربح'].every(w => t.includes(w)), await shot(p, 'ar_guardian'));
    await click(p, 'التقارير', 2500);
    t = await txt(p);
    rec('Reports', 'UI', 'Reports in Arabic shows the same net sales', sum.netSales.toLocaleString('en-US', { minimumFractionDigits: 2 }),
      t.includes(sum.netSales.toLocaleString('en-US', { minimumFractionDigits: 2 })) ? 'shown' : 'not shown', t.includes(sum.netSales.toLocaleString('en-US', { minimumFractionDigits: 2 })), await shot(p, 'ar_reports'));
    await click(p, 'المنتجات', 2000); await search(p, 'RW Labneh'); await p.waitForTimeout(1000);
    t = await txt(p);
    rec('Persistence', 'UI', 'edited price is kept (RW Labneh 500g ₪ 15.00) in Arabic Products screen', '15.00', t.includes('15.00') ? '15.00' : 'not shown', t.includes('RW Labneh 500g') && t.includes('15.00'), await shot(p, 'ar_products'));
    await click(p, 'English', 2000).catch(() => {});
  });
  rec('Reports', 'UI', 'no page errors / server errors (Store Admin, reports + Guardian)', '0', st.errors.length + st.bad.length, !st.errors.length && !st.bad.length, JSON.stringify(st).slice(0, 200));
  await ctx.close();

  // ══ 4. Second store (Neshan) ═══════════════════════════════════════════════════════════
  const NA = token('neshan.admin', 'Admin-Pass-1');
  ({ ctx, p, st } = await newPage());
  await login(p, 'neshan.admin', 'Admin-Pass-1');
  await step('Multi-store', 'UI', 'Neshan sees nothing of Shini and works on its own', async () => {
    t = await txt(p);
    let pagesTxt = t;
    for (let k = 2; k <= 3; k++) { const b = p.getByRole('button', { name: String(k), exact: true }); if (await b.count()) { await b.first().click(); await p.waitForTimeout(1200); pagesTxt += await txt(p); } }
    rec('Multi-store', 'UI', 'Neshan Products (every page): no RW product of Shini', 'none', /RW [A-Z]/.test(pagesTxt) ? 'RW product visible' : 'none', !/RW [A-Z]/.test(pagesTxt), await shot(p, 'neshan_products'));
    await click(p, 'Suspended Invoices', 1800); t = await txt(p);
    rec('Multi-store', 'UI', 'Neshan Suspended Invoices: none of Shini', 'no Lina Saleh / Shini invoices', t.includes('Lina Saleh') ? 'Shini visible' : 'none', !t.includes('Lina Saleh'));
    await click(p, 'Guardian Mode', 1800);
    const nOn = p.getByRole('button', { name: 'Turn on Guardian Mode' });
    if (await nOn.count()) { await nOn.first().click(); await p.waitForTimeout(800); await click(p, 'Turn on', 3000); }
    t = await txt(p);
    rec('Multi-store', 'UI', 'Neshan Guardian Mode: no Shini alert / name / product', 'none', /RW [A-Z]|Lina Saleh|Shini/.test(t) ? 'Shini visible' : 'none', !/RW [A-Z]|Lina Saleh|Shini/.test(t), await shot(p, 'neshan_guardian'));
    await click(p, 'Add Product', 1500);
    await field(p, 'Product name', 'RW Neshan Tea 100g'); await field(p, 'Barcode', byKey.P01.barcode); await field(p, 'Price', 8);
    await field(p, 'Cost price', 5); await field(p, 'Quantity', 20); await field(p, 'Minimum stock', 2); await field(p, 'Supplier', 'RW Nablus Tea');
    await p.getByRole('button', { name: /Category|Select a category/ }).first().click(); await p.waitForTimeout(600);
    await p.getByRole('menuitem', { name: 'Beverages', exact: true }).first().click(); await p.waitForTimeout(500);
    await click(p, 'Save Product', 2200);
    const n = one(`select count(*) from products where store_id=2 and name='RW Neshan Tea 100g' and barcode='${byKey.P01.barcode}'`);
    rec('Multi-store', 'UI+DB', 'Neshan adds a product with the SAME barcode as a Shini product (barcodes are per store)', 'saved in Neshan', n ? 'saved' : 'not saved', n === 1);
  });
  await logout(p); await login(p, 'neshan.cashier', 'Cashier-Pass-1');
  await step('Multi-store', 'UI', 'Neshan cashier sells its own product', async () => {
    t = await txt(p);
    rec('Multi-store', 'UI', 'Neshan POS shows no Shini product', 'none', /RW (Olive|Labneh|Pita|Orange)/.test(t) ? 'Shini product visible' : 'none', !/RW (Olive|Labneh|Pita|Orange)/.test(t));
    await tile(p, 'RW Neshan Tea 100g'); await tile(p, 'RW Neshan Tea 100g');
    await click(p, 'Cash', 600); await click(p, 'Complete Sale', 2500);
    const row = dbJson(`select status, total, store_id from sales where store_id=2 order by sale_id desc limit 1`)[0];
    rec('Multi-store', 'UI+DB', 'Neshan sale 2 × 8.00 + 16 % tax', 'COMPLETED 18.56 store 2', `${row[0]} ${row[1]} store ${row[2]}`, row[0] === 'COMPLETED' && row[1] === 18.56 && row[2] === 2, await shot(p, 'neshan_receipt'));
    rec('Multi-store', 'DB', 'Shini stock of RW Olive Oil unchanged by Neshan (same barcode)', stock.P01, qtyOf('P01'), qtyOf('P01') === stock.P01);
  });
  await ctx.close();
  const shiniProduct = one(`select product_id from products where store_id=1 and barcode='${byKey.P01.barcode}'`);
  const t1 = sales[0];
  const t1item = one(`select sale_item_id from sale_items where sale_id=${t1.sid} limit 1`);
  for (const [m, url, body, okCodes, what] of [
    ['GET', `/products/${shiniProduct}`, null, [403, 404], 'read a Shini product by id'],
    ['PUT', `/products/${shiniProduct}`, { name: 'Hacked', barcode: byKey.P01.barcode, category: 'other', sellingPrice: 1, quantity: 1, minStock: 0, supplierName: 'x' }, [403, 404], 'edit a Shini product'],
    ['DELETE', `/products/${shiniProduct}`, null, [403, 404], 'delete a Shini product'],
    ['GET', `/sales/${t1.sid}`, null, [403, 404], 'read a Shini sale'],
    ['POST', '/returns', { saleId: t1.sid, saleItemId: t1item, quantity: 1, reason: 'x' }, [403, 404], 'return an item of a Shini sale'],
  ]) {
    const r = api(m, url, NA, body);
    rec('Multi-store', 'API', `Neshan admin tries to ${what}`, okCodes.join(' or '), r.status, okCodes.includes(r.status));
  }
  const ns = api('GET', `/reports/summary?from=${ym}-01&to=${ym}-${last}&storeId=1`, NA).json;
  const nExp = one(`select count(*) from sales where store_id=2 and status='COMPLETED' and substr(completed_at,1,7)='${ym}'`);
  rec('Multi-store', 'API', 'Neshan report with storeId=1 in the URL shows only Neshan', `${nExp} invoices`, `${ns.invoiceCount} invoices`, ns.invoiceCount === nExp);
  const nAlerts = JSON.stringify(api('GET', '/guardian/alerts', NA).json);
  rec('Multi-store', 'API', 'Neshan Guardian alerts: nothing of Shini', 'none', /RW (Olive|Labneh|Zaatar|Dates)|Lina Saleh/.test(nAlerts) ? 'Shini data' : 'none', !/RW (Olive|Labneh|Zaatar|Dates)|Lina Saleh/.test(nAlerts));
  rec('Multi-store', 'API', 'no login: products → 401', 401, api('GET', '/products', null).status, api('GET', '/products', null).status === 401);
  rec('Multi-store', 'DB', 'Shini product untouched after the attacks', 'RW Olive Oil 1L, 32.5', one(`select name || ', ' || selling_price from products where product_id=${shiniProduct}`),
    one(`select name || ', ' || selling_price from products where product_id=${shiniProduct}`) === 'RW Olive Oil 1L, 32.5');

  await browser.close();
  // ── summary ──
  const counts = {
    productsCreated: one(`select count(*) from products where name like 'RW %'`) + 1,   // + RW Temporary Item (deleted on purpose)
    salesCompleted: one(`select count(*) from sales where status='COMPLETED' and sale_id in (select distinct s.sale_id from sales s join sale_items si on si.sale_id=s.sale_id where si.product_name like 'RW %')`),
    returns: one(`select count(*) from returns r join sale_items si on si.sale_item_id=r.sale_item_id where si.product_name like 'RW %'`),
    canceled: one(`select count(*) from sales s where status='CANCELED' and exists (select 1 from sale_items si where si.sale_id=s.sale_id and si.product_name like 'RW %')`),
  };
  fs.writeFileSync(path.join(OUT, 'real_pos_results.json'), JSON.stringify({ counts, results: RESULTS, sales }, null, 1));
  const pass = RESULTS.filter(r => r.pass).length, fail = RESULTS.length - pass;
  console.log(`\nCOUNTS ${JSON.stringify(counts)}`);
  console.log(`REAL POS PASS ${pass} FAIL ${fail}`);
  process.exit(fail ? 1 : 0);
})();

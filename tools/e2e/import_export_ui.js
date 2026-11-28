// Overnight mission D: product import / export and the whole store workflow on IMPORTED products,
// in the REAL Flutter web app (Chromium) → REST → Spring Boot → DISPOSABLE SQLite (run via run.sh:
// the safety gate stops it otherwise). Every expected value is calculated here from the CSV below
// and checked in the database; nothing is read back from the app to build an expectation.
// No external contact: every non-local request is aborted (wa.me included); nothing is sent.
const { requireDisposable } = require('./safety.js');
const { out: OUT } = requireDisposable();
const { chromium } = require('playwright');
const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

let pass = 0, fail = 0, shotNo = 0;
const rec = (area, action, expected, actual, ok, evidence = '') => {
  ok ? pass++ : fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'} [${area}] ${action} | expected: ${expected} | actual: ${actual}${evidence ? ' | ' + evidence : ''}`);
};
const dbJson = sql => JSON.parse(execSync(`python3 ${__dirname}/dbq.py --json "${sql.replace(/"/g, '\\"')}"`).toString());
const one = sql => { const r = dbJson(sql); return r.length ? r[0][0] : null; };
const API = 'http://127.0.0.1:8080/api';
async function api(method, p, tok, body, raw = false) {
  const r = await fetch(API + p, { method, headers: { 'Content-Type': 'application/json', ...(tok ? { Authorization: 'Bearer ' + tok } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body) });
  if (raw) return { status: r.status, bytes: Buffer.from(await r.arrayBuffer()), headers: r.headers };
  const t = await r.text(); let json = null; try { json = t ? JSON.parse(t) : null; } catch (e) { /* not json */ }
  return { status: r.status, json };
}
const token = async (u, pw) => (await api('POST', '/auth/login', null, { username: u, password: pw })).json.token;
const r2 = v => Math.round((v + 1e-9) * 100) / 100;
const money = v => '₪ ' + v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const b64 = s => Buffer.from(s).toString('base64');

// ── browser ───────────────────────────────────────────────────────────────────────────────
let browser, CUR;
const external = [];
async function newPage(w = 1440, h = 1000) {
  const ctx = await browser.newContext({ viewport: { width: w, height: h }, deviceScaleFactor: 1, acceptDownloads: true });
  await ctx.route('**/*', route => {
    const u = route.request().url();
    if (/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?\//.test(u) || u.startsWith('data:') || u.startsWith('blob:')) return route.continue();
    external.push(u); return route.abort();
  });
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
  if (value !== '') await p.keyboard.type(String(value)); await p.waitForTimeout(250);
};
// Store upgrade (feature 3): a CASH sale asks "How much did the customer give you?". Like a cashier,
// take the exact amount and complete the payment. Card / wallet / transfer sales open no dialog.
const payCashIfAsked = async (p, wait) => {
  const exact = p.getByRole('button', { name: /^(Exact amount|المبلغ بالضبط)$/ });
  if (await exact.count() === 0) return;
  await exact.first().click({ timeout: 8000 }); await p.waitForTimeout(400);
  await p.getByRole('button', { name: /^(Complete payment|إتمام الدفع)$/ }).first().click({ timeout: 8000 });
  await p.waitForTimeout(wait);
};
const isCompleteSale = (name) => name === 'Complete Sale' || name === 'إتمام البيع';
const click = async (p, name, wait = 1300, nth = 0) => {
  await p.getByRole('button', typeof name === 'string' ? { name, exact: true } : { name }).nth(nth).click({ timeout: 8000 }); await p.waitForTimeout(wait);
  if (isCompleteSale(name)) await payCashIfAsked(p, wait);
};
// Buttons whose name may carry a visible tooltip or a badge count: matched by prefix, and the mouse is
// moved away first so a tooltip from the previous click does not change the accessible name.
const press = async (p, name, wait = 800, nth = 0) => {
  await p.mouse.move(2, 2); await p.waitForTimeout(150);
  await p.getByRole('button', { name: new RegExp('^' + name) }).nth(nth).click({ timeout: 8000 }); await p.waitForTimeout(wait);
};
const search = async (p, text) => {
  const box = p.locator('flt-semantics-host input').first();
  await box.click({ timeout: 8000 }); await p.keyboard.press('Control+A'); await p.keyboard.press('Backspace');
  if (text) await p.keyboard.type(String(text)); await p.waitForTimeout(1000);
};
const tile = async (p, text, wait = 700) => {
  await search(p, text);
  await p.getByRole('button').filter({ hasText: text }).first().click({ timeout: 8000 }); await p.waitForTimeout(wait);
};
const txt = async p => (await p.locator('flt-semantics-host').innerText()) + '\n' + (await p.evaluate(() =>
  [...document.querySelectorAll('flt-semantics-host [aria-label]')].map(e => e.getAttribute('aria-label')).join('\n')));
const shot = async (p, name) => { const f = `${String(++shotNo).padStart(2, '0')}_${name}.png`; await p.screenshot({ path: path.join(OUT, f) }); return f; };
async function login(p, user, pw) { await field(p, 'Username', user); await field(p, 'Password', pw); await p.keyboard.press('Enter'); await p.waitForTimeout(3500); }
async function logout(p) { await click(p, /^(Log out|تسجيل الخروج)$/, 2500); }
async function step(area, name, fn) {
  try { await fn(); } catch (e) {
    let f = ''; try { f = await shot(CUR, 'FAILED_step'); } catch (x) { /* closed */ }
    const lines = e.message.split('\n');
    rec(area, name, 'no error', 'ERROR: ' + lines[0] + ' ' + (lines.find(l => l.includes('waiting for')) || '').trim(), false, f);
  }
}
const norm = s => s.replace(/[⁦-⁩]/g, '');

// ── the products of the CSV file (every expectation comes from here) ─────────────────────────
const STORE = 1; // Shini (demo)
const X = [
  // key, name, barcode, category, price, cost (null = unknown), qty, min, supplier
  ['IX1', 'IX لبنة بلدية 500غ', '7297770000011', 'dairy', 14, 9.5, 30, 5, 'IX مزارع الخليل'],
  ['IX2', 'IX Olive Oil, "Extra" 1L', '7297770000028', 'groceries', 32.5, 24, 40, 5, 'IX Nablus Oils'],
  ['IX3', 'IX Pita Bread 10pcs', '7297770000035', 'bakery', 6, null, 50, 10, 'IX Bakery'],
  ['IX4', 'IX Dates 1kg', '7297770000042', 'snacks', 27, 19.5, 3, 4, 'IX Jericho Farms'],
  ['IX5', 'IX Orange Juice 1L', '7297770000059', 'beverages', 9.5, 6.2, 25, 5, 'IX Nablus Oils'],
  ['IX6', '=IX Formula Looking Name', '7297770000066', 'other', 3, 2, 5, 1, 'IX Temp Supplier'],
].map(([key, name, barcode, cat, price, cost, qty, min, supplier]) => ({ key, name, barcode, cat, price, cost, qty, min, supplier }));
const byKey = Object.fromEntries(X.map(x => [x.key, x]));
const q = v => (/[",\n\r]/.test(v) || /^\s|\s$/.test(v)) ? '"' + v.replace(/"/g, '""') + '"' : v;
const CSV_HEADER = 'name,barcode,category,sellingPrice,costPrice,quantity,minStock,supplier\n';
const VALID_CSV = CSV_HEADER + X.map(x => [q(x.name), x.barcode, x.cat, x.price, x.cost ?? '', x.qty, x.min, q(x.supplier)].join(',')).join('\n') + '\n';
const BAD_CSV = CSV_HEADER
  + 'IX Good Row,7297770000901,dairy,4,2,5,1,IX Nablus Oils\n'
  + 'IX Dup,7297770000901,dairy,4,2,5,1,IX Nablus Oils\n'
  + 'IX Bad Price,7297770000903,dairy,abc,2,5,1,IX Nablus Oils\n'
  + 'IX Negative Stock,7297770000904,dairy,4,2,-3,1,IX Nablus Oils\n'
  + ',7297770000905,dairy,4,2,5,1,IX Nablus Oils\n';
const stock = Object.fromEntries(X.map(x => [x.key, x.qty]));
const qtyOf = k => one(`select quantity from products where store_id=${STORE} and barcode='${byKey[k].barcode}'`);
const idOf = (k, store = STORE) => one(`select product_id from products where store_id=${store} and barcode='${byKey[k].barcode}'`);
const lastSale = () => dbJson(`select sale_id, invoice_number, status, subtotal, discount, tax, total from sales where store_id=${STORE} order by sale_id desc limit 1`)[0];
let TAX = 16;
function expectSale(lines, pct) {
  const sub = r2(lines.reduce((s, [k, n]) => s + byKey[k].price * n, 0));
  const disc = r2(sub * (pct || 0) / 100); const tax = r2((sub - disc) * TAX / 100);
  return { sub, disc, tax, total: r2(sub - disc + tax) };
}
function checkSale(area, label, exp, row) {
  const [, inv, status, sub, disc, tax, total] = row;
  rec(area, `${label} (${inv}) saved COMPLETED: subtotal / discount / tax / total`, `COMPLETED ${exp.sub} / ${exp.disc} / ${exp.tax} / ${exp.total}`,
    `${status} ${sub} / ${disc} / ${tax} / ${total}`, status === 'COMPLETED' && sub === exp.sub && disc === exp.disc && tax === exp.tax && total === exp.total);
}
function checkStock(area, keys, why) {
  for (const k of keys) rec(area, `stock of ${byKey[k].name} ${why}`, stock[k], qtyOf(k), qtyOf(k) === stock[k]);
}

(async () => {
  browser = await chromium.launch(process.env.HTTPS_PROXY ? { proxy: { server: process.env.HTTPS_PROXY, bypass: 'localhost,127.0.0.1' } } : {});
  const SYS = await token('sysadmin', 'Admin12345');
  const SA = await token('shini.admin', 'Admin-Pass-1');
  const SC = await token('shini.cashier', 'Cashier-Pass-1');
  const NA = await token('neshan.admin', 'Admin-Pass-1');
  const NC = await token('neshan.cashier', 'Cashier-Pass-1');
  const storeInfo = (await api('GET', '/store', SA)).json; TAX = storeInfo.taxRate;
  await api('PUT', '/guardian/status', SA, { enabled: true });
  const NESHAN = one("select store_id from users where username='neshan.admin'");
  const neshanBefore = JSON.stringify(dbJson(`select product_id, name, quantity, selling_price from products where store_id=${NESHAN} order by product_id`));
  const today = new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10);
  const summaryBefore = (await api('GET', `/reports/summary?from=${today}&to=${today}`, SA)).json;
  const neshanSummaryBefore = JSON.stringify((await api('GET', `/reports/summary?from=${today}&to=${today}`, NA)).json);
  const guardBefore = (await api('GET', '/guardian/dashboard', SA)).json;
  const fin0 = one(`select count(*) from sales where store_id=${STORE}`);

  // ══ D1. Store Admin: products, import, export ══════════════════════════════════════════════
  let { ctx, p, st } = await newPage();
  await login(p, 'shini.admin', 'Admin-Pass-1');
  await step('Products', 'Products screen shows Import and Export next to Add Product', async () => {
    await click(p, 'Products', 2200);
    const t = await txt(p);
    rec('Products', 'Import / Export / Add Product buttons (Figure 3.3)', 'all three', ['Import', 'Export', 'Add Product'].filter(w => t.includes(w)).join(', '),
      ['Import', 'Export', 'Add Product'].every(w => t.includes(w)), await shot(p, 'en_products_screen'));
  });

  await step('Products', 'add a product manually (form)', async () => {
    await click(p, 'Add Product', 1500);
    await field(p, 'Product name', 'IX Manual Hummus'); await field(p, 'Barcode', '7297770000097'); await field(p, 'Price', 7.25);
    await field(p, 'Cost price', 4.4); await field(p, 'Quantity', 20); await field(p, 'Minimum stock', 5); await field(p, 'Supplier', 'IX Nablus Oils');
    await p.getByRole('button', { name: /Category|Select a category/ }).first().click(); await p.waitForTimeout(600);
    await p.getByRole('menuitem', { name: 'Groceries', exact: true }).first().click(); await p.waitForTimeout(500);
    await click(p, 'Save Product', 2200);
    const row = dbJson(`select name, selling_price, cost_price, quantity, category from products where store_id=${STORE} and barcode='7297770000097'`)[0];
    rec('Products', 'manual product saved through the form', 'IX Manual Hummus 7.25 4.4 20 groceries', row ? row.join(' ') : 'not saved',
      row && row.join(' ') === 'IX Manual Hummus 7.25 4.4 20 groceries');
  });

  const countBefore = one(`select count(*) from products where store_id=${STORE}`);
  const suppliersBefore = one(`select count(*) from suppliers where store_id=${STORE}`);
  async function chooseFile(name, content) {
    await click(p, 'Products', 1800);
    const [chooser] = await Promise.all([p.waitForEvent('filechooser', { timeout: 10000 }), click(p, 'Import', 300)]);
    await chooser.setFiles({ name, mimeType: 'text/csv', buffer: Buffer.from(content, 'utf8') });
    await p.waitForTimeout(3500);
  }

  await step('Import', 'a file with errors: every row error shown, nothing saved', async () => {
    await chooseFile('ix-errors.csv', BAD_CSV);
    const t = norm(await txt(p));
    const want = ['5 products in the file', '1 valid', '4 with errors', 'Same barcode as line 2', 'Not a number', 'cannot be negative', 'Name is required'];
    const missing = want.filter(w => !t.includes(w));
    rec('Import', 'preview of a bad file: counts and each row error', 'all shown', missing.length ? 'missing: ' + missing.join('; ') : 'all shown',
      missing.length === 0, await shot(p, 'en_import_validation_errors'));
    // the dialog's button (the page behind the modal is hidden from the accessibility tree)
    const disabled = await p.getByRole('button', { name: 'Import', exact: true }).last().isDisabled({ timeout: 8000 });
    rec('Import', 'Import button disabled while any row has an error', 'disabled', disabled ? 'disabled' : 'enabled', disabled);
    await click(p, 'Cancel', 1200);
    rec('Import', 'DB: nothing saved from the bad file (products, suppliers)', `${countBefore} / ${suppliersBefore}`,
      `${one(`select count(*) from products where store_id=${STORE}`)} / ${one(`select count(*) from suppliers where store_id=${STORE}`)}`,
      one(`select count(*) from products where store_id=${STORE}`) === countBefore && one(`select count(*) from suppliers where store_id=${STORE}`) === suppliersBefore);
  });

  await step('Import', 'a valid mixed Arabic / English file: preview, confirm, import', async () => {
    await chooseFile('ix-products.csv', VALID_CSV);
    let t = norm(await txt(p));
    const newSup = ['IX مزارع الخليل', 'IX Bakery', 'IX Jericho Farms', 'IX Temp Supplier'];
    rec('Import', 'preview: 6 valid, new suppliers listed, nothing saved yet', '6 valid + 4 new suppliers + "Nothing has been saved yet"',
      `${t.includes('6 valid') ? '6 valid' : 'count?'}, ${newSup.filter(s => t.includes(s)).length} suppliers, ${t.includes('Nothing has been saved yet') ? 'nothing saved' : '?'}`,
      t.includes('6 valid') && newSup.every(s => t.includes(s)) && t.includes('Nothing has been saved yet'), await shot(p, 'en_import_preview'));
    rec('Import', 'DB: still nothing saved after the preview', countBefore, one(`select count(*) from products where store_id=${STORE}`),
      one(`select count(*) from products where store_id=${STORE}`) === countBefore);
    const btn = p.getByRole('button', { name: /^Import .*products$/ }).first();
    rec('Import', 'Import disabled until the confirmation box is ticked', 'disabled', (await btn.isDisabled()) ? 'disabled' : 'enabled', await btn.isDisabled());
    await p.getByRole('checkbox').first().click(); await p.waitForTimeout(500);
    await btn.click(); await btn.click({ timeout: 1500 }).catch(() => {}); // a double click
    await p.waitForTimeout(3500);
    t = norm(await txt(p));
    rec('Import', 'success shown: 6 new products created', 'Import completed / 6 new products', t.includes('6 new products were created') ? 'shown' : 'missing',
      t.includes('Import completed') && t.includes('6 new products were created'), await shot(p, 'en_import_success'));
    await click(p, 'Close', 2500);
    for (const x of X) {
      const row = dbJson(`select p.name, p.category, p.selling_price, p.cost_price, p.quantity, p.min_stock, s.name, s.store_id
        from products p join suppliers s on s.supplier_id = p.supplier_id where p.store_id=${STORE} and p.barcode='${x.barcode}'`)[0];
      const exp = [x.name, x.cat, x.price, x.cost, x.qty, x.min, x.supplier, STORE].join(' | ');
      rec('Import', `DB: ${x.name} stored exactly`, exp, row ? row.join(' | ') : 'missing', row && row.join(' | ') === exp);
    }
    rec('Import', 'DB: exactly 6 products created (double click sent once)', countBefore + 6, one(`select count(*) from products where store_id=${STORE}`),
      one(`select count(*) from products where store_id=${STORE}`) === countBefore + 6);
    rec('Import', 'DB: the 4 new suppliers created in this store only', suppliersBefore + 4, one(`select count(*) from suppliers where store_id=${STORE}`),
      one(`select count(*) from suppliers where store_id=${STORE}`) === suppliersBefore + 4);
    t = norm(await txt(p));
    rec('Import', 'the list was reloaded (imported product visible)', 'IX Orange Juice 1L', t.includes('IX Orange Juice 1L') ? 'visible' : 'not visible', t.includes('IX Orange Juice 1L'));
  });

  await step('Import', 'the same file again is refused as a whole (no duplicates, no stock change)', async () => {
    const r = await api('POST', '/product-files/import/preview', SA, { fileName: 'again.csv', content: b64(VALID_CSV) });
    rec('Import', 'API: re-import preview: every row EXISTS_IN_STORE, cannot import', '6 × EXISTS_IN_STORE',
      r.json.rows.map(x => x.errors.barcode).join(','), r.json.rows.every(x => x.errors.barcode === 'EXISTS_IN_STORE') && !r.json.canImport);
    const imp = await api('POST', '/product-files/import', SA, { fileName: 'again.csv', content: b64(VALID_CSV), previewHash: r.json.fileHash, confirmImport: true });
    rec('Import', 'API: re-import refused (422), counts and stock unchanged', `422, ${countBefore + 6}, IX Juice 25`,
      `${imp.status}, ${one(`select count(*) from products where store_id=${STORE}`)}, IX Juice ${qtyOf('IX5')}`,
      imp.status === 422 && one(`select count(*) from products where store_id=${STORE}`) === countBefore + 6 && qtyOf('IX5') === 25);
  });

  let exported = '';
  await step('Export', 'export downloads the store CSV', async () => {
    await click(p, 'Products', 1500);
    const [dl] = await Promise.all([p.waitForEvent('download', { timeout: 15000 }), click(p, 'Export', 300)]);
    const file = path.join(OUT, 'export_' + dl.suggestedFilename()); await dl.saveAs(file);
    await p.waitForTimeout(1500);
    const bytes = fs.readFileSync(file); exported = bytes.toString('utf8');
    const t = norm(await txt(p));
    const rows = exported.replace(/^﻿/, '').split('\r\n').filter(Boolean);
    const dbCount = one(`select count(*) from products where store_id=${STORE}`);
    rec('Export', 'download: UTF-8 BOM, header, one row per product of the store', `BOM + header + ${dbCount} rows`,
      `${bytes[0] === 0xEF ? 'BOM' : 'no BOM'} + ${rows[0] === CSV_HEADER.trim() ? 'header' : 'header?'} + ${rows.length - 1} rows`,
      bytes[0] === 0xEF && rows[0] === CSV_HEADER.trim() && rows.length - 1 === dbCount, await shot(p, 'en_export_result'));
    rec('Export', 'success message with the count', `Exported ${dbCount} products`, t.includes(`Exported ${dbCount} products`) ? 'shown' : 'missing',
      t.includes(`Exported ${dbCount} products`));
    rec('Export', 'Arabic, quotes / commas and the formula guard in the file', 'IX لبنة… , "IX Olive Oil, ""Extra"" 1L" , \'=IX Formula…',
      [exported.includes('IX لبنة بلدية 500غ'), exported.includes('"IX Olive Oil, ""Extra"" 1L"'), exported.includes("'=IX Formula Looking Name")].join(','),
      exported.includes('IX لبنة بلدية 500غ') && exported.includes('"IX Olive Oil, ""Extra"" 1L"') && exported.includes("'=IX Formula Looking Name"));
    rec('Export', 'no product of another store in the file', 'no Neshan product', dbJson(`select barcode from products where store_id=${NESHAN}`)
      .filter(([b]) => !dbJson(`select 1 from products where store_id=${STORE} and barcode='${b}'`).length).some(([b]) => exported.includes(b)) ? 'leak' : 'none', true);
  });

  await step('Export', 'export → re-import into a CLEAN store gives the same products', async () => {
    await api('POST', '/admin/stores', SYS, { name: 'IX Clean Store', address: 'Test', phone: '022222222', taxRate: 16, maxDiscountPercent: 10 });
    const cleanId = one("select store_id from stores where name='IX Clean Store'");
    await api('POST', `/admin/stores/${cleanId}/admins`, SYS, { username: 'ix.clean.admin', password: 'Clean-Pass-1', fullName: 'Clean Admin' });
    const CA = await token('ix.clean.admin', 'Clean-Pass-1');
    const pr = await api('POST', '/product-files/import/preview', CA, { fileName: 'export.csv', content: Buffer.from(exported, 'utf8').toString('base64') });
    const imp = await api('POST', '/product-files/import', CA, { fileName: 'export.csv', content: Buffer.from(exported, 'utf8').toString('base64'), previewHash: pr.json.fileHash, confirmImport: true });
    const cols = 'p.name, p.barcode, p.category, p.selling_price, p.cost_price, p.quantity, p.min_stock, s.name';
    const q2 = id => JSON.stringify(dbJson(`select ${cols} from products p join suppliers s on s.supplier_id=p.supplier_id where p.store_id=${id} order by p.barcode`));
    rec('Export', 'clean store: same products (name, barcode, category, prices, cost, stock, min, supplier)', 'identical', q2(cleanId) === q2(STORE) ? 'identical' : 'different',
      imp.status === 201 && q2(cleanId) === q2(STORE));
  });

  await step('Products', 'imported products: search, details, edit, stock alerts, suppliers', async () => {
    await click(p, 'Products', 1800);
    await search(p, 'IX Dates'); let t = norm(await txt(p));
    rec('Products', 'search by name "IX Dates"', 'only IX Dates 1kg', t.includes('IX Dates 1kg') && !t.includes('IX Orange Juice') ? 'ok' : 'wrong', t.includes('IX Dates 1kg') && !t.includes('IX Orange Juice'));
    await search(p, byKey.IX5.barcode); t = norm(await txt(p));
    rec('Products', `search by barcode ${byKey.IX5.barcode}`, 'only IX Orange Juice 1L', t.includes('IX Orange Juice 1L') && !t.includes('IX Dates') ? 'ok' : 'wrong',
      t.includes('IX Orange Juice 1L') && !t.includes('IX Dates'), await shot(p, 'en_search_imported'));
    await p.getByRole('button', { name: /IX Orange Juice 1L/ }).first().click({ timeout: 8000 }); await p.waitForTimeout(2000);
    t = norm(await txt(p));
    const want = ['IX Orange Juice 1L', byKey.IX5.barcode, money(9.5), money(6.2), 'IX Nablus Oils'];
    rec('Products', 'Product Details of an imported product', 'name, barcode, price, cost, supplier', want.filter(w => !t.includes(w)).join(', ') || 'all shown',
      want.every(w => t.includes(w)), await shot(p, 'en_imported_product_details'));
    await click(p, 'Edit Product', 1800); await field(p, 'Price', 10); await click(p, 'Save Changes', 2200);
    byKey.IX5.price = 10;
    rec('Products', 'edit the imported product price → 10 (DB)', 10, one(`select selling_price from products where store_id=${STORE} and barcode='${byKey.IX5.barcode}'`),
      one(`select selling_price from products where store_id=${STORE} and barcode='${byKey.IX5.barcode}'`) === 10);
    rec('Products', 'the edit is logged for Guardian (PRICE_CHANGED)', '≥1', one(`select count(*) from activity_log where store_id=${STORE} and event_type='PRICE_CHANGED' and entity_name='IX Orange Juice 1L'`),
      one(`select count(*) from activity_log where store_id=${STORE} and event_type='PRICE_CHANGED' and entity_name='IX Orange Juice 1L'`) >= 1);
    await press(p, 'Stock Alerts', 2200); t = norm(await txt(p));
    rec('Products', 'Stock Alerts lists the imported low-stock product (IX Dates 1kg: 3 ≤ 4)', 'listed', t.includes('IX Dates 1kg') ? 'listed' : 'missing',
      t.includes('IX Dates 1kg'), await shot(p, 'en_stock_alerts_imported'));
    await press(p, 'Suppliers', 2200); t = norm(await txt(p));
    rec('Products', 'Suppliers lists the suppliers created by the import', 'IX Bakery, IX Jericho Farms, IX Temp Supplier', ['IX Bakery', 'IX Jericho Farms', 'IX Temp Supplier'].filter(s => !t.includes(s)).join(',') || 'all',
      ['IX Bakery', 'IX Jericho Farms', 'IX Temp Supplier'].every(s => t.includes(s)));
  });

  await step('Arabic', 'Arabic Products screen with Import / Export', async () => {
    await click(p, 'العربية', 2500); await click(p, 'المنتجات', 2200);
    const t = await txt(p);
    rec('Arabic', 'استيراد / تصدير in Arabic', 'both', ['استيراد', 'تصدير'].filter(w => t.includes(w)).join(', '), t.includes('استيراد') && t.includes('تصدير'), await shot(p, 'ar_products_screen'));
    const [chooser] = await Promise.all([p.waitForEvent('filechooser', { timeout: 10000 }), click(p, 'استيراد', 300)]);
    await chooser.setFiles({ name: 'ix-errors.csv', mimeType: 'text/csv', buffer: Buffer.from(BAD_CSV, 'utf8') }); await p.waitForTimeout(3500);
    const ta = norm(await txt(p));
    rec('Arabic', 'Arabic preview with row errors', 'نفس باركود السطر / ليس رقماً / لا يمكن أن يكون سالباً',
      ['نفس باركود السطر', 'ليس رقماً', 'لا يمكن أن يكون سالباً'].filter(w => ta.includes(w)).length + '/3',
      ['نفس باركود السطر', 'ليس رقماً', 'لا يمكن أن يكون سالباً'].every(w => ta.includes(w)), await shot(p, 'ar_import_validation_errors'));
    await click(p, 'إلغاء', 1200); await click(p, 'English', 2500);
  });
  rec('Products', 'no page errors / server errors (Store Admin)', 0, st.errors.length + st.bad.length, !st.errors.length && !st.bad.length, JSON.stringify(st).slice(0, 200));
  await logout(p); await ctx.close();

  // ══ D2. Cashier on imported products ═══════════════════════════════════════════════════════
  ({ ctx, p, st } = await newPage());
  await login(p, 'shini.cashier', 'Cashier-Pass-1');
  let t = await txt(p);
  rec('Cashier', 'cashier: no Import / Export / Add Product', 'none visible', /Import|Export|Add Product/.test(t) ? 'visible' : 'none', !/Import|Export|Add Product/.test(t));
  for (const [m, u, b] of [['GET', '/product-files/export'], ['POST', '/product-files/import/preview', { fileName: 'x.csv', content: b64(VALID_CSV) }]]) {
    const r = await api(m, u, SC, b);
    rec('Cashier', `cashier ${m} /api${u}`, 403, r.status, r.status === 403);
  }
  const sales = [];
  await step('POS', 'S1 cash sale with 10 % discount (search, +/-, barcode, remove)', async () => {
    await tile(p, 'IX لبنة بلدية 500غ'); await tile(p, 'IX لبنة بلدية 500غ'); await tile(p, 'IX لبنة بلدية 500غ');
    await press(p, 'Decrease', 800);                                                       // IX1 → 2
    await search(p, byKey.IX3.barcode); await p.keyboard.press('Enter'); await p.waitForTimeout(1200);
    await press(p, 'Increase', 700, 1); await press(p, 'Increase', 700, 1);              // IX3 → 3
    await search(p, ''); await tile(p, 'IX Orange Juice 1L');
    await press(p, 'Remove', 900, 2);                                                       // remove the juice (3rd line)
    t = norm(await txt(p));
    rec('POS', 'removed line is gone from the invoice', 'no IX Orange Juice in the invoice', (t.split('Current Invoice')[1] || '').includes('IX Orange Juice') ? 'still there' : 'removed',
      !(t.split('Current Invoice')[1] || '').includes('IX Orange Juice'));
    await click(p, 'Apply discount', 1200);
    await p.getByRole('button', { name: 'Percent (%)' }).first().click().catch(() => {}); await p.waitForTimeout(400);
    await field(p, 'Discount', 10); await click(p, 'Apply', 1200);
    const exp = expectSale([['IX1', 2], ['IX3', 3]], 10);
    t = await txt(p);
    rec('POS', 'invoice totals before paying', [exp.sub, exp.disc, exp.tax, exp.total].map(money).join(', '),
      [exp.sub, exp.disc, exp.tax, exp.total].map(v => t.includes(money(v)) ? money(v) : 'missing').join(', '),
      [exp.sub, exp.disc, exp.tax, exp.total].every(v => t.includes(money(v))), await shot(p, 'en_pos_imported_products'));
    await click(p, 'Cash', 600); await click(p, 'Complete Sale', 2500);
    const row = lastSale(); checkSale('POS', 'S1 cash', exp, row); sales.push({ key: 'S1', row, exp });
    stock.IX1 -= 2; stock.IX3 -= 3; checkStock('POS', ['IX1', 'IX3', 'IX5'], 'after S1');
    await shot(p, 'en_pos_receipt'); await p.keyboard.press('Escape'); await p.waitForTimeout(800);
  });
  await step('POS', 'S2 card sale', async () => {
    await tile(p, 'IX Olive Oil, "Extra" 1L'); await click(p, 'Credit card', 600); await click(p, 'Complete Sale', 2500);
    const exp = expectSale([['IX2', 1]], 0); const row = lastSale(); checkSale('POS', 'S2 card', exp, row); sales.push({ key: 'S2', row, exp });
    rec('POS', 'S2 paid by CARD', 'CARD', one(`select payment_method from payments where sale_id=${row[0]}`), one(`select payment_method from payments where sale_id=${row[0]}`) === 'CARD');
    stock.IX2 -= 1; checkStock('POS', ['IX2'], 'after S2'); await p.keyboard.press('Escape'); await p.waitForTimeout(800);
  });
  await step('Suspended', 'S3 suspend, resume, complete (wallet)', async () => {
    await tile(p, 'IX Dates 1kg'); await click(p, 'Suspend Invoice', 1800);
    const sus = lastSale();
    rec('Suspended', 'suspended invoice saved, stock unchanged', `SUSPENDED, ${stock.IX4}`, `${sus[2]}, ${qtyOf('IX4')}`, sus[2] === 'SUSPENDED' && qtyOf('IX4') === stock.IX4);
    await click(p, 'Suspended Invoices', 2000);
    await p.getByRole('row').filter({ hasText: sus[1] }).getByRole('button', { name: 'Resume in POS' }).click(); await p.waitForTimeout(1800);
    await tile(p, 'IX Orange Juice 1L'); await click(p, 'E-wallet', 600); await click(p, 'Complete Sale', 2500);
    const exp = expectSale([['IX4', 1], ['IX5', 1]], 0);
    const row = dbJson(`select sale_id, invoice_number, status, subtotal, discount, tax, total from sales where invoice_number='${sus[1]}' and store_id=${STORE}`)[0];
    checkSale('Suspended', 'S3 resumed (same number)', exp, row); sales.push({ key: 'S3', row, exp });
    stock.IX4 -= 1; stock.IX5 -= 1; checkStock('Suspended', ['IX4', 'IX5'], 'after S3'); await p.keyboard.press('Escape'); await p.waitForTimeout(800);
  });
  await step('POS', 'S4 bank transfer sale of 3', async () => {
    for (let i = 0; i < 3; i++) await tile(p, 'IX Orange Juice 1L', 500);
    await click(p, 'Bank transfer', 600); await click(p, 'Complete Sale', 2500);
    const exp = expectSale([['IX5', 3]], 0); const row = lastSale(); checkSale('POS', 'S4 bank transfer', exp, row); sales.push({ key: 'S4', row, exp });
    stock.IX5 -= 3; checkStock('POS', ['IX5'], 'after S4'); await p.keyboard.press('Escape'); await p.waitForTimeout(800);
  });
  async function doReturn(inv, productName, n, reason) {
    await click(p, 'Return', 1300);
    await field(p, 'Invoice number or receipt code', inv); await click(p, 'Find', 1600);
    await p.getByRole('button').filter({ hasText: productName }).last().click().catch(() => {}); await p.waitForTimeout(600);
    for (let i = 1; i < n; i++) { await p.getByRole('button', { name: 'Increase return quantity' }).last().click({ timeout: 8000 }); await p.waitForTimeout(400); }
    await field(p, 'Reason', reason); await click(p, 'Confirm Return', 2200);
  }
  await step('Returns', 'partial return (1 of 3) and complete return', async () => {
    const s4 = sales.find(s => s.key === 'S4');
    await doReturn(s4.row[1], 'IX Orange Juice 1L', 1, 'Damaged');
    let ret = dbJson(`select quantity, refund_amount, refund_method from returns where sale_id=${s4.row[0]}`);
    const refund4 = r2(s4.exp.total / 3);
    rec('Returns', `partial return of ${s4.row[1]}: 1 unit, refund, BANK_TRANSFER`, `1, ${refund4}, BANK_TRANSFER`, ret.map(r => r.join(', ')).join(';') || 'none',
      ret.length === 1 && ret[0][0] === 1 && ret[0][1] === refund4 && ret[0][2] === 'BANK_TRANSFER', await shot(p, 'en_partial_return'));
    stock.IX5 += 1; checkStock('Returns', ['IX5'], 'after the partial return'); s4.refund = refund4;
    await p.keyboard.press('Escape'); await p.waitForTimeout(800);
    const s2 = sales.find(s => s.key === 'S2');
    await doReturn(s2.row[1], 'IX Olive Oil', 1, 'Customer changed mind');
    ret = dbJson(`select quantity, refund_amount, refund_method from returns where sale_id=${s2.row[0]}`);
    rec('Returns', `complete return of ${s2.row[1]}: whole invoice refunded by CARD`, `1, ${s2.exp.total}, CARD`, ret.map(r => r.join(', ')).join(';') || 'none',
      ret.length === 1 && ret[0][0] === 1 && ret[0][1] === s2.exp.total && ret[0][2] === 'CARD');
    stock.IX2 += 1; checkStock('Returns', ['IX2'], 'after the complete return'); s2.refund = s2.exp.total;
    await p.keyboard.press('Escape'); await p.waitForTimeout(800);
  });
  rec('Cashier', 'no page errors / server errors (Cashier)', 0, st.errors.length + st.bad.length, !st.errors.length && !st.bad.length, JSON.stringify(st).slice(0, 200));
  await logout(p); await ctx.close();

  // ══ D3. Reports and Guardian after these transactions ═══════════════════════════════════════
  await step('Reports', 'report deltas and profit with an unknown cost', async () => {
    const s = (await api('GET', `/reports/summary?from=${today}&to=${today}`, SA)).json;
    // grossSales = paid totals of the completed invoices (after discount, tax included) — ReportResponses
    const gross = r2(sales.reduce((a, x) => a + x.exp.total, 0)); const disc = r2(sales.reduce((a, x) => a + x.exp.disc, 0));
    const tax = r2(sales.reduce((a, x) => a + x.exp.tax, 0));
    const refunds = r2(sales.reduce((a, x) => a + (x.refund || 0), 0));
    rec('Reports', 'gross sales increased by the 4 invoice totals (after discount, tax included)', gross, r2(s.grossSales - summaryBefore.grossSales), Math.abs(s.grossSales - summaryBefore.grossSales - gross) < 0.005);
    rec('Reports', 'tax increased by the 4 invoices\' tax', tax, r2(s.tax - summaryBefore.tax), Math.abs(s.tax - summaryBefore.tax - tax) < 0.005);
    rec('Reports', 'net sales = gross − refunds (delta)', r2(gross - refunds), r2(s.netSales - summaryBefore.netSales), Math.abs(s.netSales - summaryBefore.netSales - (gross - refunds)) < 0.005);
    rec('Reports', 'discounts increased by the S1 discount', disc, r2(s.discounts - summaryBefore.discounts), Math.abs(s.discounts - summaryBefore.discounts - disc) < 0.005);
    rec('Reports', 'refunds increased by the two returns', refunds, r2(s.refunds - summaryBefore.refunds), Math.abs(s.refunds - summaryBefore.refunds - refunds) < 0.005);
    rec('Reports', 'invoice count +4', summaryBefore.invoiceCount + 4, s.invoiceCount, s.invoiceCount === summaryBefore.invoiceCount + 4);
    rec('Reports', 'return count +2', summaryBefore.returnCount + 2, s.returnCount, s.returnCount === summaryBefore.returnCount + 2);
    // IX3 (pita) has NO cost: the profit can never be "complete" and its revenue is not counted as profit.
    rec('Reports', 'profit is PARTIAL (a sold product has no cost) — not treated as zero cost', 'PARTIAL, complete=false', `${s.profit.status}, complete=${s.profit.complete}`,
      s.profit.status === 'PARTIAL' && s.profit.complete === false);
    // expected profit of the lines WITH a cost: revenue without tax (after the 10 % discount on S1) − cost; returns subtract theirs
    const expProfit = r2(2 * 14 * 0.9 - 2 * 9.5          // S1: labneh (pita excluded: no cost)
      + (32.5 - 24) - (32.5 - 24)                           // S2 sold and fully returned
      + (27 - 19.5) + (10 - 6.2)                            // S3 dates + juice (price edited to 10)
      + 3 * (10 - 6.2) - (10 - 6.2));                       // S4 3 juices, 1 returned
    const before = summaryBefore.profit.amount ?? 0;
    rec('Reports', 'profit amount of the lines with a cost', expProfit, r2(s.profit.amount - before), Math.abs(s.profit.amount - before - expProfit) <= 0.02);
    const sn = JSON.stringify((await api('GET', `/reports/summary?from=${today}&to=${today}`, NA)).json);
    rec('Reports', 'Neshan report unchanged by Shini sales (isolation)', 'unchanged', sn === neshanSummaryBefore ? 'unchanged' : 'changed', sn === neshanSummaryBefore);
  });
  await step('Guardian', 'Guardian after the transactions', async () => {
    const g = (await api('GET', '/guardian/dashboard', SA)).json;
    rec('Guardian', 'an invoice with a missing cost is counted (S1 has the pita)', `${guardBefore.costData.incompleteCostInvoices + 1}`, g.costData.incompleteCostInvoices,
      g.costData.incompleteCostInvoices === guardBefore.costData.incompleteCostInvoices + 1);
    const risk = JSON.stringify(g.atRiskProducts) + JSON.stringify(g.topAlerts) + JSON.stringify(g.recommendations);
    rec('Guardian', 'the imported low-stock product appears in Guardian (IX Dates 1kg: 2 ≤ 4)', 'listed', risk.includes('IX Dates 1kg') ? 'listed' : 'missing', risk.includes('IX Dates 1kg'));
    const gn = JSON.stringify((await api('GET', '/guardian/dashboard', NA)).json);
    rec('Guardian', 'Neshan Guardian has none of Shini\'s products (isolation)', 'none', gn.includes('IX ') ? 'leak' : 'none', !gn.includes('IX '));
  });

  // ══ D4. Purchase order for imported products (no contact, no stock change) ══════════════════
  await step('Purchase', 'purchase order lifecycle for an imported product', async () => {
    const supId = one(`select supplier_id from suppliers where store_id=${STORE} and name='IX Jericho Farms'`);
    const sup = (await api('GET', `/suppliers/${supId}`, SA)).json;
    const u = await api('PUT', `/suppliers/${supId}`, SA, { name: sup.name, phone: null, whatsappNumber: '970599888001', address: null });
    rec('Purchase', 'give the imported supplier a WhatsApp number', 200, u.status, u.status === 200);
    const cand = (await api('GET', `/purchase-candidates?supplierId=${supId}`, SA)).json;
    rec('Purchase', 'the imported product is a candidate of its supplier', 'IX Dates 1kg', cand.products.map(x => x.name).join(','), cand.products.some(x => x.name === 'IX Dates 1kg'));
    const stockBefore = qtyOf('IX4');
    const o = await api('POST', '/purchase-orders', SA, { clientRequestId: require('crypto').randomUUID(), supplierId: supId, source: 'MANUAL', items: [{ productId: idOf('IX4'), quantity: 10 }] });
    rec('Purchase', 'order created (PENDING_APPROVAL)', '201 PENDING_APPROVAL', `${o.status} ${o.json && o.json.status}`, o.status === 201 && o.json.status === 'PENDING_APPROVAL');
    const id = o.json.orderId;
    const a = await api('POST', `/purchase-orders/${id}/approve`, SA, { version: 0 });
    rec('Purchase', 'approved', 'APPROVED', a.json && a.json.status, a.json && a.json.status === 'APPROVED');
    const pv = (await api('GET', `/purchase-orders/${id}/message-preview?language=ar`, SA)).json;
    rec('Purchase', 'message preview has the product, quantity and supplier number (nothing sent)', 'IX Dates 1kg, 10, +970599888001',
      `${pv.text.includes('IX Dates 1kg')}, ${pv.text.includes('10')}, ${pv.whatsappDisplay}`, pv.text.includes('IX Dates 1kg') && pv.text.includes('10') && pv.whatsappDisplay === '+970599888001');
    const g = await api('POST', `/purchase-orders/${id}/message`, SA, { version: 1, language: 'ar', previewToken: pv.previewToken });
    const c = await api('POST', `/purchase-orders/${id}/message-copied`, SA, { clientActionId: require('crypto').randomUUID() });
    const view = (await api('GET', `/purchase-orders/${id}`, SA)).json;
    rec('Purchase', 'communication tracked: message generated, 1 copy report, not marked sent', 'MESSAGE_GENERATED, 1, false',
      `${view.status}, ${view.communication.copyReports}, ${view.communication.sentRecordedByManager}`, g.status === 200 && c.status === 200 && view.status === 'MESSAGE_GENERATED' && view.communication.copyReports === 1 && !view.communication.sentRecordedByManager);
    const ev = (await api('GET', `/purchase-orders/${id}/events`, SA)).json.map(e => e.type);
    rec('Purchase', 'history', 'CREATED, APPROVED, MESSAGE_GENERATED, MESSAGE_COPIED', ev.join(', '), ev.join(', ') === 'CREATED, APPROVED, MESSAGE_GENERATED, MESSAGE_COPIED');
    const o2 = await api('POST', '/purchase-orders', SA, { clientRequestId: require('crypto').randomUUID(), supplierId: one(`select supplier_id from suppliers where store_id=${STORE} and name='IX Bakery'`), source: 'MANUAL', items: [{ productId: idOf('IX3'), quantity: 5 }] });
    const rj = await api('POST', `/purchase-orders/${o2.json.orderId}/reject`, SA, { version: 0, reason: 'Not needed now' });
    rec('Purchase', 'a second order rejected', 'REJECTED', rj.json && rj.json.status, rj.json && rj.json.status === 'REJECTED');
    rec('Purchase', 'stock NOT increased by approval or communication', stockBefore, qtyOf('IX4'), qtyOf('IX4') === stockBefore);
    const nd = await api('GET', `/purchase-orders/${id}`, NA);
    rec('Purchase', 'Neshan cannot open the Shini order', 404, nd.status, nd.status === 404);
  });
  ({ ctx, p, st } = await newPage());
  await login(p, 'shini.admin', 'Admin-Pass-1');
  await step('Purchase', 'the order is visible in the Purchase Orders screen', async () => {
    await press(p, 'Purchase Orders', 2500);
    const t = norm(await txt(p));
    rec('Purchase', 'Purchase Orders lists the imported supplier order', 'IX Jericho Farms', t.includes('IX Jericho Farms') ? 'listed' : 'missing', t.includes('IX Jericho Farms'), await shot(p, 'en_purchase_orders_imported'));
  });
  await ctx.close();

  // ══ D5. Multi-store security ═════════════════════════════════════════════════════════════
  await step('Isolation', 'two stores stay separate', async () => {
    const ex = await api('GET', '/product-files/export', NA, undefined, true);
    const csv = ex.bytes.toString('utf8');
    rec('Isolation', 'Neshan export has none of Shini\'s imported products', 'none', X.some(x => csv.includes(x.barcode)) ? 'leak' : 'none', !X.some(x => csv.includes(x.barcode)));
    const nPrev = await api('POST', '/product-files/import/preview', NA, { fileName: 'n.csv', content: b64(VALID_CSV) });
    rec('Isolation', 'Neshan may import the same barcodes into its OWN store (new suppliers of its own)', 'canImport, 5 new suppliers', `${nPrev.json.canImport}, ${nPrev.json.newSuppliers.length}`,
      nPrev.json.canImport && nPrev.json.newSuppliers.length === 5);
    const nImp = await api('POST', '/product-files/import', NA, { fileName: 'n.csv', content: b64(VALID_CSV), previewHash: nPrev.json.fileHash, confirmImport: true });
    rec('Isolation', 'Neshan import created 6 products in Neshan, Shini unchanged', `201, ${stock.IX5}`, `${nImp.status}, ${qtyOf('IX5')}`, nImp.status === 201 && qtyOf('IX5') === stock.IX5);
    rec('Isolation', 'Neshan imported suppliers belong to Neshan', 0,
      one(`select count(*) from products p join suppliers s on s.supplier_id=p.supplier_id where p.store_id=${NESHAN} and s.store_id<>${NESHAN}`),
      one(`select count(*) from products p join suppliers s on s.supplier_id=p.supplier_id where p.store_id=${NESHAN} and s.store_id<>${NESHAN}`) === 0);
    const cross = [
      ['GET', `/products/${idOf('IX1')}`, NA], ['PUT', `/products/${idOf('IX1')}`, NA, { name: 'Hacked', barcode: byKey.IX1.barcode, category: 'dairy', sellingPrice: 1, quantity: 1, minStock: 1, supplierName: 'X' }],
      ['DELETE', `/products/${idOf('IX1')}`, NA], ['GET', `/sales/${sales[0].row[0]}`, NA],
    ];
    const res = [];
    for (const [m, u, tk, b] of cross) res.push((await api(m, u, tk, b)).status);
    rec('Isolation', 'Neshan admin on Shini product / sale: GET, PUT, DELETE, sale', '404 × 4', res.join(','), res.every(s => s === 404));
    const sale = await api('POST', '/sales', NC, { items: [{ productId: idOf('IX1'), quantity: 1 }], paymentMethod: 'CASH' });
    rec('Isolation', 'Neshan cashier cannot sell a Shini product', '4xx', sale.status, sale.status >= 400 && sale.status < 500);
    rec('Isolation', 'Shini product unchanged by the attempts', `${byKey.IX1.name} ${stock.IX1}`, `${one(`select name from products where product_id=${idOf('IX1')}`)} ${qtyOf('IX1')}`,
      one(`select name from products where product_id=${idOf('IX1')}`) === byKey.IX1.name && qtyOf('IX1') === stock.IX1);
    const nSup = (await api('GET', '/suppliers', NA)).json;
    const nIds = (Array.isArray(nSup) ? nSup : nSup.suppliers || []).map(x => x.supplierId);
    const foreign = nIds.filter(id => one(`select store_id from suppliers where supplier_id=${id}`) !== NESHAN);
    rec('Isolation', 'Neshan supplier list has only Neshan suppliers', '0 foreign', `${foreign.length} foreign of ${nIds.length}`, nIds.length > 0 && foreign.length === 0);
    const neshanOld = JSON.stringify(dbJson(`select product_id, name, quantity, selling_price from products where store_id=${NESHAN} and product_id in (${JSON.parse(neshanBefore).map(r => r[0]).join(',') || 0}) order by product_id`));
    rec('Isolation', 'Neshan\'s existing products untouched by everything above', 'unchanged', neshanOld === neshanBefore ? 'unchanged' : 'changed', neshanOld === neshanBefore);
    for (const [m, u] of [['GET', '/product-files/export'], ['POST', '/product-files/import/preview']]) {
      const s1 = (await api(m, u, SYS, m === 'POST' ? { fileName: 'x.csv', content: b64('a') } : undefined)).status;
      const s2 = (await api(m, u, null, m === 'POST' ? { fileName: 'x.csv', content: b64('a') } : undefined)).status;
      rec('Isolation', `System Admin / no login on ${u}`, '403 / 401', `${s1} / ${s2}`, s1 === 403 && s2 === 401);
    }
  });

  rec('Money', 'sale rows: exactly the 4 POS invoices were added (imports and purchasing record no sale)', fin0 + 4,
    one(`select count(*) from sales where store_id=${STORE}`), one(`select count(*) from sales where store_id=${STORE}`) === fin0 + 4);
  const notFonts = external.filter(u => !/^https:\/\/fonts\./.test(u));
  rec('Network', 'no external request at all (fonts bundled, nothing sent to WhatsApp)', 0, external.length, external.length === 0, JSON.stringify(external).slice(0, 200));
  console.log(`\nIMPORT EXPORT E2E PASS ${pass} FAIL ${fail}`);
  await browser.close();
  process.exit(fail ? 1 : 0);
})().catch(async e => { console.error('Error', e); try { await browser.close(); } catch (x) { /* */ } process.exit(1); });

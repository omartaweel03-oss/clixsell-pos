// Complete browser verification: real Flutter web app → API → Spring Boot → SQLite.
// Disposable demo database only: run via run.sh (the safety gate stops it otherwise).
const { requireDisposable } = require('./safety.js');
const { work: WORK, out: S } = requireDisposable();
const { chromium } = require('playwright');
const { execSync } = require('child_process');
const fs = require('fs');
let pass = 0, fail = 0; const fails = [];
const ok = (n, c, info = '') => { c ? pass++ : (fail++, fails.push(n)); console.log(c ? 'PASS' : 'FAIL', n, c ? '' : info); };
const q = sql => execSync(`python3 ${__dirname}/dbq.py "${sql}"`).toString().trim(); // read-only
const num = sql => +q(sql).replace(/[^0-9.\-]/g, '');
const api = (u, pw) => +execSync(`curl -s -o /dev/null -w '%{http_code}' -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"${u}","password":"${pw}"}'`).toString();
let browser;
async function open(ctx, w = 1440, h = 1000) {
  const p = await ctx.newPage(); await p.setViewportSize({ width: w, height: h });
  const st = { errors: [], bad: [] };
  p.on('pageerror', e => st.errors.push(e.message));
  p.on('response', r => { if (r.url().includes(':8080/api') && r.status() >= 500) st.bad.push(`${r.status()} ${new URL(r.url()).pathname}`); });
  await p.goto('http://localhost:8090/', { waitUntil: 'networkidle' }); await p.waitForTimeout(3000);
  await p.evaluate(() => document.querySelector('flt-semantics-placeholder')?.click()); await p.waitForTimeout(800);
  return { p, st };
}
async function session(user, pw, w, h) {
  const ctx = await browser.newContext();
  const { p, st } = await open(ctx, w, h);
  await field(p, 'Username', user); await field(p, 'Password', pw); await p.keyboard.press('Enter'); await p.waitForTimeout(3000);
  return { ctx, p, st };
}
const field = async (p, l, v) => { await p.locator(`flt-semantics-host input[aria-label^="${l}"], flt-semantics-host textarea[aria-label^="${l}"]`).first().click({ timeout: 8000 }); await p.keyboard.press('Control+A'); await p.keyboard.type(v); await p.waitForTimeout(250); };
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
const click = async (p, name, wait = 1400) => { await p.getByRole('button', { name }).first().click({ timeout: 8000 }); await p.waitForTimeout(wait); if (isCompleteSale(name)) await payCashIfAsked(p, wait); };
const has = async (p, name) => (await p.getByRole('button', { name }).count()) > 0;
const txt = async p => p.locator('flt-semantics-host').innerText();
// Visible text PLUS the accessible labels (aria-label) Flutter uses for many texts.
const txtAll = async p => (await txt(p)) + '\n' + (await p.evaluate(() =>
  [...document.querySelectorAll('flt-semantics-host [aria-label]')].map(e => e.getAttribute('aria-label')).join('\n')));
// Rows of a read-only query as a list of strings (first column).
const col = sql => JSON.parse(execSync(`python3 ${__dirname}/dbq.py --json "${sql}"`).toString()).map(r => String(r[0]));
const shot = (p, n) => p.screenshot({ path: `${S}/${n}.png` });
const section = async (name, fn) => { console.log(`\n=== ${name}`); try { await fn(); } catch (e) { ok(`${name}: finished without script error`, false, e.message.split('\n')[0]); } };

(async () => {
  browser = await chromium.launch(process.env.HTTPS_PROXY ? { proxy: { server: process.env.HTTPS_PROXY, bypass: 'localhost,127.0.0.1' } } : {});

  await section('Login errors', async () => {
    const ctx = await browser.newContext(); const { p } = await open(ctx);
    await field(p, 'Username', 'shini.admin'); await field(p, 'Password', 'wrong-pass'); await p.keyboard.press('Enter'); await p.waitForTimeout(2500);
    const t = await txt(p); ok('wrong password: clear message, stays on login', /Invalid username or password|incorrect/i.test(t) && t.includes('Log in'), t.slice(0, 200));
    await ctx.close();
  });

  await section('System Admin', async () => {
    const { ctx, p, st } = await session('sysadmin', 'Admin12345');
    let t = await txt(p);
    ok('menu: Stores + Settings only', t.includes('Stores') && t.includes('Settings') && !/Point of Sale|Reports|Stock Alerts|Cashiers/.test(t));
    ok('Stores page: both stores (cards)', (await p.getByRole('button', { name: 'Store Admins' }).count()) === 2);
    // Each store card opens a page with exactly ITS Store Admins (from the database) and its own store
    // name, never the other store's name or admins. ("Store Admins" is a page with a Back button.)
    const admins = id => col(`select username from users where role='STORE_ADMIN' and store_id=${id} order by username`);
    const stores = { 1: col('select name from stores where store_id=1')[0], 2: col('select name from stores where store_id=2')[0] };
    const expect = { 1: admins(1), 2: admins(2) };
    const pages = [];
    for (let i = 0; i < 2; i++) {
      await p.getByRole('button', { name: 'Store Admins' }).nth(i).click({ timeout: 8000 }); await p.waitForTimeout(1500);
      pages.push(await txtAll(p));
      await click(p, 'Back', 1200);
    }
    for (const [id, other] of [[1, 2], [2, 1]]) {
      const pg = pages.find(x => x.includes(stores[id]));
      ok(`Store Admins page of ${stores[id]}: its admins (${expect[id]}) and none of ${stores[other]}'s (${expect[other]})`,
        !!pg && expect[id].length > 0 && expect[id].every(u => pg.includes(u)) && expect[other].every(u => !pg.includes(u))
          && !pg.includes(stores[other]));
    }
    await click(p, 'Settings'); t = await txt(p);
    ok('Settings: 8 cards', ['Printer', 'Taxes', 'Users', 'Profile', 'Language', 'General', 'AI Mobile', 'Backup'].every(x => t.includes(x)));
    ok('Printer + AI Mobile marked unavailable', (t.match(/Not available yet/g) || []).length === 2);
    // Backup
    const dir = `${WORK}/backups`; const before = fs.existsSync(dir) ? fs.readdirSync(dir).length : 0;
    await click(p, /^Backup/); await click(p, 'Create backup now', 2500); t = await txt(p);
    ok('backup created and checked in the UI', t.includes('created and checked (integrity: ok)'));
    const files = fs.readdirSync(dir); ok('backup file in backend/backups (server folder)', files.length === before + 1 && files.every(f => /^clixsell-backup-\d{8}-\d{6}(-\d+)?\.db$/.test(f)), files.join(','));
    ok('backup folder owner-only (700), file 600', (fs.statSync(dir).mode & 0o777) === 0o700 && files.every(f => (fs.statSync(`${dir}/${f}`).mode & 0o777) === 0o600));
    await shot(p, 'v_sys_backup'); await click(p, 'Close');
    // Profile: own password (then back)
    await click(p, /^Profile/);
    await field(p, 'Current password', 'Admin12345'); await field(p, 'New password', 'Sys-Admin-Changed-9'); await field(p, 'Confirm new password', 'Sys-Admin-Changed-9');
    await click(p, 'Change password', 1800); t = await txt(p);
    ok('sysadmin changes own password', t.includes('Password changed') && api('sysadmin', 'Sys-Admin-Changed-9') === 200 && api('sysadmin', 'Admin12345') === 401);
    await click(p, /^Profile/);
    await field(p, 'Current password', 'Sys-Admin-Changed-9'); await field(p, 'New password', 'Admin12345'); await field(p, 'Confirm new password', 'Admin12345');
    await click(p, 'Change password', 1800);
    ok('sysadmin password back to the demo one', api('sysadmin', 'Admin12345') === 200);
    await click(p, /^Users/); t = await txt(p); ok('Users card opens Stores page', t.includes('Create stores and manage the Store Admins'));
    ok('no page errors / server errors', !st.errors.length && !st.bad.length, JSON.stringify(st));
    await ctx.close();
  });

  await section('Store Admin Shini – products CRUD', async () => {
    const { ctx, p, st } = await session('shini.admin', 'Admin-Pass-1');
    await click(p, 'Add Product');
    await field(p, 'Product name', 'Verify Juice 1L'); await field(p, 'Barcode', '62599900123'); await field(p, 'Price', '7.5');
    await field(p, 'Cost price', '5'); await field(p, 'Quantity', '6'); await field(p, 'Minimum stock', '3'); await field(p, 'Supplier', 'Verify Co');
    await p.getByRole('button', { name: /Category|Select a category/ }).first().click(); await p.waitForTimeout(600);
    await p.getByRole('menuitem', { name: 'Beverages' }).first().click(); await p.waitForTimeout(600);
    await click(p, 'Save Product', 2000);
    let row = q("select name,barcode,selling_price,cost_price,quantity,min_stock from products where barcode='62599900123'");
    ok('add product saved in DB (store 1)', row.includes("'Verify Juice 1L'") && num("select store_id from products where barcode='62599900123'") === 1, row);
    const id = num("select product_id from products where barcode='62599900123'");
    // edit: open via search in products list
    await p.getByRole('button', { name: /Verify Juice 1L/ }).first().click().catch(() => {});
    await p.waitForTimeout(800);
    let edited = false;
    if (await has(p, 'Edit')) { await click(p, 'Edit'); edited = true; }
    else { const e = p.getByRole('button', { name: /Edit/ }); if (await e.count()) { await e.last().click(); await p.waitForTimeout(1300); edited = true; } }
    if (edited) {
      await field(p, 'Quantity', '2'); await click(p, 'Save Changes', 2000);
      ok('edit product: quantity 2 in DB', num(`select quantity from products where product_id=${id}`) === 2);
    } else ok('edit product opened', false, 'no Edit button found');
    await shot(p, 'v_products');
    ok('products: no page errors / server errors', !st.errors.length && !st.bad.length, JSON.stringify(st));
    await ctx.close();
  });

  await section('Store Admin Shini – POS, receipt, suspend/resume, return', async () => {
    const { ctx, p, st } = await session('shini.admin', 'Admin-Pass-1');
    await click(p, 'Point of Sale');
    const water0 = num("select quantity from products where product_id=10");
    await p.getByRole('button').filter({ hasText: 'Mineral Water' }).first().click(); await p.waitForTimeout(700);
    await p.getByRole('button').filter({ hasText: 'Mineral Water' }).first().click(); await p.waitForTimeout(700);
    await click(p, 'Credit card', 800);
    await click(p, 'Complete Sale', 2000);
    let t = await txt(p);
    const sale = q("select sale_id,invoice_number,status,total from sales where store_id=1 order by sale_id desc limit 1");
    const sid = +sale.replace(/[()]/g, '').split(',')[0]; const inv = sale.match(/INV-\d+/)[0];
    ok('POS card sale completed (DB)', sale.includes('COMPLETED') && q(`select payment_method from payments where sale_id=${sid}`).includes('CARD'), sale);
    ok('QR receipt saved + shown', q(`select count(*) from receipts where sale_id=${sid}`) === '(1,)' && t.includes(inv));
    ok('stock -2', num("select quantity from products where product_id=10") === water0 - 2);
    await shot(p, 'v_receipt'); await p.keyboard.press('Escape'); await p.waitForTimeout(800);
    // suspend + resume from Suspended Invoices screen + complete
    await p.getByRole('button').filter({ hasText: 'Mineral Water' }).first().click(); await p.waitForTimeout(700);
    await click(p, 'Suspend Invoice', 1500);
    const susp = q("select sale_id,invoice_number,status from sales where store_id=1 order by sale_id desc limit 1");
    ok('suspend saved SUSPENDED', susp.includes('SUSPENDED'), susp);
    const sinv = susp.match(/INV-\d+/)[0];
    await click(p, 'Suspended Invoices');
    await p.getByRole('row').filter({ hasText: sinv }).getByRole('button', { name: 'Resume in POS' }).click(); await p.waitForTimeout(1500);
    await click(p, 'Complete Sale', 2000);
    ok('resumed + completed with same number', q(`select status from sales where invoice_number='${sinv}' and store_id=1`).includes('COMPLETED'));
    await p.keyboard.press('Escape'); await p.waitForTimeout(800);
    // return 1 of the card sale
    const water1 = num("select quantity from products where product_id=10");
    await click(p, 'Return', 1200);
    await field(p, 'Invoice number or receipt code', inv); await click(p, 'Find', 1500);
    await p.getByRole('button').filter({ hasText: 'Mineral Water' }).last().click().catch(() => {}); await p.waitForTimeout(600);
    await field(p, 'Reason', 'Damaged package');
    await click(p, 'Confirm Return', 2000);
    ok('return saved, stock back +1', q(`select count(*) from returns where sale_id=${sid}`) === '(1,)' && num("select quantity from products where product_id=10") === water1 + 1);
    await p.keyboard.press('Escape'); await p.waitForTimeout(600);
    ok('POS: no page errors / server errors', !st.errors.length && !st.bad.length, JSON.stringify(st));
    await ctx.close();
  });

  await section('Store Admin Shini – alerts, reports, analytics, cashiers, settings (EN + AR)', async () => {
    const { ctx, p, st } = await session('shini.admin', 'Admin-Pass-1');
    await click(p, 'Stock Alerts'); let t = await txt(p);
    const out = num("select count(*) from products where store_id=1 and quantity=0"), low = num("select count(*) from products where store_id=1 and quantity>0 and quantity<=min_stock");
    const m = t.match(/Total alerts\s*(\d+)/); ok('Stock Alerts total = DB', m && +m[1] === out + low, `${m && m[1]} vs ${out + low}`);
    await click(p, 'Reports', 2000); t = await txt(p); ok('Reports page with profit', /Profit|Net/.test(t));
    await shot(p, 'v_reports_en');
    await click(p, 'Sales Analytics', 2000); t = await txt(p); ok('Sales Analytics page', t.includes('Sales Analytics'));
    // Cashiers: add + reset password
    await click(p, 'Cashiers'); await click(p, 'Add Cashier');
    await field(p, 'Username', 'shini.verify'); await field(p, 'Full name', 'Verify Cashier'); await field(p, 'Password', 'Verify-Pass-1');
    await click(p, 'Create', 1800);
    ok('cashier created in store 1', q("select role,store_id from users where username='shini.verify'") === "('CASHIER', 1)");
    const rp = p.getByRole('button', { name: 'Reset password' });
    const n = await rp.count(); await rp.nth(n - 1).click(); await p.waitForTimeout(800);
    await field(p, 'New password', 'Reset-By-Admin-2'); await click(p, 'Save', 1500).catch(async () => { await p.keyboard.press('Enter'); await p.waitForTimeout(1500); });
    ok('Store Admin reset a cashier password (login works)', api('shini.verify', 'Reset-By-Admin-2') === 200 || api('shini.cashier2', 'Reset-By-Admin-2') === 200 || api('shini.cashier', 'Reset-By-Admin-2') === 200);
    await p.keyboard.press('Escape'); await p.waitForTimeout(500);
    // Settings: taxes change → new sale
    await click(p, 'Settings'); await click(p, /^Taxes/);
    await field(p, 'Tax rate', '10'); await click(p, 'Save', 1500);
    ok('tax 10 saved for Shini only', q('select tax_rate from stores order by store_id') === '(10.0,) | (16.0,)');
    await click(p, /^General/); await field(p, 'Phone', '+970 2 111 2222'); await click(p, 'Save', 1500);
    ok('General kept tax 10 (no lost update) + phone saved', q('select tax_rate,phone from stores where store_id=1') === "(10.0, '+970 2 111 2222')");
    // Arabic: every page opens
    await click(p, 'العربية', 1500);
    for (const name of ['نقطة البيع', 'الفواتير المعلقة', 'المنتجات', 'تنبيهات المخزون', 'التقارير', 'المبيعات', 'الكاشير', 'الإعدادات']) {
      const before = st.errors.length; await click(p, name, 1500);
      ok(`AR page opens: ${name}`, st.errors.length === before);
    }
    await shot(p, 'v_settings_ar');
    ok('alerts/reports/settings: no page errors / server errors', !st.errors.length && !st.bad.length, JSON.stringify(st));
    // language survives a restart (same browser profile, new tab after closing the old one)
    await p.close();
    const { p: p2 } = await open(ctx); t = await txt(p2);
    ok('language remembered after restart (login in Arabic)', t.includes('تسجيل الدخول'));
    await ctx.close();
  });

  await section('New sale uses new tax; old sales unchanged', async () => {
    const before = q("select sale_id,tax_rate,total from sales where status='COMPLETED' and store_id=1 order by sale_id");
    const { ctx, p } = await session('shini.cashier', 'Cashier-Pass-1');
    let t = await txt(p); ok('cashier POS shows Tax (10%)', t.includes('Tax (10%)'));
    await p.getByRole('button').filter({ hasText: 'Mineral Water' }).first().click(); await p.waitForTimeout(700);
    await click(p, 'Complete Sale', 2000);
    ok('new sale saved with 10%', q("select tax_rate,total from sales where store_id=1 order by sale_id desc limit 1") === '(10.0, 2.75)', q("select tax_rate,total from sales where store_id=1 order by sale_id desc limit 1"));
    const lastBefore = before.split(' | ').slice(-1)[0].replace(/[()]/g, '').split(',')[0];
    ok('earlier completed sales unchanged', q(`select sale_id,tax_rate,total from sales where status='COMPLETED' and store_id=1 and sale_id<=${lastBefore} order by sale_id`) === before);
    await ctx.close();
  });

  for (const [user, store, own, other] of [['shini.cashier', 1, 'Mineral Water', 'Hamoudeh Yogurt'], ['neshan.cashier', 2, 'Hamoudeh Yogurt', 'Mineral Water']]) {
    await section(`Cashier ${user}`, async () => {
      const { ctx, p, st } = await session(user, 'Cashier-Pass-1');
      let t = await txt(p);
      ok(`${user}: menu POS/Suspended/Products only, no Settings`, t.includes('Point of Sale') && !/Settings|Reports|Stock Alerts|Cashiers|Add Product/.test(t));
      ok(`${user}: sees own store products only`, t.includes(own) && !t.includes(other));
      await click(p, 'Products'); t = await txt(p);
      ok(`${user}: products read-only`, !t.includes('Add Product') && !(await has(p, 'Edit')));
      ok(`${user}: no page errors / server errors`, !st.errors.length && !st.bad.length, JSON.stringify(st));
      if (store === 1) await shot(p, 'v_cashier');
      await ctx.close();
    });
  }

  await section('Store Admin Neshan', async () => {
    const { ctx, p, st } = await session('neshan.admin', 'Admin-Pass-1');
    // Products: the list shows 8 per page. Read EVERY page; compare with the database (read-only).
    const own = col('select name from products where store_id=2 order by name');
    const other = col('select name from products where store_id=1 order by name');
    let t = await txtAll(p); const pages = [t];
    const count = (t.match(/of (\d+) products/) || [])[1];
    for (let k = 2; k <= Math.ceil(own.length / 8); k++) {
      await p.getByRole('button', { name: String(k), exact: true }).first().click({ timeout: 8000 }); await p.waitForTimeout(1500);
      pages.push(await txtAll(p));
    }
    const all = pages.join('\n');
    ok(`neshan.admin: product count on screen = database (${own.length})`, count === String(own.length), count);
    ok(`neshan.admin: all ${own.length} own products found across ${pages.length} pages`, own.every(n => all.includes(n)),
      own.filter(n => !all.includes(n)).join(', '));
    ok(`neshan.admin: none of Shini's ${other.length} products on any page`, other.every(n => !all.includes(n)),
      other.filter(n => all.includes(n)).join(', '));
    t = pages[0];
    await click(p, 'Suspended Invoices'); t = await txt(p); ok('neshan: no Shini invoices', !t.includes('Lina Saleh'));
    await click(p, 'Settings'); await click(p, /^Taxes/);
    const vals = await p.$$eval('input', els => els.map(e => e.value)); await shot(p, 'v_neshan_taxes');
    ok('neshan tax still 16 (DB)', q('select tax_rate from stores where store_id=2') === '(16.0,)');
    ok('neshan: no page errors / server errors', !st.errors.length && !st.bad.length, JSON.stringify(st));
    await ctx.close();
  });

  console.log(`\nFULL E2E PASS ${pass} FAIL ${fail}`); if (fails.length) console.log('FAILED:', fails.join(' | '));
  await browser.close();
})().catch(e => { console.log('ERR', e.message.split('\n')[0]); process.exit(1); });

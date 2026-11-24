// Supplier Management in the REAL Flutter web app (Chromium) → REST → Spring Boot → disposable SQLite.
// English and Arabic, two stores, cashier menu. Every change is checked in the database.
// Run via run.sh (suite: supplier) after supplier_check.py; the safety gate stops it otherwise.
const { requireDisposable } = require('./safety.js');
const { out: OUT } = requireDisposable();
const { chromium } = require('playwright');
const { execSync } = require('child_process');
const path = require('path');

let pass = 0, fail = 0, shotNo = 0;
const ok = (n, c, info = '') => { c ? pass++ : fail++; console.log(c ? 'PASS' : 'FAIL', n, c ? '' : '| ' + info); };
const dbJson = sql => JSON.parse(execSync(`python3 ${__dirname}/dbq.py --json "${sql.replace(/"/g, '\\"')}"`).toString());
const one = sql => { const r = dbJson(sql); return r.length ? r[0][0] : null; };

let browser, CUR;
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
  const f = p.locator(`flt-semantics-host input[aria-label^="${label}"]`).first();
  await f.click({ timeout: 8000 }); await p.keyboard.press('Control+A'); await p.keyboard.press('Backspace');
  if (value !== '') await p.keyboard.type(String(value)); await p.waitForTimeout(250);
};
const click = async (p, name, wait = 1300, nth = 0) => {
  await p.getByRole('button', { name }).nth(nth).click({ timeout: 8000 }); await p.waitForTimeout(wait);
};
const txt = async p => (await p.locator('flt-semantics-host').innerText()) + '\n' + (await p.evaluate(() =>
  [...document.querySelectorAll('flt-semantics-host [aria-label]')].map(e => e.getAttribute('aria-label')).join('\n')));
const shot = async (p, name) => { const f = `${String(++shotNo).padStart(2, '0')}_${name}.png`; await p.screenshot({ path: path.join(OUT, f) }); return f; };
async function login(p, user, pw) {
  await field(p, 'Username', user); await field(p, 'Password', pw); await p.keyboard.press('Enter'); await p.waitForTimeout(3500);
}
async function step(name, fn) {
  try { await fn(); } catch (e) {
    let f = ''; try { f = await shot(CUR, 'FAILED_step'); } catch (x) { /* closed */ }
    ok(name, false, 'ERROR: ' + e.message.split('\n')[0] + ' ' + f);
  }
}
const sup = name => dbJson(`select supplier_id, name, phone, whatsapp_number, address, active, store_id from suppliers where name='${name.replace(/'/g, "''")}'`);

(async () => {
  browser = await chromium.launch(process.env.HTTPS_PROXY ? { proxy: { server: process.env.HTTPS_PROXY, bypass: 'localhost,127.0.0.1' } } : {});

  // ══ English: Shini Store Admin ══════════════════════════════════════════════════════════
  let { ctx, p, st } = await newPage();
  await login(p, 'shini.admin', 'Admin-Pass-1');
  await step('open Suppliers (EN)', async () => {
    await click(p, 'Suppliers', 2500);
    const t = await txt(p);
    const dbNames = dbJson('select name from suppliers where store_id=1').map(r => r[0]);
    ok('EN: page title and Add Supplier button', t.includes('Add Supplier') && t.includes('Suppliers'));
    ok(`EN: all ${dbNames.length} Shini suppliers from the DB are listed`, dbNames.every(n => t.includes(n)), dbNames.filter(n => !t.includes(n)).join(', '));
    ok('EN: no Neshan-only supplier shown', !t.includes('E2E Fresh Farms') || one("select count(*) from suppliers where store_id=1 and name='E2E Fresh Farms'") === 1);
    ok('EN: nothing about purchase orders or sending in Phase 1', !/Purchase Order|Send/i.test(t));
    await shot(p, 'en_suppliers_list');
  });

  await step('add a supplier through the dialog (EN)', async () => {
    await click(p, 'Add Supplier', 1200);
    await field(p, 'Supplier name', 'UI Olive House');
    await field(p, 'WhatsApp number', '0599002222');           // local number: refused in the form
    await click(p, 'Create', 1200);
    let t = await txt(p);
    ok('EN: local WhatsApp number refused with a clear message', t.includes('international format'), t.slice(0, 200));
    ok('EN: nothing saved for the refused form', sup('UI Olive House').length === 0);
    await shot(p, 'en_add_supplier_validation');
    await field(p, 'WhatsApp number', '+970 59 900 2222');
    await field(p, 'Phone (optional)', '09-2381234');
    await field(p, 'Address (optional)', 'Nablus');
    await click(p, 'Create', 2200);
    const row = sup('UI Olive House');
    ok('UI+DB: supplier saved (name, phone, WhatsApp digits, address, active, Shini)',
      JSON.stringify(row.map(r => r.slice(1))) === JSON.stringify([['UI Olive House', '09-2381234', '970599002222', 'Nablus', 1, 1]]), JSON.stringify(row));
    t = await txt(p);
    ok('EN: new supplier listed with +970 599 002 222', t.includes('UI Olive House') && t.includes('+970 599 002 222'));
    await shot(p, 'en_supplier_added');
  });

  await step('search, edit and details (EN)', async () => {
    await field(p, 'Search suppliers', 'Olive House');
    let t = await txt(p);
    ok('EN: search keeps only the matching supplier', t.includes('UI Olive House') && !t.includes('E2E Fresh Farms'));
    await click(p, /^Edit UI Olive House|^Edit$/, 1200);
    await field(p, 'Address (optional)', 'Nablus, Rafidia');
    await click(p, 'Save', 2000);
    ok('UI+DB: address edited', sup('UI Olive House')[0]?.[4] === 'Nablus, Rafidia', JSON.stringify(sup('UI Olive House')));
    await click(p, /^Details of UI Olive House|^Details$/, 1800);
    t = await txt(p);
    ok('EN: details dialog shows contact and "no product yet"', t.includes('No product uses this supplier yet.') && t.includes('Nablus, Rafidia'));
    await shot(p, 'en_supplier_details');
    await click(p, 'Close', 1000);
  });

  await step('deactivate and activate (EN)', async () => {
    await click(p, 'Deactivate', 1200);
    let t = await txt(p);
    ok('EN: deactivation asks first and explains the effect', t.includes('Deactivate supplier?') && t.includes('activate it again'));
    await shot(p, 'en_deactivate_confirm');
    await p.getByRole('button', { name: 'Deactivate' }).last().click(); await p.waitForTimeout(2000);
    ok('UI+DB: supplier inactive', sup('UI Olive House')[0]?.[5] === 0);
    t = await txt(p);
    ok('EN: row shows Inactive and an Activate button', t.includes('Inactive') && t.includes('Activate'));
    await shot(p, 'en_supplier_inactive');
    await click(p, 'Activate', 2000);
    ok('UI+DB: supplier active again (only through this button)', sup('UI Olive House')[0]?.[5] === 1);
  });
  ok('EN: no page errors and no server errors', st.errors.length === 0 && st.bad.length === 0, st.errors.concat(st.bad).join('; '));

  // ══ Arabic ═══════════════════════════════════════════════════════════════════════════════
  await step('Arabic Suppliers page', async () => {
    await click(p, 'العربية', 2500);
    await click(p, 'الموردون', 2500);
    let t = await txt(p);
    ok('AR: title, Add button and WhatsApp number left-to-right', t.includes('الموردون') && t.includes('إضافة مورد') && t.includes('+970 599 002 222'));
    await shot(p, 'ar_suppliers_list');
    await click(p, 'إضافة مورد', 1200);
    await field(p, 'اسم المورد', 'مورد الزيتون التجريبي');
    await field(p, 'رقم WhatsApp', '0599003333');
    await click(p, 'إنشاء', 1200);
    t = await txt(p);
    ok('AR: local number refused with an Arabic message', t.includes('الصيغة الدولية'));
    await shot(p, 'ar_add_supplier_validation');
    await field(p, 'رقم WhatsApp', '970599003333');
    await click(p, 'إنشاء', 2200);
    const row = sup('مورد الزيتون التجريبي');
    ok('AR UI+DB: Arabic supplier saved in Shini with WhatsApp digits', row.length === 1 && row[0][3] === '970599003333' && row[0][6] === 1, JSON.stringify(row));
    t = await txt(p);
    ok('AR: Arabic supplier listed', t.includes('مورد الزيتون التجريبي'));
    await shot(p, 'ar_supplier_added');
    await click(p, 'English', 1500);
  });
  await ctx.close();

  // ══ Neshan Store Admin: isolation ════════════════════════════════════════════════════════
  ({ ctx, p, st } = await newPage());
  await login(p, 'neshan.admin', 'Admin-Pass-1');
  await step('Neshan sees only its own suppliers', async () => {
    await click(p, 'Suppliers', 2500);
    const t = await txt(p);
    const own = dbJson('select name from suppliers where store_id=2').map(r => r[0]);
    ok('Neshan: its own suppliers listed', own.every(n => t.includes(n)), own.filter(n => !t.includes(n)).join(', '));
    ok('Neshan: Shini suppliers not shown', !t.includes('UI Olive House') && !t.includes('مورد الزيتون التجريبي'));
    await shot(p, 'neshan_suppliers_list');
  });
  await ctx.close();

  // ══ Cashier: no Suppliers in the menu ════════════════════════════════════════════════════
  ({ ctx, p, st } = await newPage());
  await login(p, 'shini.cashier', 'Cashier-Pass-1');
  await step('cashier menu', async () => {
    const n = await p.getByRole('button', { name: 'Suppliers' }).count();
    ok('Cashier: no Suppliers item in the menu', n === 0);
    await shot(p, 'cashier_menu');
  });
  await ctx.close();

  // ══ narrow desktop window: everything still reachable ════════════════════════════════════
  ({ ctx, p, st } = await newPage(1024, 768));
  await login(p, 'shini.admin', 'Admin-Pass-1');
  await step('1024 × 768', async () => {
    await click(p, 'Suppliers', 2500);
    const t = await txt(p);
    ok('1024×768: Suppliers page opens and lists suppliers', t.includes('UI Olive House'));
    ok('1024×768: Settings still reachable in the menu', (await p.getByRole('button', { name: 'Settings' }).count()) > 0);
    await shot(p, 'en_suppliers_1024x768');
  });
  ok('no page errors in the other sessions', st.errors.length === 0, st.errors.join('; '));
  await ctx.close();

  ok('DB: still no purchase orders (Phase 1)', one('select count(*) from purchase_orders') === 0);
  await browser.close();
  console.log(`\nSUPPLIER UI PASS ${pass} FAIL ${fail}`);
  process.exit(fail ? 1 : 0);
})();

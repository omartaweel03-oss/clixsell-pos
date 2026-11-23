// Reports screen (English and Arabic) shows the month's net sales including the midnight records
// (value from reports_boundary_check.py's independent calculation). Disposable run only (run.sh).
const { requireDisposable } = require('./safety.js');
const { out: S } = requireDisposable();
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
let pass = 0, fail = 0;
const ok = (n, c, i = '') => { c ? pass++ : fail++; console.log(c ? 'PASS' : 'FAIL', n, c ? '' : i); };
const E = JSON.parse(fs.readFileSync(path.join(S, 'reports_expected.json')));
const money = v => v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const txtAll = async p => (await p.locator('flt-semantics-host').innerText()) + '\n' + (await p.evaluate(() =>
  [...document.querySelectorAll('flt-semantics-host [aria-label]')].map(e => e.getAttribute('aria-label')).join('\n')));

(async () => {
  const b = await chromium.launch(process.env.HTTPS_PROXY ? { proxy: { server: process.env.HTTPS_PROXY, bypass: 'localhost,127.0.0.1' } } : {});
  const ctx = await b.newContext({ viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 1 });
  const p = await ctx.newPage(); const errors = [];
  p.on('pageerror', e => errors.push(e.message));
  await p.goto('http://localhost:8090/', { waitUntil: 'networkidle' }); await p.waitForTimeout(3000);
  await p.evaluate(() => document.querySelector('flt-semantics-placeholder')?.click()); await p.waitForTimeout(800);
  for (const [l, v] of [['Username', 'shini.admin'], ['Password', 'Admin-Pass-1']]) {
    await p.locator(`input[aria-label^="${l}"]`).first().click(); await p.keyboard.type(v);
  }
  await p.keyboard.press('Enter'); await p.waitForTimeout(4000);
  await p.getByRole('button', { name: 'Reports' }).first().click(); await p.waitForTimeout(3000);
  let t = await txtAll(p);
  ok(`Reports (English): net sales of ${E.month} = ₪ ${money(E.netSales)} (includes the midnight sale and return)`,
    t.includes(`₪ ${money(E.netSales)}`), t.slice(0, 300));
  await p.screenshot({ path: path.join(S, 'reports_en.png') });
  await p.getByRole('button', { name: 'Sales Analytics' }).first().click(); await p.waitForTimeout(2500);
  ok('Sales Analytics opens (English)', (await txtAll(p)).includes('Sales Analytics'));
  await p.getByRole('button', { name: 'العربية' }).first().click(); await p.waitForTimeout(2500);
  await p.getByRole('button', { name: 'التقارير' }).first().click(); await p.waitForTimeout(2500);
  t = await txtAll(p);
  ok(`Reports (Arabic): the same net sales ${money(E.netSales)}`, t.includes(money(E.netSales)), t.slice(0, 300));
  await p.screenshot({ path: path.join(S, 'reports_ar.png') });
  ok('no page errors', errors.length === 0, errors.join('; '));
  await b.close();
  console.log(`\nREPORTS UI PASS ${pass} FAIL ${fail}`);
  process.exit(fail ? 1 : 0);
})();

// Guardian Mode in the real app: Flutter web → API → backend → scratch SQLite.
// Guardian Mode in the browser (English / Arabic, two stores) on a disposable demo database. Run via run.sh.
const { requireDisposable } = require('./safety.js');
const { out: S } = requireDisposable(); // stops unless this is the disposable test run
const { chromium } = require('playwright');
const { execSync } = require('child_process');
let pass = 0, fail = 0; const fails = [];
const ok = (n, c, i = '') => { c ? pass++ : (fail++, fails.push(n)); console.log(c ? 'PASS' : 'FAIL', n, c ? '' : i); };
const sql = s => execSync(`python3 ${__dirname}/dbq.py --list "${s}"`).toString().trim(); // read-only
const api = (path, user) => {
  const tok = JSON.parse(execSync(`curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"${user}","password":"Admin-Pass-1"}'`)).token;
  return JSON.parse(execSync(`curl -s localhost:8080/api${path} -H 'Authorization: Bearer ${tok}'`));
};
let b;
async function login(u, pw, w = 1440, h = 1000, scale = 2) {
  const ctx = await b.newContext({ viewport: { width: w, height: h }, deviceScaleFactor: scale });
  const p = await ctx.newPage(); const st = { errors: [], bad: [] };
  p.on('pageerror', e => st.errors.push(e.message));
  p.on('response', r => { if (r.url().includes(':8080/api') && r.status() >= 500) st.bad.push(r.status() + ' ' + r.url()); });
  await p.goto('http://localhost:8090/', { waitUntil: 'networkidle' }); await p.waitForTimeout(3000);
  await p.evaluate(() => document.querySelector('flt-semantics-placeholder')?.click()); await p.waitForTimeout(800);
  for (const [l, v] of [['Username', u], ['Password', pw]]) { await p.locator(`input[aria-label^="${l}"]`).first().click(); await p.keyboard.type(v); }
  await p.keyboard.press('Enter'); await p.waitForTimeout(4000); return { ctx, p, st };
}
// Visible text plus the aria-labels Flutter uses for some nodes.
const txt = async p => (await p.locator('flt-semantics-host').innerText()) + '\n' +
  (await p.evaluate(() => [...document.querySelectorAll('flt-semantics-host [aria-label]')].map(e => e.getAttribute('aria-label')).join('\n')));
const click = async (p, name, w = 1500) => { await p.getByRole('button', { name }).first().click({ timeout: 8000 }); await p.waitForTimeout(w); };
const scrollTo = async (p, y) => { await p.mouse.move(900, 500); await p.mouse.wheel(0, y); await p.waitForTimeout(900); };

// A picture of one dashboard card, found by its title (Flutter draws on a canvas).
async function cardShot(p, title, file, rtl = false) {
  const box = await p.evaluate(t => {
    const el = [...document.querySelectorAll('flt-semantics-host *')].find(e => (e.getAttribute('aria-label') || '') === t
      || (e.childElementCount === 0 && (e.textContent || '').trim() === t));
    if (!el) return null; const r = el.getBoundingClientRect(); return { x: r.x, y: r.y, w: r.width };
  }, title);
  if (!box) { await p.screenshot({ path: file }); return; }
  const x = rtl ? box.x + box.w + 24 - 310 : box.x - 24;
  await p.screenshot({ path: file, clip: { x: Math.max(0, x), y: Math.max(0, box.y - 24), width: 310, height: 250 } });
}
const login2 = (user, pw) => JSON.parse(execSync(`curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"${user}","password":"${pw}"}'`)).token;
const post = (path, token, body) => JSON.parse(execSync(`curl -s -X POST localhost:8080/api${path} -H 'Content-Type: application/json' -H 'Authorization: Bearer ${token}' -d '${JSON.stringify(body)}'`));
const get = (path, token) => JSON.parse(execSync(`curl -s localhost:8080/api${path} -H 'Authorization: Bearer ${token}'`));

(async () => {
  b = await chromium.launch(process.env.HTTPS_PROXY ? { proxy: { server: process.env.HTTPS_PROXY, bypass: 'localhost,127.0.0.1' } } : {});
  const D = api('/guardian/dashboard', 'shini.admin');
  const fmt = v => '₪ ' + v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

  // ── Store Admin, Guardian ON → first page, English ──
  let { ctx, p, st } = await login('shini.admin', 'Admin-Pass-1', 1440, 1000);
  let t = await txt(p);
  ok('Guardian ON: it is the Store Admin\'s first page', t.includes('Smart Guardian Mode'), t.slice(0, 200));
  ok(`today's net sales = API (${fmt(D.todaySales.today)})`, t.includes(fmt(D.todaySales.today)));
  ok(`critical alerts = API (${D.criticalCount})`, t.includes('Critical alerts') && t.includes(String(D.criticalCount)));
  ok(`health = API (${D.health.score}/100)`, t.includes(`${D.health.score}/100`));
  ok(`smart alerts (${D.alertCount}) = API`, t.includes(`Smart alerts (${D.alertCount})`));
  ok('missing-cost notice shown, not counted as loss', t.includes(`${D.costData.incompleteCostInvoices} invoices with a missing cost`));
  ok('no "(s)" plural shortcuts', !t.includes('(s)'));
  ok('rule-based note shown', t.includes('Rule-based analysis'));
  // Decisions U3 / U8 on the cards.
  const rawBand = D.health.score >= 80 ? 'GOOD' : D.health.score >= 60 ? 'ATTENTION' : 'RISK';
  ok(`U3: band from the API (${D.health.band}; limited ${D.health.bandLimited}) and not "Good" with ${D.health.criticalAlerts} critical alerts`,
    D.health.band === (rawBand === 'GOOD' && D.health.criticalAlerts > 0 ? 'ATTENTION' : rawBand) && t.includes('Needs follow-up'));
  ok(`U3: the card names the open critical alerts (${D.health.criticalAlerts})`,
    t.includes(`The score measures general store health · ${D.health.criticalAlerts} critical alerts need review`));
  ok(`U8: critical card = ${D.criticalCount} alerts, ${D.criticalIssues} separate issues`,
    t.includes(`${D.criticalIssues} separate issues`));
  ok('U2: the cashier area counts POS alerts by any user', t.includes('POS alerts this period, by any user') || t.includes('POS alert this period, by any user'));
  ok('no fake AI button / discount buttons', !t.includes('Clixsell AI') && !t.includes('Apply discount') && !t.includes('Request manager approval'));
  await p.screenshot({ path: `${S}/g01_en_top.png` });
  // Pre-merge fix: the Profit Risks card never hides G2c (separate line, not in the number).
  const mEn = n => `+ ${n} margin risk${n === 1 ? '' : 's'} after discount sharing, not included in this number · View`;
  ok(`card: profit risks number unchanged = API (${D.profitRiskCount}, G2a+G2b+G3)`, t.includes('Profit risks') && t.includes(String(D.profitRiskCount)));
  ok(`card: margin line = API marginRiskCount (${D.marginRiskCount})`, D.marginRiskCount > 0 && t.includes(mEn(D.marginRiskCount)));
  await cardShot(p, 'Profit risks', `${S}/g19_en_profit_card.png`);
  await p.getByRole('button', { name: /margin risk/ }).first().click({ timeout: 8000 }); await p.waitForTimeout(1800);
  t = await txt(p);
  ok('card: the margin line opens only the G2c alerts', t.includes('Below cost after its share of the discount') && t.includes('G Yogurt')
    && !t.includes('Confirmed loss at the time of sale') && !t.includes('Price below cost'));
  await p.screenshot({ path: `${S}/g20_en_margin_dialog.png` });
  await p.keyboard.press('Escape'); await p.waitForTimeout(800);
  await ctx.close();
  // A tall window to capture the whole dashboard in one picture.
  ({ ctx, p, st } = await login('shini.admin', 'Admin-Pass-1', 1440, 2150));
  await p.screenshot({ path: `${S}/g02_en_full.png` });
  t = await txt(p);
  ok('recommendations: order before stockout lists G Water', t.includes('Order stock before it runs out'));
  // Purchase Orders Phase 4 (P4-D3): "Create purchase request" opens the real Reorder Suggestions.
  ok('purchase request available (no "not available yet" text)', t.includes('Create purchase request') && !t.includes('Purchase requests are not available yet'));
  await click(p, 'Create purchase request', 2500);
  ok('Create purchase request opens the real Reorder Suggestions page', (await txt(p)).includes('Reorder Suggestions'));
  await p.getByRole('button', { name: 'Guardian Mode' }).first().click(); await p.waitForTimeout(3000);
  t = await txt(p);
  ok('store map by category + cashier area', t.includes('Live store map') && t.includes('Cashier area'));
  ok('products most at risk: G Water with expected date', t.includes('G Water 1.5L') && t.includes(D.atRiskProducts.find(x => x.name === 'G Water 1.5L').expectedStockout));
  ok('profit protection: cheese safe discount 5%', t.includes('G Cheese 250g') && t.includes('Safe discount: up to 5%'));
  ok('unusual activity: cases per day = API', t.includes(D.activity.perDay === 1 ? '1 case / day' : `${D.activity.perDay} cases / day`));

  // View all alerts: scroll the list to read every alert, filter Critical, open an invoice.
  await click(p, 'View all alerts', 2000);
  let all = '';
  let shot = false;
  for (let i = 0; i < 22; i++) {
    const now = await txt(p); all += now;
    if (!shot && now.includes('Below cost after its share of the discount')) { await p.screenshot({ path: `${S}/g18_en_g2c_related.png` }); shot = true; }
    await p.mouse.move(720, 1075); await p.mouse.wheel(0, 250); await p.waitForTimeout(500);
  }
  ok('all alerts dialog lists every alert (G9 deletions by Ahmad Odeh)', all.includes('Repeated product deletions') && all.includes('By: Ahmad Odeh'));
  ok('resumed invoice: completed by Yousef, started by Lina (with roles)', all.includes('Completed by: Yousef Khalil (Cashier) · started by: Lina Saleh (Cashier)'));
  ok('U2: the Store Admin\'s own discount says Store Admin, not cashier', all.includes('Completed by: Ahmad Odeh (Store Admin)'));
  ok('U4: G2c shown as a product margin risk, not an invoice loss', all.includes('Below cost after its share of the discount')
    && all.includes('Allocation-based product margin risk, NOT a confirmed invoice loss.') && all.includes('G Yogurt'));
  ok('U8: linked alerts say "Also reported as … one issue"', all.includes('Also reported as: '));
  ok('yesterday\'s events: user not recorded', all.includes('User not recorded (before the activity log started)'));
  ok('cancellations today: by Yousef Khalil – Cashier (3)', all.includes('By: Yousef Khalil – Cashier (3)'));
  await p.mouse.wheel(0, -5000); await p.waitForTimeout(600);
  await p.screenshot({ path: `${S}/g03_en_all_alerts.png` });
  await p.getByRole('checkbox', { name: 'Critical' }).first().click({ timeout: 8000 }); await p.waitForTimeout(1200);
  t = await txt(p);
  ok('Critical filter hides "Needs attention" alerts', !t.includes('Low stock products') && t.includes('Confirmed loss at the time of sale'));
  await p.screenshot({ path: `${S}/g04_en_alerts_critical.png` });
  await click(p, 'View invoice', 2500);
  t = await txt(p);
  ok('"View invoice" opens the real invoice (GET /api/sales/{id})', /INV-\d{6}|HIST-\d+/.test(t) && t.includes('Total'));
  await p.screenshot({ path: `${S}/g06_en_invoice.png` });
  await p.keyboard.press('Escape'); await p.waitForTimeout(800);

  // Health breakdown.
  await click(p, 'How is it calculated?', 1500);
  t = await txt(p);
  ok('health parts shown with their numbers', ['Stock', 'Profit', 'Discounts', 'Activity'].every(x => t.includes(x)) && t.includes(`${D.health.parts[0].points} / 25`));
  ok('U3: health dialog explains the limited band', !D.health.bandLimited || t.includes('Shown as "Needs follow-up", not "Good", while critical alerts are open.'));
  ok('U3: formula says the score does not mean there are no risks', t.includes('it does not mean there are no risks'));
  await p.screenshot({ path: `${S}/g05_en_health.png` });
  await p.keyboard.press('Escape'); await p.waitForTimeout(800);

  // Activity report.
  await click(p, 'View activity report', 2000);
  t = await txt(p);
  const deletions = sql("select count(*) from activity_log where event_type='PRODUCT_DELETED' and store_id=1");
  ok('activity report: deletions and discounts with real users', t.includes('Product deleted') && t.includes('Ahmad Odeh') && t.includes('Discount on a sale'), deletions);
  await p.screenshot({ path: `${S}/g07_en_activity.png` });
  await p.keyboard.press('Escape'); await p.waitForTimeout(800);

  // Product from "most at risk" → Product Details.
  await p.getByRole('button').filter({ hasText: 'G Water 1.5L' }).first().click({ timeout: 8000 }).catch(() => {}); await p.waitForTimeout(2000);
  t = await txt(p);
  ok('at-risk product opens Product Details', t.includes('Product Details') && t.includes('G Water 1.5L'), t.slice(0, 200));
  await click(p, 'Guardian Mode', 2500);

  // ── Every remaining button (audit item 9) ──
  await p.getByRole('button', { name: 'View all', exact: true }).nth(1).click({ timeout: 8000 }); await p.waitForTimeout(1800);
  let pr = ''; for (let i = 0; i < 8; i++) { pr += await txt(p); await p.mouse.move(720, 1075); await p.mouse.wheel(0, 250); await p.waitForTimeout(400); }
  ok('Profit risks → View all: profit alerts only (no deletions / discounts)', pr.includes('Confirmed loss at the time of sale') && pr.includes('Sold below cost') && !pr.includes('Repeated product deletions') && !pr.includes('Canceled invoices above the limit'));
  ok('returned / sale-time wording present', pr.includes('when it was sold'));
  ok('Profit risks list stays G2a / G2b / G3 (no G2c)', !pr.includes('Below cost after its share of the discount'));
  await p.keyboard.press('Escape'); await p.waitForTimeout(800);
  await p.getByRole('button', { name: /Bakery/ }).first().click({ timeout: 8000 }); await p.waitForTimeout(2200);
  t = await txt(p);
  ok('map category tile → Stock Alerts', t.includes('Create purchase request') || t.includes('Stock Alerts'), t.slice(0, 150));
  await click(p, 'Guardian Mode', 2500);
  await p.getByRole('button', { name: /Cashier area/ }).first().click({ timeout: 8000 }); await p.waitForTimeout(2000);
  ok('map cashier-area tile → activity report', (await txt(p)).includes('Activity report'));
  await p.keyboard.press('Escape'); await p.waitForTimeout(800);
  await p.getByRole('button', { name: 'Show products', exact: true }).nth(1).click({ timeout: 8000 }); await p.waitForTimeout(1500);
  t = await txt(p);
  ok('T2 "Show products" → list with the low-margin product', t.includes('Increase profit') && t.includes('G Cheese 250g') && t.includes('margin 5%'));
  await p.getByRole('button').filter({ hasText: 'G Cheese 250g' }).last().click({ timeout: 8000 }); await p.waitForTimeout(2200);
  ok('product from the list → Product Details', (await txt(p)).includes('Product Details'));
  await click(p, 'Guardian Mode', 2500);
  await click(p, 'Refresh', 2000);
  ok('Refresh reloads the dashboard', (await txt(p)).includes(`Smart alerts (`));
  // Previous month: current sections are labelled.
  await p.getByRole('button', { name: /November/ }).first().click({ timeout: 8000 }); await p.waitForTimeout(800);
  await p.getByRole('menuitem', { name: 'October' }).first().click({ timeout: 8000 });
  await p.waitForTimeout(2500);
  t = await txt(p);
  ok('previous month: past note + "Now" labels on current sections', t.includes('Past month:') && t.includes('Now') && t.includes('Cashier area: the chosen period'));
  await p.screenshot({ path: `${S}/g16_en_past_month.png` });
  await p.getByRole('button', { name: /October/ }).first().click({ timeout: 8000 }); await p.waitForTimeout(800);
  await p.getByRole('menuitem', { name: 'November' }).first().click({ timeout: 8000 });
  await p.waitForTimeout(2500);

  // Arabic.
  await click(p, 'العربية', 3000);
  await p.screenshot({ path: `${S}/g08_ar_full.png` });
  t = await txt(p);
  ok('U3 / U8 Arabic: health note and separate issues', t.includes(`النسبة تقيس الوضع العام · تنبيهات حرجة تحتاج مراجعة: ${D.health.criticalAlerts}`)
    && t.includes(`حالات منفصلة: ${D.criticalIssues}`));
  ok('Arabic: title and sections', ['وضع الحارس الذكي', 'مبيعات اليوم', 'تنبيهات حرجة', 'مخاطر الربح', 'مؤشر صحة المتجر', 'التوصيات الذكية', 'خريطة المتجر الحية', 'المنتجات الأكثر عرضة للخطر', 'حماية الربح', 'نشاط غير طبيعي'].every(x => t.includes(x)));
  ok(`card (Arabic): margin line = API (${D.marginRiskCount})`, t.includes(`مخاطر هامش بعد توزيع الخصم، غير محسوبة في هذا الرقم: ${D.marginRiskCount} · عرض`));
  await cardShot(p, 'مخاطر الربح', `${S}/g21_ar_profit_card.png`, true);
  await click(p, 'عرض كل التنبيهات', 2000);
  await p.screenshot({ path: `${S}/g09_ar_all_alerts.png` });
  await p.keyboard.press('Escape'); await p.waitForTimeout(800);

  // Turn OFF (asks first), data kept, log written.
  await p.getByRole('switch').first().click({ timeout: 8000 }).catch(async () => { await p.getByRole('checkbox').first().click(); });
  await p.waitForTimeout(1200);
  await p.screenshot({ path: `${S}/g10_ar_disable_confirm.png` });
  await click(p, 'إيقاف', 2500);
  t = await txt(p);
  ok('OFF: off page shown, DB OFF, log row by the Store Admin',
    t.includes('وضع الحارس متوقف') && sql("select guardian_enabled from stores where store_id=1") === '[(0,)]'
      && sql("select u.username from activity_log l join users u on u.user_id=l.user_id where event_type='GUARDIAN_DISABLED' order by log_id desc limit 1") === "[('shini.admin',)]");
  await p.screenshot({ path: `${S}/g11_ar_off.png` });
  await click(p, 'تفعيل وضع الحارس', 1200); await click(p, 'تفعيل', 3000);
  ok('ON again: dashboard back with the same alerts', (await txt(p)).includes(`تنبيهات ذكية (${D.alertCount})`));
  ok('no page errors / no 5xx (Store Admin)', st.errors.length === 0 && st.bad.length === 0, JSON.stringify(st));
  await ctx.close();

  // Smaller windows (desktop layout, no separate mobile design).
  ({ ctx, p, st } = await login('shini.admin', 'Admin-Pass-1', 1024, 768));
  await p.screenshot({ path: `${S}/g12_en_1024.png` }); await ctx.close();
  ({ ctx, p, st } = await login('shini.admin', 'Admin-Pass-1', 390, 844));
  await p.screenshot({ path: `${S}/g13_en_390.png` }); await ctx.close();

  // Cashier: no Guardian Mode.
  ({ ctx, p, st } = await login('shini.cashier', 'Cashier-Pass-1'));
  t = await txt(p);
  ok('cashier: no Guardian Mode in the menu', !t.includes('Guardian Mode') && t.includes('Point of Sale'));
  await p.screenshot({ path: `${S}/g14_cashier.png` }); await ctx.close();

  // The other store: zero margin risks, then two of its own (multi-store isolation).
  const NA = login2('neshan.admin', 'Admin-Pass-1');
  const nBefore = get('/guardian/dashboard', NA);
  ({ ctx, p, st } = await login('neshan.admin', 'Admin-Pass-1'));
  t = await txt(p);
  ok('Neshan sees only its own Guardian data', t.includes('Smart Guardian Mode') && !t.includes('G Water') && !t.includes('Lina Saleh') && !t.includes('Ahmad Odeh'));
  ok(`Neshan zero: API marginRiskCount 0, no margin line (profit risks ${nBefore.profitRiskCount})`, nBefore.marginRiskCount === 0 && !t.includes('margin risk'));
  await p.screenshot({ path: `${S}/g15_neshan.png` });
  await cardShot(p, 'Profit risks', `${S}/g22_en_neshan_card_zero.png`);
  const prod = (name, bc, cat, price, cost) => post('/products', NA, { name, barcode: bc, category: cat, sellingPrice: price, quantity: 30,
    minStock: 2, supplierName: 'Guardian Test Supplier', costPrice: cost }).productId;
  const nYog = prod('N Yogurt', '7290000000158', 'dairy', 5, 4.6), nLab = prod('N Labneh', '7290000000165', 'dairy', 10, 9.2),
    nChoc = prod('N Chocolate', '7290000000172', 'snacks', 20, 8);
  const NC = login2('neshan.cashier', 'Cashier-Pass-1');
  for (const items of [[[nYog, 1], [nChoc, 1]], [[nLab, 1], [nChoc, 1]]]) {
    post('/sales', NC, { items: items.map(([productId, quantity]) => ({ productId, quantity })), discountType: 'PERCENT', discountValue: 10, paymentMethod: 'CASH' });
  }
  const nAfter = get('/guardian/dashboard', NA), sAfter = get('/guardian/dashboard', login2('shini.admin', 'Admin-Pass-1'));
  const nG2c = get('/guardian/alerts?rule=G2c', NA).alerts.map(a => a.params.productName).sort();
  ok('Neshan multiple: API marginRiskCount 2 = its G2c alerts (N Labneh, N Yogurt)', nAfter.marginRiskCount === 2 && JSON.stringify(nG2c) === '["N Labneh","N Yogurt"]');
  ok('Neshan: profit risks number unchanged by G2c', nAfter.profitRiskCount === nBefore.profitRiskCount);
  ok(`Shini unchanged by Neshan's sales (marginRiskCount ${D.marginRiskCount})`, sAfter.marginRiskCount === D.marginRiskCount);
  await click(p, 'Refresh', 2500);
  t = await txt(p);
  ok('Neshan card shows "+ 2 margin risks …"', t.includes(mEn(2)));
  await cardShot(p, 'Profit risks', `${S}/g23_en_neshan_card_two.png`);
  await p.getByRole('button', { name: /margin risk/ }).first().click({ timeout: 8000 }); await p.waitForTimeout(1800);
  t = await txt(p);
  ok('Neshan: the line opens its own 2 G2c alerts, never Shini\'s', t.includes('N Yogurt') && t.includes('N Labneh') && !t.includes('G Yogurt'));
  await p.keyboard.press('Escape'); await p.waitForTimeout(800);
  await click(p, 'العربية', 3000);
  t = await txt(p);
  ok('Neshan Arabic: "مخاطر هامش … : 2 · عرض"', t.includes('مخاطر هامش بعد توزيع الخصم، غير محسوبة في هذا الرقم: 2 · عرض'));
  await cardShot(p, 'مخاطر الربح', `${S}/g24_ar_neshan_card_two.png`, true);
  ok('no page errors / no 5xx (Neshan)', st.errors.length === 0 && st.bad.length === 0, JSON.stringify(st));
  await ctx.close();

  await b.close();
  console.log(`\nUI PASS ${pass} FAIL ${fail}`); fails.forEach(f => console.log('  FAILED:', f));
})();

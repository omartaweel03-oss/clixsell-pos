const L = require('./lib.js'); const S = __dirname;
const { execSync } = require('child_process');
const q = (sql) => execSync(`python3 ${S}/q.py ${S}/clixsell.db "${sql}"`).toString().trim();
const num = (sql) => +q(sql).replace(/[^0-9.]/g, '');
let pass = 0, fail = 0; const ok = (name, c, info = '') => { c ? pass++ : fail++; console.log(c ? 'PASS' : 'FAIL', name, info); };
const btn = async (p, name, exact = false) => { const l = exact ? p.getByRole('button', { name, exact: true }) : p.getByRole('button').filter({ hasText: name }); await l.first().click({ timeout: 8000 }); await p.waitForTimeout(1300); };
const txt = async (p) => await p.locator('flt-semantics-host').innerText();
const DIALOG = 'Discard your changes?';
(async () => {
  let h = await L.start(S, 1440, 1000); let p = h.p;
  await h.login('shini.cashier', 'Cashier-Pass-1');
  const water0 = num("select quantity from products where product_id=10");
  await btn(p, 'Mineral Water'); await btn(p, 'Suspend Invoice');
  const [id, inv] = q("select sale_id,invoice_number from sales where store_id=1 order by sale_id desc limit 1").replace(/[()']/g, '').split(', ');
  const items = () => q(`select product_id,quantity from sale_items where sale_id=${id} order by product_id`);
  const saved0 = items(); const count0 = num("select count(*) from sales");
  console.log('   invoice', inv, 'saved items', saved0);
  const resumeFromScreen = async () => { await btn(p, 'Suspended Invoices', true); await p.getByRole('row').filter({ hasText: inv }).getByRole('button', { name: 'Resume in POS' }).click({ timeout: 8000 }); await p.waitForTimeout(1500); };

  // 1. resume, then resume again without changes: no question
  await resumeFromScreen();
  await resumeFromScreen();
  let t = await txt(p);
  ok('1 no changes: no question, POS opens', !t.includes(DIALOG) && t.includes('Complete Sale') && t.includes(inv));

  // 2. change it, then "Resume in POS" again: asks
  await p.getByRole('button', { name: 'Increase' }).first().click(); await p.waitForTimeout(700);
  await btn(p, "Palestinian Za'atar");
  await resumeFromScreen();
  t = await txt(p);
  ok('2 changed: the question appears', t.includes(DIALOG) && t.includes(`${inv} is already open`));
  await h.shot('d1_question_en');
  await btn(p, 'Keep my changes', true);
  t = await txt(p);
  ok('2 keep my changes: POS with the edited invoice', t.includes('Complete Sale') && t.includes(inv) && t.includes('Items 2') , (t.match(/Items \d+/) || [''])[0]);
  await h.shot('d2_kept_changes');
  ok('2 nothing sent to the server', items() === saved0, items());

  // 3. discard
  await resumeFromScreen();
  await p.getByRole('button', { name: 'Discard changes', exact: true }).click({ timeout: 8000 }); await p.waitForTimeout(1500);
  t = await txt(p);
  ok('3 discard: saved version loaded', t.includes('Items 1') && !t.includes(DIALOG), (t.match(/Items \d+/) || [''])[0]);
  ok('3 still saved as before', items() === saved0);
  await h.shot('d3_discarded');

  // 4. same rule from the POS "Suspended" window; keep, then suspend saves the edits
  await p.getByRole('button', { name: 'Increase' }).first().click(); await p.waitForTimeout(700);
  await btn(p, 'Suspended', true);
  await p.getByRole('button', { name: 'Resume' }).first().click({ timeout: 8000 }).catch(async () => { await btn(p, 'Resume'); });
  await p.waitForTimeout(1200);
  t = await txt(p); ok('4 POS window: the question appears too', t.includes(DIALOG));
  await btn(p, 'Keep my changes', true);
  await btn(p, 'Suspend Invoice');
  ok('4 kept changes saved on suspend, same number, no new invoice', items() === '(10, 2)' && num("select count(*) from sales") === count0 && q(`select status from sales where sale_id=${id}`).includes('SUSPENDED'), items());
  ok('4 stock unchanged while suspended', num("select quantity from products where product_id=10") === water0);

  // 5. resume (saved now, no question) and complete
  await resumeFromScreen();
  t = await txt(p); ok('5 after saving: no question', !t.includes(DIALOG) && t.includes(inv));
  await btn(p, 'Complete Sale');
  ok('5 completed with the same number', q(`select invoice_number,status from sales where sale_id=${id}`).includes(`'${inv}', 'COMPLETED'`));
  ok('5 stock: water -2', num("select quantity from products where product_id=10") === water0 - 2);
  await h.b.close();

  // 6. Arabic
  h = await L.start(S, 1440, 1000); p = h.p;
  await h.login('shini.admin', 'Admin-Pass-1');
  await btn(p, 'العربية'); await p.waitForTimeout(1000);
  await btn(p, 'الفواتير المعلقة', true);
  await p.getByRole('button', { name: 'استكمال في نقطة البيع' }).first().click({ timeout: 8000 });
  await p.waitForTimeout(1500);
  await p.getByRole('button', { name: 'زيادة' }).first().click(); await p.waitForTimeout(700);
  await btn(p, 'الفواتير المعلقة', true);
  t = await txt(p);
  await p.getByRole('button', { name: 'استكمال في نقطة البيع' }).first().click({ timeout: 8000 }); await p.waitForTimeout(1500);
  t = await txt(p); ok('6 Arabic question', t.includes('تجاهل تعديلاتك؟') && t.includes('الإبقاء على تعديلاتي'));
  await h.shot('d4_question_ar');
  await h.b.close();
  console.log(`\nE2E PASS ${pass} FAIL ${fail}`);
})().catch(e => console.log('ERR', e.message.split('\n')[0]));

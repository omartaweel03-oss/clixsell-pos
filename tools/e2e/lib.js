const { chromium } = require('playwright');
const { execSync } = require('child_process');
exports.start = async (S, w = 1440, h = 900) => {
  const b = await chromium.launch(process.env.HTTPS_PROXY ? { proxy: { server: process.env.HTTPS_PROXY, bypass: 'localhost,127.0.0.1' } } : {});
  const p = await b.newPage({ viewport: { width: w, height: h } });
  p.on('pageerror', e => console.log('pageerror:', e.message));
  p.on('response', r => { if (r.url().includes(':8080/api')) console.log('   ', r.request().method(), new URL(r.url()).pathname + new URL(r.url()).search, r.status()); });
  await p.goto('http://localhost:8090/', { waitUntil: 'networkidle' }); await p.waitForTimeout(3000);
  await p.evaluate(() => document.querySelector('flt-semantics-placeholder')?.click());
  await p.waitForTimeout(800);
  const h_ = {
    b, p,
    field: async (label, text) => { await p.locator(`flt-semantics-host input[aria-label^="${label}"], flt-semantics-host textarea[aria-label^="${label}"]`).first().click(); await p.keyboard.press('Control+A'); await p.keyboard.type(text); await p.waitForTimeout(300); },
    btn: async (name, exact = false) => { const loc = exact ? p.getByRole('button', { name, exact: true }) : p.getByRole('button').filter({ hasText: name }); await loc.first().click(); await p.waitForTimeout(1200); },
    shot: async (n) => { await p.screenshot({ path: `${S}/${n}.png` }); console.log('  [shot]', n); },
    db: (sql) => console.log('   DB:', execSync(`python3 ${S}/q.py ${S}/clixsell.db "${sql}"`).toString().trim().replace(/\n/g, ' | ')),
    login: async (u, pw) => { await h_.field('Username', u); await h_.field('Password', pw); await p.keyboard.press('Enter'); await p.waitForTimeout(3000); },
  };
  return h_;
};

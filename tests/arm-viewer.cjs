// Controlled browser regressions for the URDF viewer page; no device claims.
// The URDF is replaced by a mesh-less 5-joint stub so SwiftShader renders fast
// and the stale/limit timing stays deterministic; three.js itself is the real file.
const { test, before, after, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');

let playwright;
try { playwright = require('playwright'); }
catch { playwright = require(path.resolve(path.dirname(process.execPath), '../lib/node_modules/playwright')); }

const ASSETS = path.resolve(__dirname, '../app/src/main/assets');
const URDF_PATH = '/urdf/URDF_armv001_a3tip.urdf';

// 五个 revolute 关节链，限位与真实 URDF 同值；无 visual → 无 STL，渲染只剩网格。
const STUB_URDF = `<?xml version="1.0"?>
<robot name="test_arm">
  <link name="base_link"/>
  <link name="l1"/><link name="l2"/><link name="l3"/><link name="l4"/><link name="l5"/>
  <joint name="j1" type="revolute">
    <parent link="base_link"/><child link="l1"/>
    <origin xyz="0 0 0.05" rpy="0 0 0"/><axis xyz="0 0 1"/>
    <limit lower="-3.14" upper="3.14"/>
  </joint>
  <joint name="j2" type="revolute">
    <parent link="l1"/><child link="l2"/>
    <origin xyz="0 0 0.15" rpy="0 0 0"/><axis xyz="0 1 0"/>
    <limit lower="-2.15" upper="1.26"/>
  </joint>
  <joint name="j3" type="revolute">
    <parent link="l2"/><child link="l3"/>
    <origin xyz="0 0 0.15" rpy="0 0 0"/><axis xyz="0 1 0"/>
    <limit lower="-0.75" upper="2.38"/>
  </joint>
  <joint name="j4" type="revolute">
    <parent link="l3"/><child link="l4"/>
    <origin xyz="0 0 0.10" rpy="0 0 0"/><axis xyz="1 0 0"/>
    <limit lower="-3.14" upper="3.14"/>
  </joint>
  <joint name="j5" type="revolute">
    <parent link="l4"/><child link="l5"/>
    <origin xyz="0 0 0.05" rpy="0 0 0"/><axis xyz="0 1 0"/>
    <limit lower="-1.57" upper="1.57"/>
  </joint>
</robot>`;

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript',
  '.urdf': 'application/xml',
};

let browser, server, origin, context, page, pageErrors;

before(async () => {
  browser = await playwright.chromium.launch({ headless: true, args: [
    '--no-sandbox', '--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader',
  ] });
  server = http.createServer((req, res) => {
    if (req.url.split('?')[0] === URDF_PATH) {
      res.setHeader('Content-Type', 'application/xml');
      res.end(STUB_URDF);
      return;
    }
    const rel = decodeURIComponent(req.url.split('?')[0]);
    const file = path.join(ASSETS, rel);
    if (!file.startsWith(ASSETS) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
      res.writeHead(404); res.end(); return;
    }
    res.setHeader('Content-Type', MIME[path.extname(file)] || 'application/octet-stream');
    res.end(fs.readFileSync(file));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  origin = `http://127.0.0.1:${server.address().port}`;
});

after(async () => {
  if (browser) await browser.close();
  if (server) await new Promise(resolve => server.close(resolve));
});

beforeEach(async () => {
  context = await browser.newContext({ viewport: { width: 800, height: 600 }, deviceScaleFactor: 1 });
  pageErrors = [];
  page = await context.newPage();
  page.on('pageerror', error => pageErrors.push(error.message));
  await page.goto(origin + '/viewer/index.html');
  await page.waitForFunction(() => window.viewerStatus === 'ready:joints=5', null, { timeout: 15000 });
});

afterEach(async () => {
  await context.close();
  assert.deepEqual(pageErrors, [], 'viewer must not throw uncaught page errors');
});

test('viewer builds five joint readouts and waits for data before any setJoints call', async () => {
  assert.equal(await page.locator('.joint').count(), 5);
  const pill = await page.evaluate(() => ({
    state: document.getElementById('statusPill').dataset.state,
    text: document.getElementById('statusText').textContent,
  }));
  assert.equal(pill.state, 'wait');
  assert.equal(pill.text, '等待数据');
  assert.ok((await page.locator('#warnLine').textContent()).includes('未收到关节数据'));
  assert.equal(await page.locator('#warnLine').evaluate(el => el.classList.contains('on')), true);
});

test('setJoints updates readouts in degrees, tolerates null joints, and flips the pill live', async () => {
  await page.evaluate(() => window.setJoints([0.5236, null, -0.2, 1.0, 0.5]));
  const vals = await page.locator('.joint .val').evaluateAll(nodes => nodes.map(n => n.textContent));
  assert.equal(vals[0], '30.0°');
  assert.equal(vals[1], '—');
  assert.equal(vals[2], '-11.5°');
  const pill = await page.evaluate(() => ({
    state: document.getElementById('statusPill').dataset.state,
    text: document.getElementById('statusText').textContent,
  }));
  assert.equal(pill.state, 'live');
  assert.equal(pill.text, '实时');
  assert.equal(await page.locator('#warnLine').evaluate(el => el.classList.contains('on')), false);
});

test('values beyond the URDF limits clamp and are flagged on the chip and warn line', async () => {
  const limits = await page.evaluate(() => window.getJointInfo());
  assert.deepEqual(limits.map(l => [l.lo, l.hi]), [
    [-3.14, 3.14], [-2.15, 1.26], [-0.75, 2.38], [-3.14, 3.14], [-1.57, 1.57],
  ]);
  await page.evaluate(() => window.setJoints([0, 1.76, 0, 0, 0]));
  const vals = await page.locator('.joint .val').evaluateAll(nodes => nodes.map(n => n.textContent));
  assert.equal(vals[1], '72.2°');
  const clampClasses = await page.locator('.joint').evaluateAll(nodes =>
    nodes.map(n => n.classList.contains('clamp')));
  assert.deepEqual(clampClasses, [false, true, false, false, false]);
  const warn = await page.locator('#warnLine').textContent();
  assert.ok(warn.includes('机械限位') && warn.includes('J2'), warn);
});

test('stale data flips the pill to stale and surfaces the stop warning', async () => {
  await page.evaluate(() => window.setJoints([0.1, 0.1, 0.1, 0.1, 0.1]));
  await page.waitForFunction(() => document.getElementById('statusPill').dataset.state === 'live');
  await page.waitForFunction(() => document.getElementById('statusPill').dataset.state === 'stale',
    null, { timeout: 5000 });
  assert.equal(await page.locator('#statusText').textContent(), '数据中断');
  assert.ok((await page.locator('#warnLine').textContent()).includes('已停止'));
});

test('layout keeps the deck inside a phone viewport with five equal columns', async () => {
  await page.setViewportSize({ width: 390, height: 844 });
  const deck = await page.evaluate(() => {
    const deck = document.querySelector('.deck').getBoundingClientRect();
    return {
      left: deck.left, right: deck.right, bottom: deck.bottom,
      viewport: { w: innerWidth, h: innerHeight },
      pageWidth: document.documentElement.scrollWidth,
      columns: getComputedStyle(document.querySelector('.joints')).gridTemplateColumns.split(' ').length,
    };
  });
  assert.equal(deck.pageWidth, deck.viewport.w);
  assert.equal(deck.columns, 5);
  assert.ok(deck.left >= 8 && deck.right <= deck.viewport.w - 8);
  assert.ok(deck.bottom <= deck.viewport.h);
});

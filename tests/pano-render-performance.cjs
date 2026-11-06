const { test, before, after, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const zlib = require('node:zlib');

let playwright;
try { playwright = require('playwright'); }
catch { playwright = require(path.resolve(path.dirname(process.execPath), '../lib/node_modules/playwright')); }

function crc32(bytes) {
  let crc = -1;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  return (crc ^ -1) >>> 0;
}

function png(width, height) {
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width); header.writeUInt32BE(height, 4);
  header[8] = 8; header[9] = 2;
  const pixels = Buffer.alloc((width * 3 + 1) * height);
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk('IHDR', header), chunk('IDAT', zlib.deflateSync(pixels)), chunk('IEND', Buffer.alloc(0))]);
}

function chunk(type, bytes) {
  const name = Buffer.from(type), length = Buffer.alloc(4), checksum = Buffer.alloc(4);
  length.writeUInt32BE(bytes.length);
  checksum.writeUInt32BE(crc32(Buffer.concat([name, bytes])));
  return Buffer.concat([length, name, bytes, checksum]);
}

let browser, server, origin, context, page;

before(async () => {
  browser = await playwright.chromium.launch({ headless: true, args: [
    '--no-sandbox', '--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader',
  ] });
  const html = fs.readFileSync(path.resolve(__dirname, '../pano_demo.html'));
  server = http.createServer((req, res) => {
    if (req.url.startsWith('/pano_demo.html')) {
      res.setHeader('Content-Type', 'text/html; charset=utf-8'); res.end(html);
    } else if (req.url === '/equirect.jpg') {
      res.setHeader('Content-Type', 'image/png'); res.end(png(64, 32));
    } else if (req.url === '/larger.png') {
      res.setHeader('Content-Type', 'image/png'); res.end(png(128, 64));
    } else { res.writeHead(404); res.end(); }
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
  await context.addInitScript(() => {
    window.__gpu = { draws: 0, allocations: 0, updates: 0, anisotropyQueries: 0 };
    const proto = WebGL2RenderingContext.prototype;
    for (const [method, counter] of [['drawArrays', 'draws'], ['texImage2D', 'allocations'],
      ['texSubImage2D', 'updates']]) {
      const original = proto[method];
      proto[method] = function (...args) {
        window.__gpu[counter]++;
        return original.apply(this, args);
      };
    }
    const getExtension = proto.getExtension;
    proto.getExtension = function (name) {
      if (name === 'EXT_texture_filter_anisotropic') window.__gpu.anisotropyQueries++;
      return getExtension.call(this, name);
    };
  });
  page = await context.newPage();
  await page.goto(origin + '/pano_demo.html?yaw=0&pitch=0&fov=90');
  await page.waitForFunction(() => ready);
});

afterEach(async () => { await context.close(); });

test('static viewer stops submitting WebGL draws after the view settles', async () => {
  await page.waitForTimeout(700);
  const settled = await page.evaluate(() => __gpu.draws);
  await page.waitForTimeout(300);
  assert.equal(await page.evaluate(() => __gpu.draws), settled);
});

test('same-size image updates reuse storage and cache anisotropy lookup', async () => {
  const before = await page.evaluate(() => ({ ...__gpu }));
  await page.evaluate(() => uploadTexture(img));
  await page.waitForFunction(count => __gpu.updates > count, before.updates);
  const reused = await page.evaluate(() => ({ ...__gpu }));
  assert.equal(reused.allocations, before.allocations);
  assert.equal(reused.updates, before.updates + 1);
  assert.equal(reused.anisotropyQueries, before.anisotropyQueries);

  await page.evaluate(async () => {
    const larger = new Image();
    larger.src = '/larger.png';
    await larger.decode();
    uploadTexture(larger);
  });
  const resized = await page.evaluate(() => ({ ...__gpu, dimensions: [textureWidth, textureHeight] }));
  assert.equal(resized.allocations, reused.allocations + 1);
  assert.deepEqual(resized.dimensions, [128, 64]);
  assert.equal(resized.anisotropyQueries, before.anisotropyQueries);
});

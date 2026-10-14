const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const manifest = require('../package.json');

test('uses the Arabic product name consistently in package metadata and UI', () => {
  assert.equal(manifest.build.productName, 'فرسان لان');
  const html = fs.readFileSync(path.join(__dirname, '../src/index.html'), 'utf8');
  assert.match(html, /<title>فرسان لان/);
  assert.match(html, /مرحباً بك في فرسان لان/);
});

test('Windows installer and portable EXE have distinct artifact names', () => {
  assert.ok(manifest.build.win.target.includes('nsis'));
  assert.ok(manifest.build.win.target.includes('portable'));
  const setup = manifest.build.nsis.artifactName;
  const portable = manifest.build.portable.artifactName;
  assert.match(setup, /Setup-\$\{version\}-\$\{arch\}\.\$\{ext\}$/);
  assert.match(portable, /Portable-\$\{version\}-\$\{arch\}\.\$\{ext\}$/);
  assert.notEqual(setup, portable);
});

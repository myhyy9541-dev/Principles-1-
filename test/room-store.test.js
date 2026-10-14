const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { RoomStore, MAX_FILE_BYTES, MAX_HISTORY } = require('../src/room-store');

function fixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'wasel-test-'));
  return { root, store: new RoomStore(root), cleanup: () => fs.rmSync(root, { recursive: true, force: true }) };
}

test('persists text history and restores it after reopening', t => {
  const f = fixture(); t.after(f.cleanup);
  const message = { id: '1', type: 'text', text: 'رسالة اختبار', user: 'أحمد', time: 10 };
  f.store.addMessage(message);
  const reopened = new RoomStore(f.root);
  assert.deepEqual(reopened.publicHistory(), [message]);
});

test('stores attachment bytes outside history and returns safe metadata', t => {
  const f = fixture(); t.after(f.cleanup);
  const bytes = Buffer.from('offline attachment');
  const record = f.store.addFile({ name: '../../unsafe.txt', mime: 'text/plain', data: `data:text/plain;base64,${bytes.toString('base64')}`, user: 'ليلى', senderId: 'peer-1', time: 20 });
  assert.equal(record.name.includes('/'), false);
  assert.equal(record.size, bytes.length);
  const location = f.store.filePath(record.id);
  assert.ok(location);
  assert.deepEqual(fs.readFileSync(location), bytes);
  assert.equal(f.store.publicHistory()[0].diskName, undefined);
  const reopened = new RoomStore(f.root);
  assert.equal(reopened.filePath(record.id), location);
});

test('rejects malformed or oversized attachments', t => {
  const f = fixture(); t.after(f.cleanup);
  assert.throws(() => f.store.addFile({ name: 'bad', mime: 'text/plain', data: 'not a data URL', user: 'A' }), /صيغة/);
  const tooLarge = Buffer.alloc(MAX_FILE_BYTES + 1).toString('base64');
  assert.throws(() => f.store.addFile({ name: 'big.bin', mime: 'application/octet-stream', data: `data:application/octet-stream;base64,${tooLarge}`, user: 'A' }), /10 ميغابايت/);
});

test('bounds history length', t => {
  const f = fixture(); t.after(f.cleanup);
  for (let i = 0; i <= MAX_HISTORY; i++) f.store.addMessage({ id: String(i), type: 'text', text: String(i) });
  assert.equal(f.store.publicHistory().length, MAX_HISTORY);
  assert.equal(f.store.publicHistory()[0].id, '1');
});

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
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
  assert.deepEqual(reopened.publicHistory(), [{ ...message, channel: 'general' }]);
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

test('isolates private history and queues messages until the recipient reconnects', t => {
  const f = fixture(); t.after(f.cleanup);
  for (const [clientId, name] of [['alice-identity-0001', 'أليس'], ['bob-identity-000001', 'بوب'], ['eve-identity-000001', 'إيفا']]) f.store.rememberContact({ clientId, name });
  const direct = { id: 'private-1', type: 'text', channel: 'direct', senderClientId: 'alice-identity-0001', toId: 'bob-identity-000001', text: 'سري', deliveredTo: [], readBy: [] };
  f.store.addMessage(direct);
  assert.deepEqual(f.store.offlineFor('bob-identity-000001').map(item => item.id), ['private-1']);
  assert.equal(f.store.historyFor('eve-identity-000001').some(item => item.id === direct.id), false);
  f.store.markDelivered(direct.id, 'bob-identity-000001');
  assert.deepEqual(f.store.offlineFor('bob-identity-000001'), []);
  f.store.markRead(direct.id, 'bob-identity-000001');
  assert.deepEqual(f.store.historyFor('alice-identity-0001')[0].readBy, ['bob-identity-000001']);
});

test('persists Android platform metadata for connected contacts', t => {
  const f = fixture(); t.after(f.cleanup);
  f.store.rememberContact({ clientId: 'android-identity-00001', name: 'هاتف', platform: 'android' });
  assert.equal(f.store.contacts[0].platform, 'android');
  const reopened = new RoomStore(f.root);
  assert.equal(reopened.contacts[0].platform, 'android');
});

test('keeps general chat out of the offline queue and persists group membership', t => {
  const f = fixture(); t.after(f.cleanup);
  f.store.rememberContact({ clientId: 'alice-identity-0001', name: 'أليس' });
  f.store.rememberContact({ clientId: 'bob-identity-000001', name: 'بوب' });
  const group = f.store.createGroup({ name: 'المالية', ownerId: 'alice-identity-0001', members: ['bob-identity-000001'] });
  f.store.addMessage({ id: 'group-1', channel: 'direct', groupId: group.id, senderClientId: 'alice-identity-0001', text: 'ملخص' });
  f.store.addMessage({ id: 'general-1', channel: 'general', text: 'عام', recipientIds: ['bob-identity-000001'] });
  assert.equal(f.store.offlineFor('bob-identity-000001').some(item => item.id === 'general-1'), false);
  assert.ok(f.store.offlineFor('bob-identity-000001').some(item => item.id === 'group-1'));
  const reopened = new RoomStore(f.root);
  assert.equal(reopened.visibleGroups('bob-identity-000001')[0].id, group.id);
});

test('resumes a chunked upload after reopening the room store', t => {
  const f = fixture(); t.after(f.cleanup);
  const transferId = crypto.randomUUID();
  const contents = Buffer.from('هذا ملف تجريبي مقسم إلى مقاطع');
  const meta = { transferId, name: 'قسم/تقرير.txt', mime: 'text/plain', size: contents.length, user: 'أليس', senderId: 'socket-1', senderClientId: 'alice-identity-0001', channel: 'general', recipientIds: ['android-identity-00001'] };
  assert.equal(f.store.initUpload(meta), 0);
  const first = contents.subarray(0, 8);
  assert.equal(f.store.appendUploadChunk(transferId, 0, first.toString('base64'), meta.senderClientId), first.length);
  assert.throws(() => f.store.appendUploadChunk(transferId, first.length, 'YWJj', 'eve-identity-000001'), /صلاحية/);
  const reopened = new RoomStore(f.root);
  assert.equal(reopened.initUpload(meta), first.length);
  const rest = contents.subarray(first.length);
  reopened.appendUploadChunk(transferId, first.length, rest.toString('base64'), meta.senderClientId);
  const record = reopened.finishUpload(transferId, meta.senderClientId);
  assert.equal(record.name, 'قسم/تقرير.txt');
  assert.deepEqual(reopened.messageRecipients(record), ['android-identity-00001']);
  assert.deepEqual(fs.readFileSync(reopened.filePath(record.id)), contents);
});

test('bounds history length', t => {
  const f = fixture(); t.after(f.cleanup);
  for (let i = 0; i <= MAX_HISTORY; i++) f.store.addMessage({ id: String(i), type: 'text', text: String(i) });
  assert.equal(f.store.publicHistory().length, MAX_HISTORY);
  assert.equal(f.store.publicHistory()[0].id, '1');
});


test('persists the same peer-to-peer group id idempotently across nodes', t => {
  const f = fixture(); t.after(f.cleanup);
  const id = crypto.randomUUID();
  const spec = { id, name: 'فريق العمل', ownerId: 'alice-identity-0001', members: ['bob-identity-000001'] };
  const first = f.store.createGroup(spec);
  const repeated = f.store.createGroup(spec);
  assert.equal(first.id, id);
  assert.deepEqual(repeated, first);
  assert.equal(f.store.groups.length, 1);
});
